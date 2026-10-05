package com.aegispay.payment.messaging;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentApprovedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentFailedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRejectedPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import com.aegispay.payment.service.PaymentMutationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Brings the payment record into step with the saga that is driving it.
 *
 * <p>Since Phase 5 payment-service accepts a payment and then stops: payment-orchestrator consumes
 * {@code payment-created}, runs the saga, and announces what happened. These handlers apply those
 * announcements to this service's own row, which is what makes
 * {@code GET /api/payments/{id}} answer truthfully after a {@code 202 Accepted}.
 *
 * <h2>Why the payment status is not simply read from the orchestrator</h2>
 *
 * <p>Because that would make every customer-facing read depend on another service being up, and it
 * would put the payment lifecycle in two places with a network call between them. The payment
 * aggregate is this service's own record of what the customer asked for and how it turned out; the
 * saga is the orchestrator's record of the work. They are different things that happen to move
 * together, and each service owning its own is what lets either be read while the other is down.
 *
 * <h2>Out-of-order delivery is expected, not exceptional</h2>
 *
 * <p>Kafka orders records within one partition of one topic. These four events travel on four
 * different topics, so {@code payment-completed} can arrive before {@code payment-approved}. That is
 * why {@code Payment.advanceTo} walks forward to the state an event asserts rather than taking a
 * single step: a later event is strictly more informative than an earlier one, so applying it and
 * then ignoring the straggler is both correct and simpler than buffering.
 *
 * <h2>Idempotency</h2>
 *
 * <p>At-least-once delivery means every handler here will see duplicates after any rebalance or
 * retry. Two things make that safe, and the second is the one that actually guarantees it:
 *
 * <ul>
 *   <li>Redis-backed de-duplication in front, which is an optimisation.</li>
 *   <li>The guarded state machine, which rejects a transition the payment has already made. A
 *       replayed {@code payment-completed} finds the payment already {@code COMPLETED}, changes
 *       nothing, and — crucially — records no second notification, so the customer is not told twice
 *       that their money arrived.</li>
 * </ul>
 *
 * <h2>Failure handling</h2>
 *
 * <p>Handlers rethrow. The container's error handler owns retry with backoff and, once attempts are
 * exhausted, the dead-letter topic. Swallowing an exception would commit the offset for an outcome
 * that was never applied, leaving a payment showing as in-flight forever while the money has
 * actually moved — the single most confusing state this service can be in, because the customer's
 * balance and the customer's payment history would disagree.
 */
@Component
public class SagaOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(SagaOutcomeListener.class);

    /**
     * De-duplication scopes, one per handler.
     *
     * <p>Scoped per consumer rather than per event id alone, so that ledger-service and this service
     * processing the same {@code payment-completed} do not suppress each other.
     */
    private static final String CONSUMER_APPROVED = "payment.saga-approved";
    private static final String CONSUMER_COMPLETED = "payment.saga-completed";
    private static final String CONSUMER_FAILED = "payment.saga-failed";
    private static final String CONSUMER_REJECTED = "payment.saga-rejected";

    private final EventConsumerSupport consumerSupport;
    private final PaymentMutationService mutations;

    public SagaOutcomeListener(EventConsumerSupport consumerSupport,
                               PaymentMutationService mutations) {
        this.consumerSupport = consumerSupport;
        this.mutations = mutations;
    }

    /**
     * A provider authorised the charge.
     *
     * <p>Applied even though {@code payment-completed} will follow within milliseconds, because the
     * two are not guaranteed to both arrive: a saga can be authorised and then fail at the ledger or
     * the capture. Recording the authorisation means a payment stuck between the two reads as
     * {@code AUTHORIZED} rather than {@code CREATED}, which is the difference between "we are part
     * way through" and "nothing has happened yet" for whoever is investigating it.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_APPROVED, groupId = "payment-service")
    public void onPaymentApproved(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_APPROVED, record, PaymentApprovedPayload.class,
                envelope -> {
                    PaymentApprovedPayload approved = envelope.payload();
                    mutations.applyApproved(
                            approved.paymentId(),
                            approved.riskLevel(),
                            approved.fraudProbability(),
                            // The saga approved it, so the decision is APPROVE by construction; the
                            // event carries the risk level and probability rather than repeating it.
                            com.aegispay.common.domain.FraudDecision.APPROVE);
                });
    }

    /**
     * The payment completed: charged, captured and posted to the ledger.
     *
     * <p>This is where the customer notifications are emitted, which is why the handler must be
     * exactly-once in effect rather than merely eventually-correct. The state machine provides that:
     * a redelivery cannot advance an already-completed payment, so it cannot record a second
     * notification either.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "payment-service")
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_COMPLETED, record, PaymentCompletedPayload.class,
                envelope -> {
                    PaymentCompletedPayload completed = envelope.payload();

                    log.debug("Applying completion of payment {} via {}",
                            completed.paymentId(), completed.providerId());

                    mutations.applyCompleted(
                            completed.paymentId(),
                            completed.providerId(),
                            completed.providerReference(),
                            // The provider fee, stored so a refund and any dispute can be reasoned
                            // about against what the payment actually cost the platform.
                            completed.fee(),
                            completed.endToEndLatencyMs());
                });
    }

    /**
     * The payment could not be completed.
     *
     * <p>{@code compensationComplete} is carried through rather than dropped. When it is false, money
     * may still be reserved at a wallet or authorised at a provider, and this service is where
     * somebody investigating a customer complaint looks first — so it is logged loudly here as well
     * as being alerted on from the orchestrator.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "payment-service")
    public void onPaymentFailed(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_FAILED, record, PaymentFailedPayload.class,
                envelope -> {
                    PaymentFailedPayload failed = envelope.payload();
                    mutations.applyFailed(
                            failed.paymentId(),
                            failed.failureCode(),
                            failed.failureReason(),
                            failed.failedStep(),
                            failed.compensationComplete());
                });
    }

    /**
     * The payment was refused: every provider declined, or fraud controls said no.
     *
     * <p>Kept distinct from a failure all the way to the customer. A rejection means "this will not
     * work"; a failure means "try again". Collapsing them would either send customers back to retry
     * a payment that can only be declined again, or tell them to give up on one that was simply
     * unlucky.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_REJECTED, groupId = "payment-service")
    public void onPaymentRejected(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_REJECTED, record, PaymentRejectedPayload.class,
                envelope -> {
                    PaymentRejectedPayload rejected = envelope.payload();
                    mutations.applyRejected(
                            rejected.paymentId(),
                            rejected.reasonCode(),
                            rejected.reason(),
                            rejected.fundsReleased());
                });
    }
}
