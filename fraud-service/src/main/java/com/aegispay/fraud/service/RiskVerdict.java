package com.aegispay.fraud.service;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.fraud.client.MlScoringClient;
import com.aegispay.fraud.config.FraudProperties;
import com.aegispay.fraud.rules.RuleHit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The combined verdict: a probability from the model, floors from the rules, and the decision.
 *
 * <p><strong>This is where APPROVE / REVIEW / HOLD / REJECT is decided, and deliberately not in
 * ml-service.</strong> Bucketing a probability needs the deterministic rules as well as the model, and
 * only this service has both. Keeping the thresholds here also means retuning one is a configuration
 * change reviewed by a fraud team rather than a model redeploy.
 *
 * <h2>How the two are combined</h2>
 *
 * <p>The model proposes, the rules floor. The probability is bucketed against the configured
 * thresholds to get a baseline level and decision; every rule hit then raises that baseline to at
 * least its own severity. Rules never lower it — if they could, adding a rule would be capable of
 * letting fraud through, and two such rules would interact unpredictably.
 *
 * <h2>What happens when the model is absent</h2>
 *
 * <p>The probability is unknown, not zero. Recording it as zero would make a model outage read as a
 * clean bill of health on every payment, so the verdict carries {@code degraded = true} and the
 * baseline comes from the rules alone. The rules cover the archetypes the model is weakest on, which
 * is precisely the coverage that matters when it is unavailable.
 */
