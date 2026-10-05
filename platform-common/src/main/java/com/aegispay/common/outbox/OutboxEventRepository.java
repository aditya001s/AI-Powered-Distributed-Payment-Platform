package com.aegispay.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of due rows for this drainer instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is the important part. Every replica of a
     * service runs its own drainer, and all of them poll the same table. Without
     * {@code SKIP LOCKED} they would either publish the same rows twice or serialise
     * behind each other's locks. With it, each instance takes rows nobody else
     * holds and moves on immediately, so the drainer scales horizontally with no
     * coordination, leader election or extra infrastructure.
     *
     * <p>Ordered by {@code created_at} so events are published in roughly the order
     * they occurred. Strict per-aggregate ordering is preserved by Kafka's
     * partition key, not by this query.
     *
     * <p>Must run inside a transaction for the row locks to be held.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /** Feeds the {@code aegis.outbox.pending} gauge; sustained growth means the drainer is stuck. */
    long countByStatus(OutboxEvent.Status status);

    /** Rows needing operator attention, newest first. */
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtDesc(OutboxEvent.Status status);

    /**
     * Retention sweep. Published rows are kept briefly as an audit trail of what
     * was emitted, then deleted so the table stays small and the claim query stays
     * fast. FAILED rows are never swept: they are evidence.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent o WHERE o.status = 'PUBLISHED' AND o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    boolean existsByEventId(String eventId);
}
