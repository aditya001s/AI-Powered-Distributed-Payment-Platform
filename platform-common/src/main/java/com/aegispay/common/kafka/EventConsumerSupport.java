package com.aegispay.common.kafka;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The shared entry path for every {@code @KafkaListener} in the platform.
 *
 * <p>A listener method reduces to one call, and in exchange gets six behaviours
 * that are wrong-by-default if each listener implements them itself:
 *
 * <ol>
 *   <li>The correlation ID is restored from the record header, so log lines
 *       produced while handling an event join up with the HTTP request that
 *       originally caused it — across a thread and a process boundary.</li>
 *   <li>The payload is decoded into the type the handler declares, and a
 *       malformed record fails as a non-retryable error that goes straight to the
 *       DLQ instead of looping.</li>
 *   <li>Already-successful replays are suppressed via {@link ProcessedEventRegistry}.</li>
 *   <li>Handler latency and outcome are recorded as metrics.</li>
 *   <li>The successful marker is written only after the handler returns, so a
 *       process crash cannot suppress work whose database transaction never committed.</li>
 *   <li>MDC is always cleared, because container threads are pooled and reused.</li>
 * </ol>
 *
 * <p>Exceptions are rethrown, never swallowed: the container's error handler owns
 * retry and dead-lettering, and swallowing here would commit the offset for a
 * record that was never successfully processed.
 */
@Component
@ConditionalOnClass(name = "org.apache.kafka.clients.consumer.ConsumerRecord")
public class EventConsumerSupport {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerSupport.class);

    private final KafkaEventCodec codec;
    private final ProcessedEventRegistry processedEvents;
    private final MeterRegistry meterRegistry;

    public EventConsumerSupport(KafkaEventCodec codec,
                                ProcessedEventRegistry processedEvents,
                                MeterRegistry meterRegistry) {
        this.codec = codec;
        this.processedEvents = processedEvents;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Decodes and dispatches one record.
     *
     * @param consumerName stable name for this handler, used to scope
     *                     de-duplication; two different services must be able to
     *                     process the same event independently
     * @param record       the raw Kafka record
     * @param payloadType  the payload type this handler expects
     * @param handler      the business logic
     */
    public <T> void handle(String consumerName,
                           ConsumerRecord<String, String> record,
                           Class<T> payloadType,
                           Consumer<EventEnvelope<T>> handler) {

        String correlationId = header(record, EventPublisher.HEADER_CORRELATION_ID);
        String eventId = header(record, EventPublisher.HEADER_EVENT_ID);

        String previousCorrelation = CorrelationContext.get();
        try {
            CorrelationContext.set(correlationId);
            CorrelationContext.getOrCreate();

            EventEnvelope<T> envelope = codec.decode(record.value(), payloadType);
            String effectiveEventId = eventId != null ? eventId : envelope.eventId();

            if (wasProcessed(consumerName, effectiveEventId)) {
                meterRegistry.counter(MetricNames.EVENTS_DUPLICATE_SUPPRESSED,
                        MetricNames.TAG_TOPIC, record.topic()).increment();
                log.debug("Skipping duplicate event {} on {} for consumer {}",
                        effectiveEventId, record.topic(), consumerName);
                return;
            }

            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                handler.accept(envelope);
                // The handler's transactional proxy has returned, so its database commit is
                // complete. Only now may a cache marker suppress a later Kafka redelivery.
                markProcessedBestEffort(consumerName, effectiveEventId);

                sample.stop(meterRegistry.timer(MetricNames.EVENTS_HANDLER_LATENCY,
                        MetricNames.TAG_TOPIC, record.topic(),
                        MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_SUCCESS));
                meterRegistry.counter(MetricNames.EVENTS_CONSUMED,
                        MetricNames.TAG_TOPIC, record.topic(),
                        MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_SUCCESS).increment();

            } catch (RuntimeException ex) {
                sample.stop(meterRegistry.timer(MetricNames.EVENTS_HANDLER_LATENCY,
                        MetricNames.TAG_TOPIC, record.topic(),
                        MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_FAILURE));
                meterRegistry.counter(MetricNames.EVENTS_CONSUMED,
                        MetricNames.TAG_TOPIC, record.topic(),
                        MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_FAILURE).increment();

                log.error("Handler {} failed for event {} on {}-{}@{}: {}",
                        consumerName, effectiveEventId, record.topic(), record.partition(),
                        record.offset(), ex.getMessage(), ex);

                // Rethrow: the container's DefaultErrorHandler owns retry + DLQ.
                throw ex;
            }

        } finally {
            CorrelationContext.set(previousCorrelation);
            if (previousCorrelation == null) {
                CorrelationContext.clear();
            }
        }
    }

    private boolean wasProcessed(String consumerName, String eventId) {
        try {
            return processedEvents.isProcessed(consumerName, eventId);
        } catch (RuntimeException cacheFailure) {
            // De-duplication is an optimisation. The owning database's natural key
            // is the correctness boundary, so Redis must never block event handling.
            log.warn("Processed-event cache read failed for consumer {} event {}; "
                    + "continuing with durable handler idempotency: {}",
                    consumerName, eventId, cacheFailure.getMessage());
            return false;
        }
    }

    private void markProcessedBestEffort(String consumerName, String eventId) {
        try {
            processedEvents.markProcessed(consumerName, eventId);
        } catch (RuntimeException cacheFailure) {
            // Business state has already committed. Rethrowing here would retry an
            // already-applied event and could eventually dead-letter a successful record.
            log.warn("Processed-event cache write failed for consumer {} event {}; "
                    + "the durable handler result remains authoritative: {}",
                    consumerName, eventId, cacheFailure.getMessage());
        }
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
