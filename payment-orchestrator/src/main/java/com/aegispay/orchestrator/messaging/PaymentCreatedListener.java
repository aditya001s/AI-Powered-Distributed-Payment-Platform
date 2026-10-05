package com.aegispay.orchestrator.messaging;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import com.aegispay.orchestrator.service.SagaOrchestrator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Starts a saga for every payment payment-service creates.
 *
 * <p>This is the single entry point into orchestration. Kafka rather than a synchronous call, for two
 * reasons that both matter: a payment must not fail because the orchestrator is briefly unavailable
 * (the outbox on the producing side plus retries here mean the saga is delayed, not lost), and the
 * customer's request must not be held open for the length of a provider round trip.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Kafka delivers at least once, so this handler will see duplicates after any rebalance, retry, or
 * crash between processing a record and committing its offset. Two things make that safe:
 *
 * <ul>
 *   <li>A saga is keyed on the payment id with a unique constraint, so a second delivery cannot start
 *       a second saga that would reserve the customer's funds twice.</li>
 *   <li>The orchestrator returns immediately when the saga it finds is already terminal, and resumes
 *       from the persisted state when it is not.</li>
 * </ul>
 *
 * <p>Redis-backed de-duplication sits in front of this as an optimisation, but the guarantee is the
 * database constraint — which is the arrangement {@code ProcessedEventRegistry}'s own contract
 * recommends for handlers that must be exactly-once.
 *
 * <h2>Failure handling</h2>
 *
 * <p>The handler rethrows. The container's error handler owns retry with backoff and, once attempts
 * are exhausted, the dead-letter topic. Swallowing an exception here would commit the offset for a
 * payment that was never orchestrated — and a silently un-orchestrated payment is the worst outcome
 * available, because the customer has been told it was accepted.
 *
 * <p>Note what does <em>not</em> reach this point: an ordinary payment failure. The orchestrator
 * records those as {@code payment-failed} and returns normally, because a declined or failed payment
 * is a successfully handled event. Only an inability to orchestrate at all propagates.
 */
@Component
public class PaymentCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCreatedListener.class);

    /** Scopes de-duplication. Two services must be able to process the same event independently. */
    private static final String CONSUMER = "orchestrator.payment-created";

    private final EventConsumerSupport consumerSupport;
    private final SagaOrchestrator orchestrator;

    public PaymentCreatedListener(EventConsumerSupport consumerSupport,
                                  SagaOrchestrator orchestrator) {
        this.consumerSupport = consumerSupport;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_CREATED, groupId = "payment-orchestrator")
    public void onPaymentCreated(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER, record, PaymentCreatedPayload.class, envelope -> {
            PaymentCreatedPayload payment = envelope.payload();

            log.debug("Orchestrating payment {} for {}", payment.paymentId(), payment.amount());
            orchestrator.orchestrate(payment);
        });
    }
}
