package com.aegispay.fraud.service;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.fraud.client.MlScoringClient;
import com.aegispay.fraud.config.FraudProperties;
import com.aegispay.fraud.rules.RuleEngine;
import com.aegispay.fraud.rules.RuleHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for how a model score and the deterministic rules are combined into one verdict.
 *
 * <p>This is where APPROVE / REVIEW / HOLD / REJECT is actually decided, and the three properties asserted below
 * are the ones a fraud review would ask about:
 *
 * <ol>
 *   <li><strong>Rules escalate only.</strong> No rule may lower what the model raised, or adding a rule
 *       could let fraud through.</li>
 *   <li><strong>A probability never reaches REJECT.</strong> Refusing a customer's payment outright
 *       requires evidence needing no judgement, and a 0.99 score is still a statistical claim.</li>
 *   <li><strong>An absent model is "unknown", not "safe".</strong> A stored probability of 0.0 with
 *       {@code degraded = true} must not read as a clean bill of health.</li>
 * </ol>
 */
class RiskVerdictTest {

    private FraudProperties properties;

    @BeforeEach
    void setUp() {
        properties = new FraudProperties();
    }

    // ------------------------------------------------------------- bucketing

    @Nested
    @DisplayName("bucketing a probability")
    class Bucketing {

        @Test
        @DisplayName("holds at or above the high-risk threshold")
        void highRisk() {
            assertThat(verdict(0.80).riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(verdict(0.80).decision()).isEqualTo(FraudDecision.HOLD);
            assertThat(verdict(0.95).riskLevel()).isEqualTo(RiskLevel.HIGH);
        }

        @Test
        @DisplayName("reviews at or above the medium threshold but below high")
        void mediumRisk() {
            assertThat(verdict(0.45).riskLevel()).isEqualTo(RiskLevel.MEDIUM);
            assertThat(verdict(0.45).decision())
                    .as("MEDIUM settles the payment and alerts, rather than making a customer wait")
                    .isEqualTo(FraudDecision.REVIEW);
            assertThat(verdict(0.79).riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("approves below the medium threshold")
        void lowRisk() {
            assertThat(verdict(0.44).riskLevel()).isEqualTo(RiskLevel.LOW);
            assertThat(verdict(0.44).decision()).isEqualTo(FraudDecision.APPROVE);
            assertThat(verdict(0.0).riskLevel()).isEqualTo(RiskLevel.LOW);
        }

        /**
         * The thresholds are inclusive at the lower bound, which matters because they are published:
         * an operator setting 0.80 expects a score of exactly 80 to be held, not allowed.
         */
        @Test
        @DisplayName("treats the threshold itself as inside the band")
        void thresholdsAreInclusive() {
            properties.getThresholds().setHighRisk(0.60);
            assertThat(verdict(0.60).riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(verdict(0.5999).riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        }

        /**
         * <strong>CRITICAL is unreachable from a probability, by design.</strong> The RiskLevel
         * contract reserves it for confirmed patterns and hard violations. A 0.999 score is still a
         * statistical claim; an analyst having confirmed fraud three times is a fact.
         */
        @Test
        @DisplayName("never reaches CRITICAL or REJECT from a score alone")
        void neverCriticalFromAScore() {
            RiskVerdict certain = verdict(1.0);

            assertThat(certain.riskLevel())
                    .as("even a certain score is a statistical claim, not confirmed fraud")
                    .isEqualTo(RiskLevel.HIGH);
            assertThat(certain.decision())
                    .as("HOLD is reversible; an automatic REJECT costs the customer their payment")
                    .isEqualTo(FraudDecision.HOLD);
        }

        /** The 0-100 score must round the same way ml-service does, or the two disagree by a point. */
        @Test
        @DisplayName("reports the score as the probability in percentage points")
        void scoreMatchesProbability() {
            assertThat(verdict(0.6234).riskScore()).isEqualTo(62);
            assertThat(verdict(0.6250).riskScore()).isEqualTo(63);
            assertThat(verdict(0.0).riskScore()).isZero();
            assertThat(verdict(1.0).riskScore()).isEqualTo(100);
        }
    }

    // ------------------------------------------------------- rule escalation

    @Nested
    @DisplayName("combining rules with the model")
    class Escalation {

        @Test
        @DisplayName("a HOLD rule raises a low score to HIGH")
        void ruleRaisesLowScore() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(score(0.02)),
                    List.of(RuleHit.hold(RuleEngine.RULE_BUST_OUT, "largest ever, new payee")),
                    "{}", properties, 12L);

            assertThat(combined.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(combined.decision()).isEqualTo(FraudDecision.HOLD);
            assertThat(combined.fraudProbability())
                    .as("the model's number is reported unchanged; only the verdict is floored")
                    .isEqualTo(0.02);
        }

        /**
         * <strong>The load-bearing assertion.</strong> If an advisory hit could lower a HIGH verdict,
         * adding a rule would be capable of letting fraud through.
         */
        @Test
        @DisplayName("an advisory rule cannot lower a high score")
        void advisoryCannotDeEscalate() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(score(0.91)),
                    List.of(RuleHit.advisory(RuleEngine.RULE_CARD_TESTING, "3 failures")),
                    "{}", properties, 12L);

            assertThat(combined.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(combined.decision()).isEqualTo(FraudDecision.HOLD);
        }

        @Test
        @DisplayName("a REVIEW rule cannot lower a HOLD from the model")
        void reviewCannotLowerAHold() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(score(0.88)),
                    List.of(RuleHit.review(RuleEngine.RULE_NEW_DEVICE_HIGH_VALUE, "new device")),
                    "{}", properties, 12L);

            assertThat(combined.decision()).isEqualTo(FraudDecision.HOLD);
        }

        /** The strongest hit wins, whatever order they arrive in. */
        @Test
        @DisplayName("takes the strongest of several hits, in any order")
        void strongestHitWins() {
            List<RuleHit> ascending = List.of(
                    RuleHit.advisory(RuleEngine.RULE_CARD_TESTING, "failures"),
                    RuleHit.review(RuleEngine.RULE_NEW_DEVICE_HIGH_VALUE, "new device"),
                    RuleHit.hold(RuleEngine.RULE_IMPOSSIBLE_TRAVEL, "6000km in 11 minutes"));

            List<RuleHit> descending = ascending.reversed();

            assertThat(RiskVerdict.combine(Optional.of(score(0.01)), ascending, "{}", properties, 1L)
                    .decision()).isEqualTo(FraudDecision.HOLD);
            assertThat(RiskVerdict.combine(Optional.of(score(0.01)), descending, "{}", properties, 1L)
                    .decision()).isEqualTo(FraudDecision.HOLD);
        }

        /**
         * REJECT is reachable, but only from the one rule that rests on prior human judgement. This
         * asserts the path exists — the negative cases above assert nothing else reaches it.
         */
        @Test
        @DisplayName("a BLOCK rule refuses the payment even on a near-zero score")
        void blockRuleRefuses() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(score(0.01)),
                    List.of(RuleHit.block(RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY,
                            "3 confirmed frauds")),
                    "{}", properties, 12L);

