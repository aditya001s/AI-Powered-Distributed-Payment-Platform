package com.aegispay.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One retrievable passage of a policy, and the metadata for the vector that represents it.
 *
 * <p><strong>The {@code embedding} column is deliberately not mapped here.</strong> It is a pgvector
 * {@code vector(384)}, which Hibernate has no type for; mapping it would need a third-party
 * dialect contribution, and {@code ddl-auto=validate} would then be validating a type it does not
 * really understand. More importantly, the only operations that matter on a vector — nearest-neighbour
 * search with {@code <=>} — have no JPA expression anyway, so a mapped field would be a field nothing
 * used. The column is written and searched through {@code VectorStore} with native SQL, and this
 * entity carries the metadata that ordinary queries need.
 *
 * <p><strong>{@code embedder} is part of the identity of a chunk, not a detail of it.</strong> A
 * vector is comparable only to vectors from the same embedder. Swap the embedder and every stored
 * vector becomes noise — and retrieval keeps working, keeps returning rows, and the rows stop meaning
 * anything, with no error anywhere. Recording the embedder on the row and filtering on it at query
 * time turns that silent corruption into a visible one: after an embedder change retrieval returns
 * nothing until the corpus is rebuilt, which is loud and correct.
 */
@Entity
@Table(name = "policy_chunks")
public class PolicyChunk {

    /**
     * Derived from the document, the ordinal and the embedder rather than random.
     *
     * <p>That makes re-seeding idempotent: the same passage embedded by the same embedder produces
     * the same id, so a restart upserts instead of accumulating a second copy of the whole corpus
     * every time the service boots.
     */
    @Id
    @Column(name = "chunk_id", nullable = false, updatable = false, length = 80)
    private String chunkId;

    @Column(name = "document_id", nullable = false, updatable = false, length = 64)
    private String documentId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    /**
     * Approximate token count, measured at index time.
     *
     * <p>Stored so the prompt budget can be enforced by summing a column rather than re-measuring
     * every candidate chunk on every request — the assembled prompt has a hard size limit, and
     * discovering that after the fact means a truncated prompt with the policy cut off mid-sentence.
     */
    @Column(name = "content_tokens", nullable = false)
    private int contentTokens;

    @Column(name = "embedder", nullable = false, length = 64)
    private String embedder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PolicyChunk() {
        // JPA
    }

    public static PolicyChunk of(String documentId, int ordinal, String content, int contentTokens,
                                 String embedder) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("a chunk must name the document it came from");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "chunk " + ordinal + " of " + documentId + " has no content");
        }
        if (embedder == null || embedder.isBlank()) {
            // An unattributed vector cannot be compared safely, because nothing says what produced
            // it. Refused at construction rather than defaulted.
            throw new IllegalArgumentException(
                    "chunk " + ordinal + " of " + documentId + " must name the embedder that "
                            + "produced its vector, or the vector is not comparable to anything");
        }
        if (contentTokens <= 0) {
            throw new IllegalArgumentException(
                    "chunk " + ordinal + " of " + documentId + " must have a positive token count");
        }

        PolicyChunk chunk = new PolicyChunk();
        chunk.chunkId = idFor(documentId, ordinal, embedder);
        chunk.documentId = documentId;
        chunk.ordinal = ordinal;
        chunk.content = content;
        chunk.contentTokens = contentTokens;
        chunk.embedder = embedder;
        chunk.createdAt = Instant.now();
        return chunk;
    }

    /**
     * The deterministic chunk id.
     *
     * <p>Truncated to fit the column, keeping the document and ordinal readable at the front so a
     * chunk id remains diagnosable by eye. The embedder is included because two embedders may
     * legitimately hold vectors for the same passage during a migration.
     */
    public static String idFor(String documentId, int ordinal, String embedder) {
        String candidate = documentId + "#" + ordinal + "@" + embedder;
        return candidate.length() <= 80 ? candidate : candidate.substring(0, 80);
    }

    public String getChunkId() {
        return chunkId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getContent() {
        return content;
    }

    public int getContentTokens() {
        return contentTokens;
    }

    public String getEmbedder() {
        return embedder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "PolicyChunk{" + chunkId + ", " + contentTokens + " tokens}";
    }
}
