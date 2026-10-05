package com.aegispay.orchestrator.client;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.exception.DomainExceptions.UpstreamTransportException;
import com.aegispay.common.exception.DomainExceptions.UpstreamUnavailableException;
import com.aegispay.common.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Calls wallet-service's reserve / capture / release primitives.
 *
 * <p>The saga owns the orchestration; wallet-service owns balances. Reaching into its tables from
 * here would make the two one distributed monolith sharing a schema, and would put balance
 * invariants beyond the reach of the service that enforces them.
 *
 * <p><strong>Error translation is the part that matters.</strong> A 422 means insufficient funds — a
 * business outcome the customer must be told about, which must not be retried and must not count as
 * a provider or infrastructure failure. A 503 means unreachable — a technical fault that is
 * retryable. Collapsing the two would either retry an unretryable decline or report an outage as
 * the customer being short of money.
 */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    private final RestClient client;
    private final String baseUrl;

    public WalletClient(@Qualifier("internalRestClient") RestClient client,
                        @Value("${aegis.clients.wallet-service-url:http://localhost:8082}")
                        String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    /**
     * Reserves funds for a payment.
     *
     * <p>Idempotent on {@code paymentId}: wallet-service enforces one hold per payment with a
     * unique constraint, so a retry returns the existing reservation rather than reserving twice.
     */
    public HoldView reserve(String paymentId, String walletId, Money amount, Duration ttl) {
        Map<String, Object> body = Map.of(
                "paymentId", paymentId,
                "walletId", walletId,
                "amount", amount.toDecimal(),
                "currency", amount.currency(),
                "ttlSeconds", ttl == null ? 900 : ttl.toSeconds());

        return exchange("POST", "/internal/wallets/reserve", body, HoldView.class,
                "reservation for payment " + paymentId);
    }

    /**
     * Captures the reservation and credits the payee, atomically inside wallet-service.
     *
     * <p>One call rather than a debit followed by a credit: two calls would leave a window in which
     * the money existed in neither wallet, and a crash inside it would destroy customer funds.
     */
    public CaptureView capture(String paymentId, String sourceWalletId,
                               String destinationWalletId, String description) {
        Map<String, Object> body = Map.of(
                "paymentId", paymentId,
                "sourceWalletId", sourceWalletId,
                "destinationWalletId", destinationWalletId,
                "description", description == null ? "" : description);

        return exchange("POST", "/internal/wallets/capture", body, CaptureView.class,
                "capture for payment " + paymentId);
    }

    /**
     * Releases a reservation. The compensating action for a failed payment.
     *
     * <p>Never throws. Compensation runs on the failure path, where the caller is already handling
     * one problem and must not be derailed by a second. A failed release is logged loudly and
     * reported through the return value so the saga can record that funds may still be reserved,
     * rather than silently claiming it cleaned up. The wallet's hold-expiry sweeper is the backstop,
     * so the worst outcome is a delayed release rather than permanently frozen funds.
     *
     * @return whether the release was confirmed
     */
    public boolean releaseQuietly(String paymentId, String reason) {
        try {
            exchange("POST", "/internal/wallets/release",
                    Map.of("paymentId", paymentId, "reason", reason),
                    HoldView.class, "release for payment " + paymentId);
            return true;
        } catch (NotFoundException ex) {
            // No hold exists. Either it was never created or it was already released — both mean
            // there is nothing outstanding, which is what the caller needs to know.
            log.debug("No reservation to release for payment {}; treating as already released",
                    paymentId);
            return true;
        } catch (RuntimeException ex) {
            log.error("""
                            COMPENSATION FAILED: could not release the reservation for payment {}. \
                            The funds stay reserved until the wallet hold-expiry sweeper releases \
                            them. Reason: {}""",
                    paymentId, ex.getMessage());
            return false;
        }
    }

    /**
     * Pushes a reservation's expiry out, so it survives a wait the saga did not budget for.
     *
     * <p><strong>Why a held payment needs this.</strong> The reservation is created with a
     * fifteen-minute lifetime, which is right for a payment that completes in seconds. A payment held
     * for fraud review waits on a human instead, and the wallet's expiry sweeper would release the
     * funds while this saga still believed they were held — the customer could then spend the money,
     * and the analyst releasing the payment would capture against a balance that had moved underneath
     * them.
     *
     * <p>Never throws, and the caller treats a failure as non-fatal. The hold is still valid for its
     * original window, so the worst case is that a long review outruns it, the capture fails, and the
     * saga compensates — a failed payment rather than money moving without funds behind it. Refusing
     * the payment because a lifetime extension could not be recorded would be a worse trade.
     *
     * <p>Idempotent and monotonic: wallet-service never shortens an expiry that is already further
     * out, so a retry cannot bring a reservation's life forward.
     *
     * @return whether the extension was confirmed
     */
    public boolean extendQuietly(String paymentId, Duration ttl, String reason) {
        try {
            exchange("POST", "/internal/wallets/extend",
                    Map.of("paymentId", paymentId,
                            "ttlSeconds", ttl == null ? 172_800 : ttl.toSeconds(),
                            "reason", reason == null ? "Extended by the payment saga" : reason),
                    HoldView.class, "hold extension for payment " + paymentId);
            return true;
        } catch (NotFoundException | ConflictException ex) {
            // No active reservation to extend: it was never created, already released, or already
            // captured. None of those is fixable by retrying, and all of them mean the saga's
            // assumption about the hold is wrong — which the capture will surface properly.
            log.warn("No active reservation to extend for payment {}: {}",
                    paymentId, ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.error("""
                            Could not extend the reservation for payment {}. It stays valid for its \
                            original window only. Reason: {}""",
                    paymentId, ex.getMessage());
            return false;
        }
    }

    public WalletView getWallet(String walletId) {
        return exchange("GET", "/internal/wallets/" + walletId, null, WalletView.class,
                "wallet " + walletId);
    }

    // --------------------------------------------------------------- plumbing

    private <T> T exchange(String method, String path, Object body, Class<T> responseType,
                           String what) {
        try {
            RestClient.RequestBodySpec request = client
                    .method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(baseUrl + path);

            RestClient.RequestHeadersSpec<?> spec = body == null ? request : request.body(body);

            return spec.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw translate(response.getStatusCode(), what);
                    })
                    .body(responseType);

        } catch (com.aegispay.common.exception.AegisException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // No trustworthy HTTP response means the wallet mutation may have committed remotely.
            throw new UpstreamTransportException("wallet-service",
                    "could not complete " + what, ex);
        }
    }

    /**
     * Maps an HTTP status onto the domain exception that means the same thing.
     *
     * <p>4xx outcomes are decisions and must not be retried; 5xx outcomes are faults and may be.
     * Getting this wrong is how a system retries a decline until it accidentally succeeds, or tells
     * a customer their balance is short during an outage.
     */
    private RuntimeException translate(HttpStatusCode status, String what) {
        return switch (status.value()) {
            case 404 -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND, "Could not find " + what);
            case 409 -> new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Wallet service reported a conflict for " + what);
            case 422 ->
                // Insufficient funds. Reported generically rather than echoing the available
                // balance, so one customer's balance cannot leak into another's error path.
                    new InsufficientFundsException("source wallet",
                            Money.ofMinor(0L, "INR"), Money.ofMinor(0L, "INR"));
            case 400 -> new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "Wallet service rejected the request for " + what);
            case 401, 403 ->
                // The service token was rejected: a credential or configuration problem, never
                // something a customer can act on.
                    new UpstreamUnavailableException("wallet-service",
                            "service-to-service authentication failed; check INTERNAL_CLIENT_SECRET");
            default -> new UpstreamUnavailableException("wallet-service",
                    "returned HTTP " + status.value() + " for " + what);
        };
    }

    // ------------------------------------------------------------- responses

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WalletView(
            String walletId,
            String userId,
            String currency,
            long balanceMinor,
            long heldMinor,
            long availableMinor,
            String status,
            boolean canSpend
    ) {
        public Money available() {
            return Money.ofMinor(availableMinor, currency);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HoldView(
            String holdId,
            String paymentId,
            String walletId,
            MoneyView amount,
            String state,
            java.time.Instant expiresAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaptureView(
            String paymentId,
            String holdId,
            MoneyView amount,
            String sourceWalletId,
            String destinationWalletId,
            String debitTransactionId,
            String creditTransactionId,
            java.time.Instant capturedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoneyView(BigDecimal amount, long amountMinor, String currency) {
    }
}
