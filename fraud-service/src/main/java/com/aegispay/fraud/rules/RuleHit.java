package com.aegispay.fraud.rules;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;

/**
 * One deterministic rule firing on one payment.
 *
 * @param ruleId stable machine code, e.g. {@code RULE_BUST_OUT_PATTERN}. Stable because it is
 *               recorded on the assessment, tagged on a metric and quoted in an alert — renaming one
 *               silently breaks a dashboard and orphans the history.
 * @param reason human-readable, and written for the analyst who will read it at 2am rather than for
 *               the engineer who wrote the rule. It states the evidence, not the rule's name.
 */
public record RuleHit(String ruleId, Severity severity, String reason) {

    /**
     * How far a rule escalates the verdict.
     *
     * <p><strong>Rules escalate; they never de-escalate.</strong> A rule can only make a verdict more
     * cautious, never less. If a rule could lower a risk level the model raised, then adding a rule
     * would be capable of letting fraud through — and the interaction between two such rules would be
     * impossible to reason about.
     */
    public enum Severity {

        /**
         * Recorded as a reason, changes nothing.
         *
         * <p>Worth having: an analyst looking at a payment the model held wants to know which
         * patterns were present even when none of them was decisive on its own.
         */
        ADVISORY(null, null),

        /** Floors the verdict at MEDIUM / REVIEW: settle it, but put it in front of a human. */
        REVIEW(RiskLevel.MEDIUM, FraudDecision.REVIEW),

        /**
         * Floors the verdict at HIGH / HOLD: stop before charging a provider, keep the funds
         * reserved, wait for a human. Reversible.
         */
        HOLD(RiskLevel.HIGH, FraudDecision.HOLD),

        /**
         * Floors the verdict at CRITICAL / REJECT. Terminal and automatic.
         *
         * <p>Reserved for evidence that needs no judgement — which in practice means a human has
         * already confirmed fraud on this account more than once. A model score, however high, never
         * reaches here on its own: the {@link RiskLevel} contract says CRITICAL is for confirmed
         * patterns and hard violations, and a probability is neither.
         */
        BLOCK(RiskLevel.CRITICAL, FraudDecision.REJECT);

        private final RiskLevel minimumRiskLevel;
        private final FraudDecision minimumDecision;

        Severity(RiskLevel minimumRiskLevel, FraudDecision minimumDecision) {
            this.minimumRiskLevel = minimumRiskLevel;
            this.minimumDecision = minimumDecision;
        }

        /** The risk level this severity floors the verdict at, or null when it does not escalate. */
        public RiskLevel minimumRiskLevel() {
            return minimumRiskLevel;
        }

        /** The decision this severity floors the verdict at, or null when it does not escalate. */
        public FraudDecision minimumDecision() {
            return minimumDecision;
        }

        public boolean escalates() {
            return minimumRiskLevel != null;
        }
    }

    public RuleHit {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("a rule hit must name its rule");
        }
        if (severity == null) {
            throw new IllegalArgumentException("rule " + ruleId + " must state a severity");
        }
        if (reason == null || reason.isBlank()) {
            // A hit with no reason would reach an analyst as a bare rule id, which tells them a rule
            // fired but not what it saw.
            throw new IllegalArgumentException("rule " + ruleId + " must explain what it found");
        }
    }

    public static RuleHit advisory(String ruleId, String reason) {
        return new RuleHit(ruleId, Severity.ADVISORY, reason);
    }

    public static RuleHit review(String ruleId, String reason) {
        return new RuleHit(ruleId, Severity.REVIEW, reason);
    }

    public static RuleHit hold(String ruleId, String reason) {
        return new RuleHit(ruleId, Severity.HOLD, reason);
    }

    public static RuleHit block(String ruleId, String reason) {
        return new RuleHit(ruleId, Severity.BLOCK, reason);
    }
}
