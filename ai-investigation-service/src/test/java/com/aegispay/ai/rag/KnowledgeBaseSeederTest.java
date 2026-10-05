package com.aegispay.ai.rag;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.PolicyChunk;
import com.aegispay.ai.domain.PolicyDocument;
import com.aegispay.ai.repository.PolicyChunkRepository;
import com.aegispay.ai.repository.PolicyDocumentRepository;
import com.aegispay.ai.repository.VectorStore;
import com.aegispay.ai.rag.PolicyDocumentLoader.LoadedPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seeding the policy corpus, and the states a re-seed has to distinguish.
 *
 * <p><strong>Why the states matter.</strong> Seeding looks like a one-off, but it runs on every startup and
 * has to tell apart four situations that look alike from the database's point of view: a fresh corpus, an
 * unchanged one, an edited policy, and a corpus whose vectors belong to a previous embedder. Conflating the
 * last with "already present" is the trap — the document row is there, so a naive existence check passes,
 * and retrieval then finds nothing at all while the service reports itself healthy.
 *
 * <p>The seeder deliberately treats an unusable corpus as fatal, which is the opposite of how the missing
 * LLM API key is handled. Both are worth stating together: a missing key still leaves a generator that
 * writes a truthful report, whereas there is no fallback for having no policy to reason from.
 */
class KnowledgeBaseSeederTest {

    private static final String EMBEDDER_ID = "test-embedder/1";

    private final PolicyDocumentLoader loader = mock(PolicyDocumentLoader.class);
    private final PolicyWriter writer = mock(PolicyWriter.class);
    private final PolicyChunkRepository chunks = mock(PolicyChunkRepository.class);
    private final VectorStore vectors = mock(VectorStore.class);
    private final AiProperties properties = new AiProperties();
    private final StubEmbedder embedder = new StubEmbedder();

    private KnowledgeBaseSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new KnowledgeBaseSeeder(loader, writer, chunks, vectors, embedder, properties);

