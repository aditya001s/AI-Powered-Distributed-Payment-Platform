package com.aegispay.common.outbox;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.kafka.KafkaEventCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records domain events into the outbox from inside a business transaction.
 *
 * <p>Usage is always the same shape: mutate state and record the event in one
 * {@code @Transactional} method, and never touch Kafka there.
 *
 * <pre>{@code
 * @Transactional
 * public Payment initiate(PaymentRequest request) {
 *     Payment payment = paymentRepository.save(Payment.create(request));
 *     outboxRecorder.record(
 *             KafkaTopics.PAYMENT_CREATED, "Payment", payment.getPaymentId(),
 *             EventEnvelope.of(...));
 *     return payment;   // one commit: both rows, or neither
 * }
 * }</pre>
 *
 * <p>Registered by {@link OutboxConfig} when {@code aegis.outbox.enabled=true}.
 */
public class OutboxRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecorder.class);

    private final OutboxEventRepository repository;
    private final KafkaEventCodec codec;

    public OutboxRecorder(OutboxEventRepository repository, KafkaEventCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    /**
     * Appends an event to the outbox.
     *
     * <p>{@link Propagation#MANDATORY} is deliberate and load-bearing: it makes it
     * a startup-time-visible error to call this outside a transaction. Without it,
     * a caller who forgot {@code @Transactional} would get a silently
     * auto-committed outbox row that could be published for a state change that
     * later rolled back — the exact failure mode the outbox exists to prevent.
     * Failing loudly is much better than publishing a lie.
     *
     * @param topic         destination topic
     * @param aggregateType aggregate kind, for querying and debugging
     * @param aggregateId   aggregate ID; also the Kafka partition key
     * @param envelope      the event to publish
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(String topic, String aggregateType, String aggregateId,
                              EventEnvelope<?> envelope) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Belt and braces: MANDATORY should already have thrown.
            throw new IllegalStateException(
                    "OutboxRecorder.record must be called inside a transaction; "
                            + "otherwise atomicity between the state change and the event is lost");
        }

        OutboxEvent row = OutboxEvent.pending(
                envelope.eventId(),
                aggregateType,
                aggregateId,
                envelope.eventType(),
                topic,
                codec.encode(envelope),
                envelope.correlationId() == null ? CorrelationContext.get() : envelope.correlationId());

        OutboxEvent saved = repository.save(row);
        log.debug("Recorded outbox event {} ({}) for {} {}",
                envelope.eventId(), topic, aggregateType, aggregateId);
        return saved;
    }

    /**
     * Records an event only if one with the same event ID is not already present.
     *
     * <p>For handlers that may legitimately run twice (a Kafka consumer that also
     * emits an event) and where the caller cannot rely on the unique constraint
     * because it would poison the surrounding transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordIfAbsent(String topic, String aggregateType, String aggregateId,
                                  EventEnvelope<?> envelope) {
        if (repository.existsByEventId(envelope.eventId())) {
            log.debug("Outbox event {} already recorded; skipping", envelope.eventId());
            return false;
        }
        record(topic, aggregateType, aggregateId, envelope);
        return true;
    }
}
