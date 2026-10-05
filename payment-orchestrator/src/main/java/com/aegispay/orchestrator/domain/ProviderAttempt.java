package com.aegispay.orchestrator.domain;

import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One call to one payment provider.
 *
 * <p>Kept separately from {@link SagaStep} because this is the data the router feeds on. Health,
 * latency and failure rate are all derived from here, so it is queried by provider and time
 * window rather than by saga.
 *
 * <p><strong>{@link Outcome} keeps declines and faults apart, and that distinction is the whole
 * reason this table is worth having.</strong> A provider that declines a payment is working
 * perfectly; one that times out is not. Collapsing the two would make a run of legitimate
 * declines look like an outage and route traffic away from a healthy provider — or, worse, trip
 * its circuit breaker and take it out of service entirely.
 */
@Entity
@Table(name = "provider_attempts")
public class ProviderAttempt {

    public enum Outcome {
        /** The provider authorised the charge. */
        APPROVED,
        /**
         * The provider refused. A business answer from a working system: it must not count
         * against the provider's health, and retrying it would produce the same answer.
         */
        DECLINED,
        /** A technical fault. Counts against health and may be retried. */
        FAILED,
        /** No answer within the deadline. Counts against health. */
        TIMEOUT,
        /** Not attempted: the breaker was open, so the call was rejected without being made. */
        CIRCUIT_OPEN,
        /** A previously approved authorisation was released. */
        VOIDED;

        /** True when the provider itself misbehaved, as opposed to answering correctly. */
        public boolean isTechnicalFailure() {
            return this == FAILED || this == TIMEOUT;
        }

        /** True when the provider gave a definite answer, whichever way it went. */
        public boolean isDefiniteAnswer() {
            return this == APPROVED || this == DECLINED || this == VOIDED;
        }
    }

    public enum Operation {
        AUTHORIZE,
        VOID,
        HEALTH_CHECK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "saga_id", updatable = false, length = 48)
    private String sagaId;

    @Column(name = "payment_id", updatable = false, length = 48)
    private String paymentId;

    @Column(name = "provider_id", nullable = false, updatable = false, length = 32)
    private String providerId;

    /** Position in the routing order. Above 1 means the router fell back past a preference. */
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, updatable = false, length = 24)
    private Operation operation = Operation.AUTHORIZE;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 24)
    private Outcome outcome;

    @Column(name = "amount_minor", updatable = false)
    private Long amountMinor;

    @Column(name = "currency", updatable = false, length = 3)
    private String currency;

    @Column(name = "fee_minor", updatable = false)
    private Long feeMinor;

    @Column(name = "authorization_id", updatable = false, length = 96)
    private String authorizationId;

    @Column(name = "provider_reference", updatable = false, length = 96)
    private String providerReference;

    @Column(name = "error_code", updatable = false, length = 48)
    private String errorCode;

    @Column(name = "error_message", updatable = false, length = 512)
    private String errorMessage;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ProviderAttempt() {
        // JPA
    }

    /** An authorisation that the provider approved. */
    public static ProviderAttempt approved(String sagaId, String paymentId, String providerId,
                                           int attemptNumber, Money amount, Money fee,
                                           String authorizationId, String providerReference,
                                           long latencyMs) {
        if (authorizationId == null || authorizationId.isBlank()) {
            // Without it there is nothing to void the authorisation with later, so the
            // compensation path would have no handle at all.
            throw new IllegalArgumentException(
                    "an approved authorisation must return an authorization id");
        }
        ProviderAttempt attempt = base(sagaId, paymentId, providerId, attemptNumber,
                Operation.AUTHORIZE, Outcome.APPROVED, latencyMs);
        attempt.amountMinor = amount.amountMinor();
        attempt.currency = amount.currency();
        attempt.feeMinor = fee == null ? 0L : fee.amountMinor();
        attempt.authorizationId = authorizationId;
        attempt.providerReference = providerReference;
        return attempt;
    }

    /** An authorisation the provider refused. A correct answer, not a fault. */
    public static ProviderAttempt declined(String sagaId, String paymentId, String providerId,
                                           int attemptNumber, Money amount, String errorCode,
                                           String reason, long latencyMs) {
        ProviderAttempt attempt = base(sagaId, paymentId, providerId, attemptNumber,
                Operation.AUTHORIZE, Outcome.DECLINED, latencyMs);
        attempt.amountMinor = amount == null ? null : amount.amountMinor();
        attempt.currency = amount == null ? null : amount.currency();
        attempt.errorCode = truncate(errorCode, 48);
        attempt.errorMessage = truncate(reason, 512);
        return attempt;
    }

    /** A technical fault: the provider did not give an answer. */
    public static ProviderAttempt failed(String sagaId, String paymentId, String providerId,
                                         int attemptNumber, Operation operation, Outcome outcome,
                                         Money amount, String errorCode, String reason,
                                         long latencyMs) {
        if (outcome.isDefiniteAnswer()) {
            throw new IllegalArgumentException(
                    outcome + " is a definite answer, not a failure; use the matching factory");
        }
        ProviderAttempt attempt = base(sagaId, paymentId, providerId, attemptNumber, operation,
                outcome, latencyMs);
        attempt.amountMinor = amount == null ? null : amount.amountMinor();
        attempt.currency = amount == null ? null : amount.currency();
        attempt.errorCode = truncate(errorCode, 48);
        attempt.errorMessage = truncate(reason, 512);
        return attempt;
    }

    /** A released authorisation. */
    public static ProviderAttempt voided(String sagaId, String paymentId, String providerId,
                                         String authorizationId, long latencyMs) {
        ProviderAttempt attempt = base(sagaId, paymentId, providerId, 1, Operation.VOID,
                Outcome.VOIDED, latencyMs);
        attempt.authorizationId = authorizationId;
        return attempt;
    }

    /** A health probe result, recorded with no saga attached. */
    public static ProviderAttempt healthCheck(String providerId, Outcome outcome, long latencyMs,
                                              String errorCode, String reason) {
        ProviderAttempt attempt = base(null, null, providerId, 1, Operation.HEALTH_CHECK, outcome,
                latencyMs);
        attempt.errorCode = truncate(errorCode, 48);
        attempt.errorMessage = truncate(reason, 512);
        return attempt;
    }

    private static ProviderAttempt base(String sagaId, String paymentId, String providerId,
                                        int attemptNumber, Operation operation, Outcome outcome,
                                        long latencyMs) {
        ProviderAttempt attempt = new ProviderAttempt();
        attempt.sagaId = sagaId;
        attempt.paymentId = paymentId;
        attempt.providerId = providerId;
        attempt.attemptNumber = Math.max(1, attemptNumber);
        attempt.operation = operation;
        attempt.outcome = outcome;
        attempt.latencyMs = Math.max(0L, latencyMs);
        attempt.createdAt = Instant.now();
        return attempt;
    }

    public Money amount() {
        return amountMinor == null || currency == null ? null : Money.ofMinor(amountMinor, currency);
    }

    public Money fee() {
        return feeMinor == null || currency == null ? null : Money.ofMinor(feeMinor, currency);
    }

    public boolean isTechnicalFailure() {
        return outcome.isTechnicalFailure();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    public Long getId() {
        return id;
    }

    public String getSagaId() {
        return sagaId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getProviderId() {
        return providerId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Operation getOperation() {
        return operation;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getFeeMinor() {
        return feeMinor;
    }

    public String getAuthorizationId() {
        return authorizationId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "ProviderAttempt{" + providerId + " #" + attemptNumber + " " + operation
                + " -> " + outcome + " in " + latencyMs + "ms}";
    }
}
