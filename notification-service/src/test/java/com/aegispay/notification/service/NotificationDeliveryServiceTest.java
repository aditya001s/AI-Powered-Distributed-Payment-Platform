package com.aegispay.notification.service;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import com.aegispay.common.events.payload.NotificationPayload;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.notification.config.NotificationProperties;
import com.aegispay.notification.domain.DeliveryStatus;
import com.aegispay.notification.domain.Notification;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.domain.NotificationPreferences;
import com.aegispay.notification.domain.SuppressionReason;
import com.aegispay.notification.template.NotificationTemplate;
import com.aegispay.notification.template.TemplateCatalogue;
import com.aegispay.notification.template.TemplateRenderer;
import com.aegispay.notification.transport.NotificationTransport;
import com.aegispay.notification.transport.TransportRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The delivery pipeline: de-duplication, channel planning, and what happens when a send fails.
 *
 * <h2>The contract being pinned down</h2>
 *
 * <p><strong>A failure to persist throws; a failure to send does not.</strong> Getting that backwards in
 * either direction is the worst defect available in this module:
 *
 * <ul>
 *   <li>If a send failure propagated, the shared Kafka error handler would back off the partition behind a
 *       slow provider, replay channels that already succeeded, and finally park the event in a topic that
 *       nothing drains.</li>
 *   <li>If a persistence failure were swallowed, nothing durable would exist and the notification would be
 *       lost silently — the one thing Kafka's redelivery was there to prevent.</li>
 * </ul>
 *
 * <p>The mutation service is faked over an in-memory map rather than mocked with rigid stubs, so the real
 * aggregates do the state transitions and the assertions are about the delivery's actual status rather than
 * about which method was called.
 */
class NotificationDeliveryServiceTest {

    private static final String USER = "USR-1";
    private static final String DEDUP = "PAY-1:sent:USR-1";

    private final TemplateCatalogue catalogue = mock(TemplateCatalogue.class);
    private final TransportRegistry transports = mock(TransportRegistry.class);
    private final RecipientResolver recipients = mock(RecipientResolver.class);
    private final NotificationMutationService mutations = mock(NotificationMutationService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final NotificationProperties properties = new NotificationProperties();

    private final Map<String, NotificationDelivery> store = new LinkedHashMap<>();
    private final List<String> existingDedupKeys = new ArrayList<>();

    private NotificationTransport emailTransport;
    private NotificationTransport inAppTransport;
    private NotificationPreferences preferences;
    private NotificationDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationDeliveryService(catalogue, new TemplateRenderer(), transports,
                recipients, mutations, new DeliveryBackoff(properties), properties, meterRegistry,
                new ObjectMapper());

        emailTransport = mock(NotificationTransport.class);
        when(emailTransport.channel()).thenReturn(NotificationChannel.EMAIL);
        inAppTransport = mock(NotificationTransport.class);
        when(inAppTransport.channel()).thenReturn(NotificationChannel.IN_APP);

        preferences = NotificationPreferences.defaultsFor(USER);

        when(transports.isDeliverable(any())).thenReturn(true);
        when(transports.forChannel(NotificationChannel.EMAIL))
                .thenReturn(Optional.of(emailTransport));
        when(transports.forChannel(NotificationChannel.IN_APP))
                .thenReturn(Optional.of(inAppTransport));
        when(transports.forChannel(NotificationChannel.SMS)).thenReturn(Optional.empty());
        when(transports.forChannel(NotificationChannel.PUSH)).thenReturn(Optional.empty());

        when(catalogue.find("payment.sent")).thenReturn(Optional.of(template()));
        when(catalogue.find("payment.unknown")).thenReturn(Optional.empty());

        when(recipients.preferencesFor(USER)).thenAnswer(invocation -> preferences);
        when(recipients.resolve(anyString(), any(), any())).thenReturn(
                new RecipientResolver.Recipient(USER, "alice@example.com", "Alice Smith",
                        null, false));

        wireFakeMutationService();
    }

