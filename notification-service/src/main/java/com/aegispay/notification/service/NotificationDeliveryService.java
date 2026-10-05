package com.aegispay.notification.service;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import com.aegispay.common.events.payload.NotificationPayload;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.notification.config.NotificationProperties;
import com.aegispay.notification.domain.Notification;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.domain.NotificationPreferences;
import com.aegispay.notification.domain.SuppressionReason;
import com.aegispay.notification.template.NotificationTemplate;
import com.aegispay.notification.template.RenderedMessage;
import com.aegispay.notification.template.TemplateCatalogue;
import com.aegispay.notification.template.TemplateRenderer;
import com.aegispay.notification.transport.NotificationTransport;
import com.aegispay.notification.transport.TransportRegistry;
import com.aegispay.notification.util.ContactMasking;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a notification request into messages.
 *
 * <pre>
 *   0. already recorded?     -&gt; return; the customer has been told
 *   1. resolve the recipient (email from auth-service, phone from preferences)
 *   2. plan the channels     (preferences, transports, available copy)
 *   3. render per channel    (escaped, defanged, length-capped)
 *   4. persist               (one transaction: notification + every delivery)
 *   5. attempt each delivery (failures become scheduled state, not exceptions)
 * </pre>
 *
 * <h2>Why this class holds no transaction</h2>
 *
 * <p>Step 5 makes network calls. A transaction spanning them would pin a database connection for the
 * length of an SMTP timeout, and during a provider outage the pool would empty — leaving the service
 * unable to record the very failures it is discovering. Every write is in
 * {@link NotificationMutationService}, behind a real bean boundary.
 *
 * <h2>The distinction this service exists to get right</h2>
 *
 * <p><strong>A failure to persist throws. A failure to send does not.</strong>
 *
 * <p>If the notification cannot be written, nothing durable exists and Kafka's redelivery is the only
 * thing that will ever bring the event back — so the exception must propagate and let the shared error
 * handler retry and eventually dead-letter the record.
 *
 * <p>If the notification is written and a send fails, the event has been fully processed: the durable
 * record of what must be sent now exists, and a scheduled worker owns it from here. Throwing would be
 * actively harmful — it would stall the partition behind a slow provider, replay channels that already
 * succeeded, and finally park the event in a Kafka topic that nothing drains.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    /**
     * Variables the service supplies itself.
     *
     * <p>{@code greeting} is a complete line ("Hi Alice," or "Hi,") rather than a bare name, so no template
     * has to do spacing arithmetic around an optional value — see {@link RecipientResolver.Recipient#greeting}
     * for the defect that taught this.
     */
    private static final String VAR_GREETING = "greeting";
    private static final String VAR_PAYMENT_ID = "paymentId";

    private final TemplateCatalogue catalogue;
    private final TemplateRenderer renderer;
    private final TransportRegistry transports;
    private final RecipientResolver recipients;
    private final NotificationMutationService mutations;
    private final DeliveryBackoff backoff;
    private final NotificationProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper json;

    public NotificationDeliveryService(TemplateCatalogue catalogue,
                                       TemplateRenderer renderer,
                                       TransportRegistry transports,
                                       RecipientResolver recipients,
                                       NotificationMutationService mutations,
                                       DeliveryBackoff backoff,
                                       NotificationProperties properties,
                                       MeterRegistry meterRegistry,
                                       ObjectMapper json) {
        this.catalogue = catalogue;
        this.renderer = renderer;
        this.transports = transports;
        this.recipients = recipients;
        this.mutations = mutations;
        this.backoff = backoff;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.json = json;
    }

    /**
     * Handles one {@code notification-required} event.
     *
     * @param eventId the envelope's event id, recorded for provenance
     * @return the notification, whether it was created now or already existed
     */
    public Notification handle(NotificationPayload payload, String eventId, String source) {
        validate(payload);

        NotificationMutationService.Created created = record(payload, eventId, source);

        if (created.alreadyExisted()) {
            // Not an error, and expected forever: Kafka delivers at least once. Counted so the rate is
            // visible, because a sudden change in it means something about redelivery has changed.
            meterRegistry.counter(MetricNames.NOTIFICATION_DUPLICATE,
                    MetricNames.TAG_REASON, "dedup_key_exists").increment();
            log.debug("Notification for dedup key {} already exists; nothing to send",
                    payload.deduplicationKey());
            return created.notification();
        }

        // Everything past here is best-effort. The record exists, so a failure is recoverable by the
        // retry worker and must not reach the Kafka error handler.
        attemptAll(created.deliveries());
        return created.notification();
    }

    /**
     * Validates the parts of a payload this service cannot work around.
     *
     * <p>Thrown as {@link IllegalArgumentException} on purpose: the shared Kafka error handler treats it
     * as non-retryable and dead-letters the record on the first attempt. A payload with no recipient will
     * be just as unusable on the fourth attempt, and retrying it only delays the dead-letter record
     * somebody needs in order to fix the producer.
     */
    private static void validate(NotificationPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("notification-required carried no payload");
        }
        if (payload.recipientUserId() == null || payload.recipientUserId().isBlank()) {
            throw new IllegalArgumentException(
                    "notification-required carried no recipient; there is nobody to notify");
        }
        if (payload.deduplicationKey() == null || payload.deduplicationKey().isBlank()) {
            throw new IllegalArgumentException(
                    "notification-required carried no deduplication key for user "
                            + payload.recipientUserId() + "; without one, every redelivery of this "
                            + "event would message the customer again");
        }
        if (payload.templateKey() == null || payload.templateKey().isBlank()) {
            throw new IllegalArgumentException(
                    "notification-required carried no template key, so there is no copy to render");
        }
    }

    // ------------------------------------------------------------- planning

    private NotificationMutationService.Created record(NotificationPayload payload, String eventId,
                                                      String source) {
        Map<String, String> variables = redact(payload.variables());

        Notification candidate = Notification.of(
                payload.deduplicationKey(),
                payload.recipientUserId(),
                payload.templateKey(),
                payload.priority() == null ? NotificationPriority.NORMAL : payload.priority(),
                payload.paymentId(),
                toJson(variables, payload.deduplicationKey()),
                eventId,
                source);

        return mutations.createIfAbsent(candidate,
                notification -> plan(notification, payload, variables));
    }

    /**
     * Decides what each requested channel gets: a real delivery, or a suppression with a reason.
     *
     * <p>Every requested channel produces a row. A channel that is simply skipped leaves no trace, and
     * "why did I not get an SMS" then has no answer — a missing row and a suppressed row look identical
     * to the customer and completely different to support.
     */
    private List<NotificationDelivery> plan(Notification notification, NotificationPayload payload,
                                            Map<String, String> variables) {

        Optional<NotificationTemplate> template = catalogue.find(notification.getTemplateKey());
        RecipientResolver.Recipient recipient = recipients.resolve(
                notification.getRecipientUserId(), payload.recipientEmail(), payload.recipientPhone());
        NotificationPreferences preferences =
                recipients.preferencesFor(notification.getRecipientUserId());

        Map<String, String> withPlatformValues = new LinkedHashMap<>(variables);
        withPlatformValues.put(VAR_GREETING, recipient.greeting());
        if (notification.getPaymentId() != null) {
            withPlatformValues.putIfAbsent(VAR_PAYMENT_ID, notification.getPaymentId());
        }

        List<NotificationDelivery> rows = new ArrayList<>();
        for (NotificationChannel channel : requestedChannels(payload)) {
            rows.add(planOne(notification, channel, template, recipient, preferences,
                    withPlatformValues));
        }
        return rows;
    }

    /**
     * The channels to attempt.
     *
     * <p>De-duplicated and ordered, because a producer listing EMAIL twice would otherwise hit the unique
     * constraint on {@code (notification_id, channel)} and fail the whole notification over a cosmetic
     * defect in somebody else's list. Falls back to email and in-app when the list is absent: a
     * notification worth publishing is worth delivering, and silently sending nothing would be the worst
     * reading of an empty list.
     */
    private static Set<NotificationChannel> requestedChannels(NotificationPayload payload) {
        if (payload.channels() == null || payload.channels().isEmpty()) {
            return Set.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP);
        }
        Set<NotificationChannel> unique = new LinkedHashSet<>(payload.channels());
        unique.remove(null);
        return unique.isEmpty()
                ? Set.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP)
                : unique;
    }

    private NotificationDelivery planOne(Notification notification,
                                         NotificationChannel channel,
                                         Optional<NotificationTemplate> template,
                                         RecipientResolver.Recipient recipient,
                                         NotificationPreferences preferences,
                                         Map<String, String> variables) {

        // ---- is there anywhere to send it ----
        if (!transports.isDeliverable(channel)) {
            return suppress(notification, channel, SuppressionReason.CHANNEL_UNAVAILABLE);
        }

        // ---- did the customer ask not to receive this ----
        // Priority decides whether a preference is even consulted. A HIGH-priority notification is a
        // security or money-movement fact, and a preference exists to stop routine noise; treating
        // "tell me nothing" as covering a fraud hold would turn a convenience setting into a way of
        // hiding an account takeover from its victim.
        if (notification.respectsChannelOptOut() && !preferences.allows(channel)) {
            return suppress(notification, channel,
                    channel == NotificationChannel.SMS && preferences.getPhoneE164() != null
                            ? SuppressionReason.SMS_NOT_OPTED_IN
                            : SuppressionReason.USER_OPTED_OUT);
        }

        // ---- is there copy ----
        if (template.isEmpty() || !template.get().supports(channel)) {
            // Loud, because it is never normal: a producer is emitting a notification nobody wrote copy
            // for, and those customers are silently receiving nothing.
            log.error("""
                            No {} copy exists for template key '{}'. The notification for user {} is \
                            suppressed rather than guessed at -- a vague message about somebody's money \
                            either alarms them for no reason or reassures them when it should not. Write \
                            the template and replay the delivery.""",
                    channel, notification.getTemplateKey(), notification.getRecipientUserId());
            return suppress(notification, channel, SuppressionReason.NO_TEMPLATE);
        }

        // ---- where does it go ----
        String destination = destinationFor(channel, recipient, notification);
        if (destination == null) {
            return suppress(notification, channel, missingDestinationReason(channel, recipient));
        }

        // ---- render ----
        try {
            RenderedMessage message = renderer.render(template.get(), channel, variables);
            return NotificationDelivery.pending(notification, channel, destination,
                    message.subject(), message.body());

        } catch (TemplateRenderer.TemplateRenderException ex) {
            // Suppressed rather than sent broken. The notification is immutable, so a message reading
            // "You sent {{amount}} INR" would be the permanent record of what this customer was told.
            log.error("Could not render {} for template '{}': {}. Suppressing rather than sending a "
                            + "message with an unresolved placeholder in it.",
                    channel, notification.getTemplateKey(), ex.getMessage());
            return suppress(notification, channel, SuppressionReason.NO_TEMPLATE);
        }
    }

    private static String destinationFor(NotificationChannel channel,
                                         RecipientResolver.Recipient recipient,
                                         Notification notification) {
        return switch (channel) {
            case EMAIL -> blankToNull(recipient.email());
            case SMS -> recipient.smsOptedIn() ? blankToNull(recipient.phoneE164()) : null;
            // Addressed by user id: the inbox row is the delivery, and the recipient is already on it.
            case IN_APP -> notification.getRecipientUserId();
            case PUSH -> null;
        };
    }

    /**
     * Treats blank as absent.
     *
     * <p>A blank address is worse than a null one: it passes a null check, reaches the transport, and
     * fails there as a permanent error — so the delivery is dead-lettered with "invalid address" rather
     * than suppressed with "no address", and the operator investigating is sent looking for a corrupted
     * record instead of a missing one.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static SuppressionReason missingDestinationReason(NotificationChannel channel,
                                                             RecipientResolver.Recipient recipient) {
        if (channel == NotificationChannel.SMS) {
            // Told apart deliberately: one is fixed by the customer adding a number, the other by them
            // agreeing to be texted, and an operator looking at a spike needs to know which.
            return recipient.phoneE164() == null
                    ? SuppressionReason.NO_DESTINATION
                    : SuppressionReason.SMS_NOT_OPTED_IN;
        }
        return SuppressionReason.NO_DESTINATION;
    }

    private NotificationDelivery suppress(Notification notification, NotificationChannel channel,
                                          SuppressionReason reason) {
        meterRegistry.counter(MetricNames.NOTIFICATION_SUPPRESSED,
                MetricNames.TAG_CHANNEL, channel.name(),
                MetricNames.TAG_REASON, reason.name()).increment();
        return NotificationDelivery.suppressed(notification, channel, reason);
    }

    // ------------------------------------------------------------- attempting

    /** Attempts every delivery that is not already terminal, isolating each one's failure. */
    private void attemptAll(List<NotificationDelivery> deliveries) {
        for (NotificationDelivery delivery : deliveries) {
            if (!delivery.getStatus().isRetryable()) {
                continue;
            }
            attempt(delivery.getDeliveryId());
        }
    }

    /**
     * Makes one attempt at one delivery.
     *
     * <p>Never throws. Every outcome — success, a transient failure, a permanent one, an exhausted
     * budget, even an unexpected bug in a transport — becomes committed state. That is what lets both
     * callers, the event handler and the retry worker, treat this as fire-and-forget: neither has any
     * reasonable response to an exception, because the durable record already says what happened.
     *
     * @return true when the message was accepted
     */
    public boolean attempt(String deliveryId) {
        Optional<NotificationDelivery> found = mutations.findDelivery(deliveryId);
        if (found.isEmpty()) {
            log.warn("Delivery {} vanished before it could be attempted", deliveryId);
            return false;
        }
        NotificationDelivery delivery = found.get();

        if (!delivery.getStatus().isRetryable()) {
            // Reached when the retry worker claimed a row that another instance finished first. Ordinary
            // under concurrency, and cheaper to check here than to prevent.
            log.debug("Delivery {} is {}; nothing to attempt", deliveryId, delivery.getStatus());
            return delivery.isSent();
        }

        Optional<NotificationTransport> transport = transports.forChannel(delivery.getChannel());
        if (transport.isEmpty()) {
            // A channel that had a transport when the row was planned and does not now: a configuration
            // change between the two. Dead-lettered rather than retried forever against nothing.
            mutations.recordDeadLetter(deliveryId, delivery.getAttemptCount() + 1,
                    "NO_TRANSPORT",
                    "no transport is registered for " + delivery.getChannel(), 0L);
            countDeadLetter(delivery);
            return false;
        }

        int attemptNumber = mutations.beginAttempt(deliveryId);
        if (attemptNumber == 0) {
            // Another listener or worker committed a lease after our initial read. It owns the
            // transport call; returning without sending is the effectively-once boundary.
            log.debug("Delivery {} is already leased by another worker", deliveryId);
            return false;
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        long startedNanos = System.nanoTime();

        try {
            String providerRef = transport.get().send(delivery.getDestination(),
                    new RenderedMessage(delivery.getChannel(), delivery.getRenderedSubject(),
                            delivery.getRenderedBody()));

            long elapsed = elapsedMillis(startedNanos);
            mutations.recordSuccess(deliveryId, attemptNumber, providerRef, elapsed);
            stop(sample, delivery, MetricNames.OUTCOME_SUCCESS);

            log.info("Delivered {} notification {} to {} on attempt {} ({}ms)",
                    delivery.getChannel(), delivery.getNotificationId(),
                    ContactMasking.mask(delivery.getChannel(), delivery.getDestination()),
                    attemptNumber, elapsed);
            return true;

        } catch (NotificationTransport.TransportException ex) {
            handleFailure(delivery, attemptNumber, ex.code(), ex.getMessage(), ex.isRetryable(),
                    elapsedMillis(startedNanos));
            stop(sample, delivery, MetricNames.OUTCOME_FAILURE);
            return false;

        } catch (RuntimeException ex) {
            // A transport that threw something it did not declare. Treated as transient because an
            // unknown fault might be transient, and the attempt budget bounds the optimism -- while
            // assuming permanence would discard a notification over a bug in one transport.
            log.error("Transport for {} threw an unexpected {}: {}", delivery.getChannel(),
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            handleFailure(delivery, attemptNumber, "UNEXPECTED_" + ex.getClass().getSimpleName(),
                    String.valueOf(ex.getMessage()), true, elapsedMillis(startedNanos));
            stop(sample, delivery, MetricNames.OUTCOME_FAILURE);
            return false;
        }
    }

    private void handleFailure(NotificationDelivery delivery, int attemptNumber, String code,
                               String message, boolean retryable, long elapsed) {
        String deliveryId = delivery.getDeliveryId();

        if (!retryable) {
            mutations.recordDeadLetter(deliveryId, attemptNumber, code, message, elapsed);
            countDeadLetter(delivery);
            log.error("""
                            {} delivery of notification {} failed permanently ({}): {}. Not retried -- \
                            the fault is in the message or the address, so every further attempt would \
                            fail identically. The customer has not been told.""",
                    delivery.getChannel(), delivery.getNotificationId(), code, message);
            return;
        }

        if (backoff.isExhausted(attemptNumber)) {
            mutations.recordDeadLetter(deliveryId, attemptNumber, code, message, elapsed);
            countDeadLetter(delivery);
            log.error("""
                            {} delivery of notification {} exhausted all {} attempts ({}): {}. The \
                            customer has not been told, and nothing will retry this without an operator \
                            replaying it.""",
                    delivery.getChannel(), delivery.getNotificationId(), backoff.maxAttempts(),
                    code, message);
            return;
        }

        Duration delay = backoff.delayAfter(attemptNumber);
        mutations.recordRetryableFailure(deliveryId, attemptNumber, code, message, elapsed,
                java.time.Instant.now().plus(delay));

        meterRegistry.counter(MetricNames.NOTIFICATION_RETRY_SCHEDULED,
                MetricNames.TAG_CHANNEL, delivery.getChannel().name()).increment();

        log.warn("{} delivery of notification {} failed on attempt {}/{} ({}): {}. Retrying in {}s.",
                delivery.getChannel(), delivery.getNotificationId(), attemptNumber,
                backoff.maxAttempts(), code, message, delay.toSeconds());
    }

    private void countDeadLetter(NotificationDelivery delivery) {
        meterRegistry.counter(MetricNames.NOTIFICATION_DEAD_LETTERED,
                MetricNames.TAG_CHANNEL, delivery.getChannel().name()).increment();
    }

    private void stop(Timer.Sample sample, NotificationDelivery delivery, String outcome) {
        sample.stop(meterRegistry.timer(MetricNames.NOTIFICATION_DELIVERY_LATENCY,
                MetricNames.TAG_CHANNEL, delivery.getChannel().name()));
        meterRegistry.counter(MetricNames.NOTIFICATION_DELIVERY_ATTEMPT,
                MetricNames.TAG_CHANNEL, delivery.getChannel().name(),
                MetricNames.TAG_OUTCOME, outcome).increment();
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    // --------------------------------------------------------------- helpers

    /**
     * Drops values whose names look like credentials.
     *
     * <p>Producers are told not to put secrets in notification variables. This is the defence for when one
     * does anyway, and it happens on the way in — before the value is stored, not just before it is
     * rendered — because this table is read by support staff and appears in an admin API.
     */
    private Map<String, String> redact(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        variables.forEach((name, value) -> {
            if (name == null) {
                return;
            }
            safe.put(name, TemplateRenderer.sanitiseForStorage(name, value));
        });
        return safe;
    }

    private String toJson(Map<String, String> variables, String deduplicationKey) {
        try {
            return json.writeValueAsString(variables);
        } catch (RuntimeException ex) {
            // The variables are a diagnostic aid; the notification is the point. Refusing to record a
            // notification because its variables would not serialise would be the wrong trade -- the
            // rendered body is stored separately and is what the customer actually received.
            log.warn("Could not serialise variables for notification {}; storing without them: {}",
                    deduplicationKey, ex.getMessage());
            return "{}";
        }
    }
}
