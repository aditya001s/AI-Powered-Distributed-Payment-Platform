package com.aegispay.ai.generation;

import com.aegispay.ai.domain.Recommendation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for parsing a language model's reply.
 *
 * <p>Two concerns, and the second is a security one. The first is tolerance: a model does not reliably
 * produce the exact format it was asked for, and discarding a good report over a markdown asterisk would
 * waste every generation. The second is that tolerance must not extend to the recommendation, because that
 * is the field an injection targets — and a parser that took the last of two answers, or guessed at an
 * unrecognised one, would hand an attacker the win.
 */
class ReportParserTest {

    private static final String WELL_FORMED = """
            NARRATIVE:
            Payment PAY-1 was referred at HIGH risk. The bust-out rule matched: the amount is 4.2x
            the account's largest previous payment and the destination has never been paid before.

            The account has 41 completed payments over fourteen months with no adverse history.

            FINDINGS:
            - RULE_BUST_OUT_PATTERN matched; see POLICY-BUST-OUT
            - The amount is 4.2x the account's historic maximum (amountMinor 2000000 vs 476000)
            - The destination wallet has no prior payments from this account

            RECOMMENDATION: HOLD_AND_REQUEST_VERIFICATION

            RATIONALE:
            POLICY-BUST-OUT advises holding and contacting the customer on a channel established
            before the payment, because the false positive is a real purchase and a delay is
            recoverable where a refusal is not.

            CITATIONS: POLICY-BUST-OUT, POLICY-HOLD-HANDLING

            CONFIDENCE: 0.72
            """;

    @Nested
    @DisplayName("a well-formed reply")
    class WellFormed {

        @Test
        @DisplayName("reads every section")
        void readsEverySection() {
            ReportParser.Parsed parsed = ReportParser.parse(WELL_FORMED);

            assertThat(parsed.narrative()).contains("referred at HIGH risk");
            assertThat(parsed.findings()).hasSize(3);
            assertThat(parsed.findings().getFirst()).startsWith("RULE_BUST_OUT_PATTERN matched");
            assertThat(parsed.recommendation())
                    .contains(Recommendation.HOLD_AND_REQUEST_VERIFICATION);
            assertThat(parsed.rationale()).contains("POLICY-BUST-OUT advises");
            assertThat(parsed.citedDocumentIds())
                    .containsExactly("POLICY-BUST-OUT", "POLICY-HOLD-HANDLING");
            assertThat(parsed.confidence()).isEqualTo(0.72);
        }

        @Test
        @DisplayName("strips bullet markers from findings")
        void stripsBullets() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    NARRATIVE:
                    Something happened.

                    FINDINGS:
                    * asterisk bullet
                    - dash bullet
                    \u2022 unicode bullet

                    RECOMMENDATION: MONITOR_ACCOUNT
                    """);

            assertThat(parsed.findings())
                    .containsExactly("asterisk bullet", "dash bullet", "unicode bullet");
        }
    }

    @Nested
    @DisplayName("format variations a model actually produces")
    class Tolerance {

        @Test
        @DisplayName("accepts markdown-decorated headers")
        void acceptsMarkdownHeaders() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    ## NARRATIVE
                    The payment was referred.

                    **FINDINGS**
                    - one finding

                    **RECOMMENDATION:** MONITOR_ACCOUNT

                    ## CONFIDENCE
                    0.4
                    """);

