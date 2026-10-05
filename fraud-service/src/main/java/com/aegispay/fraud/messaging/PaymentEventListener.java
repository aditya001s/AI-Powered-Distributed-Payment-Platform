package com.aegispay.fraud.messaging;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentFailedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRejectedPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import com.aegispay.fraud.domain.PaymentObservation;
import com.aegispay.fraud.service.FraudMutationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Builds this service's own view of payment history from the event stream.
 *
 * <h2>Why fraud-service listens at all, when the saga calls it directly</h2>
 *
 * <p>The synchronous call answers "what is the risk of this payment". These listeners build the
 * history that answers it: how many payments this account made in the last hour, whether this device
 * is new, how far from the last known location. Fetching those from payment-service and wallet-service
 * at scoring time would put two more network calls on the payment's critical path and would make a
 * fraud decision fail whenever an unrelated service was redeploying.
 *
 * <h2>Why the outcome matters, not just the attempt</h2>
 *
 * <p>The velocity features distinguish attempts from failures, because a burst of declines is one of
 * the strongest card-testing signals there is while a burst of successes is a customer doing their
 * shopping. Recording only that a payment was attempted would discard exactly the evidence that
 * separates them.
 *
 * <h2>Ordering</h2>
 *
 * <p>{@code payment-created} normally arrives before its outcome, but the four topics are separate and
 * Kafka orders records only within one partition of one topic. An outcome that arrives first is
 * therefore expected rather than exceptional: it is logged at debug and dropped, the creation event
 * then lands, and the row simply starts at {@code PENDING}. The cost is one payment's worth of
 * accuracy in one feature — far cheaper than a listener that dead-lettered on a race it cannot avoid.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Handlers rethrow. The container's error handler owns retry with backoff and, once attempts are
 * exhausted, the dead-letter topic. Swallowing an exception would commit the offset for a payment that
 * was never recorded, and the gap would silently weaken every future score for that account — with
 * nothing anywhere reporting a problem.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    /** Scopes de-duplication. Two services must be able to process the same event independently. */
    private static final String CONSUMER_CREATED = "fraud.payment-created";
    private static final String CONSUMER_COMPLETED = "fraud.payment-completed";
    private static final String CONSUMER_FAILED = "fraud.payment-failed";
    private static final String CONSUMER_REJECTED = "fraud.payment-rejected";

    private final EventConsumerSupport consumerSupport;
    private final FraudMutationService mutations;

    public PaymentEventListener(EventConsumerSupport consumerSupport,
                               FraudMutationService mutations) {
        this.consumerSupport = consumerSupport;
        this.mutations = mutations;
    }

    /**
     * Records a payment in the read model.
     *
     * <p>Note what this does <em>not</em> do: score it. Orchestration is driven by the saga, which
     * calls this service synchronously at its risk step because it needs the verdict before it charges
     * a provider. Scoring from this listener as well would assess every payment twice and put the two
     * results in a race.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_CREATED, groupId = "fraud-service")
    public void onPaymentCreated(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_CREATED, record, PaymentCreatedPayload.class, envelope -> {
            PaymentCreatedPayload payment = envelope.payload();
            mutations.observe(payment);
            log.debug("Recorded payment {} in the fraud read model", payment.paymentId());
        });
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "fraud-service")
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_COMPLETED, record, PaymentCompletedPayload.class,
                envelope -> mutations.recordOutcome(
                        envelope.payload().paymentId(), PaymentObservation.Outcome.COMPLETED));
    }

    /**
     * A technical failure.
     *
     * <p>Counted in the failure velocity alongside rejections. From the account's behaviour the two
     * look the same — an attempt that did not go through — and a card-testing burst produces both.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "fraud-service")
    public void onPaymentFailed(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_FAILED, record, PaymentFailedPayload.class,
                envelope -> mutations.recordOutcome(
                        envelope.payload().paymentId(), PaymentObservation.Outcome.FAILED));
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REJECTED, groupId = "fraud-service")
    public void onPaymentRejected(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_REJECTED, record, PaymentRejectedPayload.class,
                envelope -> mutations.recordOutcome(
                        envelope.payload().paymentId(), PaymentObservation.Outcome.REJECTED));
    }
}
