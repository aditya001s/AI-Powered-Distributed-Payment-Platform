package com.aegispay.ai.repository;

import com.aegispay.ai.domain.PolicyChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Metadata reads over the retrieval corpus.
 *
 * <p>The vector itself is neither written nor searched here — see {@code VectorStore}. pgvector's
 * nearest-neighbour operator has no JPA expression, and a {@code vector(384)} column has no Hibernate
 * type, so trying to own both from one place would mean either a third-party dialect contribution or a
 * mapped field nothing could use.
 */
public interface PolicyChunkRepository extends JpaRepository<PolicyChunk, String> {

    Optional<PolicyChunk> findByChunkId(String chunkId);

    List<PolicyChunk> findByDocumentIdOrderByOrdinalAsc(String documentId);

    long countByEmbedder(String embedder);

    /**
     * How many chunks exist for the embedder currently in use.
     *
     * <p>The readiness question. A corpus embedded by a previous embedder is invisible to retrieval —
     * correctly, because the vectors are not comparable — so a service that came up with a full table
     * and zero usable chunks would answer every investigation ungrounded and report no error. This is
     * what makes that state detectable at startup rather than in a week's worth of empty citations.
     */
    @Query("SELECT COUNT(c) FROM PolicyChunk c WHERE c.embedder = :embedder")
    long countUsable(@Param("embedder") String embedder);

    /**
     * Removes chunks a re-seed has orphaned.
     *
     * <p>Needed because an edited policy may chunk into fewer passages than before. Without this, the
     * tail of the previous version would survive as retrievable text belonging to no current
     * paragraph — cite-able, plausible, and no longer part of the policy it claims to be from.
     */
    @Modifying
    @Query("DELETE FROM PolicyChunk c WHERE c.documentId = :documentId AND c.embedder = :embedder "
            + "AND c.ordinal >= :fromOrdinal")
    int deleteTrailingChunks(@Param("documentId") String documentId,
                             @Param("embedder") String embedder,
                             @Param("fromOrdinal") int fromOrdinal);
}
