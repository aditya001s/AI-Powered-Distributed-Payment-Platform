package com.aegispay.ai.rag;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.PolicyChunk;
import com.aegispay.ai.domain.PolicyDocument;
import com.aegispay.ai.repository.PolicyChunkRepository;
import com.aegispay.ai.repository.PolicyDocumentRepository;
import com.aegispay.ai.repository.VectorStore;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hybrid retrieval: the declared rule mapping, then vector search.
 *
 * <p><strong>Why this class carries so much weight.</strong> Retrieval decides what an investigation is
 * allowed to know, and every one of its failure modes produces a report that reads perfectly well. A
 * declared mapping that silently misses returns fewer citations; a floor that is not applied returns
 * irrelevant ones; a de-duplication bug returns the same document twice and makes one source look like
 * two agreeing ones. None of those throw, and none of them look wrong in the output.
 *
 * <p>The database is mocked rather than real. That is a genuine limitation — the {@code LIKE} delimiters
 * in {@code findByRelatedRule} and the SQL similarity floor are only exercised by the integration tests,
 * which need Docker. What is tested here is everything above the query: which route wins, what gets
 * de-duplicated, how the budget is spent, and what happens when nothing matches.
 */
class PolicyRetrieverTest {

    private static final String EMBEDDER_ID = "test-embedder/1";