            assertThat(combined.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
            assertThat(combined.decision()).isEqualTo(FraudDecision.REJECT);
        }

        /** Rule ids are stored and de-duplicated, since two hits from one rule is not two findings. */
        @Test
        @DisplayName("records each rule id once")
        void deduplicatesRuleIds() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(score(0.01)),
                    List.of(RuleHit.review(RuleEngine.RULE_CARD_TESTING, "failures"),
                            RuleHit.advisory(RuleEngine.RULE_CARD_TESTING, "also failures")),
                    "{}", properties, 12L);

            assertThat(combined.ruleHits()).containsExactly(RuleEngine.RULE_CARD_TESTING);
        }
    }

    // ---------------------------------------------------------- degraded mode

    @Nested
    @DisplayName("when the model is unavailable")
    class Degraded {

        /**
         * A probability of 0.0 with {@code degraded = true} means "unknown", not "safe". Every consumer
         * has to make that distinction, which is why the flag travels on the verdict, the row and the
         * event rather than only reaching a log.
         */
        @Test
        @DisplayName("marks the verdict degraded and says so first in the reasons")
        void marksDegraded() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.empty(), List.of(), "{}", properties, 1_205L);

            assertThat(combined.degraded()).isTrue();
            assertThat(combined.modelVersion())
                    .as("an unattributable score cannot be reproduced, so none is claimed")
                    .isNull();
            assertThat(combined.reasons().getFirst())
                    .as("it changes how everything below it reads, so it comes first")
                    .contains("model was unavailable");
        }

        /**
         * Nothing was scored, so there is no vector to store. Keeping one would imply the model saw
         * inputs it never did — and the snapshot exists precisely to make a verdict reproducible.
         */
        @Test
        @DisplayName("stores no feature snapshot, because nothing was scored")
        void noSnapshotWhenDegraded() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.empty(), List.of(), "{\"amountMinor\":100}", properties, 1L);

            assertThat(combined.featureSnapshot()).isNull();
        }

        /**
         * The rules still cover the archetypes the model scores worst on, which is exactly the
         * coverage that matters when it is absent.
         */
        @Test
        @DisplayName("still applies the rules")
        void rulesStillApply() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.empty(),
                    List.of(RuleHit.hold(RuleEngine.RULE_IMPOSSIBLE_TRAVEL, "6000km in 11 min")),
                    null, properties, 1L);

            assertThat(combined.decision()).isEqualTo(FraudDecision.HOLD);
            assertThat(combined.degraded()).isTrue();
        }

        /**
         * The default is to proceed, and that is a deliberate trade: refusing every payment while the
         * model is down turns a fraud-service outage into a total payment outage.
         */
        @Test
        @DisplayName("approves by default rather than failing every payment")
        void approvesByDefault() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.empty(), List.of(), null, properties, 1L);

            assertThat(combined.decision()).isEqualTo(FraudDecision.APPROVE);
            assertThat(combined.riskLevel())
                    .as("LOW here is a floor the rules raise, not a claim of safety")
                    .isEqualTo(RiskLevel.LOW);
        }

        /** An operator can choose the other trade-off, and it must actually take effect. */
        @Test
        @DisplayName("holds every payment when configured to refuse without a model")
        void refusesWhenConfigured() {
            properties.getMl().setRejectWhenUnavailable(true);

            RiskVerdict combined = RiskVerdict.combine(
                    Optional.empty(), List.of(), null, properties, 1L);

            assertThat(combined.decision()).isEqualTo(FraudDecision.HOLD);
            assertThat(combined.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(combined.degraded()).isTrue();
        }

        /**
         * Even then, a rule that already refused must not be softened into a hold. The strict setting
         * raises a floor; it does not replace the verdict.
         */
        @Test
        @DisplayName("the strict setting does not soften a rule that already refused")
        void strictSettingDoesNotSoftenABlock() {
            properties.getMl().setRejectWhenUnavailable(true);

            RiskVerdict combined = RiskVerdict.combine(
                    Optional.empty(),
                    List.of(RuleHit.block(RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY, "3 frauds")),
                    null, properties, 1L);

            assertThat(combined.decision()).isEqualTo(FraudDecision.REJECT);
        }
    }

    // ---------------------------------------------------------------- reasons

    @Nested
    @DisplayName("the reasons an analyst reads")
    class Reasons {

        /**
         * Rule reasons come first because a rule states a concrete finding ("6,000km in eleven
         * minutes") while a model contribution states an influence ("the amount raised the score").
         * The concrete finding is what someone acts on.
         */
        @Test
        @DisplayName("puts rule findings ahead of model contributions")
        void ruleReasonsComeFirst() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(scoreWith(0.7, new MlScoringClient.Contribution(
                            "amountMinor", "The amount was unusually large", 100000.0, 12.4, "up"))),
                    List.of(RuleHit.hold(RuleEngine.RULE_IMPOSSIBLE_TRAVEL,
                            "Implies travelling 32000 km/h since the previous payment")),
                    "{}", properties, 12L);

            assertThat(combined.reasons().getFirst()).contains("32000 km/h");
            assertThat(combined.reasons().get(1)).contains("unusually large");
        }

        /**
         * A contribution that lowered the score is useful in a full explanation but reads as nonsense
         * in a list headed "why this was held".
         */
        @Test
        @DisplayName("omits contributions that lowered the score")
        void omitsNegativeContributions() {
            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(scoreWith(0.7,
                            new MlScoringClient.Contribution("isNewDevice",
                                    "The device was already known", 0.0, -8.1, "down"),
                            new MlScoringClient.Contribution("amountMinor",
                                    "The amount was unusually large", 1.0, 12.4, "up"))),
                    List.of(), "{}", properties, 12L);

            assertThat(combined.reasons()).noneMatch(reason -> reason.contains("already known"));
            assertThat(combined.reasons()).anyMatch(reason -> reason.contains("unusually large"));
        }

        /** A verdict with no reason at all would reach an analyst as a bare decision. */
        @Test
        @DisplayName("always gives at least one reason")
        void alwaysExplainsItself() {
            RiskVerdict clean = RiskVerdict.combine(
                    Optional.of(score(0.01)), List.of(), "{}", properties, 12L);

            assertThat(clean.reasons()).isNotEmpty();
            assertThat(clean.summary()).isNotBlank();
        }

        @Test
        @DisplayName("tolerates a model response with no contributions")
        void toleratesMissingContributions() {
            MlScoringClient.ScoreResponse noContributions = new MlScoringClient.ScoreResponse(
                    0.3, 30, "fraud_hgb-abc", Map.of(), null, 5L);

            RiskVerdict combined = RiskVerdict.combine(
                    Optional.of(noContributions), List.of(), "{}", properties, 12L);

            assertThat(combined.reasons()).isNotEmpty();
        }
    }

    // ------------------------------------------------------------ analyst gate

    @Nested
    @DisplayName("whether a human is needed")
    class NeedsAnalyst {

        @Test
        @DisplayName("is true for REVIEW and HOLD, and false otherwise")
        void onlyForReviewAndHold() {
            assertThat(verdict(0.90).needsAnalyst()).as("HOLD").isTrue();
            assertThat(verdict(0.50).needsAnalyst()).as("REVIEW").isTrue();
            assertThat(verdict(0.10).needsAnalyst()).as("APPROVE").isFalse();

            RiskVerdict refused = RiskVerdict.combine(
                    Optional.of(score(0.01)),
                    List.of(RuleHit.block(RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY, "3 frauds")),
                    "{}", properties, 1L);

            assertThat(refused.needsAnalyst())
                    .as("a terminal refusal has nothing left for a human to decide")
                    .isFalse();
        }
    }

    // ---------------------------------------------------------------- helpers

    private RiskVerdict verdict(double probability) {
        return RiskVerdict.combine(Optional.of(score(probability)), List.of(), "{}", properties, 7L);
    }

    private static MlScoringClient.ScoreResponse score(double probability) {
        return new MlScoringClient.ScoreResponse(
                probability, (int) Math.round(probability * 100), "fraud_hgb-c9026b7d59cb",
                Map.of("amountMinor", 100_000), List.of(), 5L);
    }

    private static MlScoringClient.ScoreResponse scoreWith(
            double probability, MlScoringClient.Contribution... contributions) {
        return new MlScoringClient.ScoreResponse(
                probability, (int) Math.round(probability * 100), "fraud_hgb-c9026b7d59cb",
                Map.of("amountMinor", 100_000), List.of(contributions), 5L);
    }
}
