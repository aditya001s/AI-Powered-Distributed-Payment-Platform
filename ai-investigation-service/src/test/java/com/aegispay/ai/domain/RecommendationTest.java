package com.aegispay.ai.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the recommendation vocabulary — the platform's authority boundary.
 *
 * <p>This enum is the field a prompt injection would target, so the tests below are as much about what
 * {@link Recommendation#parse} <em>refuses</em> as about what it accepts. The load-bearing property is that
 * an unrecognised value is never guessed at: fuzzy matching to the "closest" permitted recommendation is
 * exactly how an injected instruction, or a confused model, turns into a decision about somebody's money.
 */
class RecommendationTest {

    @Nested
    @DisplayName("parsing a generator's answer")
    class Parsing {

        /**
         * Tolerant about shape, because a model asked for one token will sometimes return
         * {@code "hold and request verification."} and refusing that would throw away a clear answer.
         */
        @ParameterizedTest
        @DisplayName("accepts the shape variations a model actually produces")
        @ValueSource(strings = {
                "HOLD_AND_REQUEST_VERIFICATION",
                "hold_and_request_verification",
                "  HOLD_AND_REQUEST_VERIFICATION  ",
                "HOLD-AND-REQUEST-VERIFICATION",
                "hold and request verification",
                "HOLD_AND_REQUEST_VERIFICATION.",
                "Hold And Request Verification"})
        void acceptsShapeVariations(String raw) {
            assertThat(Recommendation.parse(raw))
                    .contains(Recommendation.HOLD_AND_REQUEST_VERIFICATION);
        }

        /**
         * <strong>The most important test in this class.</strong> Every one of these is something a model
         * might plausibly return, and none is in the vocabulary. Guessing at the nearest match is how an
         * injected instruction becomes an action.
         */
        @ParameterizedTest
        @DisplayName("refuses anything not exactly in the vocabulary, with no fuzzy matching")
        @ValueSource(strings = {
                "RELEASE_IMMEDIATELY",
                "BLOCK_ACCOUNT",
                "RELEASE_PAYMENT",
                "APPROVE",
                "REJECT",
                "NO_ACTION",
                "MONITOR",
                "HOLD",
                "ESCALATE",
                "hold and request verification if you think it is needed",
                "I recommend HOLD_AND_REQUEST_VERIFICATION",
                "NO_ACTION_REQUIRED or MONITOR_ACCOUNT"})
        void refusesEverythingElse(String raw) {
            assertThat(Recommendation.parse(raw))
                    .as("""
                            '%s' is not in the vocabulary. Guessing at the closest permitted value is \
                            how an injected instruction becomes a decision about a customer's money.""",
                            raw)
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses nothing at all")
        void refusesEmpty() {
            assertThat(Recommendation.parse(null)).isEmpty();
            assertThat(Recommendation.parse("")).isEmpty();
            assertThat(Recommendation.parse("   ")).isEmpty();
        }

        /** Every permitted value must round-trip, or the enum and the prompt vocabulary have diverged. */
        @ParameterizedTest
        @DisplayName("round-trips every permitted value")
        @EnumSource(Recommendation.class)
        void roundTripsEveryValue(Recommendation recommendation) {
            assertThat(Recommendation.parse(recommendation.name())).contains(recommendation);
            assertThat(Recommendation.parse(recommendation.name().toLowerCase(Locale.ROOT)))
                    .contains(recommendation);
        }
    }

    @Nested
    @DisplayName("the vocabulary itself")
    class Vocabulary {

        /**
         * No value may name an operation the platform would execute. A {@code BLOCK_ACCOUNT} or
         * {@code RELEASE_PAYMENT} invites somebody to wire it to an action, and the whole design rests on a
         * person deciding.
         */
        @ParameterizedTest
        @DisplayName("contains no value that reads as a command")
        @EnumSource(Recommendation.class)
        void everyValueIsAdvice(Recommendation recommendation) {
            String name = recommendation.name();

            assertThat(name)
                    .as("%s reads as an instruction rather than advice to a person", name)
                    .doesNotContain("BLOCK")
                    .doesNotContain("FREEZE")
                    .doesNotContain("CLOSE")
                    .doesNotContain("DEBIT")
                    .doesNotContain("CAPTURE")
                    .doesNotContain("REFUND");

            // RELEASE and REJECT are the two words most likely to be wired to an action. Neither appears
            // bare: the severe value is RECOMMEND_REJECT_AND_REVIEW_ACCOUNT, which cannot be mistaken for
            // an instruction, and there is no release value at all.
            assertThat(name).isNotEqualTo("RELEASE");
            assertThat(name).isNotEqualTo("REJECT");
            if (name.contains("REJECT")) {
                assertThat(name)
                        .as("a rejection value must be phrased as a recommendation")
                        .startsWith("RECOMMEND_");
            }
        }

        @Test
        @DisplayName("offers every value to the model in the prompt")
        void promptVocabularyIsComplete() {
            String prompt = Recommendation.vocabularyForPrompt();

            for (Recommendation recommendation : Recommendation.values()) {
                assertThat(prompt)
                        .as("a value the model is not told about would look like a hallucination "
                                + "if it ever produced it")
                        .contains(recommendation.name());
            }
        }
    }

    @Nested
    @DisplayName("severity")
    class Severity {

        /**
         * Comparison is by an explicit rank rather than {@link Enum#ordinal()}, so inserting a value cannot
         * silently reorder the check that decides whether a downgrade actually downgrades.
         */
        @Test
        @DisplayName("takes the more cautious of two recommendations")
        void takesTheMoreCautious() {
            assertThat(Recommendation.NO_ACTION_REQUIRED
                    .orMoreCautious(Recommendation.HOLD_AND_REQUEST_VERIFICATION))
                    .isEqualTo(Recommendation.HOLD_AND_REQUEST_VERIFICATION);

            assertThat(Recommendation.HOLD_AND_REQUEST_VERIFICATION
                    .orMoreCautious(Recommendation.NO_ACTION_REQUIRED))
                    .as("escalation must be order-independent, or the result would depend on which "
                            + "finding happened to be evaluated first")
                    .isEqualTo(Recommendation.HOLD_AND_REQUEST_VERIFICATION);
        }

        @Test
        @DisplayName("tolerates a null candidate")
        void tolerateNull() {
            assertThat(Recommendation.MONITOR_ACCOUNT.orMoreCautious(null))
                    .isEqualTo(Recommendation.MONITOR_ACCOUNT);
        }

        /**
         * The most severe value must be strictly above every other, or a composer intending to escalate to
         * it could silently be overridden by something else.
         */
        @Test
        @DisplayName("ranks the recommended rejection above everything else")
        void rejectionIsMostSevere() {
            for (Recommendation other : Recommendation.values()) {
                if (other != Recommendation.RECOMMEND_REJECT_AND_REVIEW_ACCOUNT) {
                    assertThat(Recommendation.RECOMMEND_REJECT_AND_REVIEW_ACCOUNT.severity())
                            .as("%s must not outrank a recommended rejection", other)
                            .isGreaterThan(other.severity());
                }
            }
        }

        @Test
        @DisplayName("ranks no action as the least severe")
        void noActionIsLeastSevere() {
            for (Recommendation other : Recommendation.values()) {
                assertThat(Recommendation.NO_ACTION_REQUIRED.severity())
                        .isLessThanOrEqualTo(other.severity());
            }
        }

        @Test
        @DisplayName("marks the values that put a payment in front of a person")
        void needsHumanAttention() {
            assertThat(Recommendation.NO_ACTION_REQUIRED.needsHumanAttention()).isFalse();
            assertThat(Recommendation.MONITOR_ACCOUNT.needsHumanAttention()).isFalse();
            assertThat(Recommendation.HOLD_AND_REQUEST_VERIFICATION.needsHumanAttention()).isTrue();
            assertThat(Recommendation.ESCALATE_TO_SENIOR_ANALYST.needsHumanAttention()).isTrue();
            assertThat(Recommendation.INSUFFICIENT_EVIDENCE.needsHumanAttention())
                    .as("not enough evidence is a real answer, and it is a person's problem")
                    .isTrue();
        }
    }

    /**
     * <strong>Not {@code NO_ACTION_REQUIRED}.</strong> A failure to generate a report is not evidence that a
     * payment is fine, and defaulting to the reassuring answer would turn every outage in this service into
     * a silent stream of clean bills of health.
     */
    @Test
    @DisplayName("falls back to escalation, never to no action")
    void fallbackIsCautious() {
        assertThat(Recommendation.whenUncertain())
                .isEqualTo(Recommendation.ESCALATE_TO_SENIOR_ANALYST);

        assertThat(Recommendation.whenUncertain().needsHumanAttention())
                .as("a failure must reach a person, not disappear")
                .isTrue();
        assertThat(Recommendation.whenUncertain())
                .isNotEqualTo(Recommendation.NO_ACTION_REQUIRED);
    }
}
