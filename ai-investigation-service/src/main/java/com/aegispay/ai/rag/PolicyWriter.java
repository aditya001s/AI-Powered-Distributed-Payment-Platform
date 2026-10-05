package com.aegispay.ai.rag;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.PolicyChunk;
import com.aegispay.ai.domain.PolicyDocument;
import com.aegispay.ai.repository.PolicyChunkRepository;
import com.aegispay.ai.repository.PolicyDocumentRepository;
import com.aegispay.ai.repository.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Writes one policy document and its embedded chunks, transactionally.
 *
 * <h2>Why this is a bean separate from {@link KnowledgeBaseSeeder}</h2>
 *
 * <p>Spring applies transaction advice through a proxy, so a {@code @Transactional} method invoked as
 * {@code this.method()} from within the same class runs <strong>untransacted</strong> and says nothing
 * about it. The seeder loops over documents and computes an embedding for each — which with a hosted
 * embedder is an HTTP call — so it must not itself be transactional, which means the per-document write has
 * to live behind a real bean boundary for its annotation to take effect at all.
 *
 * <p>This is the same trap the auth service hit in Phase 2, where a private self-called method meant
 * refresh-token reuse detection revoked nothing. Here the symptom would be a corpus that appeared to seed
 * and left orphaned rows behind on any failure.
 *
 * <h2>Why one transaction per document rather than one for the corpus</h2>
 *
 * <p>A single transaction spanning the whole corpus would hold a database connection open across every
 * embedding computation. It would also make a failure on the last document discard the work done on all the
 * others, so a transient fault would mean starting again. Per-document commits mean an interrupted seed
 * leaves the corpus partially built and the next startup completes it.
 */
@Service
public class PolicyWriter {

    private static final Logger log = LoggerFactory.getLogger(PolicyWriter.class);

    private final PolicyDocumentRepository documents;
    private final PolicyChunkRepository chunks;
    private final VectorStore vectors;
    private final Embedder embedder;
    private final AiProperties properties;

    public PolicyWriter(PolicyDocumentRepository documents,
                        PolicyChunkRepository chunks,
                        VectorStore vectors,
                        Embedder embedder,
                        AiProperties properties) {
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
        this.embedder = embedder;
        this.properties = properties;
    }

    /** What writing one document did. */
    public enum Action {
        INSERTED, UPDATED, UNCHANGED
    }

    public record Outcome(Action action, int chunksEmbedded) {
    }

    /**
     * Writes one policy, inserting, upgrading or leaving it alone.
     *
     * <p>Three states are distinguished, and conflating them is how a corpus quietly degrades:
     *
     * <ul>
     *   <li><strong>Present and unchanged</strong> — do nothing. Re-embedding an unchanged corpus on every
     *       restart is wasted work and, with a hosted embedder, wasted money.</li>
     *   <li><strong>Present, version bumped</strong> — re-chunk and re-embed.</li>
     *   <li><strong>Present, but with no chunks for the current embedder</strong> — re-chunk without
     *       touching the document. This is the state after an embedder change, and it is the one that
     *       would otherwise be silently skipped: the document is there, so a naive "exists?" check
     *       succeeds, and retrieval finds nothing.</li>
     * </ul>
     */
    @Transactional
    public Outcome write(PolicyDocumentLoader.LoadedPolicy policy, TextChunker chunker,
                         String embedderId) {

        Optional<PolicyDocument> existing = documents.findByDocumentId(policy.documentId());

        if (existing.isPresent()) {
            PolicyDocument document = existing.get();
            boolean bundledIsNewer = policy.version() > document.getVersion();
            boolean missingForEmbedder = chunks
                    .findByDocumentIdOrderByOrdinalAsc(policy.documentId())
                    .stream()
                    .noneMatch(chunk -> embedderId.equals(chunk.getEmbedder()));

            if (!missingForEmbedder && (!bundledIsNewer
                    || !properties.getKnowledgeBase().isUpgradeExistingDocuments())) {
                // Compared by version rather than by body text. A body comparison would re-chunk and
                // re-embed the whole corpus on every whitespace change, and the version is what a
                // citation records anyway.
                return new Outcome(Action.UNCHANGED, 0);
            }

            if (bundledIsNewer) {
                document.revise(policy.title(), policy.category(), policy.relatedRuleIds(),
                        policy.body(), policy.version(), policy.source());
                documents.save(document);
                log.info("Policy {} upgraded to version {}", policy.documentId(), policy.version());
            }
            int count = rebuildChunks(document, chunker, embedderId);
            return new Outcome(bundledIsNewer ? Action.UPDATED : Action.UNCHANGED, count);
        }

        PolicyDocument document = documents.save(PolicyDocument.of(
                policy.documentId(), policy.title(), policy.category(), policy.relatedRuleIds(),
                policy.body(), policy.version(), policy.source()));

        return new Outcome(Action.INSERTED, rebuildChunks(document, chunker, embedderId));
    }