    private final PolicyDocumentRepository documents = mock(PolicyDocumentRepository.class);
    private final PolicyChunkRepository chunks = mock(PolicyChunkRepository.class);
    private final VectorStore vectors = mock(VectorStore.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiProperties properties = new AiProperties();

    private StubEmbedder embedder;
    private PolicyRetriever retriever;

    @BeforeEach
    void setUp() {
        embedder = new StubEmbedder();
        retriever = new PolicyRetriever(documents, chunks, vectors, embedder, properties, meterRegistry);
    }

    // ------------------------------------------------------------------------
    // the declared route
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("declared rule mapping")
    class DeclaredRoute {

        /**
         * The authoritative route. When a rule fired, the policy that defines it is the document a
         * reviewer expects to see cited, and its presence must not depend on an embedder having learned
         * that a machine token relates to a paragraph of prose.
         */
        @Test
        @DisplayName("includes the policy declared against a fired rule")
        void includesDeclaredPolicy() {
            declaredPolicy("RULE_BUST_OUT_PATTERN", "POLICY-BUST-OUT", "Bust-out", 40);
            noVectorMatches();

            List<RetrievedPolicy> retrieved =
                    retriever.retrieve("large amount", List.of("RULE_BUST_OUT_PATTERN"));

            assertThat(retrieved).singleElement().satisfies(policy -> {
                assertThat(policy.documentId()).isEqualTo("POLICY-BUST-OUT");
                assertThat(policy.source()).isEqualTo(RetrievedPolicy.Source.DECLARED_RULE);
                assertThat(policy.isAuthoritative()).isTrue();
            });
        }

        /**
         * A declared match compared no vectors, so reporting a measured similarity would be inventing a
         * number — and 0.9-something would make an exact, written-down association look like a close
         * guess.
         */
        @Test
        @DisplayName("reports a declared match as certain rather than inventing a similarity")
        void declaredMatchIsCertain() {
            declaredPolicy("RULE_IMPOSSIBLE_TRAVEL", "POLICY-IMPOSSIBLE-TRAVEL", "Travel", 40);
            noVectorMatches();

            assertThat(retriever.retrieve("speed", List.of("RULE_IMPOSSIBLE_TRAVEL")))
                    .singleElement()
                    .extracting(RetrievedPolicy::similarity)
                    .isEqualTo(1.0);
        }

        /**
         * Ranked ahead of every searched passage regardless of similarity, because the prompt budget
         * should be spent on the authoritative document before the merely similar one.
         */
        @Test
        @DisplayName("ranks declared policy ahead of searched policy")
        void declaredOutranksSearched() {
            declaredPolicy("RULE_BUST_OUT_PATTERN", "POLICY-BUST-OUT", "Bust-out", 40);
            when(vectors.search(any(), eq(EMBEDDER_ID), anyDouble(), anyInt()))
                    .thenReturn(List.of(match("POLICY-HOLD-HANDLING", 0.95, 1)));

            List<RetrievedPolicy> retrieved =
                    retriever.retrieve("large amount", List.of("RULE_BUST_OUT_PATTERN"));

            assertThat(retrieved).extracting(RetrievedPolicy::documentId)
                    .as("a 0.95 similarity does not outrank the policy that defines the rule which "
                            + "actually fired")
                    .containsExactly("POLICY-BUST-OUT", "POLICY-HOLD-HANDLING");
            assertThat(retrieved.getFirst().rank()).isEqualTo(1);
            assertThat(retrieved.getLast().rank()).isEqualTo(2);
        }

        /**
         * Two rules can legitimately declare the same policy. Citing it twice would suggest two
         * independent sources agreed, when it is one document counted twice — and in an evidence list
         * that reads as corroboration.
         */
        @Test
        @DisplayName("cites a policy shared by two rules only once")
        void deduplicatesAcrossRules() {
            PolicyDocument shared = document("POLICY-ACCOUNT-TAKEOVER", "Takeover",
                    List.of("RULE_NEW_DEVICE_HIGH_VALUE", "RULE_DORMANT_ACCOUNT_LARGE_PAYMENT"));
            when(documents.findByRelatedRule("RULE_NEW_DEVICE_HIGH_VALUE")).thenReturn(List.of(shared));
            when(documents.findByRelatedRule("RULE_DORMANT_ACCOUNT_LARGE_PAYMENT"))
                    .thenReturn(List.of(shared));
            chunksFor("POLICY-ACCOUNT-TAKEOVER", 40);
            noVectorMatches();

            List<RetrievedPolicy> retrieved = retriever.retrieve("new device", List.of(
                    "RULE_NEW_DEVICE_HIGH_VALUE", "RULE_DORMANT_ACCOUNT_LARGE_PAYMENT"));

            assertThat(retrieved)
                    .as("one document counted twice reads as two sources agreeing")
                    .hasSize(1);
        }

        /**
         * A document that exists but has no chunks for the current embedder is a corpus mid-migration.
         * Citing it from the raw body was rejected: a citation has to point at something retrieval could
         * actually have found, or the chunk id on it refers to nothing.
         */
        @Test
        @DisplayName("skips a declared policy that has no chunks for the current embedder")
        void skipsUnchunkedPolicy() {
            when(documents.findByRelatedRule("RULE_BUST_OUT_PATTERN"))
                    .thenReturn(List.of(document("POLICY-BUST-OUT", "Bust-out",
                            List.of("RULE_BUST_OUT_PATTERN"))));
            when(chunks.findByDocumentIdOrderByOrdinalAsc("POLICY-BUST-OUT")).thenReturn(List.of());
            noVectorMatches();

            assertThat(retriever.retrieve("amount", List.of("RULE_BUST_OUT_PATTERN"))).isEmpty();
        }

        /**
         * The first chunk, not the whole policy. A policy is authored with its definition up front, and
         * pulling every chunk of a long document would spend the entire prompt budget on one of them.
         */
        @Test
        @DisplayName("takes the opening chunk of a declared policy")
        void takesOpeningChunk() {
            when(documents.findByRelatedRule("RULE_BUST_OUT_PATTERN"))
                    .thenReturn(List.of(document("POLICY-BUST-OUT", "Bust-out",
                            List.of("RULE_BUST_OUT_PATTERN"))));
            when(chunks.findByDocumentIdOrderByOrdinalAsc("POLICY-BUST-OUT")).thenReturn(List.of(
                    chunk("POLICY-BUST-OUT", 0, "What a bust-out is.", 40),
                    chunk("POLICY-BUST-OUT", 1, "What to do about one.", 40)));
            noVectorMatches();

            assertThat(retriever.retrieve("amount", List.of("RULE_BUST_OUT_PATTERN")))
                    .singleElement()
                    .satisfies(policy -> {
                        assertThat(policy.ordinal()).isZero();
                        assertThat(policy.content()).isEqualTo("What a bust-out is.");
                    });
        }

        @Test
        @DisplayName("ignores null and blank rule ids without querying for them")
        void ignoresBlankRuleIds() {
            noVectorMatches();

            retriever.retrieve("amount", java.util.Arrays.asList(null, "", "  "));

            verify(documents, never()).findByRelatedRule(anyString());
        }

        /**
         * With the declared route disabled, retrieval falls back to resemblance alone — which is the
         * configuration this service deliberately does not ship, and worth proving the switch is real
         * rather than decorative.
         */
        @Test
        @DisplayName("skips the declared lookup entirely when it is switched off")
        void honoursTheSwitch() {
            properties.getRetrieval().setAlwaysIncludeRulePolicies(false);
            noVectorMatches();

            retriever.retrieve("amount", List.of("RULE_BUST_OUT_PATTERN"));

            verify(documents, never()).findByRelatedRule(anyString());
        }
    }

    // ------------------------------------------------------------------------
    // the vector route
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("vector search")
    class VectorRoute {

        /**
         * The floor is passed to the query rather than applied afterwards, so a caller cannot forget it
         * and the ORDER BY and the filter agree on the same quantity.
         */
        @Test
        @DisplayName("passes the configured floor and embedder id down to the query")
        void passesFloorAndEmbedder() {
            properties.getRetrieval().setMinimumSimilarity(0.42);
            noVectorMatches();

            retriever.retrieve("unfamiliar country", List.of());

            ArgumentCaptor<Double> floor = ArgumentCaptor.forClass(Double.class);
            verify(vectors).search(any(), eq(EMBEDDER_ID), floor.capture(), anyInt());
            assertThat(floor.getValue())
                    .as("the floor is what makes 'no policy matched' expressible; a caller must not "
                            + "be able to omit it")
                    .isEqualTo(0.42);
        }

        /**
         * <strong>A subtle one.</strong> Declared documents are excluded from the search results, so if
         * the limit were a plain {@code topK} a declared policy that also matched by similarity would
         * consume one of the k slots and be discarded as a duplicate — silently narrowing the search by
         * exactly the number of rules that fired.
         */
        @Test
        @DisplayName("widens the search limit by the documents already taken")
        void widensLimitForDeclaredDocuments() {
            properties.getRetrieval().setTopK(5);
            declaredPolicy("RULE_BUST_OUT_PATTERN", "POLICY-BUST-OUT", "Bust-out", 40);
            noVectorMatches();

            retriever.retrieve("amount", List.of("RULE_BUST_OUT_PATTERN"));

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(vectors).search(any(), eq(EMBEDDER_ID), anyDouble(), limit.capture());
            assertThat(limit.getValue())
                    .as("a plain topK would let an already-taken document eat one of the k slots")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("keeps at most topK searched passages")
        void capsAtTopK() {
            properties.getRetrieval().setTopK(2);
            when(vectors.search(any(), eq(EMBEDDER_ID), anyDouble(), anyInt())).thenReturn(List.of(
                    match("POLICY-A", 0.9, 1),
                    match("POLICY-B", 0.8, 2),
                    match("POLICY-C", 0.7, 3)));

            assertThat(retriever.retrieve("amount", List.of()))
                    .extracting(RetrievedPolicy::documentId)
                    .containsExactly("POLICY-A", "POLICY-B");
        }

        /**
         * Two chunks of one policy are not two sources. Keeping both would fill the evidence list with
         * one document's paragraphs and crowd out every other policy.
         */
        @Test
        @DisplayName("keeps only the strongest passage per document")
        void oneChunkPerDocument() {
            when(vectors.search(any(), eq(EMBEDDER_ID), anyDouble(), anyInt())).thenReturn(List.of(
                    match("POLICY-A", 0.9, 1),
                    match("POLICY-A", 0.85, 2),
                    match("POLICY-B", 0.5, 3)));

            assertThat(retriever.retrieve("amount", List.of()))
                    .extracting(RetrievedPolicy::documentId)
                    .containsExactly("POLICY-A", "POLICY-B");
        }

        @Test
        @DisplayName("does not search on an absent query")
        void skipsSearchWithoutQuery() {
            retriever.retrieve("   ", List.of());

            verify(vectors, never()).search(any(), anyString(), anyDouble(), anyInt());
        }

        /**
         * A zero vector means every term was a stopword or the query was punctuation. Cosine distance
         * against it is undefined and pgvector returns NaN — and a NaN distance does not sort, so the
         * chunk would land at an arbitrary position in every result rather than being excluded from any.
         */
        @Test
        @DisplayName("refuses to search on a zero vector rather than let NaN distances sort")
        void refusesZeroVector() {
            embedder.returnZero = true;

            assertThat(retriever.retrieve("the and of", List.of())).isEmpty();
            verify(vectors, never()).search(any(), anyString(), anyDouble(), anyInt());
        }
    }

    // ------------------------------------------------------------------------
    // budget and observability
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("prompt budget")
    class Budget {

        /**
         * Whole passages are dropped rather than the last one clipped. A chunk cut mid-sentence is worse
         * than an absent chunk: it is still cited, still looks like evidence, and the removed half may
         * have been the part that mattered.
         */
        @Test
        @DisplayName("drops whole passages to fit the budget")
        void dropsWholePassages() {
            properties.getRetrieval().setContextTokenBudget(250);
            properties.getRetrieval().setTopK(5);
            when(vectors.search(any(), eq(EMBEDDER_ID), anyDouble(), anyInt())).thenReturn(List.of(
                    match("POLICY-A", 0.9, 1, 100),
                    match("POLICY-B", 0.8, 2, 100),
                    match("POLICY-C", 0.7, 3, 100)));

            List<RetrievedPolicy> retrieved = retriever.retrieve("amount", List.of());

            assertThat(retrieved).extracting(RetrievedPolicy::documentId)
                    .containsExactly("POLICY-A", "POLICY-B");
            assertThat(retrieved).allSatisfy(policy ->
                    assertThat(policy.content()).doesNotContain("…").doesNotContain("..."));
        }

        /**
         * A retrieval that found something and then reported nothing because of an accounting limit
         * would be indistinguishable from a corpus with no relevant policy — and those two states call
         * for completely different reports.
         */
        @Test
        @DisplayName("keeps one passage even when it alone exceeds the budget")
        void alwaysKeepsOne() {
            properties.getRetrieval().setContextTokenBudget(200);
            when(vectors.search(any(), eq(EMBEDDER_ID), anyDouble(), anyInt()))
                    .thenReturn(List.of(match("POLICY-A", 0.9, 1, 5_000)));

            assertThat(retriever.retrieve("amount", List.of()))
                    .as("'found nothing' and 'found something too long to include' need different "
                            + "reports, so they must not produce the same empty result")
                    .hasSize(1);
        }

        /**
         * An empty retrieval is not an error — the narrative should say it found no written grounding.
         * But a rising rate means the corpus has drifted away from the traffic, which nothing else in
         * the system would report, so it gets a counter of its own.
         */
        @Test
        @DisplayName("counts an ungrounded retrieval without failing it")
        void countsUngroundedRetrieval() {
            noVectorMatches();

            assertThat(retriever.retrieve("nothing matches this", List.of())).isEmpty();
            assertThat(meterRegistry.counter(MetricNames.AI_UNGROUNDED_INVESTIGATION).count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("does not count a grounded retrieval as ungrounded")
        void doesNotMiscountGrounded() {
            when(vectors.search(any(), eq(EMBEDDER_ID), anyDouble(), anyInt()))
                    .thenReturn(List.of(match("POLICY-A", 0.9, 1)));

            retriever.retrieve("amount", List.of());

            assertThat(meterRegistry.counter(MetricNames.AI_UNGROUNDED_INVESTIGATION).count())
                    .isZero();
            assertThat(meterRegistry.summary(MetricNames.AI_RETRIEVED_CHUNKS).max()).isEqualTo(1.0);
        }

        /**
         * Retrieval latency is tagged with the embedder because a swap to a hosted embedding model
         * changes this number by orders of magnitude, and an untagged timer would blend the two into an
         * average that describes neither.
         */
        @Test
        @DisplayName("times retrieval, tagged by embedder")
        void timesRetrieval() {
            noVectorMatches();

            retriever.retrieve("amount", List.of());

            assertThat(meterRegistry.timer(MetricNames.AI_RETRIEVAL_LATENCY, "embedder", EMBEDDER_ID)
                    .count()).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------------
    // disclosure
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("disclosure")
    class Disclosure {

        /**
         * The report has to be able to describe its own confidence honestly. With a lexical embedder,
         * "no policy matched" means no policy shared vocabulary with the query — not that no relevant
         * policy exists, which is what a semantic null result would imply.
         */
        @Test
        @DisplayName("exposes whether retrieval was semantic, so a report can say so")
        void exposesSemanticFlag() {
            assertThat(retriever.isSemanticRetrieval()).isFalse();
            assertThat(retriever.embedderId()).isEqualTo(EMBEDDER_ID);
        }

        /**
         * Zero usable chunks with a non-empty table means the corpus belongs to a previous embedder and
         * is invisible to retrieval — correctly, but silently. Exposed so readiness can fail on it
         * instead of a week of ungrounded reports being the first symptom.
         */
        @Test
        @DisplayName("reports how many chunks the current embedder can actually use")
        void reportsUsableChunks() {
            when(chunks.countUsable(EMBEDDER_ID)).thenReturn(37L);

            assertThat(retriever.usableChunkCount()).isEqualTo(37L);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private void declaredPolicy(String ruleId, String documentId, String title, int tokens) {
        when(documents.findByRelatedRule(ruleId))
                .thenReturn(List.of(document(documentId, title, List.of(ruleId))));
        chunksFor(documentId, tokens);
    }

    private void chunksFor(String documentId, int tokens) {
        when(chunks.findByDocumentIdOrderByOrdinalAsc(documentId))
                .thenReturn(List.of(chunk(documentId, 0, "Opening passage of " + documentId, tokens)));
    }

    private void noVectorMatches() {
        when(vectors.search(any(), anyString(), anyDouble(), anyInt())).thenReturn(List.of());
    }

    private static PolicyDocument document(String documentId, String title, List<String> ruleIds) {
        return PolicyDocument.of(documentId, title, "ATTACK_PATTERN", ruleIds,
                "The authored body of " + documentId + ".", 1, "Fraud Operations Manual");
    }

    private static PolicyChunk chunk(String documentId, int ordinal, String content, int tokens) {
        return PolicyChunk.of(documentId, ordinal, content, tokens, EMBEDDER_ID);
    }

    private static VectorStore.Match match(String documentId, double similarity, int rank) {
        return match(documentId, similarity, rank, 40);
    }

    private static VectorStore.Match match(String documentId, double similarity, int rank, int tokens) {
        return new VectorStore.Match(
                documentId + "#0@" + EMBEDDER_ID, documentId, "Title of " + documentId, 1,
                "ATTACK_PATTERN", 0, "Passage of " + documentId, tokens, similarity, rank);
    }

    /**
     * A deterministic embedder, so a retrieval assertion is not also an assertion about hashing.
     *
     * <p>Hand-written rather than mocked: the zero-vector case is the interesting one, and expressing
     * "return a vector that is entirely zeros" is clearer as a field than as a stubbing chain.
     */
    private static final class StubEmbedder implements Embedder {

        private boolean returnZero;

        @Override
        public String id() {
            return EMBEDDER_ID;
        }

        @Override
        public int dimensions() {
            return 4;
        }

        @Override
        public float[] embed(String text) {
            return returnZero ? new float[4] : new float[] {1f, 0f, 0f, 0f};
        }
    }
}
