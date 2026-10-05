package com.aegispay.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The authoritative record that an idempotent operation was attempted.
 *
 * <p><strong>Why the database and not just Redis.</strong> Redis is checked first
 * because it answers a replay in microseconds and keeps duplicate bursts off the
 * database. But Redis can be empty, flushed, failed over or simply unavailable, and
 * an idempotency guarantee that evaporates under those conditions is not a guarantee.
 * The composite primary key below is the real mechanism: a second insert for the same
 * {@code (scope, key)} cannot succeed, so a retried payment cannot be executed twice
 * even if the cache knows nothing about it.
 *
 * <p><strong>Why {@code requestHash} is stored.</strong> A key alone is not enough.
 * If a client sends key {@code K} for "transfer ₹2,000 to Bob" and later reuses
 * {@code K} for "transfer ₹50,000 to Carol", both possible shortcuts are wrong:
 * returning the first response silently drops the second transfer, and executing it
 * defeats the key's purpose. The only correct answer is to reject the second request
 * as a client bug, and that requires knowing the payloads differ.
 *
 * <p><strong>Why the response is stored.</strong> A replay must return the original
 * outcome, not merely "already done". A client that retried because it never saw the
 * first response needs the payment ID back.
 *
 * <p>Lives in the shared kernel because payment-service and wallet-service need
 * identical semantics; each keeps its own copy of the table in its own database.
 */
@Entity
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_idempotency_expiry", columnList = "expires_at")
})
@IdClass(IdempotencyRecord.Key.class)
public class IdempotencyRecord {

    /** How long a completed record is replayable before the sweeper removes it. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    public enum Status {
        /**
         * Claimed but not finished. A concurrent request seeing this must be told to
         * retry, never allowed to proceed — that is what prevents two simultaneous
         * identical payments from both executing.
         */
        IN_PROGRESS,
        /** Finished successfully. {@code responseBody} is replayed verbatim. */
        COMPLETED,
        /**
         * Finished in a failure the client may legitimately retry. Retained for
         * diagnostics; the key is released so the same key can be reused.
         */
        FAILED
    }

    /**
     * Composite key: the operation scope plus the client-supplied key.
     *
     * <p>Scoping matters. A client that generates one key per user action would
     * otherwise collide across operations, so the same key used for a deposit and a
     * payment would make the second look like a replay of the first.
     */
    public static class Key implements Serializable {

        private String scope;
        private String idempotencyKey;

        public Key() {
        }

        public Key(String scope, String idempotencyKey) {
            this.scope = scope;
            this.idempotencyKey = idempotencyKey;
        }

        public String getScope() {
            return scope;
        }

        public String getIdempotencyKey() {
            return idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(scope, key.scope)
                    && Objects.equals(idempotencyKey, key.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scope, idempotencyKey);
        }
    }

    @Id
    @Column(name = "scope", nullable = false, updatable = false, length = 48)
    private String scope;

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.IN_PROGRESS;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** The resource the operation produced, for correlation and debugging. */
    @Column(name = "resource_id", length = 48)
    private String resourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
        // JPA
    }

    public static IdempotencyRecord begin(String scope, String idempotencyKey,
                                          String requestHash, Duration retention) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.scope = scope;
        record.idempotencyKey = idempotencyKey;
        record.requestHash = requestHash;
        record.status = Status.IN_PROGRESS;
        record.createdAt = Instant.now();
        record.expiresAt = record.createdAt.plus(retention == null ? DEFAULT_RETENTION : retention);
        return record;
    }

    public void complete(String responseBody, int responseStatus, String resourceId) {
        this.status = Status.COMPLETED;
        this.responseBody = responseBody;
        this.responseStatus = responseStatus;
        this.resourceId = resourceId;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public boolean isInProgress() {
        return status == Status.IN_PROGRESS;
    }

    /**
     * True when the presented request matches the one this key was created for.
     *
     * <p>Constant-time comparison. Both operands are server-side digests so a timing
     * side channel is not a realistic threat here, but comparing digests with
     * {@code equals} is not a habit worth forming.
     */
    public boolean matchesRequest(String candidateHash) {
        return RequestFingerprint.matches(requestHash, candidateHash);
    }

    /**
     * True when an {@code IN_PROGRESS} claim is old enough to be considered abandoned.
     *
     * <p>Without this, a process that died mid-operation would lock its key out
     * permanently and the client could never retry.
     */
    public boolean isStale(Duration staleAfter) {
        return status == Status.IN_PROGRESS && createdAt.isBefore(Instant.now().minus(staleAfter));
    }

    public String getScope() {
        return scope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Status getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResourceId() {
        return resourceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
