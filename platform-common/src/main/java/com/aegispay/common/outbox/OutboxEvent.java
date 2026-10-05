package com.aegispay.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One pending domain event, stored in the <em>same database and the same
 * transaction</em> as the state change that produced it.
 *
 * <p><strong>The problem this solves.</strong> A service that writes to its
 * database and then publishes to Kafka has no way to make the two atomic:
 *
 * <pre>
 *   tx.commit();                  // (1) payment row is durable
 *   kafka.send(paymentCreated);   // (2) process dies here
 * </pre>
 *
 * The payment exists but no saga ever starts, and it hangs in CREATED forever.
 * Swapping the order is worse: an event is published for a payment that was
 * rolled back, so downstream services act on something that never happened.
 * A distributed (XA) transaction across Postgres and Kafka would be correct but
 * is slow, operationally fragile, and not supported by Kafka in practice.
 *
 * <p><strong>The outbox pattern.</strong> Insert the event into this table inside
 * the business transaction. Either both the payment and its event commit, or
 * neither does — one database, one transaction, no distributed commit. A separate
 * poller then drains the table to Kafka and marks rows published. If the poller
 * crashes mid-flight the row stays {@code PENDING} and is retried, so publication
 * is at-least-once. Combined with the producer's idempotence setting and
 * consumer-side de-duplication on {@code eventId}, the end-to-end effect is
 * effectively-once.
 *
 * <p>The cost is latency: an event appears in Kafka one poll interval (200ms
 * here) after the commit, instead of immediately. For payment orchestration that
 * is an easy trade against losing events.
 */
@Entity
@Table(name = "outbox_events", indexes = {
        // The drainer's claim query. Partial-index equivalent for the hot path.
        @Index(name = "idx_outbox_dispatch", columnList = "status, next_attempt_at, created_at"),
        // Publisher-side idempotency: prevents the same logical event twice.
        @Index(name = "idx_outbox_event_id", columnList = "event_id", unique = true),
        @Index(name = "idx_outbox_aggregate", columnList = "aggregate_type, aggregate_id"),
        // Supports the retention sweep.
        @Index(name = "idx_outbox_published_at", columnList = "published_at")
})
public class OutboxEvent {

    /** Upper bound on retry backoff, so a long outage cannot defer a row for hours. */
    private static final long MAX_BACKOFF_MILLIS = 5L * 60 * 1000;

    /** Lifecycle of an outbox row. */
    public enum Status {
        /** Awaiting publication (or awaiting its next retry). */
        PENDING,
        /** Acknowledged by Kafka. Retained briefly, then swept. */
        PUBLISHED,
        /** Retries exhausted. Requires an operator; alerted on. */
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The envelope's event ID. Unique, so a retry cannot enqueue a second copy. */
    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    /** Doubles as the Kafka partition key, preserving per-aggregate ordering. */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 96)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 96)
    private String topic;

    /** The fully serialised {@code EventEnvelope}, published verbatim. */
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /** Next eligible publication time. Pushed out by exponential backoff on failure. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Truncated failure message from the most recent attempt. */
    @Column(name = "last_error", length = 1024)
    private String lastError;

    /**
     * Guards against two drainer instances double-publishing if {@code FOR UPDATE
     * SKIP LOCKED} is ever bypassed (for example by a future non-locking read).
     * Defence in depth on the money path.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OutboxEvent() {
        // JPA
    }

    private OutboxEvent(String eventId, String aggregateType, String aggregateId,
                        String eventType, String topic, String payload, String correlationId) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
        this.nextAttemptAt = this.createdAt;
    }

    public static OutboxEvent pending(String eventId, String aggregateType, String aggregateId,
                                      String eventType, String topic, String payload,
                                      String correlationId) {
        return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, topic, payload, correlationId);
    }

    /** Marks the row published after Kafka acknowledged the record. */
    public void markPublished() {
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
        this.attempts = this.attempts + 1;
        this.lastError = null;
    }

    /**
     * Records a failed attempt and schedules the next one with exponential
     * backoff, or gives up once {@code maxAttempts} is reached.
     */
    public void markAttemptFailed(String error, int maxAttempts, long baseBackoffMillis) {
        this.attempts = this.attempts + 1;
        this.lastError = truncate(error);
        if (this.attempts >= maxAttempts) {
            this.status = Status.FAILED;
        } else {
            // 2^attempts * base, capped so a long outage does not push a row's
            // next attempt hours into the future.
            long delay = Math.min(baseBackoffMillis * (1L << Math.min(this.attempts, 10)),
                    MAX_BACKOFF_MILLIS);
            this.nextAttemptAt = Instant.now().plusMillis(delay);
        }
    }

    /** Returns a FAILED row to PENDING so an operator can replay it. */
    public void resetForReplay() {
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
        this.lastError = null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1024 ? value : value.substring(0, 1021) + "...";
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public long getVersion() {
        return version;
    }
}
