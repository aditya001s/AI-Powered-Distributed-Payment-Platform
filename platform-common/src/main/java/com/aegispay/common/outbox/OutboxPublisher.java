package com.aegispay.common.outbox;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drains the outbox to Kafka.
 *
 * <p>Runs on a schedule in every replica. Concurrency safety comes from
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} in
 * {@link OutboxEventRepository#claimPendingBatch}, so no leader election or
 * distributed lock is needed: each instance grabs rows nobody else holds.
 *
 * <p><strong>Ordering of operations matters.</strong> The record is published and
 * <em>acknowledged</em> before the row is marked PUBLISHED, all inside one
 * transaction. A crash between the two leaves the row PENDING, so the event is
 * republished — at-least-once. The alternative (mark first, publish after) would
 * be at-most-once and could lose a payment event, which is strictly worse:
 * duplicates are absorbed by consumer de-duplication, losses are not recoverable.
 *
 * <p>The stored payload is republished byte-for-byte. Re-serialising would risk a
 * schema change altering the meaning of an event recorded by an older build.
 *
 * <p>Registered by {@link OutboxConfig}, which is active only when
 * {@code aegis.outbox.enabled=true}.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final MeterRegistry meterRegistry;

    /** Cached gauge value; the gauge is sampled by Prometheus, not by the poller. */
    private final AtomicLong pendingGauge = new AtomicLong(0);

    public OutboxPublisher(OutboxEventRepository repository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           OutboxProperties properties,
                           MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge(MetricNames.OUTBOX_PENDING, pendingGauge, AtomicLong::doubleValue);
    }

    /**
     * One drain cycle.
     *
     * <p>{@code fixedDelayString} (not {@code fixedRate}) so cycles never overlap:
     * the next poll starts only after this one finishes, which prevents a slow
     * broker from stacking up concurrent drainers.
     */
    @Scheduled(fixedDelayString = "${aegis.outbox.poll-interval:200ms}")
    @Transactional
    public void drain() {
        List<OutboxEvent> batch = repository.claimPendingBatch(Instant.now(), properties.getBatchSize());
        if (batch.isEmpty()) {
            return;
        }

        int published = 0;
        int failed = 0;

        for (OutboxEvent event : batch) {
            // Restore the correlation ID of the request that created the event, so
            // drainer log lines join that request's trace.
            String previous = CorrelationContext.get();
            try {
                CorrelationContext.set(event.getCorrelationId());
                publish(event);
                event.markPublished();
                published++;

                meterRegistry.timer(MetricNames.OUTBOX_PUBLISH_LAG,
                                MetricNames.TAG_TOPIC, event.getTopic())
                        .record(Duration.between(event.getCreatedAt(), Instant.now()));

            } catch (RuntimeException ex) {
                // One bad event must not abort the rest of the batch.
                event.markAttemptFailed(ex.getMessage(), properties.getMaxAttempts(),
                        properties.getRetryBackoff().toMillis());
                failed++;

                if (event.getStatus() == OutboxEvent.Status.FAILED) {
                    log.error("Outbox event {} ({}) permanently FAILED after {} attempts. "
                                    + "Manual replay required.",
                            event.getEventId(), event.getTopic(), event.getAttempts(), ex);
                } else {
                    log.warn("Outbox event {} ({}) attempt {} failed, retrying at {}: {}",
                            event.getEventId(), event.getTopic(), event.getAttempts(),
                            event.getNextAttemptAt(), ex.getMessage());
                }
            } finally {
                CorrelationContext.set(previous);
            }
        }

        // Saved explicitly so both success and failure state changes are flushed
        // in this transaction; the rows are still locked until commit.
        repository.saveAll(batch);

        if (published > 0) {
            meterRegistry.counter(MetricNames.OUTBOX_PUBLISHED).increment(published);
        }
        if (failed > 0) {
            meterRegistry.counter(MetricNames.OUTBOX_FAILED).increment(failed);
        }
        log.debug("Outbox drain cycle: {} published, {} failed", published, failed);
    }

    /**
     * Publishes and blocks for the broker acknowledgement.
     *
     * <p>Blocking is correct here: the surrounding transaction must not commit
     * "PUBLISHED" until Kafka has durably accepted the record.
     */
    private void publish(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getTopic(), event.getAggregateId(), event.getPayload());

        addHeader(record, "aegis-event-id", event.getEventId());
        addHeader(record, "aegis-event-type", event.getEventType());
        addHeader(record, "aegis-correlation-id", event.getCorrelationId());
        addHeader(record, "aegis-outbox-id", event.getId().toString());

        // join() surfaces broker failures as an exception, which the caller turns
        // into a retry with backoff.
        kafkaTemplate.send(record).join();
    }

    /** Keeps the pending gauge fresh without adding a query to the hot drain path. */
    @Scheduled(fixedDelay = 5_000L)
    @Transactional(readOnly = true)
    public void refreshPendingGauge() {
        pendingGauge.set(repository.countByStatus(OutboxEvent.Status.PENDING));
    }

    /**
     * Retention sweep for successfully published rows.
     *
     * <p>FAILED rows are intentionally never deleted: they are the evidence an
     * operator needs to decide whether to replay or discard.
     */
    @Scheduled(fixedDelayString = "${aegis.outbox.cleanup-interval:15m}")
    @Transactional
    public void purgePublished() {
        Instant cutoff = Instant.now().minus(properties.getPublishedRetention());
        int deleted = repository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.debug("Purged {} published outbox rows older than {}", deleted, cutoff);
        }
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null && !value.isBlank()) {
            record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
