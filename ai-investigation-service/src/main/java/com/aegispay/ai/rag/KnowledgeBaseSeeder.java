package com.aegispay.ai.rag;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.repository.PolicyChunkRepository;
import com.aegispay.ai.repository.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads the bundled policies into the database and embeds them.
 *
 * <h2>Why this runs at startup rather than in a migration</h2>
 *
 * <p>The embeddings depend on the embedder, and the embedder is application code. A migration cannot compute
 * a vector, and hard-coding pre-computed vectors into SQL would let the corpus and the embedder drift apart
 * with nothing to detect it — the numbers would still be there, still comparable-looking, and no longer
 * produced by the code that queries them.
 *
 * <p>Running at startup also makes an embedder change self-healing: the new embedder finds no chunks of its
 * own, re-embeds the corpus, and retrieval starts working again. Under a migration it would need a human to
 * notice and write one.
 *
 * <h2>Why this class holds no transaction</h2>
 *
 * <p>It loops over documents computing an embedding for each, which with a hosted embedder is an HTTP call.
 * A transaction spanning that would hold a database connection open across the network. Every write
 * therefore happens in {@link PolicyWriter}, behind a real bean boundary — a {@code this.method()} call
 * would bypass Spring's proxy and run untransacted with no symptom.
 *
 * <h2>Why a failure here is fatal</h2>
 *
 * <p>A service that came up with an empty or partial corpus would answer every investigation ungrounded and
 * report no error at all. That is worse than not starting: the reports would still be produced, still be
 * read, and still look like the grounded ones.
 */
@Service
public class KnowledgeBaseSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    private final PolicyDocumentLoader loader;
    private final PolicyWriter writer;
    private final PolicyChunkRepository chunks;
    private final VectorStore vectors;
    private final Embedder embedder;
    private final AiProperties properties;

    public KnowledgeBaseSeeder(PolicyDocumentLoader loader,
                               PolicyWriter writer,
                               PolicyChunkRepository chunks,
                               VectorStore vectors,
                               Embedder embedder,
                               AiProperties properties) {
        this.loader = loader;
        this.writer = writer;
        this.chunks = chunks;
        this.vectors = vectors;
        this.embedder = embedder;
        this.properties = properties;
    }

    /** What one seed pass did, for the startup log and for tests. */
    public record SeedResult(int documentsInserted, int documentsUpdated, int documentsUnchanged,
                             int chunksEmbedded, int vectorsRepaired) {

        public int totalDocuments() {
            return documentsInserted + documentsUpdated + documentsUnchanged;
        }

        public boolean changedAnything() {
            return documentsInserted > 0 || documentsUpdated > 0 || vectorsRepaired > 0;
        }
    }

    /**
     * Seeds the corpus, then asserts it is usable.
     *
     * @throws IllegalStateException when no chunk is usable by the current embedder. Deliberately fatal.
     */
    public SeedResult seed() {
        String embedderId = embedder.id();
        List<PolicyDocumentLoader.LoadedPolicy> policies = loader.loadAll();

        TextChunker chunker = new TextChunker(
                properties.getKnowledgeBase().getChunkTargetTokens(),
                properties.getKnowledgeBase().getChunkMaxTokens(),
                properties.getKnowledgeBase().getChunkOverlapTokens());

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int embedded = 0;

        for (PolicyDocumentLoader.LoadedPolicy policy : policies) {
            PolicyWriter.Outcome outcome = writer.write(policy, chunker, embedderId);
            switch (outcome.action()) {
                case INSERTED -> inserted++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
            embedded += outcome.chunksEmbedded();
        }

        int repaired = repairMissingVectors(embedderId);
        SeedResult result = new SeedResult(inserted, updated, unchanged, embedded, repaired);

        long usable = chunks.countUsable(embedderId);
        if (usable == 0) {
            throw new IllegalStateException(
                    "The knowledge base has no chunks usable by embedder " + embedderId
                            + ". Every investigation would report no grounding, which produces no error "
                            + "and looks exactly like a working service.");
        }

        if (result.changedAnything()) {
            log.info("""
                            Knowledge base seeded with embedder {}: {} document(s) inserted, {} \
                            updated, {} unchanged; {} chunk(s) embedded, {} vector(s) repaired. {} \
                            chunk(s) usable.""",
                    embedderId, inserted, updated, unchanged, embedded, repaired, usable);
        } else {
            log.info("Knowledge base already current for embedder {}: {} document(s), {} chunk(s)",
                    embedderId, result.totalDocuments(), usable);
        }
        return result;
    }

    /**
     * Fills in vectors for chunks that have metadata but none.
     *
     * <p>Reachable if a previous run died between the row insert and the vector update — separate statements
     * in one transaction, but not one statement. Such a chunk is invisible to retrieval, because the search
     * joins on a non-null embedding, so it silently shrinks the corpus.
     *
     * <p>Checked on every startup rather than only after a known failure, because "the last run finished" is
     * not something a fresh process can know.
     */
    private int repairMissingVectors(String embedderId) {
        List<String> broken = vectors.findChunksMissingEmbedding(embedderId);
        if (broken.isEmpty()) {
            return 0;
        }
        log.warn("""
                        {} chunk(s) have no vector, which makes them invisible to retrieval. A previous \
                        seed was interrupted between writing the row and writing the vector. Repairing.""",
                broken.size());

        int repaired = 0;
        for (String chunkId : broken) {
            if (writer.repairVector(chunkId)) {
                repaired++;
            }
        }
        return repaired;
    }
}
