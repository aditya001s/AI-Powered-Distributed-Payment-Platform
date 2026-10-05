package com.aegispay.fraud.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A human decision on a fraud alert. Append-only.
 *
 * <p><strong>Why append-only rather than a status column.</strong> The audit question is "who
 * decided what, when, and why", and a mutable column answers only the last of those. An analyst who
 * releases a payment and a second who later reverses it are two facts, and both have to survive —
 * particularly the first one, which is the one somebody will want to ask about.
 *
 * <p>A database trigger refuses {@code UPDATE} and {@code DELETE} on this table, so the guarantee
 * holds against ad-hoc SQL as well as against application code. That is the same discipline
 * ledger-service applies to its journal, and for the same reason: a comment saying "append-only" is
 * a hope, a trigger is a guarantee.
 *
 * <p>Every field is {@code updatable = false} as well, so Hibernate will not even generate an UPDATE
 * that the trigger would then have to reject.
 */
@Entity
@Table(name = "analyst_decisions")
public class AnalystDecision {

    /** What the analyst chose. Both outcomes are irreversible for the payment. */
    public enum Action {
        /**
         * Let the payment continue. The saga resumes and charges a provider.
         *
         * <p>The consequential one: releasing a payment that turns out to be fraud is a real loss,
         * which is why the reason is mandatory and the actor is taken from the token.
         */
        RELEASE,

        /** Refuse the payment terminally. The saga compensates and the funds are released. */
        REJECT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "decision_id", nullable = false, unique = true, updatable = false, length = 48)
    private String decisionId;

    @Column(name = "alert_id", nullable = false, updatable = false, length = 48)
    private String alertId;

    @Column(name = "payment_id", nullable = false, updatable = false, length = 48)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 16)
    private Action action;

    @Column(name = "reason", nullable = false, updatable = false, length = 512)
    private String reason;

    /**
     * The analyst, taken from the validated token and never from a request field.
     *
     * <p>"Who authorised this" is the first question asked about a released payment that turned out
     * to be fraud, and an answer the caller could choose for itself would be worthless.
     */
    @Column(name = "decided_by", nullable = false, updatable = false, length = 40)
    private String decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt = Instant.now();

    protected AnalystDecision() {
        // JPA
    }

    /**
     * Records a decision.
     *
     * @param reason mandatory. Enforced here and by a schema CHECK, because support tooling and
     *               batch scripts also write to this table and a blank reason makes the record
     *               useless for the only purpose it has.
     */
    public static AnalystDecision record(String alertId, String paymentId, Action action,
                                         String reason, String decidedBy) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "A fraud decision requires a reason: it is the only record of why a "
                            + "customer's payment was released or refused");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "a decision must name the analyst who made it, taken from their token");
        }
        if (action == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "A fraud decision must be RELEASE or REJECT");
        }

        AnalystDecision decision = new AnalystDecision();
        decision.decisionId = "DEC-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase();
        decision.alertId = alertId;
        decision.paymentId = paymentId;
        decision.action = action;
        decision.reason = reason.length() <= 512 ? reason : reason.substring(0, 509) + "...";
        decision.decidedBy = decidedBy;
        decision.decidedAt = Instant.now();
        return decision;
    }

    public boolean releasesPayment() {
        return action == Action.RELEASE;
    }

    public Long getId() {
        return id;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public String getAlertId() {
        return alertId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public Action getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    @Override
    public String toString() {
        return "AnalystDecision{" + decisionId + ", " + action + " on " + paymentId
                + " by " + decidedBy + '}';
    }
}