public record RiskVerdict(

        /**
         * Calibrated probability, or 0.0 when the model was unavailable.
         *
         * <p>Read it only alongside {@link #degraded()}. A stored 0.0 with {@code degraded = true}
         * means "unknown", not "safe", and every consumer has to make that distinction — which is why
         * the flag travels on the event as well as the row.
         */
        double fraudProbability,

        RiskLevel riskLevel,
        FraudDecision decision,

        /** Ordered, human-readable, written for the analyst who reads them rather than the author. */
        List<String> reasons,

        List<String> ruleHits,

        /** Null when the model was unavailable. */
        String modelVersion,

        /** The exact vector scored, as JSON. Null in degraded mode: nothing was scored. */
        String featureSnapshot,

        long scoringLatencyMs,

        /** True when the model was unreachable and the rules alone decided. */
        boolean degraded
) {

    /**
     * Combines a model score with the rule hits.
     *
     * @param score empty when ml-service could not be reached
     */
    public static RiskVerdict combine(Optional<MlScoringClient.ScoreResponse> score,
                                      List<RuleHit> hits,
                                      String featureSnapshot,
                                      FraudProperties properties,
                                      long elapsedMs) {

        boolean degraded = score.isEmpty();
        double probability = score.map(MlScoringClient.ScoreResponse::fraudProbability).orElse(0.0);

        RiskLevel level = degraded
                // No model, so no probability-derived baseline. LOW is the floor the rules then
                // raise; it is not a claim that the payment is safe, which is what `degraded` says.
                ? RiskLevel.LOW
                : bucket(probability, properties.getThresholds());
        FraudDecision decision = degraded ? FraudDecision.APPROVE : decisionFor(level);

        // Rules escalate only. Applied after the model's baseline so a hit can raise a LOW verdict
        // but nothing can lower a HIGH one.
        for (RuleHit hit : hits) {
            if (!hit.severity().escalates()) {
                continue;
            }
            level = higherOf(level, hit.severity().minimumRiskLevel());
            decision = strongerOf(decision, hit.severity().minimumDecision());
        }

        // An operator can choose to refuse rather than proceed while the model is down. Off by
        // default: turning a fraud-service outage into a total payment outage is a larger incident
        // than the fraud it prevents.
        if (degraded && properties.getMl().isRejectWhenUnavailable()
                && decision == FraudDecision.APPROVE) {
            level = higherOf(level, RiskLevel.HIGH);
            decision = FraudDecision.HOLD;
        }

        return new RiskVerdict(
                probability,
                level,
                decision,
                buildReasons(score, hits, degraded),
                hits.stream().map(RuleHit::ruleId).distinct().toList(),
                score.map(MlScoringClient.ScoreResponse::modelVersion).orElse(null),
                degraded ? null : featureSnapshot,
                elapsedMs,
                degraded);
    }

    /**
     * Buckets a probability into a risk band.
     *
     * <p>{@code CRITICAL} is deliberately unreachable from here. The {@link RiskLevel} contract
     * reserves it for confirmed patterns and hard rule violations, and a probability — however high —
     * is neither: a 0.99 score is still a statistical claim, whereas an analyst having already
     * confirmed fraud on the account three times is a fact.
     */
    static RiskLevel bucket(double probability, FraudProperties.Thresholds thresholds) {
        if (probability >= thresholds.getHighRisk()) {
            return RiskLevel.HIGH;
        }
        if (probability >= thresholds.getMediumRisk()) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    /**
     * The decision a risk band implies on its own.
     *
     * <p>{@code HIGH} holds rather than rejects, and that is the important one: a hold keeps the
     * customer's funds reserved and waits for a human, so a false positive costs a delay. A rejection
     * costs the customer their payment, and only evidence needing no judgement should reach it
     * automatically.
     */
    static FraudDecision decisionFor(RiskLevel level) {
        return switch (level) {
            case LOW -> FraudDecision.APPROVE;
            case MEDIUM -> FraudDecision.REVIEW;
            case HIGH -> FraudDecision.HOLD;
            case CRITICAL -> FraudDecision.REJECT;
        };
    }

    private static RiskLevel higherOf(RiskLevel current, RiskLevel candidate) {
        if (candidate == null) {
            return current;
        }
        // Ordinal order is LOW, MEDIUM, HIGH, CRITICAL, which is the severity order. Relying on it
        // is safe only because the enum is declared in that order, which its javadoc states.
        return candidate.ordinal() > current.ordinal() ? candidate : current;
    }

    /**
     * The more cautious of two decisions.
     *
     * <p>Explicit ranking rather than the enum's declaration order: {@code FraudDecision} is declared
     * APPROVE, REVIEW, HOLD, REJECT, which happens to be increasing caution — but relying on that
     * would break silently if a value were ever inserted, and this is the comparison that decides
     * whether a payment is stopped.
     */
    private static FraudDecision strongerOf(FraudDecision current, FraudDecision candidate) {
        if (candidate == null) {
            return current;
        }
        return rank(candidate) > rank(current) ? candidate : current;
    }

    private static int rank(FraudDecision decision) {
        return switch (decision) {
            case APPROVE -> 0;
            case REVIEW -> 1;
            case HOLD -> 2;
            case REJECT -> 3;
        };
    }

    /**
     * Builds the reason list an analyst reads.
     *
     * <p>Rule reasons first, then the model's top contributions. That order is on purpose: a rule
     * reason states a concrete finding ("6,000km in eleven minutes"), whereas a model contribution
     * states an influence ("the amount raised the score by 12 points"). The concrete finding is what
     * someone acts on.
     */
    private static List<String> buildReasons(Optional<MlScoringClient.ScoreResponse> score,
                                             List<RuleHit> hits, boolean degraded) {
        List<String> reasons = new ArrayList<>();

        if (degraded) {
            // First, because it changes how everything below it should be read.
            reasons.add("The fraud model was unavailable, so this verdict came from the "
                    + "deterministic rules alone");
        }
        hits.forEach(hit -> reasons.add(hit.reason()));

        score.ifPresent(response -> {
            if (response.contributions() == null) {
                return;
            }
            response.contributions().stream()
                    // Only what raised the score. A contribution that lowered it is useful in the
                    // full explanation but reads as nonsense in a list headed "why this was held".
                    .filter(contribution -> contribution.impactPoints() > 0)
                    .limit(4)
                    .forEach(contribution -> reasons.add(
                            "%s (raised the score by %.1f points)"
                                    .formatted(contribution.description(),
                                            contribution.impactPoints())));
        });

        if (reasons.isEmpty()) {
            reasons.add("No fraud rules matched and the model scored this payment as routine");
        }
        return List.copyOf(reasons);
    }

    /** The 0-100 score: the calibrated probability in percentage points. */
    public int riskScore() {
        return (int) Math.round(fraudProbability * 100.0);
    }

    public boolean needsAnalyst() {
        return decision == FraudDecision.REVIEW || decision == FraudDecision.HOLD;
    }

    /** A one-line summary for the alert queue. */
    public String summary() {
        return reasons.isEmpty() ? decision.name() : reasons.getFirst();
    }
}
