package com.aegispay.notification;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import com.aegispay.common.events.payload.NotificationPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The notification flow end to end, against a real database, broker and identity service.
 *
 * <p>What is exercised here and nowhere else: the unique constraints that make delivery effectively-once, the
 * CHECK constraints that encode rules Java also enforces, the append-only triggers, and the authorisation on
 * the customer-facing surface.
 */
class NotificationFlowIT extends AbstractNotificationIntegrationTest {

    @Override
    protected void requestNotification(NotificationPayload payload) {
        super.requestNotification(payload);
        awaitUntil("delivery work for " + payload.deduplicationKey(), Duration.ofSeconds(30),
                () -> countRetryableDeliveries(payload.deduplicationKey()) == 0);
    }

    // ------------------------------------------------------------------------
    // the event path
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("notification-required")
    class Consuming {

        @Test
        @DisplayName("renders and delivers a requested notification")
        void deliversNotification() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            String notificationId = notificationIdFor(payload.deduplicationKey());
            awaitUntil("both channels to be attempted", Duration.ofSeconds(30),
                    () -> countDeliveries(notificationId) == 2);

            assertThat(deliveryStatus(notificationId, NotificationChannel.EMAIL)).isEqualTo("SENT");
            assertThat(deliveryStatus(notificationId, NotificationChannel.IN_APP)).isEqualTo("SENT");
        }

