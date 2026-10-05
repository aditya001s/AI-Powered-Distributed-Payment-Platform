package com.aegispay.orchestrator.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.exception.DomainExceptions.IllegalStateTransitionException;
import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The orchestration state of one payment.
 *
 * <p>A saga is a distributed transaction without two-phase commit, so its correctness rests
 * entirely on being able to answer two questions after a crash: how far did this get, and what
 * therefore needs undoing. This aggregate is the answer to both.
 *
 * <p><strong>The compensation handles are the important part.</strong> {@code holdId},
 * {@code authorizationId} and {@code ledgerTransactionId} are each written the moment their step
 * succeeds, and their presence is what tells the compensator there is a real side effect to
 * undo. Nothing may be inferred from the status alone: a crash between "provider approved" and
 * "status updated" would leave an authorisation outstanding with the status still saying
 * {@code RISK_ASSESSED}, so the handle — not the state — is the source of truth about what
 * exists in the outside world.
 *
 * <p>Transitions go through {@link #transitionTo}, which refuses anything the state machine does
 * not allow. That is what makes "capture without authorising" unexpressible rather than merely
 * unlikely.
 */
@Entity
@Table(name = "payment_sagas")
public class PaymentSaga {

    /** Comma-separated provider list cap, matching {@code provider_attempts VARCHAR(256)}. */
    private static final int MAX_ATTEMPTS_COLUMN = 256;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "saga_id", nullable = false, unique = true, updatable = false, length = 48)
    private String sagaId;

    /**
     * The idempotency key. One saga per payment, enforced by a unique constraint, which is what
     * stops a redelivered {@code payment-created} reserving the customer's funds a second time.
     */
    @Column(name = "payment_id", nullable = false, unique = true, updatable = false, length = 48)
    private String paymentId;

    @Column(name = "transaction_id", updatable = false, length = 48)
    private String transactionId;

    @Column(name = "initiator_user_id", nullable = false, updatable = false, length = 40)
    private String initiatorUserId;

    @Column(name = "source_wallet_id", nullable = false, updatable = false, length = 40)
    private String sourceWalletId;

    @Column(name = "destination_wallet_id", nullable = false, updatable = false, length = 40)
    private String destinationWalletId;

    @Column(name = "destination_user_id", updatable = false, length = 40)
    private String destinationUserId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, updatable = false, length = 24)
    private PaymentType paymentType = PaymentType.P2P_TRANSFER;

    @Column(name = "description", updatable = false, length = 256)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SagaStatus status = SagaStatus.STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 32)
    private SagaStep.Name currentStep;

    // ---- Compensation handles ----

    @Column(name = "hold_id", length = 48)
    private String holdId;

    @Column(name = "provider_id", length = 32)
    private String providerId;

    @Column(name = "authorization_id", length = 96)
    private String authorizationId;

    @Column(name = "provider_reference", length = 96)
    private String providerReference;

    @Column(name = "ledger_transaction_id", length = 80)
    private String ledgerTransactionId;

    @Column(name = "provider_attempts", length = MAX_ATTEMPTS_COLUMN)
    private String providerAttempts;

    // ---- Risk verdict ----

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 16)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "fraud_decision", length = 16)
    private FraudDecision fraudDecision;

    @Column(name = "fraud_probability")
    private Double fraudProbability;

    /**
     * The fraud assessment this saga acted on.
     *
     * <p>Stored because the two services have separate databases: without the reference, a disputed
     * payment can be traced from the assessment to the saga but not back.
     */
    @Column(name = "risk_assessment_id", length = 48)
    private String riskAssessmentId;

    /**
     * True when the verdict was reached without the model, on the deterministic rules alone.
     *
     * <p>Kept on the saga as well as on the assessment because it describes a decision <em>this</em>
     * service made: it continued a payment on a verdict of reduced assurance. That is the fact an
     * audit asks about, and it must be answerable from the payment's own history.
     */
    @Column(name = "risk_degraded", nullable = false)
    private boolean riskDegraded;

    /** When fraud controls parked the payment. {@code now()} minus this is the customer's wait. */
    @Column(name = "risk_held_at")
    private Instant riskHeldAt;

    @Column(name = "risk_reviewed_at")
    private Instant riskReviewedAt;

    /**
     * The analyst who released or refused the payment.
     *
     * <p>Copied from the decision event, which took it from their token. Duplicated from
     * fraud-service's append-only record deliberately: "who let this payment through" has to be
     * answerable from the payment's own history, because that is the question asked when the payment
     * turns out to have been fraud.
     */
    @Column(name = "risk_reviewed_by", length = 40)
    private String riskReviewedBy;

    @Column(name = "risk_review_reason", length = 512)
    private String riskReviewReason;

    /**
     * The device, IP and location context the customer transacted in, as JSON.
     *
     * <p><strong>Stored rather than carried in memory.</strong> A saga resumed by the recovery sweep
     * no longer has the {@code payment-created} event, and re-deriving this later would describe a
     * different situation than the one the customer was actually in — so a resumed payment would be
     * scored on a fabricated context, silently, with every device and location feature wrong.
     *
     * <p>Held as a {@code String} rather than as a mapped object so the bytes the event carried are
     * preserved exactly, and forwarded to fraud-service verbatim. This service has no opinion about
     * the shape: interpreting it here would put the same knowledge in two places, and a new signal
     * would need a change in both.
     *
     * <p>Personal data. Never rendered to another customer; see the Security section in README.md.
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "risk_signals", updatable = false)
    private String riskSignals;

    // ---- Failure detail ----

    @Column(name = "failure_code", length = 48)
    private String failureCode;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_step", length = 32)
    private SagaStep.Name failedStep;

    @Column(name = "compensation_complete", nullable = false)
    private boolean compensationComplete = true;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentSaga() {
        // JPA
    }

    /**
     * Starts a saga from the event that triggered it.
     *
     * <p>Copies every fact the saga needs, so no step has to call back into payment-service to
     * discover what it is processing. That keeps orchestration working while payment-service is
     * being redeployed, which is the whole reason the event carries them.
     */
    public static PaymentSaga start(PaymentCreatedPayload payment, String correlationId) {
        return start(payment, correlationId, null);
    }

    /**
     * Starts a saga, retaining the risk context the payment was made in.
     *
     * @param riskSignalsJson the event's {@code riskSignals} serialised verbatim, so a saga resumed
     *                        after a crash scores the situation the customer was actually in rather
     *                        than one re-derived later
     */
    public static PaymentSaga start(PaymentCreatedPayload payment, String correlationId,
                                    String riskSignalsJson) {
        Money amount = payment.amount();
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException(
                    "cannot start a saga for payment " + payment.paymentId()
                            + " with a non-positive amount " + amount);
        }
        if (payment.sourceWalletId() == null || payment.sourceWalletId().isBlank()
                || payment.destinationWalletId() == null || payment.destinationWalletId().isBlank()) {
            throw new IllegalArgumentException(
                    "cannot start a saga for payment " + payment.paymentId()
                            + ": both wallet ids are required");
        }
        if (payment.sourceWalletId().equals(payment.destinationWalletId())) {
            // Would net to nothing while still producing provider calls, ledger entries and
            // notifications.
            throw new IllegalArgumentException(
                    "cannot start a saga for payment " + payment.paymentId()
                            + ": source and destination wallets are identical");
        }

        PaymentSaga saga = new PaymentSaga();
        saga.sagaId = "SAGA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                .toUpperCase();
        saga.paymentId = payment.paymentId();
        saga.transactionId = payment.transactionId();
        saga.initiatorUserId = payment.initiatorUserId();
        saga.sourceWalletId = payment.sourceWalletId();
        saga.destinationWalletId = payment.destinationWalletId();
        saga.destinationUserId = payment.destinationUserId();
        saga.amountMinor = amount.amountMinor();
        saga.currency = amount.currency();
        saga.feeMinor = 0L;
        saga.paymentType = payment.paymentType() == null
                ? PaymentType.P2P_TRANSFER
                : payment.paymentType();
        saga.description = payment.description();
        saga.status = SagaStatus.STARTED;
        saga.correlationId = correlationId;
        saga.riskSignals = riskSignalsJson;
        saga.compensationComplete = true;
        saga.attemptCount = 1;
        saga.createdAt = Instant.now();
        saga.updatedAt = saga.createdAt;
        return saga;
    }

    // ------------------------------------------------------------------ queries

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public Money fee() {
        return Money.ofMinor(feeMinor, currency);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * Whether the saga is waiting on a human and must not be driven.
     *
     * <p>Not the same as terminal: there is more work to do, but nothing this service can do to move
     * it along. The driver stops on it and the recovery sweep skips it — treating a parked payment as
     * abandoned would "recover" every held payment on every sweep.
     */
    public boolean isParked() {
        return status.isParked();
    }

    /** How long the customer has been waiting on a review, if they are. */
    public java.util.Optional<java.time.Duration> reviewWait() {
        if (riskHeldAt == null) {
            return java.util.Optional.empty();
        }
        Instant end = riskReviewedAt == null ? Instant.now() : riskReviewedAt;
        return java.util.Optional.of(java.time.Duration.between(riskHeldAt, end));
    }

    /** Providers tried, in routing order. More than one means a fallback occurred. */
    public List<String> providerAttemptList() {
        if (providerAttempts == null || providerAttempts.isBlank()) {
            return List.of();
        }
        return Arrays.stream(providerAttempts.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Whether a wallet reservation exists that a failure would have to release.
     *
     * <p>Keyed off the handle rather than the status, deliberately. A crash between the reserve
     * call returning and the status being updated would leave a real hold behind while the status
     * still said {@code STARTED}; trusting the status would strand the customer's funds.
     */
    public boolean hasOutstandingHold() {
        return holdId != null && !holdId.isBlank();
    }

    /** Whether a provider authorisation exists that a failure would have to void. */
    public boolean hasOutstandingAuthorization() {
        return authorizationId != null && !authorizationId.isBlank();
    }

    /** Whether a ledger posting exists that a failure would have to reverse. */
    public boolean hasLedgerPosting() {
        return ledgerTransactionId != null && !ledgerTransactionId.isBlank();
    }

    /**
     * True once the money has actually moved.
     *
     * <p>Past this point compensation is no longer available: releasing a reservation that has
     * already been captured would hand back money legitimately taken. The only way forward is to
     * finish recording what happened.
     */
    public boolean fundsCaptured() {
        return status == SagaStatus.CAPTURED || status == SagaStatus.COMPLETED;
    }

    // ----------------------------------------------------------------- mutators

    /**
     * Moves to {@code target}, refusing anything the state machine disallows.
     *
     * <p>Refusing rather than logging is what makes an out-of-order saga impossible to persist.
     * A duplicate event delivery that tried to capture an already-captured payment would
     * otherwise apply twice.
     */
    public void transitionTo(SagaStatus target) {
        if (status == target) {
            // Idempotent: a redelivered event re-asserting the current state is not an error.
            return;
        }
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(
                    ErrorCode.INVALID_PAYMENT_STATE, "Saga " + sagaId, status.name(), target.name());
        }
        this.status = target;
        if (target.isTerminal() && completedAt == null) {
            this.completedAt = Instant.now();
        }
        touch();
    }

    /** Attempts a transition, reporting rather than throwing when it is not allowed. */
    public boolean tryTransitionTo(SagaStatus target) {
        if (status == target) {
            return true;
        }
        if (!status.canTransitionTo(target)) {
            return false;
        }
        transitionTo(target);
        return true;
    }

    public void beginStep(SagaStep.Name step) {
        this.currentStep = step;
        touch();
    }

    /** Records the reservation, then advances. The handle is set first, on purpose. */
    public void reservedFunds(String holdId) {
        this.holdId = holdId;
        transitionTo(SagaStatus.FUNDS_RESERVED);
    }

    /** Records a verdict that cleared the payment to continue. */
    public void recordRiskVerdict(RiskLevel riskLevel, Double probability, FraudDecision decision) {
        recordRiskVerdict(riskLevel, probability, decision, null, false);
    }

    /**
     * Records a verdict that cleared the payment, with its provenance.
     *
     * @param assessmentId the fraud assessment that produced it, so a dispute can be traced back
     * @param degraded     whether it was reached without the model, on rules alone
     */
    public void recordRiskVerdict(RiskLevel riskLevel, Double probability, FraudDecision decision,
                                  String assessmentId, boolean degraded) {
        this.riskLevel = riskLevel;
        this.fraudProbability = probability;
        this.fraudDecision = decision;
        this.riskAssessmentId = truncate(assessmentId, 48);
        this.riskDegraded = degraded;
        transitionTo(SagaStatus.RISK_ASSESSED);
    }

    /**
     * Parks the payment pending human review.
     *
     * <p>The verdict is recorded before the transition, so a crash between the two leaves a saga that
     * still says {@code FUNDS_RESERVED} carrying the verdict — recoverable — rather than one parked in
     * {@code RISK_HELD} with no record of why, which nobody could act on.
     *
     * <p>{@code riskHeldAt} is set here and never overwritten. It is how long the customer has been
     * waiting with their funds reserved, and taking that from {@code updatedAt} instead would reset
     * every time anything touched the row.
     */
    public void heldForReview(RiskLevel riskLevel, Double probability, FraudDecision decision,
                              String assessmentId, boolean degraded) {
        this.riskLevel = riskLevel;
        this.fraudProbability = probability;
        this.fraudDecision = decision;
        this.riskAssessmentId = truncate(assessmentId, 48);
        this.riskDegraded = degraded;
        if (this.riskHeldAt == null) {
            this.riskHeldAt = Instant.now();
        }
        transitionTo(SagaStatus.RISK_HELD);
    }

    /**
     * Applies an analyst's release, moving the payment back onto the normal path.
     *
     * <p>Lands in {@code RISK_ASSESSED}, which means the released payment takes exactly the same
     * steps it would have taken had it never been held. A dedicated "resume" state would be a second
     * implementation of the same sequence, and the two would drift.
     *
     * <p>The machine's original verdict is <strong>not</strong> overwritten: {@code fraudDecision}
     * still reads {@code HOLD}, alongside a record of the human who overrode it. Rewriting it to
     * {@code APPROVE} would erase the fact that the engine stopped this payment, which is precisely
     * what somebody reviewing a released fraud needs to see.
     *
     * @return whether the release was applied. {@code false} means the saga was not parked — a
     *         redelivered decision, or a decision on an alert that never held the payment.
     */
    public boolean riskReviewReleased(String analystUserId, String reason) {
        if (status != SagaStatus.RISK_HELD) {
            return false;
        }
        this.riskReviewedBy = truncate(analystUserId, 40);
        this.riskReviewReason = truncate(reason, 512);
        this.riskReviewedAt = Instant.now();
        transitionTo(SagaStatus.RISK_ASSESSED);
        return true;
    }

    /**
     * Records that an analyst refused the payment, without settling it.
     *
     * <p>Only attributes the refusal. The caller still has to compensate — release the reservation —
     * and then mark the saga rejected, in that order, so {@code compensationComplete} on the
     * {@code payment-rejected} event is a statement of fact rather than an intention.
     *
     * @return whether the saga was parked and the refusal therefore applies
     */
    public boolean riskReviewRefused(String analystUserId, String reason) {
        if (status != SagaStatus.RISK_HELD) {
            return false;
        }
        this.riskReviewedBy = truncate(analystUserId, 40);
        this.riskReviewReason = truncate(reason, 512);
        this.riskReviewedAt = Instant.now();
        touch();
        return true;
    }

    /**
     * Records a provider authorisation and the fee that provider charged.
     *
     * <p>The fee is stored here rather than recomputed later because it is the provider's
     * number, not ours: recomputing it from a rate could disagree with what was actually
     * charged, and the ledger would then book a fee nobody levied.
     */
    public void authorized(String providerId, String authorizationId, String providerReference,
                           Money fee) {
        if (providerId == null || providerId.isBlank()) {
            // The schema refuses an unattributable authorisation, and so does this: its fee
            // could not be booked against any settlement account.
            throw new IllegalArgumentException("an authorisation must name the provider that gave it");
        }
        if (fee != null && !fee.currency().equals(currency)) {
            throw new IllegalArgumentException(
                    "provider fee is in " + fee.currency() + " but the payment is in " + currency);
        }
        this.providerId = providerId;
        this.authorizationId = authorizationId;
        this.providerReference = providerReference;
        this.feeMinor = fee == null ? 0L : fee.amountMinor();
        transitionTo(SagaStatus.AUTHORIZED);
    }

    public void ledgerPosted(String ledgerTransactionId) {
        this.ledgerTransactionId = ledgerTransactionId;
        transitionTo(SagaStatus.LEDGER_POSTED);
    }

    public void capturedFunds() {
        transitionTo(SagaStatus.CAPTURED);
    }

    public void completed() {
        transitionTo(SagaStatus.COMPLETED);
    }

    /** Notes that a provider was tried, whatever the outcome. */
    public void recordProviderAttempt(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        List<String> attempts = new ArrayList<>(providerAttemptList());
        if (attempts.contains(providerId)) {
            return;
        }
        attempts.add(providerId);
        String joined = String.join(",", attempts);
        // Truncating the audit trail is better than failing the write that records why a
        // payment took an unusual route.
        this.providerAttempts = joined.length() <= MAX_ATTEMPTS_COLUMN
                ? joined
                : joined.substring(0, MAX_ATTEMPTS_COLUMN);
        touch();
    }

    public void beginCompensating() {
        // tryTransitionTo, not transitionTo: compensation can be triggered from several states
        // and from the recovery sweep, and a saga already compensating must not throw.
        tryTransitionTo(SagaStatus.COMPENSATING);
        touch();
    }

    /** Clears the hold handle once the reservation is confirmed released. */
    public void holdReleased() {
        this.holdId = null;
        touch();
    }

    /** Clears the authorisation handle once the provider confirms the void. */
    public void authorizationVoided() {
        this.authorizationId = null;
        touch();
    }

    /** Clears the ledger handle once the posting is confirmed reversed. */
    public void ledgerReversed() {
        this.ledgerTransactionId = null;
        touch();
    }

    /**
     * Marks the saga failed.
     *
     * @param compensationComplete whether every side effect was confirmed undone. {@code false}
     *                             means money may still be reserved or authorised somewhere and
     *                             an operator must look, which is why it travels on the
     *                             {@code payment-failed} event rather than only reaching a log.
     * @return whether the failure was applied. {@code false} means the saga had already reached a
     *         terminal state and the caller must not announce a second outcome — telling a customer
     *         their completed payment failed is worse than telling them nothing.
     */
    public boolean failed(String failureCode, String reason, SagaStep.Name failedStep,
                          boolean compensationComplete) {
        if (isTerminal()) {
            // The first recorded outcome stands. This is reachable in practice: the recovery
            // sweep can pick up a saga that has since completed while a concurrent path is
            // still trying to record the failure it saw. Nothing may be written — least of all
            // compensationComplete=false, which would put a perfectly settled payment into the
            // operator's "money may be stranded" queue.
            touch();
            return false;
        }
        this.failureCode = truncate(failureCode, 48);
        this.failureReason = truncate(reason, 512);
        this.failedStep = failedStep;
        this.compensationComplete = compensationComplete;
        return tryTransitionTo(SagaStatus.FAILED);
    }

    /**
     * Marks the saga refused by risk controls or by every provider declining.
     *
     * @return whether the rejection was applied. {@code false} means the saga had already reached a
     *         terminal state, which the caller must not announce a second time.
     */
    public boolean rejected(String reasonCode, String reason, boolean compensationComplete) {
        if (isTerminal()) {
            touch();
            return false;
        }
        this.failureCode = truncate(reasonCode, 48);
        this.failureReason = truncate(reason, 512);
        this.compensationComplete = compensationComplete;
        return tryTransitionTo(SagaStatus.REJECTED);
    }

    /** Counts a re-entry by the recovery sweep, so a saga that never progresses is visible. */
    public void recordRecoveryAttempt() {
        this.attemptCount++;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
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

    public String getTransactionId() {
        return transactionId;
    }

    public String getInitiatorUserId() {
        return initiatorUserId;
    }

    public String getSourceWalletId() {
        return sourceWalletId;
    }

    public String getDestinationWalletId() {
        return destinationWalletId;
    }

    public String getDestinationUserId() {
        return destinationUserId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public PaymentType getPaymentType() {
        return paymentType;
    }

    public String getDescription() {
        return description;
    }

    public SagaStatus getStatus() {
        return status;
    }

    public SagaStep.Name getCurrentStep() {
        return currentStep;
    }

    public String getHoldId() {
        return holdId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getAuthorizationId() {
        return authorizationId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public String getProviderAttempts() {
        return providerAttempts;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public FraudDecision getFraudDecision() {
        return fraudDecision;
    }

    public Double getFraudProbability() {
        return fraudProbability;
    }

    public String getRiskAssessmentId() {
        return riskAssessmentId;
    }

    public boolean isRiskDegraded() {
        return riskDegraded;
    }

    public Instant getRiskHeldAt() {
        return riskHeldAt;
    }

    public Instant getRiskReviewedAt() {
        return riskReviewedAt;
    }

    public String getRiskReviewedBy() {
        return riskReviewedBy;
    }

    public String getRiskReviewReason() {
        return riskReviewReason;
    }

    public String getRiskSignals() {
        return riskSignals;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public SagaStep.Name getFailedStep() {
        return failedStep;
    }

    public boolean isCompensationComplete() {
        return compensationComplete;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "PaymentSaga{" + sagaId + ", payment=" + paymentId + ", " + status
                + ", " + amount() + '}';
    }
}