    /**
     * Chunks, embeds and stores a document's passages.
     *
     * <p>Upserts by deterministic chunk id, then deletes any chunk beyond the new count. That deletion is
     * the part worth noticing: an edited policy may produce fewer passages than before, and without it the
     * previous version's tail would remain in the corpus as retrievable text belonging to no current
     * paragraph — cite-able, plausible, and no longer part of the policy it claims to be from.
     */
    private int rebuildChunks(PolicyDocument document, TextChunker chunker, String embedderId) {
        List<TextChunker.Chunk> passages = chunker.chunk(document.getBody());
        if (passages.isEmpty()) {
            throw new IllegalStateException(
                    "Policy " + document.getDocumentId() + " produced no chunks, so it could never be "
                            + "retrieved despite being present in the corpus");
        }

        for (TextChunker.Chunk passage : passages) {
            String chunkId = PolicyChunk.idFor(document.getDocumentId(), passage.ordinal(), embedderId);

            // saveAndFlush, because the native vector update below cannot see an unflushed JPA insert:
            // they are separate statements against the same transaction, and Hibernate has no reason to
            // flush before an unrelated JdbcTemplate call.
            chunks.saveAndFlush(chunks.findByChunkId(chunkId).orElseGet(() ->
                    PolicyChunk.of(document.getDocumentId(), passage.ordinal(), passage.content(),
                            passage.approximateTokens(), embedderId)));

            vectors.storeEmbedding(chunkId, embedder.embed(embeddingTextFor(document, passage)));
        }

        int removed = chunks.deleteTrailingChunks(
                document.getDocumentId(), embedderId, passages.size());
        if (removed > 0) {
            log.info("Removed {} stale chunk(s) from policy {} after re-chunking",
                    removed, document.getDocumentId());
        }
        return passages.size();
    }

    /**
     * Repairs a chunk whose row exists but whose vector does not.
     *
     * <p>Its own transaction, so one unrepairable chunk does not abort the repair of the others.
     */
    @Transactional
    public boolean repairVector(String chunkId) {
        Optional<PolicyChunk> chunk = chunks.findByChunkId(chunkId);
        Optional<PolicyDocument> document = chunk.flatMap(
                value -> documents.findByDocumentId(value.getDocumentId()));

        if (chunk.isEmpty() || document.isEmpty()) {
            log.error("Chunk {} has no vector and no resolvable document; leaving it for an operator "
                    + "rather than guessing at its content", chunkId);
            return false;
        }
        TextChunker.Chunk passage = new TextChunker.Chunk(
                chunk.get().getOrdinal(), chunk.get().getContent(), chunk.get().getContentTokens());

        vectors.storeEmbedding(chunkId, embedder.embed(embeddingTextFor(document.get(), passage)));
        return true;
    }

    /**
     * The text actually embedded for a passage.
     *
     * <p>Not just the passage. The document title, its category and the rule ids it explains are prepended,
     * so a chunk from the middle of a policy still carries what the policy is <em>about</em>.
     *
     * <p>This matters more than it looks. With a lexical embedder, a paragraph three chunks into
     * POLICY-BUST-OUT may never repeat the phrase "bust out" — the author already said it in the heading —
     * so a query about bust-out would score that paragraph near zero despite it being exactly the relevant
     * text. Prepending the document's own vocabulary to every chunk is what keeps a mid-document passage
     * findable, and including the rule ids gives the vector a second route to the association the declared
     * mapping covers exactly.
     *
     * <p>The stored {@code content} is the passage alone, so a citation shows what the author wrote rather
     * than this synthetic header.
     */
    static String embeddingTextFor(PolicyDocument document, TextChunker.Chunk passage) {
        StringBuilder builder = new StringBuilder();
        builder.append(document.getTitle()).append('\n');
        builder.append(document.getCategory()).append('\n');
        if (document.getRelatedRuleIds() != null && !document.getRelatedRuleIds().isBlank()) {
            builder.append(document.getRelatedRuleIds().replace(",", " ")).append('\n');
        }
        builder.append(passage.content());
        return builder.toString();
    }
}