        /**
         * The rendered text is stored, so re-wording a template later cannot change the record of what this
         * customer read.
         */
        @Test
        @DisplayName("stores the text that was actually sent")
        void storesRenderedText() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            assertThat(renderedBody(notificationId, NotificationChannel.EMAIL))
                    .contains("2000.00")
                    .contains("INR")
                    .contains("Hi Alice,");
        }

        /**
         * <strong>The guarantee this service exists for.</strong> Kafka delivers at least once, and payment
         * events are redelivered routinely. A customer must not be told twice that their payment succeeded.
         */
        @Test
        @DisplayName("creates one notification however many times the event arrives")
        void isIdempotent() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);
            publish(payload);
            publish(payload);
            sleepBriefly();

            assertThat(countNotifications(payload.deduplicationKey())).isEqualTo(1L);
            assertThat(countDeliveries(notificationIdFor(payload.deduplicationKey())))
                    .as("two channels, not six")
                    .isEqualTo(2L);
        }

        /**
         * The application-level check is only an optimisation — two consumer threads on two instances would
         * both pass it. This asserts the constraint that actually arbitrates.
         */
        @Test
        @DisplayName("refuses a second notification with the same deduplication key")
        void dedupKeyIsUnique() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO notifications (notification_id, deduplication_key, recipient_user_id,
                                               template_key, priority, payment_id)
                    VALUES ('NTF-DUPLICATE', ?, ?, 'payment.sent', 'NORMAL', ?)
                    """, payload.deduplicationKey(), userId, paymentId))
                    .as("only the database can arbitrate between two instances that both passed the "
                            + "application-level check")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /** One attempt-set per channel. Without this a retry could race a redelivery into a second send. */
        @Test
        @DisplayName("refuses a second delivery row for the same channel")
        void oneDeliveryPerChannel() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO notification_deliveries (delivery_id, notification_id,
                            recipient_user_id, priority, channel, status)
                    VALUES ('NDL-DUPLICATE', ?, ?, 'NORMAL', 'EMAIL', 'PENDING')
                    """, notificationId, userId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * Credential-looking variables are dropped on the way in, not merely at render time. This table is read
         * by support staff and returned by an admin API, so a value that was only un-rendered would still be
         * exposed.
         */
        @Test
        @DisplayName("redacts a credential-looking variable before storing it")
        void redactsSecretsOnTheWayIn() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = new NotificationPayload(userId, null, null,
                    List.of(NotificationChannel.IN_APP), "payment.sent",
                    Map.of("transactionId", "TXN-1", "amount", "10.00", "currency", "INR",
                            "description", "Rent", "otp", "123456"),
                    NotificationPriority.NORMAL, paymentId + ":sent:" + userId, paymentId);

            requestNotification(payload);

            assertThat(variablesFor(payload.deduplicationKey()))
                    .contains("[redacted]")
                    .doesNotContain("123456");
        }

        /**
         * A malformed payload is dead-lettered by the shared Kafka handler on the first attempt, because the
         * service throws {@link IllegalArgumentException} — which that handler treats as non-retryable. No
         * notification row appears, and the partition keeps moving.
         */
        @Test
        @DisplayName("records nothing for a payload with no recipient")
        void unusablePayloadRecordsNothing() {
            long before = countNotifications();

            publish(new NotificationPayload(null, null, null,
                    List.of(NotificationChannel.EMAIL), "payment.sent", Map.of(),
                    NotificationPriority.NORMAL, "dedup-with-no-recipient", newPaymentId()));
            sleepBriefly();

            assertThat(countNotifications()).isEqualTo(before);
        }
    }

    // ------------------------------------------------------------------------
    // suppression
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("suppression")
    class Suppression {

        /**
         * PUSH has no transport: there is no mobile application and no device-token registry.
         * {@code NotificationPayload.security(...)} asks for it, so the row must explain where it went rather
         * than the channel silently vanishing.
         */
        @Test
        @DisplayName("records push as suppressed with a reason rather than dropping it")
        void suppressesPush() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.IN_APP, NotificationChannel.PUSH);
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            awaitUntil("both rows", Duration.ofSeconds(30),
                    () -> countDeliveries(notificationId) == 2);

            assertThat(deliveryStatus(notificationId, NotificationChannel.PUSH))
                    .isEqualTo("SUPPRESSED");
            assertThat(suppressedReason(notificationId, NotificationChannel.PUSH))
                    .isEqualTo("CHANNEL_UNAVAILABLE");
        }

        /** SMS with no opted-in number: suppressed, not failed and not retried. */
        @Test
        @DisplayName("suppresses SMS for a user who never opted in")
        void suppressesSmsWithoutOptIn() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.IN_APP, NotificationChannel.SMS);
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            awaitUntil("both rows", Duration.ofSeconds(30),
                    () -> countDeliveries(notificationId) == 2);

            assertThat(deliveryStatus(notificationId, NotificationChannel.SMS))
                    .isEqualTo("SUPPRESSED");
            assertThat(suppressedReason(notificationId, NotificationChannel.SMS)).isNotNull();
        }

        /**
         * A failed address lookup suppresses email and leaves the in-app copy intact. It must not fail the
         * event: rethrowing would push it into a Kafka retry and then into a topic nothing drains, turning a
         * brief identity-service blip into permanently lost notifications.
         */
        @Test
        @DisplayName("suppresses email when the address cannot be resolved, without losing the event")
        void suppressesWhenLookupFails() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.setFailing(true);

            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            awaitUntil("both rows", Duration.ofSeconds(30),
                    () -> countDeliveries(notificationId) == 2);

            assertThat(deliveryStatus(notificationId, NotificationChannel.EMAIL))
                    .isEqualTo("SUPPRESSED");
            assertThat(deliveryStatus(notificationId, NotificationChannel.IN_APP))
                    .as("the in-app copy does not depend on an external lookup")
                    .isEqualTo("SENT");
        }

        /**
         * The database requires a reason on any suppressed row. A suppressed delivery with no reason is
         * indistinguishable from a bug, and support would have nothing to tell the customer.
         */
        @Test
        @DisplayName("refuses a suppressed row with no reason")
        void suppressionNeedsAReason() {
            String userId = newUserId();

            jdbc.update("""
                    INSERT INTO notifications (notification_id, deduplication_key, recipient_user_id,
                                               template_key, priority)
                    VALUES ('NTF-CHECK1', 'check-suppress-reason', ?, 'payment.sent', 'NORMAL')
                    """, userId);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO notification_deliveries (delivery_id, notification_id,
                            recipient_user_id, priority, channel, status)
                    VALUES ('NDL-CHECK1', 'NTF-CHECK1', ?, 'NORMAL', 'SMS', 'SUPPRESSED')
                    """, userId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ------------------------------------------------------------------------
    // schema-enforced rules
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("schema guarantees")
    class SchemaGuarantees {

        /**
         * A notification records something a customer was already told. Editing it only makes the record wrong
         * — the customer still read what they read.
         */
        @Test
        @DisplayName("refuses an update to a stored notification")
        void notificationsAreAppendOnly() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE notifications SET template_key = 'payment.failed' WHERE deduplication_key = ?",
                    payload.deduplicationKey()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("refuses a delete of a stored notification")
        void notificationsCannotBeDeleted() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM notifications WHERE deduplication_key = ?",
                    payload.deduplicationKey()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * Replaying a dead delivery overwrites its last error, so without an immutable attempt trail the
         * history of why it failed would be destroyed by the act of investigating it.
         */
        @Test
        @DisplayName("refuses an update to the attempt trail")
        void attemptsAreAppendOnly() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.EMAIL);
            awaitUntil("an attempt to be recorded", Duration.ofSeconds(30),
                    () -> countAttempts(deliveryId) >= 1);

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE notification_delivery_attempts SET succeeded = false WHERE delivery_id = ?",
                    deliveryId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * Nothing on this platform tracks whether an email was opened, so the schema refuses to let anything
         * claim otherwise — including a direct write that bypasses the Java aggregate.
         */
        @Test
        @DisplayName("refuses a read receipt on anything but in-app")
        void onlyInAppCanBeRead() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            String emailDelivery = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.EMAIL);

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE notification_deliveries SET read_at = now() WHERE delivery_id = ?",
                    emailDelivery))
                    .as("nothing here tracks whether an email was opened, so nothing may claim it")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * A terminal row must carry no schedule. One that did would be retried forever and never appear in the
         * dead-letter queue, so the failure would be permanently invisible.
         */
        @Test
        @DisplayName("refuses a schedule on a terminal delivery")
        void terminalRowsCarryNoSchedule() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.EMAIL);
            awaitUntil("the delivery to be sent", Duration.ofSeconds(30),
                    () -> "SENT".equals(statusOf(deliveryId)));

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE notification_deliveries SET next_attempt_at = now() WHERE delivery_id = ?",
                    deliveryId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * SMS cannot be on without a number and a consent record. Enforced in the schema because an SMS to
         * somebody who did not opt in is a regulatory problem, not a bug to be found in review.
         */
        @Test
        @DisplayName("refuses SMS enabled with no number or consent")
        void smsNeedsConsent() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO notification_preferences (user_id, sms_enabled)
                    VALUES (?, TRUE)
                    """, newUserId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("refuses a phone number that is not E.164")
        void phoneMustBeE164() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO notification_preferences (user_id, phone_e164)
                    VALUES (?, '9876543210')
                    """, newUserId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ------------------------------------------------------------------------
    // the customer API
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("customer API")
    class CustomerApi {

        @Test
        @DisplayName("shows the in-app notification in the caller's inbox")
        void inboxShowsNotification() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            awaitUntil("the in-app copy to be stored", Duration.ofSeconds(30),
                    () -> "SENT".equals(deliveryStatus(
                            notificationIdFor(payload.deduplicationKey()),
                            NotificationChannel.IN_APP)));

            Response<Map> response = get("/api/notifications", customerToken(userId), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat((Integer) response.body().get("totalElements")).isEqualTo(1);
            assertThat(response.body().get("unread")).isNotNull();
        }

        /**
         * The inbox is scoped by the token, not by a parameter. Another customer must see nothing, because a
         * notification carries an amount, a reference and a note from a counterparty.
         */
        @Test
        @DisplayName("shows nothing to a different customer")
        void inboxIsScopedToTheCaller() {
            String owner = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(owner, "alice@example.com", "Alice Smith");
            requestNotification(transactional(owner, paymentId, "sent"));

            Response<Map> response = get("/api/notifications", customerToken(newUserId()), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat((Integer) response.body().get("totalElements")).isZero();
        }

        @Test
        @DisplayName("marks an in-app notification read, idempotently")
        void marksRead() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);

            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.IN_APP);
            awaitUntil("the in-app row", Duration.ofSeconds(30), () -> deliveryId != null);

            assertThat(post("/api/notifications/" + deliveryId + "/read", null,
                    customerToken(userId), Map.class).status()).isEqualTo(HttpStatus.OK);
            assertThat(post("/api/notifications/" + deliveryId + "/read", null,
                    customerToken(userId), Map.class).status()).isEqualTo(HttpStatus.OK);

            Response<Map> unread = get("/api/notifications/unread-count",
                    customerToken(userId), Map.class);
            assertThat((Integer) unread.body().get("unread")).isZero();
        }

        /**
         * 404 rather than 403 for somebody else's notification. A 403 would confirm that the id exists and
         * belongs to someone, which is more than the caller is entitled to learn.
         */
        @Test
        @DisplayName("reports another customer's notification as not found")
        void cannotReadSomebodyElsesNotification() {
            String owner = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(owner, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(owner, paymentId, "sent");
            requestNotification(payload);

            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.IN_APP);

            assertThat(post("/api/notifications/" + deliveryId + "/read", null,
                    customerToken(newUserId()), Map.class).status())
                    .as("a 403 would confirm the id exists and belongs to somebody")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("refuses an unauthenticated request")
        void refusesAnonymous() {
            assertThat(get("/api/notifications", null, Map.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("returns default preferences without creating a row")
        void preferencesDefaultWithoutWriting() {
            String userId = newUserId();

            Response<Map> response = get("/api/notifications/preferences",
                    customerToken(userId), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body().get("emailEnabled")).isEqualTo(true);
            assertThat(response.body().get("smsEnabled")).isEqualTo(false);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM notification_preferences WHERE user_id = ?",
                    Long.class, userId))
                    .as("a read that wrote would put a write lock on this table in the delivery path")
                    .isZero();
        }

        @Test
        @DisplayName("records an SMS opt-in with its consent timestamp")
        void smsOptIn() {
            String userId = newUserId();

            Response<Map> response = post("/api/notifications/preferences/sms",
                    Map.of("phoneE164", "+919876543210"), customerToken(userId), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body().get("smsEnabled")).isEqualTo(true);
            assertThat(response.body().get("smsOptInAt")).isNotNull();
            assertThat((String) response.body().get("phone"))
                    .as("masked even for its owner: they already know it, and returning it in full "
                            + "puts it in every cache and proxy log")
                    .doesNotContain("9876543210");
        }

        @Test
        @DisplayName("refuses a phone number that is not E.164")
        void rejectsBadPhoneNumber() {
            assertThat(post("/api/notifications/preferences/sms",
                    Map.of("phoneE164", "9876543210"), customerToken(newUserId()), Map.class)
                    .status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("forgets the number when consent is withdrawn")
        void smsOptOutForgetsTheNumber() {
            String userId = newUserId();
            post("/api/notifications/preferences/sms", Map.of("phoneE164", "+919876543210"),
                    customerToken(userId), Map.class);

            Response<Map> response = delete("/api/notifications/preferences/sms",
                    customerToken(userId), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body().get("smsEnabled")).isEqualTo(false);
            assertThat(jdbc.queryForObject(
                    "SELECT phone_e164 FROM notification_preferences WHERE user_id = ?",
                    String.class, userId))
                    .as("holding a number whose stated purpose has been withdrawn is what the "
                            + "consent record exists to prevent")
                    .isNull();
        }

        /**
         * A customer who opted out of routine email still gets a HIGH-priority one. Otherwise a convenience
         * setting would become a way of hiding an account takeover from its victim.
         */
        @Test
        @DisplayName("still delivers a high-priority notification to somebody who opted out")
        void highPriorityIgnoresOptOut() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            put("/api/notifications/preferences", Map.of("emailEnabled", false),
                    customerToken(userId), Map.class);

            NotificationPayload highPriority = new NotificationPayload(userId, null, null,
                    List.of(NotificationChannel.EMAIL), "payment.failed",
                    Map.of("transactionId", "TXN-1", "amount", "2000.00", "currency", "INR",
                            "description", ""),
                    NotificationPriority.HIGH, paymentId + ":failed:" + userId, paymentId);

            requestNotification(highPriority);
            String notificationId = notificationIdFor(highPriority.deduplicationKey());

            awaitUntil("the email to be sent", Duration.ofSeconds(30),
                    () -> "SENT".equals(deliveryStatus(notificationId, NotificationChannel.EMAIL)));

            assertThat(deliveryStatus(notificationId, NotificationChannel.EMAIL))
                    .as("a customer must not be able to silence being told their payment failed")
                    .isEqualTo("SENT");
        }

        @Test
        @DisplayName("suppresses a routine notification for somebody who opted out")
        void routinePriorityHonoursOptOut() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            put("/api/notifications/preferences", Map.of("emailEnabled", false),
                    customerToken(userId), Map.class);

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.EMAIL, NotificationChannel.IN_APP);
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            awaitUntil("both rows", Duration.ofSeconds(30),
                    () -> countDeliveries(notificationId) == 2);

            assertThat(deliveryStatus(notificationId, NotificationChannel.EMAIL))
                    .isEqualTo("SUPPRESSED");
            assertThat(suppressedReason(notificationId, NotificationChannel.EMAIL))
                    .isEqualTo("USER_OPTED_OUT");
        }

        /** Staff only: the detail view carries the rendered body and every attempt. */
        @Test
        @DisplayName("keeps the detail view staff-only")
        void detailIsStaffOnly() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            NotificationPayload payload = transactional(userId, paymentId, "sent");
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            assertThat(get("/api/notifications/" + notificationId + "/detail",
                    customerToken(userId), Map.class).status())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/api/notifications/" + notificationId + "/detail",
                    analystToken(), Map.class).status())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ------------------------------------------------------------------------
    // address resolution
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("address resolution")
    class AddressResolution {

        /**
         * A completed payment produces two notifications, each fanning out to two channels, so the same address
         * is asked for several times within a second. Caching also means a brief auth-service outage does not
         * stop every notification on the platform.
         */
        @Test
        @DisplayName("resolves an address once for several notifications to the same user")
        void cachesTheLookup() {
            String userId = newUserId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            requestNotification(transactional(userId, newPaymentId(), "sent"));
            int afterFirst = AUTH.lookups();

            requestNotification(transactional(userId, newPaymentId(), "sent"));

            assertThat(AUTH.lookups())
                    .as("a burst of notifications about one payment should resolve the address once")
                    .isEqualTo(afterFirst);
        }

        /**
         * An address on the event wins over a lookup. A producer that took the trouble to include one may be
         * notifying an address that is not the account's, and overriding it would send the message to the wrong
         * person.
         */
        @Test
        @DisplayName("prefers an address supplied on the event over the account's")
        void suppliedAddressWins() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "account@example.com", "Alice Smith");

            NotificationPayload payload = new NotificationPayload(userId, "supplied@example.com",
                    null, List.of(NotificationChannel.EMAIL), "payment.sent",
                    Map.of("transactionId", "TXN-1", "amount", "10.00", "currency", "INR",
                            "description", ""),
                    NotificationPriority.NORMAL, paymentId + ":sent:" + userId, paymentId);

            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());
            awaitUntil("the email row", Duration.ofSeconds(30),
                    () -> deliveryIdFor(notificationId, NotificationChannel.EMAIL) != null);

            assertThat(jdbc.queryForObject("""
                    SELECT destination FROM notification_deliveries
                    WHERE notification_id = ? AND channel = 'EMAIL'
                    """, String.class, notificationId))
                    .isEqualTo("supplied@example.com");
        }
    }
}
