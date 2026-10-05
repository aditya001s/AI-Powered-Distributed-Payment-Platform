package com.aegispay.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * How current one consumed topic is.
 *
 * <h2>Why a read model must publish its own staleness</h2>
 *
 * <p>A projection that stops updating does not go blank. It keeps serving the last numbers it had,
 * confidently, with no indication that they are old. An operator reading "0 payments held for review"
 * cannot tell whether the queue is clear or the consumer died three hours ago — and those two call for
 * opposite responses.
 *
 * <p>So every API response carries a freshness figure derived from these rows, and it is <em>per
 * topic</em> rather than one global number. A single figure would only look stale once every stream had
 * stopped; per topic, a broken {@code fraud-checked} consumer is visible while payments still flow.
 *
 * <p>Deliberately holds no event count. A count is a counter, and a redelivered event would inflate it
 * — the exact mistake the whole projection is shaped to avoid. Timestamps are idempotent: applying the
 * same event twice writes the same value.
 */
@Entity
@Table(name = "projection_stream_state")
public class StreamState {

    @Id
    @Column(name = "topic", nullable = false, updatable = false, length = 96)
    private String topic;

    /**
     * {@code occurredAt} of the newest event applied from this topic.
     *
     * <p>{@code now()} minus this is the consumer lag, which is what an operator actually wants to
     * know. Uses the event's own timestamp rather than the moment it was processed, so the figure
     * measures how far behind the platform this projection is, not how long ago it last did some work.
     */
    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    /**
     * When this service applied it.
     *
     * <p>Distinct from {@link #lastEventAt} on purpose. During a replay the two diverge by hours, and
     * conflating them would make a backfill read as an outage.
     */
    @Column(name = "last_applied_at", nullable = false)
    private Instant lastAppliedAt = Instant.now();

    protected StreamState() {
        // JPA
    }

    public static StreamState of(String topic, Instant eventAt) {
        StreamState state = new StreamState();
        state.topic = topic;
        state.lastEventAt = eventAt == null ? Instant.now() : eventAt;
        state.lastAppliedAt = Instant.now();
        return state;
    }

    /**
     * Advances the high-water mark.
     *
     * <p>{@code lastEventAt} only ever moves forward: a redelivered older event must not make the
     * projection look staler than it is. {@code lastAppliedAt} always moves, because the service did
     * genuinely do work — that is what distinguishes "idle" from "stuck".
     */
    public void observe(Instant eventAt) {
        if (eventAt != null && (lastEventAt == null || eventAt.isAfter(lastEventAt))) {
            this.lastEventAt = eventAt;
        }
        this.lastAppliedAt = Instant.now();
    }

    /** How far behind the platform this stream is. */
    public Duration lag() {
        return Duration.between(lastEventAt, Instant.now());
    }

    public String getTopic() {
        return topic;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public Instant getLastAppliedAt() {
        return lastAppliedAt;
    }

    @Override
    public String toString() {
        return "StreamState{" + topic + ", lastEvent=" + lastEventAt + '}';
    }
}
