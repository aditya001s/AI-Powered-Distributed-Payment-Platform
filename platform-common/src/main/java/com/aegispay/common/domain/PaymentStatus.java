package com.aegispay.common.domain;

import java.util.Set;

/**
 * Payment lifecycle states, with the legal transitions encoded in the enum
 * itself.
 *
 * <p>Encoding the state machine here (rather than as scattered {@code if}
 * statements) means an illegal transition is impossible to express, and the
 * whole lifecycle is reviewable in one place:
 *
 * <pre>
 *   CREATED ──▶ RISK_ASSESSED ──▶ AUTHORIZED ──▶ CAPTURED ──▶ COMPLETED
 *      │              │                │                         │
 *      │              ├──▶ ON_HOLD ────┤                         ├──▶ REFUNDED
 *      │              │       │        │                         │
 *      ▼              ▼       ▼        ▼                         ▼
 *   FAILED         REJECTED  ...    FAILED                  PARTIALLY_REFUNDED
 * </pre>
 */
public enum PaymentStatus {

    /** Persisted and idempotency-protected; funds not yet reserved. */
    CREATED,

    /** Funds reserved on the source wallet; awaiting the fraud verdict. */
    FUNDS_RESERVED,

    /** Fraud engine returned a verdict. */
    RISK_ASSESSED,

    /** Held by fraud controls. Funds stay reserved; awaits a human decision. */
    ON_HOLD,

    /** A provider authorised the charge. */
    AUTHORIZED,

    /** Reservation converted into an actual debit. */
    CAPTURED,

    /** Ledger posted, notifications emitted. Terminal success. */
    COMPLETED,

    /** Refused by fraud controls or by an analyst. Terminal. */
    REJECTED,

    /** Could not be completed; every compensation has run. Terminal. */
    FAILED,

    /** Fully refunded. Terminal. */
    REFUNDED,

    /** Some but not all of the amount was refunded. */
    PARTIALLY_REFUNDED;

    private static final Set<PaymentStatus> TERMINAL =
            Set.of(COMPLETED, REJECTED, FAILED, REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** True once money has definitely moved and a refund is meaningful. */
    public boolean isRefundable() {
        return this == COMPLETED || this == PARTIALLY_REFUNDED;
    }

    /**
     * The allow-list of next states. Anything absent here is rejected by the
     * payment aggregate.
     */
    public Set<PaymentStatus> allowedTransitions() {
        return switch (this) {
            case CREATED -> Set.of(FUNDS_RESERVED, REJECTED, FAILED);
            case FUNDS_RESERVED -> Set.of(RISK_ASSESSED, FAILED, REJECTED);
            case RISK_ASSESSED -> Set.of(AUTHORIZED, ON_HOLD, REJECTED, FAILED);
            case ON_HOLD -> Set.of(AUTHORIZED, REJECTED, FAILED);
            case AUTHORIZED -> Set.of(CAPTURED, FAILED);
            case CAPTURED -> Set.of(COMPLETED, FAILED);
            case COMPLETED -> Set.of(REFUNDED, PARTIALLY_REFUNDED);
            case PARTIALLY_REFUNDED -> Set.of(REFUNDED, PARTIALLY_REFUNDED);
            case REJECTED, FAILED, REFUNDED -> Set.of();
        };
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return allowedTransitions().contains(target);
    }
}
