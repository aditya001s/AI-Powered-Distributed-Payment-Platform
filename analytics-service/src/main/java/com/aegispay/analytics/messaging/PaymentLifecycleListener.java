package com.aegispay.analytics.messaging;

import com.aegispay.analytics.service.PaymentProjectionService;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentApprovedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentFailedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRefundedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRejectedPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Builds the payment read model from the lifecycle stream.
 *
 * <h2>Why one class for six topics</h2>
 *
 * <p>They all write one row through one method. Splitting them into six classes would duplicate the
 * consumer-name convention, the projection call and the payload-null guard six times, and the only thing
 * that genuinely differs is which fields each payload carries.
 *
 * <h2>Why these six and not all fifteen</h2>
 *
 * <p>Two topics are deliberately not consumed.
 *
 * <p><strong>{@code audit-recorded}</strong> has no producer. Its javadoc names analytics-service as the
 * consumer, but nothing on the platform publishes it, so a listener would be dead code that looks like a
 * working feature — and would report a permanently stale stream in the freshness figure.
 *
 * <p><strong>{@code investigation-completed}</strong> and its sibling are already exposed by
 * ai-investigation-service's own API, complete with the trust caveats a narrative has to be read
 * alongside. Copying a recommendation into this read model would create a second, less careful place to
 * read it from, and a dashboard showing the recommendation without those caveats is exactly the failure
 * that service is built to prevent.
 *
 * <p>{@code wallet-movement-recorded} and the ledger topics are likewise skipped: ledger-service is the
 * book of record and already publishes a trial balance. A second running total of the platform's money,
 * derived independently, would eventually disagree with it — and then nobody could tell which was right.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Nothing is caught. A projection write that fails must reach the shared error handler so the record
 * is retried and eventually dead-lettered: the read model is rebuildable, so the correct response to a
 * transient database fault is to try again, and swallowing would commit the offset for an event that was
 * never applied — leaving a permanent hole nothing would ever notice.
 */
@Component
public class PaymentLifecycleListener {

    /**
     * De-duplication scopes, namespaced per service.
     *
     * <p>The Redis cache in front of these saves work but is not what makes the numbers right — the
     * projection is idempotent by construction, so a redelivery past the 24-hour window is still
     * harmless. See the migration header.
     */
    private static final String CONSUMER_CREATED = "analytics.payment-created";
    private static final String CONSUMER_APPROVED = "analytics.payment-approved";
    private static final String CONSUMER_COMPLETED = "analytics.payment-completed";
    private static final String CONSUMER_FAILED = "analytics.payment-failed";
    private static final String CONSUMER_REJECTED = "analytics.payment-rejected";
    private static final String CONSUMER_REFUNDED = "analytics.payment-refunded";

    private final EventConsumerSupport consumerSupport;
    private final PaymentProjectionService projection;

