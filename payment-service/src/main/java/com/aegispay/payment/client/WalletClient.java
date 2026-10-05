package com.aegispay.payment.client;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
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
 * Calls wallet-service's internal reserve / capture / release API.
 *
 * <p><strong>Why HTTP and not a shared database.</strong> Balances belong to
 * wallet-service. Reaching into its tables from here would make the two services one
 * distributed monolith sharing a schema, and would put balance invariants beyond the reach
 * of the service that owns them. The cost is that these calls can fail in ways a local
 * query cannot, which is what the error translation below exists to handle.
 *
 * <p>Uses the {@code internalRestClient}, which attaches a client-credentials bearer token
 * and propagates the correlation ID, so a payment is traceable across both services.
 *
 * <p><strong>Error translation is the important part.</strong> A 422 from wallet-service
 * means "insufficient funds" — a business outcome the customer must be told about, and one
 * that must NOT be retried or counted as a provider failure. A 503 means the service is
 * unreachable — a technical fault that is retryable and should trip a circuit breaker.
 * Collapsing both into a generic "call failed" would either retry an unretryable decline
 * or hide a real outage.
 *
 * <p>Resilience4j retry and circuit breaking are wired around these calls in Phase 5, when
 * the saga takes ownership of the orchestration. The registries already exist in the shared
 * kernel; what is missing is the policy for who retries what, which belongs with the saga.
 */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    private final RestClient client;
    private final String baseUrl;

    public WalletClient(@Qualifier("internalRestClient") RestClient client,
                        @Value("${aegis.clients.wallet-service-url:http://localhost:8082}") String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    // --------------------------------------------------------------- queries

    /** Looks up a wallet by ID. */
    public WalletView getWallet(String walletId) {
        return exchange("GET", "/internal/wallets/" + walletId, null, WalletView.class,
                "wallet " + walletId);
    }

    /** Resolves a user's wallet in a currency, for payments that name the payee by user. */
    public WalletView getWalletByUser(String userId, String currency) {
        return exchange("GET",
                "/internal/wallets/by-user/" + userId + "?currency=" + currency,
                null, WalletView.class, "wallet for user " + userId);
    }

    // ------------------------------------------------------------ operations

    /**
     * Reserves funds.
     *
     * <p>Idempotent on {@code paymentId}: wallet-service enforces one hold per payment, so
     * a retry returns the existing reservation rather than reserving twice.
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
     * Captures the reservation and credits the destination, atomically inside
     * wallet-service.
     *
     * <p>One call rather than a debit followed by a credit, deliberately: two calls would
     * leave a window in which the money existed in neither wallet, and a failure between
     * them would destroy funds.
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
     * <p>Never throws. Compensation runs on the failure path, where the caller is already
     * handling one problem and must not be derailed by a second. A failed release is logged
     * loudly and left to the hold-expiry sweeper, which is exactly the safety net that
     * exists for this case — so the worst outcome is a delayed release rather than
     * permanently frozen funds.
     *
     * @return true when the release was confirmed
     */
    public boolean releaseQuietly(String paymentId, String reason) {
        try {
            Map<String, Object> body = Map.of("paymentId", paymentId, "reason", reason);
            exchange("POST", "/internal/wallets/release", body, HoldView.class,
                    "release for payment " + paymentId);
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
            // Already translated into a domain exception; let it through unchanged.
            throw ex;
        } catch (RestClientException ex) {
            // Connection refused, DNS failure, read timeout: technical and retryable.
            throw new UpstreamUnavailableException("wallet-service",
                    "could not complete " + what, ex);
        }
    }

    /**
     * Maps an HTTP status onto the domain exception that means the same thing.
     *
     * <p>The distinction that matters: 4xx outcomes are <em>decisions</em> and must not be
     * retried, while 5xx outcomes are <em>faults</em> and may be. Getting this wrong is how
     * a system ends up retrying a decline until it accidentally succeeds, or treating an
     * outage as a business rejection and telling the customer their balance is short.
     */
    private RuntimeException translate(HttpStatusCode status, String what) {
        int code = status.value();
        return switch (code) {
            case 404 -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                    "Could not find " + what);
            case 409 -> new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Wallet service reported a conflict for " + what);
            case 422 ->
                // Insufficient funds. A business outcome: report it to the customer and do
                // not retry. The available/requested detail lives in wallet-service's
                // response body; this platform reports the generic form to avoid leaking
                // one user's balance into another's error path.
                    new InsufficientFundsException("source wallet",
                            Money.ofMinor(0L, "INR"), Money.ofMinor(0L, "INR"));
            case 400 -> new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "Wallet service rejected the request for " + what);
            case 401, 403 ->
                // The service token was rejected. A configuration or credential problem,
                // never something the customer can act on.
                    new UpstreamUnavailableException("wallet-service",
                            "service-to-service authentication failed; check INTERNAL_CLIENT_SECRET");
            default -> new UpstreamUnavailableException("wallet-service",
                    "returned HTTP " + code + " for " + what);
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
