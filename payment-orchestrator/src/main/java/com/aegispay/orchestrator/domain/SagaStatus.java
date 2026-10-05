package com.aegispay.orchestrator.domain;

import java.util.Set;

/**
 * Saga lifecycle states, with the legal transitions encoded in the enum itself.
 *
 * <p>Same discipline as {@link com.aegispay.common.domain.PaymentStatus}: keeping the machine
 * here rather than as scattered {@code if} statements means an illegal transition cannot be
 * expressed, and the whole lifecycle is reviewable in one place.
 *
 * <pre>
 *   STARTED ─▶ FUNDS_RESERVED ─▶ RISK_ASSESSED ─▶ AUTHORIZED ─▶ LEDGER_POSTED ─▶ CAPTURED ─▶ COMPLETED
 *      │             │      ▲          │              │               │             │
 *      │             │      │          │              │               │             │
 *      │             ▼      │          │              │               │             │
 *      │        RISK_HELD ──┘          │              │               │             │
 *      │        (analyst)              │              │               │             │
 *      │             │                 │              │               │             │
 *      │             └────────┬────────┴──────────────┴───────────────┴─────────────┘
 *      │                      ▼
 *      │               COMPENSATING ──▶ FAILED
 *      ▼                      │
 *   FAILED               (or) REJECTED
 * </pre>
 *
 * <p>{@link #RISK_HELD} is the one state the saga does not leave on its own. Everything else is
 * either progressing or terminal; a held payment is waiting on a person.
 *
 * <h2>Why the order is reserve, authorise, post, capture</h2>
 *
 * <p>Two orderings matter and neither is arbitrary:
 *
 * <ul>
 *   <li><strong>Reserve before authorise.</strong> Asking a provider to authorise money the
 *       customer does not have wastes a provider call and produces an avoidable decline. The
 *       reservation is also what makes the funds unspendable for the duration, so a second
 *       concurrent payment cannot claim them mid-saga.</li>
 *   <li><strong>Post to the ledger before capturing.</strong> Money should never move that has
 *       not been recorded. If the process dies between the two, the books show a transfer the
 *       wallets have not made — detectable by reconciliation and undone by a reversal. The
 *       opposite order would move real money with no record of why, which is strictly worse to
 *       discover.</li>
 * </ul>
 *
 * <p>{@link #COMPENSATING} is a real, persisted state rather than a transient flag. A crash
 * during compensation has to be distinguishable from a crash before it started, because the
 * recovery sweep treats them differently.
 */
public enum SagaStatus {

    /** Saga row committed; nothing external has been touched yet. */
    STARTED,

    /** Funds reserved on the payer's wallet. A hold now exists that failure must release. */
    FUNDS_RESERVED,

    /** A risk verdict has been recorded and it cleared the payment to continue. */
    RISK_ASSESSED,

    /**
     * Fraud controls held the payment and it is waiting on a human.
     *
     * <p><strong>Parked, not stalled, and the difference is load-bearing.</strong> The saga stops
     * here deliberately and may sit for hours; nothing will move it except an analyst's decision
     * arriving on {@code fraud-decision-applied}. The recovery sweep therefore <em>excludes</em> this
     * state — without that exclusion every held payment would be "recovered" once a minute, each
     * sweep incrementing its attempt count and re-entering a saga that is behaving exactly as
     * intended.
     *
     * <p>The customer's funds stay reserved throughout, which is why the reservation is extended when
     * a payment is parked. The default fifteen-minute hold is sized for a payment that completes in
     * seconds; without the extension the wallet's expiry sweeper would release the funds while this
     * saga still believed they were held.
     *
     * <p>Not terminal. A released payment continues from {@link #RISK_ASSESSED} through exactly the
     * same steps it would have taken had it never been held — no second code path, which is where
     * that kind of bug lives.
     */
    RISK_HELD,

    /**
     * A provider authorised the charge. An authorisation now exists that failure must void.
     */
    AUTHORIZED,

    /** The double entry is committed in the ledger. Failure must now reverse it. */
    LEDGER_POSTED,

    /** The reservation has been converted into a real debit and the payee credited. */
    CAPTURED,

    /** Terminal success. */
    COMPLETED,

    /**
     * Undoing side effects. Persisted, so a crash mid-compensation is distinguishable from a
     * crash before compensation began — the recovery sweep treats those differently.
     */
    COMPENSATING,

    /** Terminal failure. {@code compensationComplete} says whether anything is still outstanding. */
    FAILED,

    /** Refused by risk controls rather than by a fault. Terminal. */
    REJECTED;

    private static final Set<SagaStatus> TERMINAL = Set.of(COMPLETED, FAILED, REJECTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** True once a side effect exists somewhere that a failure would have to undo. */
    public boolean hasExternalEffects() {
        return this != STARTED;
    }

    /** The allow-list of next states. Anything absent is rejected by the aggregate. */
    public Set<SagaStatus> allowedTransitions() {
        return switch (this) {
            // Nothing external has happened yet, so a failure here needs no compensation.
            case STARTED -> Set.of(FUNDS_RESERVED, FAILED, REJECTED);
            // The risk step has three outcomes: cleared, held for a human, or refused outright.
            case FUNDS_RESERVED -> Set.of(RISK_ASSESSED, RISK_HELD, COMPENSATING, FAILED, REJECTED);
            // An analyst's RELEASE moves a held payment to RISK_ASSESSED, from where it takes exactly
            // the same path it would have taken had it never been held. A REJECT compensates.
            case RISK_HELD -> Set.of(RISK_ASSESSED, COMPENSATING, FAILED, REJECTED);
            case RISK_ASSESSED -> Set.of(AUTHORIZED, COMPENSATING, FAILED, REJECTED);
            case AUTHORIZED -> Set.of(LEDGER_POSTED, COMPENSATING, FAILED);
            case LEDGER_POSTED -> Set.of(CAPTURED, COMPENSATING, FAILED);
            // Past this point the money has moved. Compensation is no longer an option:
            // the only way forward is to finish recording it.
            case CAPTURED -> Set.of(COMPLETED, FAILED);
            case COMPENSATING -> Set.of(FAILED, REJECTED);
            case COMPLETED, FAILED, REJECTED -> Set.of();
        };
    }

    public boolean canTransitionTo(SagaStatus target) {
        return allowedTransitions().contains(target);
    }

    /**
     * Whether the saga is waiting on something outside the platform and must not be driven.
     *
     * <p>Distinct from {@link #isTerminal()}: a parked saga has more work to do, but not now and not
     * on its own. The driver stops when it reaches one of these, and the recovery sweep skips them —
     * treating a parked saga as abandoned would "recover" every held payment on every sweep.
     */
    public boolean isParked() {
        return this == RISK_HELD;
    }
}