        when(loader.loadAll()).thenReturn(List.of(policy("POLICY-BUST-OUT", 1)));
        when(vectors.findChunksMissingEmbedding(EMBEDDER_ID)).thenReturn(List.of());
        when(chunks.countUsable(EMBEDDER_ID)).thenReturn(4L);
    }

    // ------------------------------------------------------------------------
    // outcomes
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("seed outcomes")
    class Outcomes {

        @Test
        @DisplayName("reports what a first seed inserted")
        void reportsInsertions() {
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.INSERTED, 4));

            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.documentsInserted()).isEqualTo(1);
            assertThat(result.chunksEmbedded()).isEqualTo(4);
            assertThat(result.totalDocuments()).isEqualTo(1);
            assertThat(result.changedAnything()).isTrue();
        }

        /**
         * Re-embedding an unchanged corpus on every restart is wasted work, and with a hosted embedder it is
         * wasted money on every deploy and every pod restart.
         */
        @Test
        @DisplayName("reports an unchanged corpus as having changed nothing")
        void reportsUnchanged() {
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.UNCHANGED, 0));

            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.documentsUnchanged()).isEqualTo(1);
            assertThat(result.changedAnything()).isFalse();
        }

        @Test
        @DisplayName("passes the configured chunk sizes to the writer")
        void usesConfiguredChunking() {
            properties.getKnowledgeBase().setChunkTargetTokens(120);
            properties.getKnowledgeBase().setChunkMaxTokens(200);
            properties.getKnowledgeBase().setChunkOverlapTokens(20);
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.INSERTED, 3));

            seeder.seed();

            verify(writer).write(any(), any(TextChunker.class), eq(EMBEDDER_ID));
        }
    }

    // ------------------------------------------------------------------------
    // repair
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("orphaned vectors")
    class Repair {

        /**
         * A chunk with a row but no vector is invisible to retrieval, because the search joins on a non-null
         * embedding. It silently shrinks the corpus, and nothing counts it as missing.
         */
        @Test
        @DisplayName("repairs a chunk whose row exists but whose vector does not")
        void repairsMissingVector() {
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.UNCHANGED, 0));
            when(vectors.findChunksMissingEmbedding(EMBEDDER_ID))
                    .thenReturn(List.of("POLICY-BUST-OUT#2@" + EMBEDDER_ID));
            when(writer.repairVector(anyString())).thenReturn(true);

            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.vectorsRepaired()).isEqualTo(1);
            assertThat(result.changedAnything())
                    .as("a repair is a change, even when no document was touched")
                    .isTrue();
        }

        /**
         * Checked on every startup rather than only after a known failure, because "the last run finished" is
         * not something a fresh process can know.
         */
        @Test
        @DisplayName("checks for orphaned vectors on every seed")
        void alwaysChecks() {
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.UNCHANGED, 0));

            seeder.seed();

            verify(vectors).findChunksMissingEmbedding(EMBEDDER_ID);
        }

        /**
         * An unrepairable chunk is counted honestly rather than assumed fixed. Guessing at its content would
         * put text in the corpus that no author wrote.
         */
        @Test
        @DisplayName("does not count a chunk it could not repair")
        void doesNotCountFailedRepair() {
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.UNCHANGED, 0));
            when(vectors.findChunksMissingEmbedding(EMBEDDER_ID)).thenReturn(List.of("orphan-1"));
            when(writer.repairVector("orphan-1")).thenReturn(false);

            assertThat(seeder.seed().vectorsRepaired()).isZero();
        }
    }

    // ------------------------------------------------------------------------
    // the fatal case
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("usability check")
    class Usability {

        /**
         * <strong>The most important assertion in this class.</strong> A service that came up with no usable
         * chunks would answer every investigation ungrounded and report no error: the reports would still be
         * produced, still be read, and still look like the grounded ones. Refusing to start is the only
         * signal available.
         */
        @Test
        @DisplayName("refuses to complete when no chunk is usable by the current embedder")
        void failsOnUnusableCorpus() {
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.UNCHANGED, 0));
            when(chunks.countUsable(EMBEDDER_ID)).thenReturn(0L);

            assertThatThrownBy(() -> seeder.seed())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(EMBEDDER_ID)
                    .hasMessageContaining("no grounding");
        }

        /**
         * The check is against the embedder in use, not the table as a whole. A full table of vectors from a
         * previous embedder is not a usable corpus — the vectors are not comparable — and counting rows
         * rather than usable rows is exactly how that state would pass unnoticed.
         */
        @Test
        @DisplayName("counts chunks usable by this embedder, not rows in the table")
        void checksPerEmbedder() {
            when(writer.write(any(), any(), eq(EMBEDDER_ID)))
                    .thenReturn(new PolicyWriter.Outcome(PolicyWriter.Action.UNCHANGED, 0));

            seeder.seed();

            verify(chunks, atLeastOnce()).countUsable(EMBEDDER_ID);
            verify(chunks, never()).count();
        }

        /**
         * A corpus that cannot be read at all is fatal at the loader, before anything is written. Starting
         * with an empty corpus is the failure this whole path exists to prevent.
         */
        @Test
        @DisplayName("propagates a corpus that cannot be loaded")
        void propagatesLoadFailure() {
            when(loader.loadAll()).thenThrow(new IllegalStateException("no policies on the classpath"));

            assertThatThrownBy(() -> seeder.seed())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no policies");
            verify(writer, never()).write(any(), any(), anyString());
        }
    }

    // ------------------------------------------------------------------------
    // the writer
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("policy writer")
    class Writer {

        private final PolicyDocumentRepository documentRepo = mock(PolicyDocumentRepository.class);
        private final PolicyChunkRepository chunkRepo = mock(PolicyChunkRepository.class);
        private final VectorStore vectorStore = mock(VectorStore.class);

        private final PolicyWriter policyWriter =
                new PolicyWriter(documentRepo, chunkRepo, vectorStore, embedder, properties);
        private final TextChunker chunker = new TextChunker(180, 300, 30);

        @BeforeEach
        void stubSaves() {
            when(documentRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(chunkRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(chunkRepo.findByChunkId(anyString())).thenReturn(Optional.empty());
            when(chunkRepo.findByDocumentIdOrderByOrdinalAsc(anyString())).thenReturn(List.of());
            when(chunkRepo.deleteTrailingChunks(anyString(), anyString(), anyInt())).thenReturn(0);
        }

        @Test
        @DisplayName("inserts a policy that is not present yet")
        void insertsNewPolicy() {
            when(documentRepo.findByDocumentId("POLICY-BUST-OUT")).thenReturn(Optional.empty());

            PolicyWriter.Outcome outcome =
                    policyWriter.write(policy("POLICY-BUST-OUT", 1), chunker, EMBEDDER_ID);

            assertThat(outcome.action()).isEqualTo(PolicyWriter.Action.INSERTED);
            assertThat(outcome.chunksEmbedded()).isPositive();
            verify(vectorStore, atLeastOnce()).storeEmbedding(anyString(), any());
        }

        /**
         * Compared by version rather than by body text. A body comparison would re-chunk and re-embed the
         * whole corpus on every whitespace change, and the version is what a citation records anyway.
         */
        @Test
        @DisplayName("leaves an unchanged policy alone")
        void leavesUnchangedPolicyAlone() {
            existing("POLICY-BUST-OUT", 1);

            PolicyWriter.Outcome outcome =
                    policyWriter.write(policy("POLICY-BUST-OUT", 1), chunker, EMBEDDER_ID);

            assertThat(outcome.action()).isEqualTo(PolicyWriter.Action.UNCHANGED);
            assertThat(outcome.chunksEmbedded()).isZero();
            verify(vectorStore, never()).storeEmbedding(anyString(), any());
        }

        @Test
        @DisplayName("upgrades a policy whose bundled version is newer")
        void upgradesOnVersionBump() {
            existing("POLICY-BUST-OUT", 1);

            PolicyWriter.Outcome outcome =
                    policyWriter.write(policy("POLICY-BUST-OUT", 2), chunker, EMBEDDER_ID);

            assertThat(outcome.action()).isEqualTo(PolicyWriter.Action.UPDATED);
            assertThat(outcome.chunksEmbedded()).isPositive();
        }

        /**
         * An older bundled version never overwrites a newer stored one. Otherwise a rollback to a previous
         * image would silently revert a policy edit, and the citation would keep reporting the newer version
         * number it no longer matches.
         */
        @Test
        @DisplayName("does not downgrade a policy that is newer in the database")
        void neverDowngrades() {
            existing("POLICY-BUST-OUT", 5);

            PolicyWriter.Outcome outcome =
                    policyWriter.write(policy("POLICY-BUST-OUT", 2), chunker, EMBEDDER_ID);

            assertThat(outcome.action()).isEqualTo(PolicyWriter.Action.UNCHANGED);
        }

        @Test
        @DisplayName("honours the switch that disables upgrades")
        void honoursUpgradeSwitch() {
            properties.getKnowledgeBase().setUpgradeExistingDocuments(false);
            existing("POLICY-BUST-OUT", 1);

            assertThat(policyWriter.write(policy("POLICY-BUST-OUT", 9), chunker, EMBEDDER_ID).action())
                    .isEqualTo(PolicyWriter.Action.UNCHANGED);
        }

        /**
         * <strong>The state that would otherwise be skipped.</strong> After an embedder change the document
         * row is present, so a naive existence check succeeds and the corpus is left with vectors nothing
         * can compare against. Retrieval then returns nothing, for every investigation, with no error.
         */
        @Test
        @DisplayName("re-chunks a present policy that has no chunks for the current embedder")
        void reChunksAfterEmbedderChange() {
            PolicyDocument document = existing("POLICY-BUST-OUT", 1);
            when(chunkRepo.findByDocumentIdOrderByOrdinalAsc("POLICY-BUST-OUT")).thenReturn(
                    List.of(PolicyChunk.of(document.getDocumentId(), 0, "Old passage.", 40,
                            "previous-embedder/1")));

            PolicyWriter.Outcome outcome =
                    policyWriter.write(policy("POLICY-BUST-OUT", 1), chunker, EMBEDDER_ID);

            assertThat(outcome.chunksEmbedded())
                    .as("the document is present but unusable; leaving it alone means retrieval "
                            + "returns nothing forever")
                    .isPositive();
            verify(vectorStore, atLeastOnce()).storeEmbedding(anyString(), any());
        }

        /**
         * An edited policy may chunk into fewer passages than before. Without this deletion the previous
         * version's tail survives as retrievable text belonging to no current paragraph — cite-able,
         * plausible, and no longer part of the policy it claims to be from.
         */
        @Test
        @DisplayName("deletes chunks the new version no longer has")
        void deletesTrailingChunks() {
            when(documentRepo.findByDocumentId("POLICY-BUST-OUT")).thenReturn(Optional.empty());

            policyWriter.write(policy("POLICY-BUST-OUT", 1), chunker, EMBEDDER_ID);

            verify(chunkRepo).deleteTrailingChunks(eq("POLICY-BUST-OUT"), eq(EMBEDDER_ID), anyInt());
        }

        /**
         * A policy present in the corpus but producing no chunks could never be retrieved, which is the
         * silent failure the whole seeding path is built to avoid.
         */
        @Test
        @DisplayName("refuses a policy that produces no chunks at all")
        void refusesUnchunkablePolicy() {
            when(documentRepo.findByDocumentId("POLICY-EMPTY")).thenReturn(Optional.empty());
            LoadedPolicy unchunkable = new LoadedPolicy("POLICY-EMPTY", "Empty", "GENERAL",
                    List.of(), 1, "manual", "A body that the chunker declines to split.");

            TextChunker returnsNothing = mock(TextChunker.class);
            when(returnsNothing.chunk(anyString())).thenReturn(List.of());

            assertThatThrownBy(() -> policyWriter.write(unchunkable, returnsNothing, EMBEDDER_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no chunks");
        }

        // ------------------------------------------------------- embedded text

        /**
         * <strong>Why the embedded text is not just the passage.</strong> With a lexical embedder, a
         * paragraph three chunks into POLICY-BUST-OUT may never repeat the phrase "bust out" — the author
         * already said it in the heading — so a query about bust-out would score that paragraph near zero
         * despite it being exactly the relevant text. Prepending the document's own vocabulary is what keeps
         * a mid-document passage findable.
         */
        @Test
        @DisplayName("embeds the document's vocabulary alongside the passage")
        void embedsDocumentContext() {
            PolicyDocument document = PolicyDocument.of("POLICY-BUST-OUT",
                    "Bust-out pattern on established accounts", "ATTACK_PATTERN",
                    List.of("RULE_BUST_OUT_PATTERN"), "Body.", 1, "manual");
            TextChunker.Chunk passage =
                    new TextChunker.Chunk(3, "The account then empties itself.", 40);

            String embedded = PolicyWriter.embeddingTextFor(document, passage);

            assertThat(embedded)
                    .contains("Bust-out pattern on established accounts")
                    .contains("ATTACK_PATTERN")
                    .as("the rule id gives the vector a second route to the association the declared "
                            + "mapping covers exactly")
                    .contains("RULE_BUST_OUT_PATTERN")
                    .endsWith("The account then empties itself.");
        }

        /**
         * The stored content stays the author's text. A citation showing the synthetic header would display
         * words nobody wrote as though they were policy.
         */
        @Test
        @DisplayName("keeps the synthetic header out of a policy with no declared rules")
        void omitsAbsentRuleIds() {
            PolicyDocument document = PolicyDocument.of("POLICY-EVIDENCE-STANDARD",
                    "Evidence standard", "EVIDENCE_STANDARD", List.of(), "Body.", 1, "manual");

            assertThat(PolicyWriter.embeddingTextFor(document,
                    new TextChunker.Chunk(0, "A claim needs evidence.", 40)))
                    .isEqualTo("Evidence standard\nEVIDENCE_STANDARD\nA claim needs evidence.");
        }

        private PolicyDocument existing(String documentId, int version) {
            PolicyDocument document = PolicyDocument.of(documentId, "Title", "ATTACK_PATTERN",
                    List.of("RULE_BUST_OUT_PATTERN"), body(), version, "manual");
            when(documentRepo.findByDocumentId(documentId)).thenReturn(Optional.of(document));
            when(chunkRepo.findByDocumentIdOrderByOrdinalAsc(documentId)).thenReturn(
                    List.of(PolicyChunk.of(documentId, 0, "Stored passage.", 40, EMBEDDER_ID)));
            return document;
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static LoadedPolicy policy(String documentId, int version) {
        return new LoadedPolicy(documentId, "Title of " + documentId, "ATTACK_PATTERN",
                List.of("RULE_BUST_OUT_PATTERN"), version, "Fraud Operations Manual", body());
    }

    private static String body() {
        return """
                An established account that behaves normally for months and then empties itself is the
                shape of a bust-out. The history is what makes it work: the account looks trustworthy
                precisely because it has been trustworthy.

                What to do about it depends on how much of the balance is still recoverable, and on
                whether the payee has ever been paid before by this account.
                """;
    }

    private static final class StubEmbedder implements Embedder {

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
            return new float[] {1f, 0f, 0f, 0f};
        }
    }
}
