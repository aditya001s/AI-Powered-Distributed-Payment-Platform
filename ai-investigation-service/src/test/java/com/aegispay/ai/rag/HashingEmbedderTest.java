package com.aegispay.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the default embedder.
 *
 * <p><strong>Including the tests that document what it cannot do.</strong> This embedder is lexical: it
 * matches shared vocabulary, not meaning. A test suite that only asserted the successes would leave a
 * reader believing retrieval is semantic, which is the single most misleading thing this codebase could
 * imply — the results look identical either way until somebody tries a paraphrase.
 *
 * <p>So {@link Limits} asserts the failure explicitly. If a future change makes the embedder semantic,
 * those tests fail and have to be revisited deliberately, which is exactly the right outcome.
 */
class HashingEmbedderTest {

    private final Embedder embedder = new HashingEmbedder();

    @Nested
    @DisplayName("the contract")
    class Contract {

        @Test
        @DisplayName("produces vectors of the declared dimension")
        void correctDimension() {
            assertThat(embedder.dimensions()).isEqualTo(384);
            assertThat(embedder.embed("a fraud policy about dormant accounts"))
                    .hasSize(384);
        }

        /**
         * Required so cosine similarity is a dot product, and so a long policy document does not out-score a
         * short one purely by containing more of everything.
         */
        @Test
        @DisplayName("normalises to unit length")
        void unitLength() {
            float[] vector = embedder.embed("bust out pattern on established accounts");

            double sumOfSquares = 0.0;
            for (float value : vector) {
                sumOfSquares += (double) value * value;
            }
            assertThat(Math.sqrt(sumOfSquares)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        }

        /**
         * The property the whole corpus depends on. A citation written today has to be reproducible in five
         * years, and an embedder whose output varied would make every stored vector incomparable with a
         * fresh query.
         */
        @Test
        @DisplayName("is deterministic across calls")
        void deterministic() {
            String text = "RULE_IMPOSSIBLE_TRAVEL matched: 6000km in eleven minutes";

            assertThat(embedder.embed(text)).isEqualTo(embedder.embed(text));
            assertThat(new HashingEmbedder().embed(text))
                    .as("a fresh instance must produce identical output, or a restart would invalidate "
                            + "the stored corpus")
                    .isEqualTo(embedder.embed(text));
        }

        /**
         * A blank input has no features, so the vector is all zeros. Cosine distance against a zero vector is
         * undefined and pgvector reports NaN, which sorts unpredictably — so the retriever checks for this
         * rather than letting such a chunk appear at a random position in every result.
         */
        @Test
        @DisplayName("returns a zero vector for empty text rather than failing")
        void zeroForEmpty() {
            for (String blank : new String[]{"", "   ", null}) {
                float[] vector = embedder.embed(blank);
                assertThat(vector).hasSize(384);
                assertThat(vector).containsOnly(0.0f);
            }
        }

        @Test
        @DisplayName("produces only finite values")
        void allFinite() {
            for (float value : embedder.embed("policy about card testing velocity and failures")) {
                assertThat(Float.isFinite(value))
                        .as("a non-finite component would make every distance involving this vector NaN")
                        .isTrue();
            }
        }

        /**
         * The id is stored on every chunk and filtered on at query time, so it must change whenever the
         * output changes. Asserted as a literal so a change to the dimension or the version cannot slip
         * through without somebody updating this test and thinking about the corpus.
         */
        @Test
        @DisplayName("identifies itself with dimension and version")
        void identifiesItself() {
            assertThat(embedder.id()).isEqualTo("hashing-384/v1");
        }

        /**
         * Reported honestly, and it reaches the API. A caller reading a null retrieval result needs to know
         * whether it means "no relevant policy exists" or merely "no policy shared vocabulary".
         */
        @Test
        @DisplayName("reports that it is not semantic")
        void notSemantic() {
            assertThat(embedder.isSemantic()).isFalse();
        }
    }

    @Nested
    @DisplayName("what it does well")
    class Strengths {

        @Test
        @DisplayName("scores identical text at similarity 1")
        void identicalTextIsIdentical() {
            String text = "money mule cash out corridor policy";
            assertThat(cosine(embedder.embed(text), embedder.embed(text)))
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        }

        /**
         * The case that matters most in practice. A retrieval query is built from rule ids, and the corpus
         * declares those same ids — underscores are normalised to word boundaries, so the identifier matches
         * prose about the same subject.
         */
        @Test
        @DisplayName("matches a rule id against prose about the same subject")
        void matchesRuleIdToProse() {
            float[] query = embedder.embed("RULE_DORMANT_ACCOUNT_LARGE_PAYMENT");
            float[] onTopic = embedder.embed(
                    "A dormant account waking up with a large payment is the pattern behind a resold "
                            + "credential.");
            float[] offTopic = embedder.embed(
                    "Total debits must equal total credits across every ledger account.");

            assertThat(cosine(query, onTopic))
                    .as("underscores are normalised to word boundaries, which is what lets a machine "
                            + "token match authored prose")
                    .isGreaterThan(cosine(query, offTopic));
        }

