package com.aegispay.common.domain;

/**
 * Coarse risk buckets derived from the ML fraud probability.
 *
 * <p>Buckets exist because thresholds are a business/compliance decision that
 * changes far more often than code. Downstream services (notifications, admin
 * UI, analytics) branch on the bucket, never on the raw probability, so
 * retuning a threshold does not ripple through the platform.
 */
public enum RiskLevel {

    /** Routine traffic. Straight through. */
    LOW,

    /** Elevated. Allowed to proceed, but recorded for analyst review. */
    MEDIUM,

    /** Held pending verification and automatically sent for AI investigation. */
    HIGH,

    /** Reserved for confirmed-fraud patterns and hard rule violations. */
    CRITICAL;

    public boolean requiresInvestigation() {
        return this == HIGH || this == CRITICAL;
    }

    public boolean blocksSettlement() {
        return this == HIGH || this == CRITICAL;
    }
}
