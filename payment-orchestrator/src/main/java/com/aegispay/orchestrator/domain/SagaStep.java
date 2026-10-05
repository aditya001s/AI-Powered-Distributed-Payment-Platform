package com.aegispay.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * One attempt at one saga step.
 *
 * <p><strong>Recorded before the side effect is attempted, not after.</strong> That ordering is
 * the point of this table. A row left {@link Status#ATTEMPTED} after a crash means "we may have
 * done this — check", which is exactly what compensation needs to assume. Recording afterwards
 * would make a crash indistinguishable from a step that never started, and compensation would
 * then have to guess.
 *
 * <p>One row per <em>attempt</em>, not per step. "Reserve succeeded on the third try" and
 * "reserve succeeded" describe very different system health, and collapsing them would hide a
 * provider that is limping.
 */
@Entity
@Table(name = "saga_steps")
public class SagaStep {

    /** The steps a payment saga runs through, in order. */
    public enum Name {
        /** Reserve funds on the payer's wallet. */
        RESERVE_FUNDS,
        /** Record a risk verdict. Auto-approved until Phase 6. */
        ASSESS_RISK,
        /** Route to a provider and obtain an authorisation, falling back as needed. */
        AUTHORIZE,
        /** Commit the double entry. Deliberately before capture. */
        POST_LEDGER,
        /** Convert the reservation into a real debit and credit the payee. */
        CAPTURE_FUNDS,
        /** Emit the terminal success event. */
        COMPLETE,

        // ---- Compensating steps, run in reverse order of the above ----
        /** Reverse the ledger posting with an opposite entry. */
        REVERSE_LEDGER,
        /** Void the provider authorisation so the hold on the rail is released. */
        VOID_AUTHORIZATION,
        /** Release the wallet reservation so the customer's funds are spendable again. */
        RELEASE_FUNDS;

        public boolean isCompensating() {
            return this == REVERSE_LEDGER || this == VOID_AUTHORIZATION || this == RELEASE_FUNDS;
        }
    }

    public enum Status {
        /** In flight, or interrupted. The effect may or may not have happened. */
        ATTEMPTED,
        SUCCEEDED,
        FAILED,
        /** Deliberately not run, e.g. compensating a step that never took effect. */
        SKIPPED,
        /** This step's effect has since been undone. */
        COMPENSATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "saga_id", nullable = false, updatable = false, length = 48)
    private String sagaId;

    @Column(name = "payment_id", nullable = false, updatable = false, length = 48)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_name", nullable = false, updatable = false, length = 32)
    private Name stepName;

    @Column(name = "attempt", nullable = false, updatable = false)
    private int attempt = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ATTEMPTED;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "error_code", length = 48)
    private String errorCode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected SagaStep() {
        // JPA
    }

    /** Opens a step record. Persist this <em>before</em> attempting the side effect. */
    public static SagaStep attempt(String sagaId, String paymentId, Name stepName, int attempt) {
        SagaStep step = new SagaStep();
        step.sagaId = sagaId;
        step.paymentId = paymentId;
        step.stepName = stepName;
        step.attempt = Math.max(1, attempt);
        step.status = Status.ATTEMPTED;
        step.startedAt = Instant.now();
        return step;
    }

    /** Records a step that was deliberately not run, with the reason it was unnecessary. */
    public static SagaStep skipped(String sagaId, String paymentId, Name stepName, String why) {
        SagaStep step = attempt(sagaId, paymentId, stepName, 1);
        step.status = Status.SKIPPED;
        step.detail = why;
        step.finish();
        return step;
    }

    public void succeeded(String detail) {
        this.status = Status.SUCCEEDED;
        this.detail = truncate(detail, 512);
        finish();
    }

    public void failed(String errorCode, String reason) {
        this.status = Status.FAILED;
        this.errorCode = truncate(errorCode, 48);
        this.detail = truncate(reason, 512);
        finish();
    }

    /** Marks a previously successful step as undone by its compensating action. */
    public void compensated(String detail) {
        this.status = Status.COMPENSATED;
        this.detail = truncate(detail, 512);
        finish();
    }

    private void finish() {
        this.finishedAt = Instant.now();
        this.durationMs = Duration.between(startedAt, finishedAt).toMillis();
    }

    public boolean isInFlight() {
        return status == Status.ATTEMPTED;
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }

    /**
     * Truncates rather than letting the insert fail.
     *
     * <p>A provider's error message is outside our control and can be arbitrarily long. Losing
     * the tail of a diagnostic string is a far better outcome than failing the write that
     * records why a payment failed — which would leave the saga with no explanation at all.
     */
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

    public Name getStepName() {
        return stepName;
    }

    public int getAttempt() {
        return attempt;
    }

    public Status getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        return "SagaStep{" + stepName + "#" + attempt + " " + status + '}';
    }
}