        @Test
        @DisplayName("ranks an on-topic policy above an unrelated one")
        void ranksOnTopicHigher() {
            float[] query = embedder.embed(
                    "HIGH risk payment. Rules that fired: RULE_CARD_TESTING_VELOCITY. "
                            + "failedPaymentCount24h paymentCount1h");

            float[] cardTesting = embedder.embed(
                    "Credential and card testing bursts. Somebody in possession of a list of "
                            + "credentials is finding out which ones still work. The signature is a burst "
                            + "of attempts with a high proportion refused.");
            float[] unrelated = embedder.embed(
                    "The authority boundary between AI and human decisions. An investigation "
                            + "recommends and a person decides.");

            assertThat(cosine(query, cardTesting)).isGreaterThan(cosine(query, unrelated));
        }

        /**
         * Character n-grams handle morphology without a stemmer. "chargeback" and "chargebacks" share every
         * 4-gram but one, so they match strongly — which matters because a policy and a query rarely agree
         * on plurality.
         */
        @Test
        @DisplayName("tolerates morphological variation")
        void toleratesMorphology() {
            double related = cosine(embedder.embed("chargeback"), embedder.embed("chargebacks"));
            double unrelated = cosine(embedder.embed("chargeback"), embedder.embed("withdrawal"));

            assertThat(related)
                    .as("shared character n-grams are what make this work without a stemmer")
                    .isGreaterThan(unrelated);
        }

        /**
         * Digits are meaningful in this vocabulary: {@code paymentCount24h} and {@code paymentCount1h} are
         * different features, and an embedder that stripped digits would make them the same token.
         */
        @Test
        @DisplayName("distinguishes feature names that differ only in a digit")
        void distinguishesDigits() {
            double sameFamily = cosine(
                    embedder.embed("paymentCount24h"), embedder.embed("paymentCount1h"));

            assertThat(sameFamily)
                    .as("they share a stem so they are related, but must not be identical: the two are "
                            + "different features")
                    .isLessThan(0.999);
        }

        @Test
        @DisplayName("is symmetric")
        void symmetric() {
            float[] a = embedder.embed("impossible travel between consecutive payments");
            float[] b = embedder.embed("account takeover and new device risk");

            assertThat(cosine(a, b)).isCloseTo(cosine(b, a),
                    org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    @Nested
    @DisplayName("what it cannot do")
    class Limits {

        /**
         * <strong>The honest test.</strong> This is the capability a semantic embedder would have and this one
         * does not. Asserting the failure documents it in the place a reader will look, and means a future
         * change to a genuinely semantic model breaks this test and has to be considered deliberately.
         */
        @Test
        @DisplayName("does not match a paraphrase that shares no vocabulary")
        void missesParaphrase() {
            float[] query = embedder.embed("an account nobody has used for several months");
            float[] policy = embedder.embed(
                    "Dormant reactivation: the pattern behind a resold or recovered credential.");
            float[] unrelated = embedder.embed(
                    "Total debits must equal total credits across every account.");

            double paraphraseScore = cosine(query, policy);

            assertThat(paraphraseScore)
                    .as("""
                            This embedder is LEXICAL. "an account nobody has used for months" and \
                            "dormant reactivation" mean the same thing and share almost no words, so the \
                            match is weak. A semantic model would score this highly. Retrieval also \
                            looks policies up by declared rule id, which is what covers the case that \
                            matters most.""")
                    .isLessThan(0.30);

            // Not zero, because "account" is shared. Weak lexical overlap is still overlap, which is why
            // the similarity floor exists rather than a simple "any match counts" rule.
            assertThat(paraphraseScore).isGreaterThanOrEqualTo(cosine(query, unrelated) - 0.1);
        }

        /**
         * Word order is only partly captured, by bigrams. Two texts with the same words in a different order
         * score highly, which a semantic model would separate.
         */
        @Test
        @DisplayName("barely distinguishes reordered words")
        void weakOnWordOrder() {
            double score = cosine(
                    embedder.embed("new device high value payment"),
                    embedder.embed("high value payment new device"));

            assertThat(score)
                    .as("bigrams capture some adjacency, but a bag-of-features model cannot represent "
                            + "syntax")
                    .isGreaterThan(0.7);
        }

        /**
         * Negation is invisible to a lexical model, and this is the most dangerous limit: a policy saying
         * "this is NOT indicative" and one saying "this IS indicative" retrieve almost identically. It is why
         * a narrative must quote the retrieved passage rather than summarise it, and why the composer
         * includes the passage verbatim.
         */
        @Test
        @DisplayName("cannot distinguish a statement from its negation")
        void cannotSeeNegation() {
            double score = cosine(
                    embedder.embed("a new device is evidence of account takeover"),
                    embedder.embed("a new device is not evidence of account takeover"));

            assertThat(score)
                    .as("""
                            The most dangerous limit of lexical retrieval: a policy and its negation \
                            retrieve almost identically. This is why a report quotes the retrieved \
                            passage verbatim rather than summarising it.""")
                    .isGreaterThan(0.9);
        }
    }

    /** Dot product, which is cosine similarity for unit vectors — as the contract guarantees these are. */
    private static double cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
