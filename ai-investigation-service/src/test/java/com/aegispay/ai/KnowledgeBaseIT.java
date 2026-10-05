package com.aegispay.ai;

import com.aegispay.ai.rag.Embedder;
import com.aegispay.ai.rag.KnowledgeBaseSeeder;
import com.aegispay.ai.rag.PolicyRetriever;
import com.aegispay.ai.repository.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The corpus as it actually exists in the database after startup.
 *
 * <p>Everything here needs pgvector. The retrieval query uses the {@code <=>} cosine operator through an
 * HNSW index, with the ANN ordering in a subquery so the index is used and the similarity floor applied
 * outside it — a shape that cannot be exercised by anything short of a real Postgres with the extension
 * installed.
 */
class KnowledgeBaseIT extends AbstractAiIntegrationTest {

    @Autowired
    private KnowledgeBaseSeeder seeder;

    @Autowired
    private PolicyRetriever retriever;

    @Autowired
    private VectorStore vectors;

    @Autowired
    private Embedder embedder;

    // ------------------------------------------------------------------------
    // seeding
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("startup seeding")
    @org.springframework.transaction.annotation.Transactional
    class Seeding {

        /**
         * The corpus is seeded by an application runner, so by the time any test runs the policies are
         * already there. A service that came up with an empty corpus would answer every investigation
         * ungrounded and report no error, which is why the runner throws rather than warns.
         */
        @Test
        @DisplayName("has the whole corpus loaded before the first request")
        void corpusIsLoadedAtStartup() {
            assertThat(countPolicyDocuments())
                    .as("the bundled policies are seeded at startup, and failing to seed prevents "
                            + "startup entirely")
                    .isGreaterThanOrEqualTo(11L);
            assertThat(countPolicyChunks()).isPositive();
        }

        /**
         * Re-seeding must be a no-op. Chunk ids are derived from the document, the ordinal and the embedder
         * rather than generated, so a restart upserts instead of accumulating a second copy of the entire
         * corpus every time the service boots.
         */
        @Test
        @DisplayName("changes nothing when run again over an unchanged corpus")
        void reseedIsIdempotent() {
            long documentsBefore = countPolicyDocuments();
            long chunksBefore = countPolicyChunks();

            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.changedAnything())
                    .as("re-embedding an unchanged corpus is wasted work, and with a hosted embedder "
                            + "it is wasted money on every pod restart")
                    .isFalse();
            assertThat(countPolicyDocuments()).isEqualTo(documentsBefore);
            assertThat(countPolicyChunks()).isEqualTo(chunksBefore);
        }

