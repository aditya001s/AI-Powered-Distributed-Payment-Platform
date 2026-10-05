package com.aegispay.analytics.domain;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
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
import java.util.List;

/**
 * One payment, as the dashboard sees it.
 *
 * <h2>This is a read model, not a record of truth</h2>
 *
 * <p>payment-service owns the payment, ledger-service owns the money, fraud-service owns the verdict.
 * Every field here is a copy assembled from events and kept for querying. Nothing in this service may be
 * the only place a fact exists — which is what makes it safe to drop the table and rebuild it by
 * replaying the topics.
 *
 * <h2>Why every setter is a merge rather than an assignment</h2>
 *
 * <p>The events that build this row arrive on different topics, and Kafka orders records only within one
 * topic partition. So a completion can be applied before the creation it belongs to.
 *
 * <p>Two rules make that survivable, and both are enforced here rather than left to each listener:
 *
 * <ul>
 *   <li><strong>Status only moves forward</strong> ({@link StatusRank}). A late {@code payment-created}
 *       cannot drag a settled payment back to {@code CREATED}, which would report it as in-flight
 *       forever with nothing left to correct it.</li>
 *   <li><strong>A later event never nulls a field an earlier one filled.</strong> Every apply method
 *       keeps what it already has when the incoming value is absent — {@code payment-completed} carries
 *       no payment type, and applying it must not erase the type {@code payment-created} recorded.</li>
 * </ul>
 *
 * <p>Putting both in the aggregate rather than in the listeners matters: there are six listeners, and a
 * rule implemented six times is a rule implemented five times.
 */