    // ------------------------------------------------------------------------
    // idempotency
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        /**
         * Kafka delivers at least once, so this event will arrive twice. A customer must not be told twice
         * that their payment succeeded.
         */
        @Test
        @DisplayName("sends nothing when the deduplication key is already known")
        void alreadyRecordedSendsNothing() {
            existingDedupKeys.add(DEDUP);

            service.handle(payload(), "evt-1", "payment-service");

            verify(emailTransport, never()).send(anyString(), any());
            verify(inAppTransport, never()).send(anyString(), any());
        }

        @Test
        @DisplayName("counts a duplicate rather than treating it as an error")
        void countsDuplicates() {
            existingDedupKeys.add(DEDUP);

            service.handle(payload(), "evt-1", "payment-service");

            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_DUPLICATE)
                    .tag("reason", "dedup_key_exists").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("delivers on the first arrival")
        void firstArrivalDelivers() {
            service.handle(payload(), "evt-1", "payment-service");

            verify(emailTransport).send(anyString(), any());
            verify(inAppTransport).send(anyString(), any());
        }
    }

    // ------------------------------------------------------------------------
    // rejecting an unusable payload
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("unusable payloads")
    class UnusablePayloads {

        /**
         * {@link IllegalArgumentException} on purpose. The shared Kafka error handler treats it as
         * non-retryable and dead-letters the record on the first attempt — a payload with no recipient will
         * be just as unusable on the fourth attempt, and retrying only delays the dead-letter record
         * somebody needs in order to fix the producer.
         */
        @Test
        @DisplayName("refuses a payload with no recipient, without retrying")
        void refusesMissingRecipient() {
            NotificationPayload broken = new NotificationPayload(null, null, null,
                    List.of(NotificationChannel.EMAIL), "payment.sent", Map.of(),
                    NotificationPriority.NORMAL, DEDUP, "PAY-1");

            assertThatThrownBy(() -> service.handle(broken, "evt-1", "payment-service"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nobody to notify");
        }

        /**
         * Without a deduplication key every redelivery would message the customer again. Refused rather than
         * defaulted to a random value, which would make the defect look like a broken producer instead of a
         * broken default.
         */
        @Test
        @DisplayName("refuses a payload with no deduplication key")
        void refusesMissingDedupKey() {
            NotificationPayload broken = new NotificationPayload(USER, null, null,
                    List.of(NotificationChannel.EMAIL), "payment.sent", Map.of(),
                    NotificationPriority.NORMAL, "  ", "PAY-1");

            assertThatThrownBy(() -> service.handle(broken, "evt-1", "payment-service"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deduplication key");
        }

        @Test
        @DisplayName("refuses a payload with no template key")
        void refusesMissingTemplateKey() {
            NotificationPayload broken = new NotificationPayload(USER, null, null,
                    List.of(NotificationChannel.EMAIL), null, Map.of(),
                    NotificationPriority.NORMAL, DEDUP, "PAY-1");

            assertThatThrownBy(() -> service.handle(broken, "evt-1", "payment-service"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("template key");
        }

        @Test
        @DisplayName("refuses a null payload")
        void refusesNullPayload() {
            assertThatThrownBy(() -> service.handle(null, "evt-1", "payment-service"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------------
    // channel planning
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("channel planning")
    class ChannelPlanning {

        /**
         * Every requested channel produces a row. A channel that is simply skipped leaves no trace, and "why
         * did I not get an SMS" then has no answer — a missing row and a suppressed row look identical to a
         * customer and completely different to support.
         */
        @Test
        @DisplayName("records a row for every requested channel, including suppressed ones")
        void everyChannelGetsARow() {
            service.handle(payload(NotificationChannel.EMAIL, NotificationChannel.SMS,
                    NotificationChannel.IN_APP), "evt-1", "payment-service");

            assertThat(store.values()).extracting(NotificationDelivery::getChannel)
                    .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.SMS,
                            NotificationChannel.IN_APP);
        }

        @Test
        @DisplayName("suppresses a channel the user opted out of")
        void suppressesOptedOutChannel() {
            preferences.setChannelEnabled(NotificationChannel.EMAIL, false);

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL))
                    .satisfies(d -> {
                        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SUPPRESSED);
                        assertThat(d.getSuppressedReason())
                                .isEqualTo(SuppressionReason.USER_OPTED_OUT);
                    });
            verify(emailTransport, never()).send(anyString(), any());
        }

        /**
         * <strong>The rule a preference cannot override.</strong> A HIGH-priority notification is a security
         * or money-movement fact. Letting "tell me nothing" cover it would turn a convenience setting into a
         * way of hiding an account takeover from its victim.
         */
        @Test
        @DisplayName("ignores an opt-out for a high-priority notification")
        void highPriorityIgnoresOptOut() {
            preferences.setChannelEnabled(NotificationChannel.EMAIL, false);

            service.handle(new NotificationPayload(USER, null, null,
                            List.of(NotificationChannel.EMAIL), "payment.sent", variables(),
                            NotificationPriority.HIGH, DEDUP, "PAY-1"),
                    "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getStatus())
                    .as("a customer must not be able to silence being told their payment failed")
                    .isEqualTo(DeliveryStatus.SENT);
        }

        /**
         * PUSH has no transport because there is no mobile application and no device-token registry.
         * {@code NotificationPayload.security(...)} asks for it, so the absence is reached in normal
         * operation and must be explicable rather than silent.
         */
        @Test
        @DisplayName("suppresses push with a reason rather than dropping it")
        void suppressesPush() {
            when(transports.isDeliverable(NotificationChannel.PUSH)).thenReturn(false);

            service.handle(payload(NotificationChannel.PUSH), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.PUSH).getSuppressedReason())
                    .isEqualTo(SuppressionReason.CHANNEL_UNAVAILABLE);
        }

        @Test
        @DisplayName("suppresses SMS when the user never opted in")
        void suppressesSmsWithoutOptIn() {
            when(transports.isDeliverable(NotificationChannel.SMS)).thenReturn(true);

            service.handle(payload(NotificationChannel.SMS), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.SMS).getSuppressedReason())
                    .isIn(SuppressionReason.NO_DESTINATION, SuppressionReason.SMS_NOT_OPTED_IN,
                            SuppressionReason.USER_OPTED_OUT);
        }

        /**
         * Sending a guessed generic message about somebody's money either alarms them for no reason or
         * reassures them when it should not — and a notification cannot be recalled.
         */
        @Test
        @DisplayName("suppresses rather than improvising when no copy exists")
        void suppressesUnknownTemplate() {
            service.handle(new NotificationPayload(USER, null, null,
                            List.of(NotificationChannel.EMAIL), "payment.unknown", variables(),
                            NotificationPriority.NORMAL, DEDUP, "PAY-1"),
                    "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getSuppressedReason())
                    .isEqualTo(SuppressionReason.NO_TEMPLATE);
            verify(emailTransport, never()).send(anyString(), any());
        }

        @Test
        @DisplayName("counts a suppression with its reason, so NO_TEMPLATE is visible")
        void countsSuppression() {
            service.handle(new NotificationPayload(USER, null, null,
                            List.of(NotificationChannel.EMAIL), "payment.unknown", variables(),
                            NotificationPriority.NORMAL, DEDUP, "PAY-1"),
                    "evt-1", "payment-service");

            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_SUPPRESSED)
                    .tag("channel", "EMAIL").tag("reason", "NO_TEMPLATE")
                    .counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("suppresses email when the recipient has no address")
        void suppressesWithoutAddress() {
            when(recipients.resolve(anyString(), any(), any())).thenReturn(
                    new RecipientResolver.Recipient(USER, null, null, null, false));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getSuppressedReason())
                    .isEqualTo(SuppressionReason.NO_DESTINATION);
        }

        /**
         * A producer listing a channel twice would otherwise hit the unique constraint on
         * {@code (notification_id, channel)} and fail the whole notification over a cosmetic defect in
         * somebody else's list.
         */
        @Test
        @DisplayName("de-duplicates a repeated channel in the request")
        void deduplicatesRequestedChannels() {
            service.handle(new NotificationPayload(USER, null, null,
                            List.of(NotificationChannel.EMAIL, NotificationChannel.EMAIL),
                            "payment.sent", variables(), NotificationPriority.NORMAL, DEDUP, "PAY-1"),
                    "evt-1", "payment-service");

            assertThat(store.values()).hasSize(1);
        }

        /**
         * A notification worth publishing is worth delivering. Reading an empty list as "send nothing" would
         * be the worst available interpretation.
         */
        @Test
        @DisplayName("falls back to email and in-app when no channels are named")
        void defaultsChannels() {
            service.handle(new NotificationPayload(USER, null, null, List.of(), "payment.sent",
                            variables(), NotificationPriority.NORMAL, DEDUP, "PAY-1"),
                    "evt-1", "payment-service");

            assertThat(store.values()).extracting(NotificationDelivery::getChannel)
                    .containsExactlyInAnyOrder(NotificationChannel.EMAIL,
                            NotificationChannel.IN_APP);
        }

        /**
         * <strong>Regression test for a real defect.</strong> The greeting was originally supplied as
         * {@code " Alice"} — a bare name carrying a leading space — so a template could write
         * {@code Hi{{greetingName?}},} and degrade to "Hi," when no name was known.
         *
         * <p>It never worked. Every interpolated value is stripped as part of sanitising it, so the space
         * vanished and every email opened "HiAlice,". Nothing failed, no metric moved, and the only symptom
         * was a permanent record of a slightly wrong greeting in every notification the platform had ever
         * sent.
         */
        @Test
        @DisplayName("greets the recipient by first name, with the spacing intact")
        void greetsByFirstName() {
            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getRenderedBody())
                    .as("""
                            a value carrying its own significant whitespace cannot survive sanitising, \
                            because normalising whitespace is part of what sanitising means""")
                    .contains("Hi Alice,")
                    .doesNotContain("HiAlice")
                    .doesNotContain("Alice Smith");
        }

        /** No name known: a plain opening rather than a generic friendly placeholder. */
        @Test
        @DisplayName("opens plainly when the recipient's name is unknown")
        void greetsWithoutAName() {
            when(recipients.resolve(anyString(), any(), any())).thenReturn(
                    new RecipientResolver.Recipient(USER, "alice@example.com", null, null, false));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getRenderedBody())
                    .startsWith("Hi,")
                    .doesNotContain("Hi there");
        }
    }

    // ------------------------------------------------------------------------
    // failure handling
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("send failures")
    class SendFailures {

        /**
         * <strong>The contract.</strong> A send failure must never reach the Kafka error handler: the durable
         * record already exists, so the event has been fully processed and the retry worker owns it from here.
         */
        @Test
        @DisplayName("does not propagate a transport failure")
        void doesNotThrowOnTransportFailure() {
            when(emailTransport.send(anyString(), any())).thenThrow(
                    NotificationTransport.TransportException.transient_("SMTP_TIMEOUT", "slow"));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getStatus())
                    .as("the failure becomes committed state, not an exception")
                    .isEqualTo(DeliveryStatus.FAILED);
        }

        @Test
        @DisplayName("schedules a retry after a transient failure")
        void schedulesRetry() {
            when(emailTransport.send(anyString(), any())).thenThrow(
                    NotificationTransport.TransportException.transient_("SMTP_TIMEOUT", "slow"));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            NotificationDelivery delivery = delivery(NotificationChannel.EMAIL);
            assertThat(delivery.getNextAttemptAt()).isNotNull().isAfter(Instant.now());
            assertThat(delivery.getAttemptCount()).isEqualTo(1);
            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_RETRY_SCHEDULED)
                    .tag("channel", "EMAIL").counter().count()).isEqualTo(1.0);
        }

        /**
         * A malformed address will fail identically forever. Retrying it eight times delays the dead-letter
         * record an operator needs and produces eight identical errors.
         */
        @Test
        @DisplayName("dead-letters a permanent failure on the first attempt")
        void permanentFailureSkipsRetries() {
            when(emailTransport.send(anyString(), any())).thenThrow(
                    NotificationTransport.TransportException.permanent("INVALID_ADDRESS", "bad"));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            NotificationDelivery delivery = delivery(NotificationChannel.EMAIL);
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
            assertThat(delivery.getAttemptCount()).isEqualTo(1);
            assertThat(delivery.getNextAttemptAt()).isNull();
        }

        @Test
        @DisplayName("counts a dead letter, which is the number to alert on")
        void countsDeadLetter() {
            when(emailTransport.send(anyString(), any())).thenThrow(
                    NotificationTransport.TransportException.permanent("INVALID_ADDRESS", "bad"));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_DEAD_LETTERED)
                    .tag("channel", "EMAIL").counter().count()).isEqualTo(1.0);
        }

        /**
         * A transport that threw something it did not declare is treated as transient, because an unknown
         * fault might be — and the attempt budget bounds the optimism. Assuming permanence would discard a
         * notification over a bug in one transport.
         */
        @Test
        @DisplayName("treats an undeclared exception as transient rather than discarding the message")
        void unexpectedExceptionIsRetried() {
            when(emailTransport.send(anyString(), any()))
                    .thenThrow(new IllegalStateException("bug in transport"));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getStatus())
                    .isEqualTo(DeliveryStatus.FAILED);
        }

        @Test
        @DisplayName("dead-letters once the attempt budget is spent")
        void deadLettersWhenBudgetSpent() {
            properties.getRetry().setMaxAttempts(2);
            when(emailTransport.send(anyString(), any())).thenThrow(
                    NotificationTransport.TransportException.transient_("SMTP_TIMEOUT", "slow"));

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");
            String deliveryId = delivery(NotificationChannel.EMAIL).getDeliveryId();

            // The second attempt is what the retry worker would make.
            service.attempt(deliveryId);

            assertThat(store.get(deliveryId).getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
            assertThat(store.get(deliveryId).getAttemptCount()).isEqualTo(2);
        }

        /**
         * One channel's failure must not affect another's. If retry were per-notification, a transient SMS
         * failure would re-send the email that already arrived.
         */
        @Test
        @DisplayName("isolates one channel's failure from another's success")
        void channelsFailIndependently() {
            when(emailTransport.send(anyString(), any())).thenThrow(
                    NotificationTransport.TransportException.transient_("SMTP_TIMEOUT", "slow"));

            service.handle(payload(NotificationChannel.EMAIL, NotificationChannel.IN_APP),
                    "evt-1", "payment-service");

            assertThat(delivery(NotificationChannel.EMAIL).getStatus())
                    .isEqualTo(DeliveryStatus.FAILED);
            assertThat(delivery(NotificationChannel.IN_APP).getStatus())
                    .as("the in-app copy already arrived and must not be re-sent")
                    .isEqualTo(DeliveryStatus.SENT);
        }

        @Test
        @DisplayName("does not attempt a suppressed delivery")
        void suppressedIsNotAttempted() {
            preferences.setChannelEnabled(NotificationChannel.EMAIL, false);

            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            verify(emailTransport, never()).send(anyString(), any());
        }

        /** Reached when the worker claims a row another instance finished first. */
        @Test
        @DisplayName("does nothing when asked to attempt an already-terminal delivery")
        void terminalDeliveryIsNotReattempted() {
            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");
            String deliveryId = delivery(NotificationChannel.EMAIL).getDeliveryId();

            assertThat(service.attempt(deliveryId)).isTrue();
            verify(emailTransport).send(anyString(), any());
        }

        @Test
        @DisplayName("reports a delivery that no longer exists without throwing")
        void missingDeliveryIsNotAnError() {
            assertThat(service.attempt("NDL-DOES-NOT-EXIST")).isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // observability
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("observability")
    class Observability {

        @Test
        @DisplayName("counts attempts per channel and outcome")
        void countsAttempts() {
            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_DELIVERY_ATTEMPT)
                    .tag("channel", "EMAIL").tag("outcome", "success")
                    .counter().count()).isEqualTo(1.0);
            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_DELIVERY_LATENCY)
                    .tag("channel", "EMAIL").timer().count()).isEqualTo(1L);
        }

        /**
         * Counts attempts rather than notifications. A per-notification count would hide a channel that fails
         * every time, because the notification as a whole would still look delivered.
         */
        @Test
        @DisplayName("counts a failed attempt separately from a successful one")
        void countsFailures() {
            when(emailTransport.send(anyString(), any())).thenThrow(
                    NotificationTransport.TransportException.transient_("SMTP_TIMEOUT", "slow"));

            service.handle(payload(NotificationChannel.EMAIL, NotificationChannel.IN_APP),
                    "evt-1", "payment-service");

            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_DELIVERY_ATTEMPT)
                    .tag("channel", "EMAIL").tag("outcome", "failure")
                    .counter().count()).isEqualTo(1.0);
            assertThat(meterRegistry.get(MetricNames.NOTIFICATION_DELIVERY_ATTEMPT)
                    .tag("channel", "IN_APP").tag("outcome", "success")
                    .counter().count()).isEqualTo(1.0);
        }
    }

    // ------------------------------------------------------------------------
    // storage
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("stored record")
    class StoredRecord {

        /**
         * The rendered text is stored, so re-wording a template later cannot change the record of what this
         * customer read.
         */
        @Test
        @DisplayName("stores what was actually sent")
        void storesRenderedText() {
            service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

            NotificationDelivery delivery = delivery(NotificationChannel.EMAIL);
            assertThat(delivery.getRenderedSubject()).contains("2000.00").contains("INR");
            assertThat(delivery.getRenderedBody()).contains("TXN-1");
            assertThat(delivery.getDestination()).isEqualTo("alice@example.com");
        }

        /**
         * Redaction happens on the way in, not only at render time. A credential that was merely un-rendered
         * would still be sitting in the database and in the admin API response.
         */
        @Test
        @DisplayName("redacts a credential-looking variable before storing it")
        void redactsBeforeStorage() {
            Map<String, String> withSecret = new LinkedHashMap<>(variables());
            withSecret.put("otp", "123456");

            service.handle(new NotificationPayload(USER, null, null,
                            List.of(NotificationChannel.IN_APP), "payment.sent", withSecret,
                            NotificationPriority.NORMAL, DEDUP, "PAY-1"),
                    "evt-1", "payment-service");

            assertThat(storedNotification.getVariables())
                    .contains("[redacted]")
                    .doesNotContain("123456");
        }

        @Test
        @DisplayName("records the source event for traceability")
        void recordsProvenance() {
            service.handle(payload(NotificationChannel.IN_APP), "evt-99", "payment-service");

            assertThat(storedNotification.getSourceEventId()).isEqualTo("evt-99");
            assertThat(storedNotification.getSource()).isEqualTo("payment-service");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private Notification storedNotification;

    /**
     * A fake mutation service over an in-memory map.
     *
     * <p>Faked rather than mocked with rigid stubs so the real aggregates perform the state transitions.
     * That makes the assertions above about a delivery's actual status rather than about which method
     * happened to be called, which is the difference between testing behaviour and testing wiring.
     */
    private void wireFakeMutationService() {
        when(mutations.createIfAbsent(any(), any())).thenAnswer(invocation -> {
            Notification candidate = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Function<Notification, List<NotificationDelivery>> plan = invocation.getArgument(1);

            if (existingDedupKeys.contains(candidate.getDeduplicationKey())) {
                return new NotificationMutationService.Created(candidate, List.of(), true);
            }
            storedNotification = candidate;
            List<NotificationDelivery> rows = plan.apply(candidate);
            rows.forEach(row -> store.put(row.getDeliveryId(), row));
            return new NotificationMutationService.Created(candidate, rows, false);
        });

        when(mutations.findDelivery(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(store.get(invocation.<String>getArgument(0))));

        when(mutations.beginAttempt(anyString())).thenAnswer(invocation ->
                store.get(invocation.<String>getArgument(0)).beginAttempt());

        org.mockito.Mockito.doAnswer(invocation -> {
            store.get(invocation.<String>getArgument(0)).markSent();
            return null;
        }).when(mutations).recordSuccess(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                any(), org.mockito.ArgumentMatchers.anyLong());

        org.mockito.Mockito.doAnswer(invocation -> {
            store.get(invocation.<String>getArgument(0)).markFailed(
                    invocation.getArgument(2) + ": " + invocation.getArgument(3),
                    invocation.getArgument(5));
            return null;
        }).when(mutations).recordRetryableFailure(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any());

        org.mockito.Mockito.doAnswer(invocation -> {
            store.get(invocation.<String>getArgument(0)).markDeadLettered(
                    invocation.getArgument(2) + ": " + invocation.getArgument(3));
            return null;
        }).when(mutations).recordDeadLetter(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private NotificationDelivery delivery(NotificationChannel channel) {
        return store.values().stream()
                .filter(row -> row.getChannel() == channel)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no delivery recorded for " + channel));
    }

    private static NotificationPayload payload(NotificationChannel... channels) {
        List<NotificationChannel> requested = channels.length == 0
                ? List.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP)
                : List.of(channels);

        return new NotificationPayload(USER, null, null, requested, "payment.sent", variables(),
                NotificationPriority.NORMAL, DEDUP, "PAY-1");
    }

    private static Map<String, String> variables() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("transactionId", "TXN-1");
        variables.put("amount", "2000.00");
        variables.put("currency", "INR");
        variables.put("description", "Rent");
        return variables;
    }

    private static NotificationTemplate template() {
        Map<NotificationChannel, String> bodies = new LinkedHashMap<>();
        bodies.put(NotificationChannel.EMAIL,
                "{{greeting?}}\n\nYou sent {{amount}} {{currency}}. Ref {{transactionId}}.");
        bodies.put(NotificationChannel.IN_APP,
                "You sent {{amount}} {{currency}}. Ref {{transactionId}}.");
        bodies.put(NotificationChannel.SMS,
                "AegisPay: you sent {{amount}} {{currency}}. Ref {{transactionId}}.");
        return new NotificationTemplate("payment.sent",
                "You sent {{amount}} {{currency}}", bodies);
    }

    /** Keeps the unused-duration import honest for the retry assertions above. */
    @Test
    @DisplayName("uses the configured backoff for the first retry")
    void usesConfiguredBackoff() {
        properties.getRetry().setJitter(0.0);
        properties.getRetry().setInitialBackoff(Duration.ofSeconds(45));
        when(emailTransport.send(anyString(), any())).thenThrow(
                NotificationTransport.TransportException.transient_("SMTP_TIMEOUT", "slow"));

        service.handle(payload(NotificationChannel.EMAIL), "evt-1", "payment-service");

        assertThat(delivery(NotificationChannel.EMAIL).getNextAttemptAt())
                .isAfter(Instant.now().plus(Duration.ofSeconds(40)));
    }
}
