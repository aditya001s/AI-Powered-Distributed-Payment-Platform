package com.aegispay.fraud.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
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
import java.util.UUID;

/**
 * An item in the analyst work queue.
 *
 * <p><strong>Why this is separate from {@link FraudAssessment}.</strong> An assessment is a fact
 * about a payment and never changes; an alert has a lifecycle and is mutated by whoever works it.
 * Merging them would give every assessment row five mostly-null workflow columns, and the queue
 * query would have to filter out the 99% of traffic that needs nobody.
 *
 * <p><strong>{@link #blocksPayment} is what orders the queue.</strong> An open alert on a held
 * payment is a customer waiting right now with their funds reserved; an open alert on a payment that
 * has already settled is a review that can wait until tomorrow. Sorting purely by risk score would
 * bury the first behind the second.
 */
@Entity
@Table(name = "fraud_alerts")
public class FraudAlert {

    public enum Status {
        OPEN,
        /** An analyst has taken it. */
        IN_REVIEW,
        /** Decided. Terminal. */
        RESOLVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "alert_id", nullable = false, unique = true, updatable = false, length = 48)
    private String alertId;

    /**
     * One alert per payment, enforced by a unique constraint.
     *
     * <p>A redelivered event must not add a second entry to the queue for the same payment: two
     * analysts would work it independently and could reach opposite conclusions, and whichever
     * committed second would win by accident.
     */
    @Column(name = "payment_id", nullable = false, unique = true, updatable = false, length = 48)
    private String paymentId;

    @Column(name = "assessment_id", nullable = false, updatable = false, length = 48)
    private String assessmentId;

    @Column(name = "initiator_user_id", nullable = false, updatable = false, length = 40)
    private String initiatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, updatable = false, length = 16)
    private RiskLevel riskLevel;

    @Column(name = "risk_score", nullable = false, updatable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 16)
    private FraudDecision decision;

    @Column(name = "summary", nullable = false, updatable = false, length = 512)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(name = "assigned_to", length = 40)
    private String assignedTo;

    @Column(name = "blocks_payment", nullable = false)
    private boolean blocksPayment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FraudAlert() {
        // JPA
    }

    /** Raises an alert for an assessment that needs a human. */
    public static FraudAlert raise(FraudAssessment assessment, String summary) {
        FraudAlert alert = new FraudAlert();
        alert.alertId = "ALR-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase();
        alert.paymentId = assessment.getPaymentId();
        alert.assessmentId = assessment.getAssessmentId();
        alert.initiatorUserId = assessment.getInitiatorUserId();
        alert.riskLevel = assessment.getRiskLevel();
        alert.riskScore = assessment.getRiskScore();
        alert.decision = assessment.getDecision();
        alert.summary = truncate(summary, 512);
        alert.status = Status.OPEN;
        // A HOLD stops the saga with the customer's funds still reserved, so it is the one
        // that is costing somebody something while it sits in the queue.
        alert.blocksPayment = assessment.getDecision() == FraudDecision.HOLD;
        alert.createdAt = Instant.now();
        alert.updatedAt = alert.createdAt;
        return alert;
    }

    // ----------------------------------------------------------------- mutators

    /**
     * Assigns the alert to an analyst.
     *
     * <p>Refuses if it is already resolved. Optimistic locking on {@code version} is what stops two
     * analysts both taking an open alert: the second write fails and that analyst is told to refresh,
     * rather than both of them working the same payment unaware of each other.
     */
    public void takeFor(String analystUserId) {
        if (status == Status.RESOLVED) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Alert " + alertId + " is already resolved and cannot be taken");
        }
        this.status = Status.IN_REVIEW;
        this.assignedTo = analystUserId;
        this.updatedAt = Instant.now();
    }

    /**
     * Closes the alert.
     *
     * <p>The decision itself is recorded separately in {@link AnalystDecision}, which is append-only.
     * This only closes the queue item: "who decided what and why" must survive independently of the
     * workflow state, because the workflow state is the part that gets overwritten.
     */
    public void resolve(String analystUserId) {
        if (status == Status.RESOLVED) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Alert " + alertId + " has already been decided");
        }
        this.status = Status.RESOLVED;
        if (this.assignedTo == null) {
            // Decided without being taken first, which is normal for an urgent hold.
            this.assignedTo = analystUserId;
        }
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
    }

    public boolean isOpen() {
        return status != Status.RESOLVED;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    public Long getId() {
        return id;
    }

    public String getAlertId() {
        return alertId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getAssessmentId() {
        return assessmentId;
    }

    public String getInitiatorUserId() {
        return initiatorUserId;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public FraudDecision getDecision() {
        return decision;
    }

    public String getSummary() {
        return summary;
    }

    public Status getStatus() {
        return status;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public boolean isBlocksPayment() {
        return blocksPayment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "FraudAlert{" + alertId + ", payment=" + paymentId + ", " + status
                + (blocksPayment ? ", BLOCKING" : "") + '}';
    }
}