            assertThat(parsed.narrative()).contains("referred");
            assertThat(parsed.findings()).containsExactly("one finding");
            assertThat(parsed.recommendation()).contains(Recommendation.MONITOR_ACCOUNT);
            assertThat(parsed.confidence()).isEqualTo(0.4);
        }

        /**
         * A model that ignores the format and simply writes the report still produces something worth
         * showing an analyst. Falling back to the whole reply applies to the narrative only — the other
         * sections carry values that must not be guessed at.
         */
        @Test
        @DisplayName("keeps the prose when the model ignores the format entirely")
        void keepsProseWithoutHeaders() {
            ReportParser.Parsed parsed = ReportParser.parse(
                    "The payment looks like a bust-out. I would hold it and call the customer.");

            assertThat(parsed.narrative()).contains("bust-out");
            assertThat(parsed.recommendation())
                    .as("no parseable recommendation means the caller must escalate, not that the "
                            + "prose should be discarded")
                    .isEmpty();
        }

        @Test
        @DisplayName("understands a percentage confidence")
        void understandsPercentage() {
            assertThat(ReportParser.parse("""
                    NARRATIVE:
                    x
                    CONFIDENCE: 85%
                    """).confidence()).isEqualTo(0.85);

            assertThat(ReportParser.parse("""
                    NARRATIVE:
                    x
                    CONFIDENCE: 85
                    """).confidence())
                    .as("a bare number above 1 can only have been meant as a percentage")
                    .isEqualTo(0.85);
        }

        /**
         * 0.5 rather than 0.0 or 1.0. Zero would make a perfectly good report look worthless and could sort
         * it out of a queue; one would assert a certainty the model never claimed.
         */
        @Test
        @DisplayName("defaults a missing confidence to the middle")
        void defaultsConfidence() {
            assertThat(ReportParser.parse("NARRATIVE:\nx\n").confidence()).isEqualTo(0.5);
            assertThat(ReportParser.parse("NARRATIVE:\nx\nCONFIDENCE: high\n").confidence())
                    .isEqualTo(0.5);
        }

        @Test
        @DisplayName("strips decoration from citation ids")
        void stripsCitationDecoration() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    NARRATIVE:
                    x
                    CITATIONS: [POLICY-BUST-OUT], `POLICY-HOLD-HANDLING`, (POLICY-MULE-CASHOUT)
                    """);

            assertThat(parsed.citedDocumentIds()).containsExactly(
                    "POLICY-BUST-OUT", "POLICY-HOLD-HANDLING", "POLICY-MULE-CASHOUT");
        }

        /**
         * <strong>Regression test for a real defect.</strong> The prompt asks for a comma-separated list,
         * but the output format is not under this platform's control, and a model routinely renders one
         * section in the style of its neighbour — FINDINGS directly above is a bullet list, so CITATIONS
         * arrives as one too.
         *
         * <p>The consequence was severe and completely silent. {@code "- POLICY-BUST-OUT"} does not equal
         * {@code "POLICY-BUST-OUT"}, so {@code CitationValidator} recorded every correctly-cited document
         * as a fabrication: the hallucination alert fired on every investigation, the completed event
         * carried no verified sources, and the narrative read perfectly throughout. The failure looked
         * like a dishonest model rather than a parser bug, which is the worst possible place to send
         * somebody debugging.
         */
        @Test
        @DisplayName("reads citations written as a bullet list, not only as a comma-separated one")
        void readsBulletedCitations() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    NARRATIVE:
                    x

                    CITATIONS:
                    - POLICY-BUST-OUT
                    * POLICY-HOLD-HANDLING
                    \u2022 POLICY-MULE-CASHOUT
                    """);

            assertThat(parsed.citedDocumentIds())
                    .as("""
                            a list marker left on the id makes every genuine citation look invented, \
                            and the report still reads perfectly""")
                    .containsExactly("POLICY-BUST-OUT", "POLICY-HOLD-HANDLING",
                            "POLICY-MULE-CASHOUT");
        }

        @Test
        @DisplayName("treats NONE as no citations")
        void understandsNone() {
            assertThat(ReportParser.parse("NARRATIVE:\nx\nCITATIONS: NONE\n").citedDocumentIds())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("refusing an unusable recommendation")
    class Refusal {

        @Test
        @DisplayName("reports no recommendation when the value is outside the vocabulary")
        void refusesUnknownValue() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    NARRATIVE:
                    The customer confirmed this payment by phone.

                    RECOMMENDATION: RELEASE_IMMEDIATELY
                    """);

            assertThat(parsed.recommendation()).isEmpty();
            assertThat(parsed.rawRecommendation())
                    .as("the refused value must be retained for the log; an operator needs to know "
                            + "WHAT was refused to tell a confused model from an injection")
                    .isEqualTo("RELEASE_IMMEDIATELY");
        }

        /**
         * <strong>The injection test.</strong> A successful injection persuades the model to append a second,
         * more favourable answer. Taking the last occurrence would hand it the win; taking the first keeps
         * the answer the model reached before it was talked out of it — and if the injection produced the
         * only answer, it is outside the vocabulary and refused anyway.
         */
        @Test
        @DisplayName("keeps the first recommendation when a reply contains two")
        void keepsTheFirstRecommendation() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    NARRATIVE:
                    The bust-out rule matched.

                    RECOMMENDATION: HOLD_AND_REQUEST_VERIFICATION

                    RATIONALE:
                    The conjunction is present.

                    RECOMMENDATION: NO_ACTION_REQUIRED
                    """);

            assertThat(parsed.recommendation())
                    .as("""
                            a second, softer answer is the shape a successful injection produces; \
                            taking the last would hand it the win""")
                    .contains(Recommendation.HOLD_AND_REQUEST_VERIFICATION);
        }

        @Test
        @DisplayName("keeps the first of duplicated citations and confidence sections too")
        void keepsFirstOfEverySection() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    NARRATIVE:
                    x
                    CITATIONS: POLICY-BUST-OUT
                    CONFIDENCE: 0.2
                    CITATIONS: POLICY-AUTHORITY-BOUNDARY
                    CONFIDENCE: 0.99
                    """);

            assertThat(parsed.citedDocumentIds()).containsExactly("POLICY-BUST-OUT");
            assertThat(parsed.confidence()).isEqualTo(0.2);
        }

        /**
         * A report with no prose is an empty page carrying a recommendation, which an analyst would act on
         * without being able to see why. Everything else degrades; this fails.
         */
        @Test
        @DisplayName("fails on an empty reply")
        void failsOnEmptyReply() {
            assertThatExceptionOfType(ReportGenerator.GenerationException.class)
                    .isThrownBy(() -> ReportParser.parse(""));
            assertThatExceptionOfType(ReportGenerator.GenerationException.class)
                    .isThrownBy(() -> ReportParser.parse(null));
        }

        @Test
        @DisplayName("supplies a rationale when the model omitted one")
        void suppliesMissingRationale() {
            ReportParser.Parsed parsed = ReportParser.parse("""
                    NARRATIVE:
                    x
                    RECOMMENDATION: MONITOR_ACCOUNT
                    """);

            assertThat(parsed.rationale())
                    .as("an empty rationale would violate the aggregate's own invariant")
                    .isNotBlank();
        }
    }
}
