package com.aegispay.orchestrator.client;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.exception.DomainExceptions.UpstreamTransportException;
import com.aegispay.common.exception.DomainExceptions.UpstreamUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Books and reverses ledger postings.
 *
 * <p><strong>Why the saga calls the ledger synchronously at all,</strong> when ledger-service
 * already books every payment from the {@code payment-completed} event. Because the saga posts
 * <em>before</em> it captures: money should never move that has not been recorded, and that ordering
 * only means anything if the saga knows the posting committed. Waiting for an event it published
 * itself would be a deadlock.
 *
 * <p>The later Kafka event is not a duplicate. ledger-service derives the same idempotency key from
 * the payment ID either way, so the event arrives and is recognised as a replay. One accounting
 * code path, two triggers.
 *
 * <p>The posting is described by the payment's <em>facts</em> rather than by a list of accounts.
 * Deciding which accounts a payment touches is an accounting decision and belongs in ledger-service
 * with the chart of accounts; an orchestrator that built its own postings would put the chart of
 * accounts in two places at once.
 */
@Component
public class LedgerClient {

    private static final Logger log = LoggerFactory.getLogger(LedgerClient.class);

    private final RestClient client;
    private final String baseUrl;

    public LedgerClient(@Qualifier("internalRestClient") RestClient client,
                        @Value("${aegis.clients.ledger-service-url:http://localhost:8083}")
                        String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    /**
     * Books the double entry for a completed payment.
     *
     * <p>Idempotent: ledger-service derives the posting key from the payment ID, so a retry returns
     * the original posting rather than booking the transfer twice.
     *
     * @return the committed posting, whose {@code ledgerTransactionId} the saga stores so a later
     *         failure can reverse it
     */
    public JournalEntryView bookPayment(PaymentCompletedPayload payment) {
        return exchange("POST", "/internal/ledger/postings/for-payment", payment,
                JournalEntryView.class, "ledger posting for payment " + payment.paymentId());
    }

    /**
     * Reverses a posting by appending its exact opposite.
     *
     * <p>Never throws, for the same reason the wallet release does not: compensation runs on the
     * failure path and must not be derailed by a second problem. A failed reversal is logged loudly
     * and reported through the return value so the saga records that the books and the wallets may
     * disagree — which the ledger's own reconciliation will also find.
     *
     * @return whether the reversal was confirmed
     */
    public boolean reverseQuietly(String ledgerTransactionId, String reason) {
        try {
            exchange("POST", "/internal/ledger/postings/" + ledgerTransactionId + "/reverse",
                    Map.of("reason", reason), JournalEntryView.class,
                    "reversal of posting " + ledgerTransactionId);
            return true;

        } catch (NotFoundException ex) {
            // Nothing was posted, so there is nothing to reverse.
            log.debug("No posting {} to reverse; treating as already absent", ledgerTransactionId);
            return true;

        } catch (ConflictException ex) {
            // Already reversed. The schema permits one reversal per posting, so a second attempt
            // conflicting means the first succeeded — which is the outcome we wanted.
            log.debug("Posting {} was already reversed", ledgerTransactionId);
            return true;

        } catch (RuntimeException ex) {
            log.error("""
                            COMPENSATION FAILED: could not reverse ledger posting {}. The books now \
                            record a transfer the wallets did not make; reconciliation will flag it. \
                            Reason: {}""",
                    ledgerTransactionId, ex.getMessage());
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
            throw ex;
        } catch (RestClientException ex) {
            // No trustworthy HTTP response means the posting may have committed remotely.
            throw new UpstreamTransportException("ledger-service",
                    "could not complete " + what, ex);
        }
    }

    private RuntimeException translate(HttpStatusCode status, String what) {
        return switch (status.value()) {
            case 404 -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                    "Could not find " + what);
            case 409 -> new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Ledger service reported a conflict for " + what);
            case 400 -> new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "Ledger service rejected " + what);
            case 422 ->
                // An unbalanced posting, or one that would drive an account negative. A
                // programming error on our side, never something a customer can act on, and
                // never retryable.
                    new BadRequestException(ErrorCode.LEDGER_IMBALANCE,
                            "Ledger service refused " + what + " as unbookable");
            case 401, 403 -> new UpstreamUnavailableException("ledger-service",
                    "service-to-service authentication failed; check INTERNAL_CLIENT_SECRET");
            default -> new UpstreamUnavailableException("ledger-service",
                    "returned HTTP " + status.value() + " for " + what);
        };
    }

    // ------------------------------------------------------------- responses

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JournalEntryView(
            long journalSequence,
            String ledgerTransactionId,
            String paymentId,
            String postingType,
            MoneyView total,
            /** True when this posting already existed and the call was a retry. */
            boolean duplicate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoneyView(java.math.BigDecimal amount, long amountMinor, String currency) {
    }
}
