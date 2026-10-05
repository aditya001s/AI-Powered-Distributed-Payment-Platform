package com.aegispay.payment.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
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

/**
 * A payment and its lifecycle.
 *
 * <h2>State transitions are guarded, not suggested</h2>
 *
 * <p>Every status change goes through {@link #transitionTo}, which consults the
 * transition table on {@link PaymentStatus} and throws on anything illegal. That single
 * choke point is what makes the dangerous outcomes impossible to express:
 *
 * <ul>
 *   <li><strong>Double capture</strong> — charging the customer twice for one payment.
 *       {@code CAPTURED} has no transition to itself or back to {@code AUTHORIZED}.</li>
 *   <li><strong>Settling after refusal</strong> — {@code REJECTED} and {@code FAILED} are
 *       terminal with no outgoing transitions at all.</li>
 *   <li><strong>Refunding money that never moved</strong> — only {@code COMPLETED} and
 *       {@code PARTIALLY_REFUNDED} can reach a refunded state.</li>
 * </ul>
 *
 * <p>This matters most because Kafka delivers at least once, so every handler will
 * eventually see a duplicate event. A replayed {@code payment-completed} attempts an
 * illegal transition and is rejected, which is what turns at-least-once delivery into
 * effectively-once state change without needing a separate de-duplication table.
 *
 * <h2>Who drives these transitions since Phase 5</h2>
 *
 * <p>Not this service. payment-orchestrator owns the saga; this row is advanced from the outcome
 * events it publishes, which is why {@link #advanceTo} exists alongside {@link #transitionTo}. The
 * wallet reservation handle is deliberately <em>not</em> stored here any more: releasing it is the
 * saga's job, and a copy this service could never act on would only invite someone to try.
 *
 * <h2>Risk context is immutable</h2>
 *
 * <p>The risk signal columns are written once at construction. The fraud model must score
 * the context the user actually transacted in; re-deriving device or location data later
 * would score a different situation and make a disputed decision impossible to reproduce.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "payment_id", nullable = false, unique = true, updatable = false, length = 48)
    private String paymentId;

    /** Human-facing reference. Stable once quoted to a customer. */
    @Column(name = "transaction_id", nullable = false, unique = true, updatable = false, length = 48)
    private String transactionId;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 120)
    private String idempotencyKey;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "description", length = 256)
    private String description;

    // ---- Risk context: written once, never recomputed ----

    @Column(name = "device_id", updatable = false, length = 96)
    private String deviceId;

    @Column(name = "device_fingerprint", updatable = false, length = 128)
    private String deviceFingerprint;

    @Column(name = "ip_address", updatable = false, length = 64)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false, length = 256)
    private String userAgent;

    @Column(name = "country_code", updatable = false, length = 2)
    private String countryCode;

    @Column(name = "city", updatable = false, length = 96)
    private String city;

    @Column(name = "latitude", updatable = false)
    private Double latitude;

    @Column(name = "longitude", updatable = false)
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 24)
    private Channel channel = Channel.UNKNOWN;

    @Column(name = "merchant_id", updatable = false, length = 48)
    private String merchantId;

    @Column(name = "merchant_category", updatable = false, length = 48)
    private String merchantCategory;

    // ---- Risk outcome. Populated by fraud-service in Phase 6. ----

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 16)
    private RiskLevel riskLevel;

    @Column(name = "fraud_probability")
    private Double fraudProbability;

    @Enumerated(EnumType.STRING)
    @Column(name = "fraud_decision", length = 16)
    private FraudDecision fraudDecision;

    // ---- Provider outcome. Populated by the orchestrator in Phase 5. ----

    @Column(name = "provider_id", length = 32)
    private String providerId;

    @Column(name = "provider_reference", length = 96)
    private String providerReference;

    @Column(name = "failure_code", length = 48)
    private String failureCode;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Payment() {
        // JPA
    }

    public static Payment create(String paymentId,
                                 String transactionId,
                                 String idempotencyKey,
                                 String initiatorUserId,
                                 String sourceWalletId,
                                 String destinationWalletId,
                                 String destinationUserId,
                                 Money amount,
                                 PaymentType paymentType,
                                 String description,
                                 RiskSignals signals) {
        Payment payment = new Payment();
        payment.paymentId = paymentId;
        payment.transactionId = transactionId;
        payment.idempotencyKey = idempotencyKey;
        payment.initiatorUserId = initiatorUserId;
        payment.sourceWalletId = sourceWalletId;
        payment.destinationWalletId = destinationWalletId;
        payment.destinationUserId = destinationUserId;
        payment.amountMinor = amount.amountMinor();
        payment.currency = amount.currency();
        payment.feeMinor = 0L;
        payment.paymentType = paymentType == null ? PaymentType.P2P_TRANSFER : paymentType;
        payment.status = PaymentStatus.CREATED;
        payment.description = description;
        payment.applySignals(signals);
        payment.createdAt = Instant.now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    private void applySignals(RiskSignals signals) {
        if (signals == null) {
            this.channel = Channel.UNKNOWN;
            return;
        }
        this.deviceId = signals.deviceId();
        this.deviceFingerprint = signals.deviceFingerprint();
        this.ipAddress = signals.ipAddress();
        this.userAgent = truncate(signals.userAgent(), 256);
        this.countryCode = signals.countryCode();
        this.city = signals.city();
        this.latitude = signals.latitude();
        this.longitude = signals.longitude();
        this.channel = signals.channel() == null ? Channel.UNKNOWN : signals.channel();
        this.merchantId = signals.merchantId();
        this.merchantCategory = signals.merchantCategory();
    }

    // ---------------------------------------------------------------- queries

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public Money fee() {
        return Money.ofMinor(feeMinor, currency);
    }

    public Money refunded() {
        return Money.ofMinor(refundedMinor, currency);
    }

    /** How much of this payment can still be refunded. */
    public Money refundableRemaining() {
        return Money.ofMinor(Math.max(0L, amountMinor - refundedMinor), currency);
    }

    public RiskSignals riskSignals() {
        return new RiskSignals(deviceId, deviceFingerprint, ipAddress, userAgent,
                countryCode, city, latitude, longitude, channel, merchantId, merchantCategory);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    // --------------------------------------------------------------- mutators

    /**
     * The single guarded state transition.
     *
     * <p>Every status change goes through here. Anything outside the table on
     * {@link PaymentStatus} throws, which is what makes double capture and
     * settle-after-refusal unrepresentable rather than merely discouraged.
     *
     * @throws IllegalStateTransitionException if the move is not permitted
     */
    public void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(
                    ErrorCode.INVALID_PAYMENT_STATE, "Payment " + paymentId,
                    status.name(), target.name());
        }
        this.status = target;
        this.updatedAt = Instant.now();

        // Stamped here rather than by the caller, so the column cannot disagree with
        // the status. A CHECK constraint enforces the same rule at the storage layer.
        if (target == PaymentStatus.COMPLETED && completedAt == null) {
            this.completedAt = this.updatedAt;
        }
    }

    /**
     * Attempts a transition, reporting whether it was legal instead of throwing.
     *
     * <p>For event handlers, where a duplicate delivery is expected rather than
     * exceptional: a replayed {@code payment-completed} should be quietly ignored, not
     * turned into an error and retried into a dead-letter topic.
     */
    public boolean tryTransitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            return false;
        }
        transitionTo(target);
        return true;
    }

    /**
     * The forward sequence a successful payment passes through.
     *
     * <p>Named explicitly rather than derived from {@link PaymentStatus#allowedTransitions()},
     * because that graph also contains the failure edges — walking it would happily "advance" a
     * payment to {@code FAILED} on the way to somewhere else.
     */
    private static final java.util.List<PaymentStatus> FORWARD_PATH = java.util.List.of(
            PaymentStatus.CREATED,
            PaymentStatus.FUNDS_RESERVED,
            PaymentStatus.RISK_ASSESSED,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.CAPTURED,
            PaymentStatus.COMPLETED);

    /**
     * Moves the payment forward to {@code target}, one guarded hop at a time.
     *
     * <p><strong>Why more than one hop is needed at all.</strong> Since Phase 5 the saga lives in
     * payment-orchestrator and this row catches up from its outcome events. Those events arrive on
     * different topics, and Kafka orders records only within a partition of a single topic — so
     * {@code payment-completed} can genuinely arrive before {@code payment-approved}. A listener
     * that could only take one step would either reject the out-of-order event or have to invent a
     * buffering scheme; walking forward to the state the event asserts is both simpler and correct,
     * because a later event is strictly more informative than an earlier one.
     *
     * <p>Every hop still goes through {@link #transitionTo}, so nothing outside the transition table
     * becomes expressible. What this method adds is the ability to take several legal steps at once,
     * not permission to take an illegal one.
     *
     * @return {@code false} when the payment is already at or past {@code target}, or has left the
     *         forward path entirely (it failed, was rejected, or was refunded). Both are ordinary
     *         outcomes for a redelivered event and neither is an error.
     */
    public boolean advanceTo(PaymentStatus target) {
        int from = FORWARD_PATH.indexOf(status);
        int to = FORWARD_PATH.indexOf(target);

        if (from < 0 || to < 0 || to <= from) {
            return false;
        }
        for (int step = from + 1; step <= to; step++) {
            transitionTo(FORWARD_PATH.get(step));
        }
        return true;
    }

    /** Records the fraud verdict. Does not itself change status; the caller decides. */
    public void recordRiskAssessment(RiskLevel riskLevel, Double probability, FraudDecision decision) {
        this.riskLevel = riskLevel;
        this.fraudProbability = probability;
        this.fraudDecision = decision;
        this.updatedAt = Instant.now();
    }

    public void recordProvider(String providerId, String providerReference, Money fee) {
        this.providerId = providerId;
        this.providerReference = providerReference;
        if (fee != null) {
            this.feeMinor = fee.amountMinor();
        }
        this.updatedAt = Instant.now();
    }

    public void recordFailure(String failureCode, String failureReason) {
        this.failureCode = failureCode;
        this.failureReason = truncate(failureReason, 512);
        this.updatedAt = Instant.now();
    }

    /**
     * Applies a refund and returns the resulting status.
     *
     * <p>Refuses to over-refund. The check is here <em>and</em> as a CHECK constraint,
     * because refunds are frequently driven by support tooling and batch scripts, not only
     * by this code path.
     */
    public PaymentStatus applyRefund(Money refundAmount) {
        if (!status.isRefundable()) {
            throw new IllegalStateTransitionException(
                    ErrorCode.REFUND_NOT_ALLOWED, "Payment " + paymentId,
                    status.name(), PaymentStatus.REFUNDED.name());
        }
        if (!refundAmount.isPositive()) {
            throw new IllegalArgumentException("Refund amount must be positive, got " + refundAmount);
        }
        long newTotal = Math.addExact(refundedMinor, refundAmount.amountMinor());
        if (newTotal > amountMinor) {
            throw new IllegalArgumentException(
                    "Refunding " + refundAmount + " would exceed the payment amount "
                            + amount() + " (already refunded " + refunded() + ")");
        }

        this.refundedMinor = newTotal;
        PaymentStatus target = newTotal == amountMinor
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;

        // PARTIALLY_REFUNDED may legitimately repeat, so the transition table permits
        // that one self-transition. Every other state forbids it.
        transitionTo(target);
        return target;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
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

    public String getIdempotencyKey() {
        return idempotencyKey;
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

    public PaymentStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public Double getFraudProbability() {
        return fraudProbability;
    }

    public FraudDecision getFraudDecision() {
        return fraudDecision;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public long getRefundedMinor() {
        return refundedMinor;
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
        return "Payment{" + paymentId + ", " + amount() + ", " + status + '}';
    }
}