        @Test
        @DisplayName("reports every document as present and unchanged on a second pass")
        void secondPassSeesEverythingUnchanged() {
            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.documentsUnchanged()).isEqualTo(result.totalDocuments());
            assertThat(result.documentsInserted()).isZero();
            assertThat(result.chunksEmbedded()).isZero();
        }

        /**
         * A chunk with metadata and no vector is invisible to retrieval, because the search joins on a
         * non-null embedding. It is reachable if a previous run died between the row insert and the vector
         * update — separate statements in one transaction, but not one statement.
         */
        @Test
        @DisplayName("repairs a chunk whose vector was lost")
        void repairsOrphanedVector() {
            String chunkId = jdbc.queryForObject("""
                    SELECT chunk_id FROM policy_chunks WHERE embedder = ? ORDER BY chunk_id LIMIT 1
                    """, String.class, embedder.id());

            jdbc.update("UPDATE policy_chunks SET embedding = NULL WHERE chunk_id = ?", chunkId);
            assertThat(vectors.findChunksMissingEmbedding(embedder.id())).contains(chunkId);

            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.vectorsRepaired()).isEqualTo(1);
            assertThat(vectors.findChunksMissingEmbedding(embedder.id())).isEmpty();
        }

        /**
         * An edited policy is picked up by version, not by body text. A body comparison would re-chunk and
         * re-embed the whole corpus on every whitespace change, and the version is what a citation records
         * anyway.
         */
        @Test
        @DisplayName("re-embeds a policy whose stored version is behind the bundled one")
        void upgradesOnVersionBump() {
            jdbc.update("UPDATE policy_documents SET version = 1 WHERE document_id = ?",
                    "POLICY-BUST-OUT");

            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.documentsUpdated()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM policy_documents WHERE document_id = ?", Integer.class,
                    "POLICY-BUST-OUT"))
                    .isEqualTo(2);
        }

        /**
         * <strong>The state a naive existence check would skip.</strong> After an embedder change every
         * document row is still present, so an "exists?" check succeeds while the corpus holds only vectors
         * nothing can compare against. Retrieval then returns nothing for every investigation, with no error
         * anywhere.
         *
         * <p>Simulated by relabelling the chunks, which is indistinguishable from the real thing as far as
         * retrieval is concerned. Seeding then self-heals: the current embedder finds no chunks of its own
         * and rebuilds them, which is why an embedder swap needs no migration and no human intervention.
         */
        @Test
        @DisplayName("rebuilds the corpus when its chunks belong to a previous embedder")
        void selfHealsAfterAnEmbedderChange() {
            String previousEmbedder = "some-previous-embedder/1";
            jdbc.update("""
                    UPDATE policy_chunks
                    SET chunk_id = left(document_id || '#' || ordinal || '@' || ?, 80),
                        embedder = ?
                    WHERE embedder = ?
                    """, previousEmbedder, previousEmbedder, embedder.id());

            assertThat(retriever.usableChunkCount())
                    .as("a corpus embedded by a previous embedder is invisible to the current one — "
                            + "correct, because the vectors are not comparable, but silent")
                    .isZero();
            assertThat(vectors.search(embedder.embed("bust-out pattern"), embedder.id(), 0.0, 5))
                    .as("and retrieval returns nothing rather than comparing across embedders")
                    .isEmpty();

            KnowledgeBaseSeeder.SeedResult result = seeder.seed();

            assertThat(result.chunksEmbedded())
                    .as("the new embedder re-chunks and re-embeds without a migration")
                    .isPositive();
            assertThat(retriever.usableChunkCount()).isPositive();
            assertThat(vectors.search(embedder.embed(
                    "bust-out pattern established account empties itself"), embedder.id(), 0.05, 5))
                    .isNotEmpty();
        }

        // The fatal-on-unusable-corpus path is asserted in KnowledgeBaseSeederTest, where the chunk count
        // can be forced to zero directly. Reaching it here would mean emptying the corpus and then
        // preventing the seeder from refilling it, which the seeder is specifically built to do — the setup
        // would end up asserting the test's own scaffolding rather than the service.
    }

    // ------------------------------------------------------------------------
    // retrieval through pgvector
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("nearest-neighbour search")
    class Search {

        /**
         * The basic guarantee: a query drawn from a policy's own vocabulary retrieves that policy. With a
         * lexical embedder this is the case that must work, and it is the case the corpus is authored for —
         * the queries are rule ids and feature names, and the policies are written to explain them.
         */
        @Test
        @DisplayName("finds the bust-out policy from bust-out vocabulary")
        void findsOnTopicPolicy() {
            List<VectorStore.Match> matches = vectors.search(
                    embedder.embed("bust-out pattern established account empties itself largest payment"),
                    embedder.id(), 0.05, 5);

            assertThat(matches).isNotEmpty();
            assertThat(matches).extracting(VectorStore.Match::documentId)
                    .contains("POLICY-BUST-OUT");
        }

        /**
         * Similarity is ordered highest first and the rank reflects it, because a citation records how
         * strong a match it was and a mis-ordered rank would make the weakest evidence look like the
         * strongest.
         */
        @Test
        @DisplayName("returns matches strongest first")
        void ordersBySimilarity() {
            List<VectorStore.Match> matches = vectors.search(
                    embedder.embed("card testing failed attempts in one hour credential burst"),
                    embedder.id(), 0.0, 5);

            assertThat(matches).isNotEmpty();
            assertThat(matches).isSortedAccordingTo(
                    (left, right) -> Double.compare(right.similarity(), left.similarity()));
            assertThat(matches.getFirst().rank()).isEqualTo(1);
        }

        /**
         * <strong>The floor, enforced by the query rather than the caller.</strong> An ANN search returns
         * its k nearest neighbours however distant they are, so a floor nothing can clear must return
         * nothing at all. Without that, "no policy matched" would not be an expressible outcome.
         */
        @Test
        @DisplayName("returns nothing when the floor is unreachable")
        void floorExcludesEverything() {
            assertThat(vectors.search(embedder.embed("bust-out pattern"), embedder.id(), 0.999, 5))
                    .as("the floor is what separates 'nothing relevant exists' from 'here are the "
                            + "five least irrelevant documents'")
                    .isEmpty();
        }

        /**
         * Cosine similarity is bounded. A value outside [-1,1] would mean the conversion from pgvector's
         * distance is wrong, and every threshold in the service is expressed against that conversion.
         */
        @Test
        @DisplayName("reports similarity as a bounded cosine value")
        void similarityIsBounded() {
            assertThat(vectors.search(embedder.embed("impossible travel speed between payments"),
                    embedder.id(), 0.0, 5))
                    .allSatisfy(match -> assertThat(match.similarity()).isBetween(-1.0, 1.0));
        }

        /**
         * A vector from a different embedder is not comparable to these. Filtering by embedder id means the
         * mismatch returns nothing rather than returning confident nonsense.
         */
        @Test
        @DisplayName("finds nothing for an embedder that owns no vectors")
        void filtersByEmbedder() {
            assertThat(vectors.search(embedder.embed("bust-out pattern"),
                    "an-embedder-that-never-ran/1", 0.0, 5))
                    .isEmpty();
        }

        /**
         * The whole corpus is usable by the embedder in use. Zero here with a non-empty table is the silent
         * state the readiness check exists to catch.
         */
        @Test
        @DisplayName("reports the whole corpus as usable by the current embedder")
        void corpusIsUsable() {
            assertThat(retriever.usableChunkCount()).isEqualTo(countPolicyChunks());
            assertThat(retriever.embedderId()).isEqualTo(EMBEDDER_ID);
        }

        /**
         * Stated in the API and asserted here, because it changes how a null result should be read: with a
         * lexical embedder, "no policy matched" means no policy shared vocabulary with the query, not that
         * no relevant policy exists.
         */
        @Test
        @DisplayName("does not claim to retrieve semantically")
        void isHonestAboutBeingLexical() {
            assertThat(retriever.isSemanticRetrieval()).isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // the declared mapping, as SQL
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("declared rule mapping")
    class DeclaredMapping {

        /**
         * The comma-delimited {@code LIKE} is what stops a rule id matching another by prefix. A bare
         * {@code LIKE '%id%'} would let {@code RULE_CARD_TESTING} match
         * {@code RULE_CARD_TESTING_VELOCITY}, and a citation pointing at the wrong policy is worse than no
         * citation because it looks correct.
         */
        @Test
        @DisplayName("matches a whole rule id, never a prefix of a longer one")
        void matchesWholeIdsOnly() {
            List<String> exact = jdbc.queryForList("""
                    SELECT document_id FROM policy_documents
                    WHERE related_rule_ids IS NOT NULL
                      AND ',' || replace(related_rule_ids, ' ', '') || ','
                          LIKE '%,' || ? || ',%'
                    ORDER BY document_id
                    """, String.class, "RULE_CARD_TESTING");

            assertThat(exact)
                    .as("RULE_CARD_TESTING is not a rule; RULE_CARD_TESTING_VELOCITY is, and it must "
                            + "not be reached by a prefix")
                    .isEmpty();

            assertThat(jdbc.queryForList("""
                    SELECT document_id FROM policy_documents
                    WHERE related_rule_ids IS NOT NULL
                      AND ',' || replace(related_rule_ids, ' ', '') || ','
                          LIKE '%,' || ? || ',%'
                    ORDER BY document_id
                    """, String.class, "RULE_CARD_TESTING_VELOCITY"))
                    .contains("POLICY-CARD-TESTING");
        }

        /**
         * A policy declaring two rules is reachable by both. The stored form is a comma-joined string, so
         * the second and any middle entry are the ones a naive delimiter would miss.
         */
        @Test
        @DisplayName("finds a policy by any of the rules it declares")
        void findsByEitherDeclaredRule() {
            for (String ruleId : List.of("RULE_NEW_DEVICE_HIGH_VALUE",
                    "RULE_DORMANT_ACCOUNT_LARGE_PAYMENT")) {

                assertThat(jdbc.queryForList("""
                        SELECT document_id FROM policy_documents
                        WHERE related_rule_ids IS NOT NULL
                          AND ',' || replace(related_rule_ids, ' ', '') || ','
                              LIKE '%,' || ? || ',%'
                        """, String.class, ruleId))
                        .as("policy for " + ruleId)
                        .isNotEmpty();
            }
        }
    }
}
