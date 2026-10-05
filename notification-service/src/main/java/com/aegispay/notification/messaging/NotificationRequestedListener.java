package com.aegispay.notification.messaging;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.NotificationPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import com.aegispay.notification.service.NotificationDeliveryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code notification-required}.
 *
 * <h2>Why this is the only topic this service listens to</h2>
 *
 * <p>It would be easy to subscribe to {@code payment-completed} directly and decide there that a
 * notification is warranted. That would be wrong twice over.
 *
 * <p><strong>It would double-notify.</strong> payment-service already publishes
 * {@code notification-required} from its outbox — one event per party — so consuming the payment event as
 * well would send every customer two of everything.
 *
 * <p><strong>It would put the decision in the wrong service.</strong> Whether a customer should be told,
 * and which customer, is a question about the payment: only payment-service knows that a completed
 * transfer has two interested parties and that a refund concerns the payer alone. This service owns
 * <em>how</em> a message is worded and delivered. Splitting it that way is what lets copy change without
 * redeploying payment-service, and lets a new notification be added without touching this module.
 *
 * <h2>What happens on failure</h2>
 *
 * <p>Nothing is caught here. The distinction that matters is made in
 * {@link NotificationDeliveryService}: a failure to <em>persist</em> propagates, so the shared error
 * handler retries the record and eventually dead-letters it, while a failure to <em>send</em> is committed
 * as scheduled state and never reaches this method. Catching here would collapse the two and turn a lost
 * database connection into a silently dropped notification.
 */
@Component
public class NotificationRequestedListener {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationRequestedListener.class);

    /**
     * Scopes de-duplication in the shared processed-event registry.
     *
     * <p>Namespaced by service, because two services must be able to process the same event
     * independently. This is a Redis-backed optimisation with a 24-hour window; the durable guarantee is
     * the unique constraint on the deduplication key, which is what makes a redelivery after the window
     * closes still harmless.
     */
    private static final String CONSUMER = "notification.notification-required";

    private final EventConsumerSupport consumerSupport;
    private final NotificationDeliveryService deliveries;

    public NotificationRequestedListener(EventConsumerSupport consumerSupport,
                                         NotificationDeliveryService deliveries) {
        this.consumerSupport = consumerSupport;
        this.deliveries = deliveries;
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATION_REQUIRED, groupId = "notification-service")
    public void onNotificationRequired(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER, record, NotificationPayload.class, envelope -> {
            NotificationPayload payload = envelope.payload();

            // Recipient and template key only. The variables carry a payment amount and a note written
            // by another customer, and neither belongs in an application log.
            log.debug("Notification requested for user {} using template {}",
                    payload == null ? "(none)" : payload.recipientUserId(),
                    payload == null ? "(none)" : payload.templateKey());

            deliveries.handle(payload, envelope.eventId(), envelope.source());
        });
    }
}
