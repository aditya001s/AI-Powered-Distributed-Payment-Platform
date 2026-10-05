package com.aegispay.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {

    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(String scope, String idempotencyKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_keys
                (scope, idempotency_key, request_hash, status, created_at, expires_at)
            VALUES (:scope, :idempotencyKey, :requestHash, 'IN_PROGRESS', :createdAt, :expiresAt)
            ON CONFLICT (scope, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("scope") String scope,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("requestHash") String requestHash,
                       @Param("createdAt") Instant createdAt,
                       @Param("expiresAt") Instant expiresAt);

    /**
     * Retention sweep for expired records.
     *
     * <p>Deletes {@code COMPLETED} and {@code FAILED} rows past their expiry.
     * {@code IN_PROGRESS} rows are deliberately excluded: a stale claim is released by
     * the explicit takeover path in {@link IdempotencyService}, which checks the
     * request hash before reusing the key. Bulk-deleting them here could release a key
     * belonging to an operation that is genuinely still running.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM IdempotencyRecord r
            WHERE r.expiresAt < :now
              AND r.status <> 'IN_PROGRESS'
            """)
    int deleteExpired(@Param("now") Instant now);

    long countByStatus(IdempotencyRecord.Status status);
}