    public PaymentLifecycleListener(EventConsumerSupport consumerSupport,
                                    PaymentProjectionService projection) {
        this.consumerSupport = consumerSupport;
        this.projection = projection;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_CREATED, groupId = "analytics-service")
    public void onPaymentCreated(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_CREATED, record, PaymentCreatedPayload.class, envelope -> {
            PaymentCreatedPayload payload = require(envelope.payload(), KafkaTopics.PAYMENT_CREATED);

            projection.apply(KafkaTopics.PAYMENT_CREATED, payload.paymentId(),
                    PaymentStatus.CREATED, payload.createdAt(),
                    fact -> fact.applyCreation(
                            payload.transactionId(),
                            payload.initiatorUserId(),
                            payload.destinationUserId(),
                            payload.sourceWalletId(),
                            payload.destinationWalletId(),
                            payload.amount(),
                            payload.paymentType(),
                            payload.createdAt()));
        });
    }

    /**
     * The approval carries the verdict but no new money facts.
     *
     * <p>Consumed even though {@code fraud-checked} carries the same risk fields more fully, because the
     * two are not guaranteed to both arrive: a payment approved by an older producer, or one whose
     * fraud-checked record was dead-lettered, would otherwise show as unscored on the dashboard while
     * having plainly been scored.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_APPROVED, groupId = "analytics-service")
    public void onPaymentApproved(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_APPROVED, record, PaymentApprovedPayload.class, envelope -> {
            PaymentApprovedPayload payload = require(envelope.payload(), KafkaTopics.PAYMENT_APPROVED);

            projection.apply(KafkaTopics.PAYMENT_APPROVED, payload.paymentId(),
                    PaymentStatus.RISK_ASSESSED, payload.approvedAt(),
                    fact -> fact.applyApproval(payload.riskLevel(), payload.fraudProbability(),
                            payload.approvedAt()));
        });
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "analytics-service")
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_COMPLETED, record, PaymentCompletedPayload.class, envelope -> {
            PaymentCompletedPayload payload =
                    require(envelope.payload(), KafkaTopics.PAYMENT_COMPLETED);

            projection.apply(KafkaTopics.PAYMENT_COMPLETED, payload.paymentId(),
                    PaymentStatus.COMPLETED, payload.completedAt(),
                    fact -> fact.applyCompletion(
                            payload.amount(),
                            payload.fee(),
                            payload.providerId(),
                            payload.providerAttempts(),
                            payload.ledgerTransactionId(),
                            payload.endToEndLatencyMs(),
                            payload.completedAt()));
        });
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED, groupId = "analytics-service")
    public void onPaymentFailed(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_FAILED, record, PaymentFailedPayload.class, envelope -> {
            PaymentFailedPayload payload = require(envelope.payload(), KafkaTopics.PAYMENT_FAILED);

            projection.apply(KafkaTopics.PAYMENT_FAILED, payload.paymentId(),
                    PaymentStatus.FAILED, payload.failedAt(),
                    fact -> fact.applyFailure(
                            payload.amount(),
                            payload.failureCode(),
                            payload.failureReason(),
                            payload.providerAttempts(),
                            payload.failedAt()));
        });
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REJECTED, groupId = "analytics-service")
    public void onPaymentRejected(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_REJECTED, record, PaymentRejectedPayload.class, envelope -> {
            PaymentRejectedPayload payload = require(envelope.payload(), KafkaTopics.PAYMENT_REJECTED);

            projection.apply(KafkaTopics.PAYMENT_REJECTED, payload.paymentId(),
                    PaymentStatus.REJECTED, payload.rejectedAt(),
                    fact -> fact.applyRejection(
                            payload.amount(),
                            payload.riskLevel(),
                            payload.decision(),
                            payload.reasonCode(),
                            payload.reason(),
                            payload.rejectedAt()));
        });
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_REFUNDED, groupId = "analytics-service")
    public void onPaymentRefunded(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_REFUNDED, record, PaymentRefundedPayload.class, envelope -> {
            PaymentRefundedPayload payload = require(envelope.payload(), KafkaTopics.PAYMENT_REFUNDED);

            projection.apply(KafkaTopics.PAYMENT_REFUNDED, payload.paymentId(),
                    payload.fullRefund() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED,
                    payload.refundedAt(),
                    fact -> fact.applyRefund(
                            payload.totalRefunded(),
                            payload.originalAmount(),
                            payload.fullRefund(),
                            payload.ledgerTransactionId(),
                            payload.refundedAt()));
        });
    }

    /**
     * Refuses an empty payload.
     *
     * <p>{@link IllegalArgumentException} because the shared error handler treats it as non-retryable and
     * dead-letters on the first attempt. A record that decoded to nothing will decode to nothing on the
     * fourth attempt too, and retrying only delays the dead-letter record somebody needs.
     */
    private static <T> T require(T payload, String topic) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "a record on " + topic + " decoded to an empty payload, so there is nothing to "
                            + "project");
        }
        return payload;
    }
}