@Entity
@Table(name = "payment_facts")
public class PaymentFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false, length = 48)
    private String paymentId;

    @Column(name = "transaction_id", length = 48)
    private String transactionId;

    @Column(name = "initiator_user_id", length = 40)
    private String initiatorUserId;

    @Column(name = "destination_user_id", length = 40)
    private String destinationUserId;

    @Column(name = "source_wallet_id", length = 48)
    private String sourceWalletId;

    @Column(name = "destination_wallet_id", length = 48)
    private String destinationWalletId;

    @Column(name = "amount_minor")
    private Long amountMinor;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "fee_minor")
    private Long feeMinor;

    /**
     * Cumulative refunded amount.
     *
     * <p>Assigned from the event's running total, never added to. A second partial refund carries the
     * new total, so adding would double-count it — the same trap the whole projection is shaped to
     * avoid.
     */
    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 32)
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentStatus status;

    @Column(name = "status_rank", nullable = false)
    private short statusRank;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 16)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "fraud_decision", length = 16)
    private FraudDecision fraudDecision;

    @Column(name = "fraud_probability")
    private Double fraudProbability;

    @Column(name = "risk_score")
    private Short riskScore;

    @Column(name = "degraded_mode", nullable = false)
    private boolean degradedMode;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "rule_hits", length = 512)
    private String ruleHits;

    @Column(name = "provider_id", length = 32)
    private String providerId;

    @Column(name = "provider_attempt_count")
    private Short providerAttemptCount;

    @Column(name = "ledger_transaction_id", length = 48)
    private String ledgerTransactionId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "end_to_end_latency_ms")
    private Long endToEndLatencyMs;

    @Column(name = "scoring_latency_ms")
    private Long scoringLatencyMs;

    /**
     * The time axis for every chart.
     *
     * <p>Only ever moves earlier. A payment cannot have been created after something that already
     * happened to it, so when the authoritative {@code payment-created} arrives late it corrects the
     * seeded value downwards and never upwards.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PaymentFact() {
        // JPA
    }

    /**
     * Creates the row for a payment this projection has not seen before.
     *
     * <p>Any of the six listeners can be the one that creates it, because any of them can be the first
     * to arrive. That is why the seed takes a status and a timestamp rather than assuming
     * {@code payment-created} came first.
     */
    public static PaymentFact seed(String paymentId, PaymentStatus status, Instant occurredAt) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("a payment fact must name its payment");
        }
        Instant when = occurredAt == null ? Instant.now() : occurredAt;

        PaymentFact fact = new PaymentFact();
        fact.paymentId = paymentId;
        fact.applyStatus(status, when);
        fact.createdAt = when;
        fact.firstSeenAt = Instant.now();
        fact.lastEventAt = when;
        fact.updatedAt = fact.firstSeenAt;
        return fact;
    }

    // ------------------------------------------------------------------ merges

    /**
     * Moves the status forward, or leaves it alone.
     *
     * @return true when the status actually advanced, so a caller can log the no-op case
     */
    public boolean applyStatus(PaymentStatus candidate, Instant occurredAt) {
        observe(occurredAt);
        if (!StatusRank.advances(this.status, candidate)) {
            return false;
        }
        this.status = candidate;
        this.statusRank = (short) StatusRank.of(candidate);
        return true;
    }

    /** Everything {@code payment-created} knows. */
    public void applyCreation(String transactionId, String initiatorUserId,
                              String destinationUserId, String sourceWalletId,
                              String destinationWalletId, Money amount, PaymentType paymentType,
                              Instant createdAt) {

        this.transactionId = keep(this.transactionId, transactionId);
        this.initiatorUserId = keep(this.initiatorUserId, initiatorUserId);
        this.destinationUserId = keep(this.destinationUserId, destinationUserId);
        this.sourceWalletId = keep(this.sourceWalletId, sourceWalletId);
        this.destinationWalletId = keep(this.destinationWalletId, destinationWalletId);
        this.paymentType = this.paymentType == null ? paymentType : this.paymentType;
        applyAmount(amount);

        // The authoritative creation time, and the only event that carries it. Moves the seeded value
        // earlier and never later: a payment cannot have been created after something that already
        // happened to it, so a max() here would let an out-of-order arrival invent a future birth date.
        if (createdAt != null && createdAt.isBefore(this.createdAt)) {
            this.createdAt = createdAt;
        }
        applyStatus(PaymentStatus.CREATED, createdAt);
    }

    /** Everything {@code fraud-checked} knows. */
    public void applyRisk(RiskLevel riskLevel, FraudDecision decision, double fraudProbability,
                          String modelVersion, List<String> ruleHits, long scoringLatencyMs,
                          boolean degradedMode, Instant evaluatedAt) {

        observe(evaluatedAt);

        this.riskLevel = riskLevel == null ? this.riskLevel : riskLevel;
        this.fraudDecision = decision == null ? this.fraudDecision : decision;

        // Stored together, always. The database CHECKs that the score is the rounded probability, so
        // writing one without the other would fail the insert -- which is the point: they are the same
        // fact twice and must not be able to drift.
        if (Double.isFinite(fraudProbability)) {
            this.fraudProbability = fraudProbability;
            this.riskScore = (short) Math.round(fraudProbability * 100);
        }

        this.modelVersion = keep(this.modelVersion, modelVersion);
        this.ruleHits = keep(this.ruleHits, joinRuleHits(ruleHits));
        this.scoringLatencyMs = scoringLatencyMs > 0 ? scoringLatencyMs : this.scoringLatencyMs;

        // Latched on rather than overwritten. Once a verdict on this payment was reached without the
        // model, that remains true however many later events arrive -- and clearing it would erase the
        // only trace that the platform decided blind.
        this.degradedMode = this.degradedMode || degradedMode;
    }

    /** Everything {@code payment-completed} knows. */
    public void applyCompletion(Money amount, Money fee, String providerId,
                                List<String> providerAttempts, String ledgerTransactionId,
                                long endToEndLatencyMs, Instant completedAt) {

        applyAmount(amount);
        if (fee != null) {
            this.feeMinor = fee.amountMinor();
        }
        this.providerId = keep(this.providerId, providerId);
        this.ledgerTransactionId = keep(this.ledgerTransactionId, ledgerTransactionId);

        // Counted from the list rather than incremented per attempt. The list is the whole truth on
        // every delivery of this event, so assigning is idempotent where adding would not be.
        if (providerAttempts != null && !providerAttempts.isEmpty()) {
            this.providerAttemptCount = (short) providerAttempts.size();
        }
        if (endToEndLatencyMs > 0) {
            this.endToEndLatencyMs = endToEndLatencyMs;
        }
        if (completedAt != null) {
            this.completedAt = completedAt;
        }
        applyStatus(PaymentStatus.COMPLETED, completedAt);
        clampCompletionToCreation();
    }

    /** Everything {@code payment-failed} knows. */
    public void applyFailure(Money amount, String failureCode, String failureReason,
                             List<String> providerAttempts, Instant failedAt) {

        applyAmount(amount);
        this.failureCode = keep(this.failureCode, failureCode);
        this.failureReason = keep(this.failureReason, truncate(failureReason, 512));

        if (providerAttempts != null && !providerAttempts.isEmpty()) {
            this.providerAttemptCount = (short) providerAttempts.size();
        }
        if (failedAt != null) {
            this.completedAt = failedAt;
        }
        applyStatus(PaymentStatus.FAILED, failedAt);
        clampCompletionToCreation();
    }

    /** Everything {@code payment-rejected} knows. */
    public void applyRejection(Money amount, RiskLevel riskLevel, FraudDecision decision,
                               String reasonCode, String reason, Instant rejectedAt) {

        applyAmount(amount);
        this.riskLevel = riskLevel == null ? this.riskLevel : riskLevel;
        this.fraudDecision = decision == null ? this.fraudDecision : decision;
        this.failureCode = keep(this.failureCode, reasonCode);
        this.failureReason = keep(this.failureReason, truncate(reason, 512));

        if (rejectedAt != null) {
            this.completedAt = rejectedAt;
        }
        applyStatus(PaymentStatus.REJECTED, rejectedAt);
        clampCompletionToCreation();
    }

    /** Everything {@code payment-refunded} knows. */
    public void applyRefund(Money totalRefunded, Money originalAmount, boolean fullRefund,
                            String ledgerTransactionId, Instant refundedAt) {

        applyAmount(originalAmount);
        this.ledgerTransactionId = keep(this.ledgerTransactionId, ledgerTransactionId);

        // Assigned from the running total the event carries, not added. A second partial refund reports
        // the new cumulative figure, so adding would count the first one twice.
        if (totalRefunded != null) {
            this.refundedMinor = Math.max(0L, totalRefunded.amountMinor());
        }
        applyStatus(fullRefund ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED,
                refundedAt);
    }

    /** {@code payment-approved} carries a verdict but no new money facts. */
    public void applyApproval(RiskLevel riskLevel, double fraudProbability, Instant approvedAt) {
        observe(approvedAt);
        this.riskLevel = riskLevel == null ? this.riskLevel : riskLevel;

        if (Double.isFinite(fraudProbability) && this.fraudProbability == null) {
            this.fraudProbability = fraudProbability;
            this.riskScore = (short) Math.round(fraudProbability * 100);
        }
        applyStatus(PaymentStatus.RISK_ASSESSED, approvedAt);
    }

    // ---------------------------------------------------------------- internals

    private void applyAmount(Money amount) {
        if (amount == null) {
            return;
        }
        // Only filled once. Every event carrying an amount carries the same amount, so re-assigning is
        // harmless -- but a payment's amount is immutable, and treating it as such means a producer bug
        // that changed it shows up as a mismatch in a test rather than as silently drifting volume.
        if (this.amountMinor == null) {
            this.amountMinor = Math.max(0L, amount.amountMinor());
        }
        this.currency = keep(this.currency, amount.currency());
    }

    /**
     * Keeps {@code completed_at >= created_at}.
     *
     * <p>The database CHECKs this, so a violation would abort the whole batch. It is reachable without
     * anybody being wrong: if a completion is applied before its creation, {@code created_at} is seeded
     * from the completion's own timestamp, and a subsequent clock difference between two services can
     * leave the terminal timestamp a few milliseconds earlier. Clamping is the right response — the
     * alternative is losing the event over a rounding difference in a diagnostic field.
     */
    private void clampCompletionToCreation() {
        if (completedAt != null && createdAt != null && completedAt.isBefore(createdAt)) {
            this.createdAt = completedAt;
        }
    }

    private void observe(Instant occurredAt) {
        Instant when = occurredAt == null ? Instant.now() : occurredAt;
        if (lastEventAt == null || when.isAfter(lastEventAt)) {
            this.lastEventAt = when;
        }
        this.updatedAt = Instant.now();
    }

    private static String keep(String current, String candidate) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return candidate == null || candidate.isBlank() ? current : candidate;
    }

    private static String joinRuleHits(List<String> ruleHits) {
        if (ruleHits == null || ruleHits.isEmpty()) {
            return null;
        }
        return truncate(String.join(",", ruleHits), 512);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    // ----------------------------------------------------------------- queries

    /** Whether routing had to fall back to a second provider. */
    public boolean fellBack() {
        return providerAttemptCount != null && providerAttemptCount > 1;
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public Money amount() {
        return amountMinor == null || currency == null
                ? null
                : Money.ofMinor(amountMinor, currency);
    }

    public List<String> ruleHitList() {
        if (ruleHits == null || ruleHits.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(ruleHits.split(","))
                .map(String::trim)
                .filter(hit -> !hit.isEmpty())
                .toList();
    }

    public Long getId() {
        return id;
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

    public String getDestinationUserId() {
        return destinationUserId;
    }

    public String getSourceWalletId() {
        return sourceWalletId;
    }

    public String getDestinationWalletId() {
        return destinationWalletId;
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

    public long getRefundedMinor() {
        return refundedMinor;
    }

    public PaymentType getPaymentType() {
        return paymentType;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public short getStatusRank() {
        return statusRank;
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

    public Short getRiskScore() {
        return riskScore;
    }

    public boolean isDegradedMode() {
        return degradedMode;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getRuleHits() {
        return ruleHits;
    }

    public String getProviderId() {
        return providerId;
    }

    public Short getProviderAttemptCount() {
        return providerAttemptCount;
    }

    public String getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getEndToEndLatencyMs() {
        return endToEndLatencyMs;
    }

    public Long getScoringLatencyMs() {
        return scoringLatencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        // No user ids and no amounts: this reaches logs, and a read model of every payment on the
        // platform is exactly the thing not to spill into one.
        return "PaymentFact{" + paymentId + ", " + status + '}';
    }
}
