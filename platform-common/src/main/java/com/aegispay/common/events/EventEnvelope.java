package com.aegispay.common.events;

import com.aegispay.common.correlation.CorrelationContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * The uniform wrapper around every event on every topic.
 *
 * <p>Why an envelope rather than bare payloads:
 *
 * <ul>
 *   <li>{@code eventId} gives consumers a natural de-duplication key, which is
 *       what turns Kafka's at-least-once delivery into effectively-once
 *       processing.</li>
 *   <li>{@code correlationId} and {@code causationId} reconstruct causality: the
 *       correlation ID is constant for the whole business operation, while the
 *       causation ID points at the specific event that triggered this one.</li>
 *   <li>{@code schemaVersion} allows additive evolution. Consumers tolerate
 *       unknown fields ({@link JsonIgnoreProperties}) so a producer can ship a
 *       new field before consumers are redeployed.</li>
 *   <li>{@code source} names the emitting service, which makes an unexpected
 *       publisher immediately obvious during incidents.</li>
 * </ul>
 *
 * @param <T> the payload type carried on this topic
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String source,
        T payload
) {

    /** Current schema version emitted by this build. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Builds an envelope for a newly produced event, inheriting the ambient
     * correlation ID so the causal chain is not broken.
     *
     * @param eventType     the topic name, which doubles as the event type
     * @param aggregateType the kind of aggregate ("Payment", "Wallet", ...)
     * @param aggregateId   the aggregate's ID; also used as the Kafka partition key
     * @param source        the emitting service name
     * @param payload       the event body
     */
    public static <T> EventEnvelope<T> of(String eventType,
                                          String aggregateType,
                                          String aggregateId,
                                          String source,
                                          T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_SCHEMA_VERSION,
                aggregateType,
                aggregateId,
                Instant.now(),
                CorrelationContext.getOrCreate(),
                null,
                source,
                payload);
    }

    /**
     * Builds an envelope caused by {@code cause}, copying its correlation ID and
     * recording its event ID as the causation ID.
     */
    public static <T> EventEnvelope<T> causedBy(EventEnvelope<?> cause,
                                                String eventType,
                                                String aggregateType,
                                                String aggregateId,
                                                String source,
                                                T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_SCHEMA_VERSION,
                aggregateType,
                aggregateId,
                Instant.now(),
                cause.correlationId() == null ? CorrelationContext.getOrCreate() : cause.correlationId(),
                cause.eventId(),
                source,
                payload);
    }

    /** Replaces the payload while preserving all envelope metadata. */
    public <R> EventEnvelope<R> withPayload(R newPayload) {
        return new EventEnvelope<>(eventId, eventType, schemaVersion, aggregateType, aggregateId,
                occurredAt, correlationId, causationId, source, newPayload);
    }
}
