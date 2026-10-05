package com.aegispay.common.domain;

/**
 * The fraud engine's verdict on a single transaction.
 *
 * <p>The fraud engine only ever returns a <em>recommendation</em>. It never
 * mutates a wallet, never posts to the ledger and never blocks an account: the
 * orchestrator's saga is the only component permitted to act, and irreversible
 * actions additionally require the human authorisation workflow in
 * ai-investigation-service.
 */
public enum FraudDecision {

    /** Continue the payment saga. */
    APPROVE,

    /** Let the payment settle, but raise an alert for an analyst. */
    REVIEW,

    /**
     * Stop before charging any provider and keep the funds reserved. Reversible:
     * a human can release or reject afterwards.
     */
    HOLD,

    /** Terminal refusal. The saga compensates and funds are released. */
    REJECT;

    /** True when the saga must stop instead of charging a provider. */
    public boolean haltsPayment() {
        return this == HOLD || this == REJECT;
    }
}
