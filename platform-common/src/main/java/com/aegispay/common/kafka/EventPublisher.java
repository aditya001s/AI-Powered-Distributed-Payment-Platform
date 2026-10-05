package com.aegispay.common.kafka;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * The single way events leave a service.
 *
 * <p>Centralising publication guarantees three things happen every time and
 * cannot be forgotten at a call site:
 *
 * <ol>
 *   <li>The partition key is the aggregate ID, which is what preserves per-payment
 *       ordering.</li>
 *   <li>Correlation and event metadata are copied into Kafka <em>headers</em>, so
 *       an operator can filter the log without parsing every value, and consumers
 *       can restore the correlation ID before doing any work.</li>
 *   <li>Success and failure are counted, so publish failures are visible on a
 *       dashboard rather than only in logs.</li>
 * </ol>
 *
 * <p>Note on durability: for events that must not be lost if the process dies,
 * callers use the transactional outbox
 * ({@link com.aegispay.common.outbox.OutboxRecorder}) rather than calling this
 * directly. A direct publish is correct for derived, replayable notifications;
 * it is not correct for "the database says this happened".
 *
 * <p>Registered as a bean by {@link KafkaConfig}, so it only exists in services
 * that actually have a broker configured.
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    public static final String HEADER_EVENT_ID = "aegis-event-id";
    public static final String HEADER_EVENT_TYPE = "aegis-event-type";
    public static final String HEADER_SCHEMA_VERSION = "aegis-schema-version";
    public static final String HEADER_CORRELATION_ID = "aegis-correlation-id";
    public static final String HEADER_CAUSATION_ID = "aegis-causation-id";
    public static final String HEADER_SOURCE = "aegis-source";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaEventCodec codec;
    private final MeterRegistry meterRegistry;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                          KafkaEventCodec codec,
                          MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Publishes asynchronously. The returned future completes when the broker
     * acknowledges the record.
     */
    public CompletableFuture<SendResult<String, String>> publish(String topic, EventEnvelope<?> envelope) {
        ProducerRecord<String, String> record = toRecord(topic, envelope);

        return kafkaTemplate.send(record).whenComplete((result, failure) -> {
            if (failure != null) {
                meterRegistry.counter(MetricNames.EVENTS_PUBLISH_FAILED, "topic", topic).increment();
                log.error("Failed to publish {} to topic {} [eventId={}]",
                        envelope.eventType(), topic, envelope.eventId(), failure);
            } else {
                meterRegistry.counter(MetricNames.EVENTS_PUBLISHED, "topic", topic).increment();
                if (log.isDebugEnabled()) {
                    log.debug("Published {} to {}-{} offset {} [eventId={}]",
                            envelope.eventType(), topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(),
                            envelope.eventId());
                }
            }
        });
    }

    /**
     * Publishes and blocks until acknowledged.
     *
     * <p>Used by the outbox drainer, which must know the record is durable in
     * Kafka before it marks the outbox row as published. Anywhere else, prefer the
     * async form so a slow broker does not consume a request thread.
     */
    public void publishSync(String topic, EventEnvelope<?> envelope) {
        try {
            publish(topic, envelope).join();
        } catch (RuntimeException ex) {
            throw new EventPublicationException(
                    "Failed to publish " + envelope.eventType() + " to " + topic, ex);
        }
    }

    private ProducerRecord<String, String> toRecord(String topic, EventEnvelope<?> envelope) {
        // Partition key = aggregate ID. This is the ordering guarantee.
        String key = envelope.aggregateId();
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, key, codec.encode(envelope));

        addHeader(record, HEADER_EVENT_ID, envelope.eventId());
        addHeader(record, HEADER_EVENT_TYPE, envelope.eventType());
        addHeader(record, HEADER_SCHEMA_VERSION, String.valueOf(envelope.schemaVersion()));
        addHeader(record, HEADER_CORRELATION_ID,
                envelope.correlationId() == null ? CorrelationContext.get() : envelope.correlationId());
        addHeader(record, HEADER_CAUSATION_ID, envelope.causationId());
        addHeader(record, HEADER_SOURCE, envelope.source());
        return record;
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null && !value.isBlank()) {
            record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Thrown when a synchronous publish cannot be completed. */
    public static class EventPublicationException extends RuntimeException {
        public EventPublicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
