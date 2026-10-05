package com.aegispay.notification;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.events.payload.NotificationPayload;
import com.aegispay.notification.config.NotificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry queue and the dead-letter path.
 *
 * <h2>Why this is a separate class from {@link NotificationFlowIT}</h2>
 *
 * <p>Because it needs the transports to fail, and the flow test needs them to work. Failure is injected through
 * configuration rather than by mocking a bean, so the real transport, the real scheduled worker and the real
 * {@code FOR UPDATE SKIP LOCKED} claim all take part.
 *
 * <h2>What this proves that nothing else can</h2>
 *
 * <p>That a delivery failure is <strong>state, not an exception</strong>. The Kafka offset advances, the
 * partition keeps moving, and a scheduled worker owns the notification from there. Every part of that lives in
 * a different place — the listener, the service, the database schema, the worker — and only running them
 * together shows they agree.
 *
 * <p>It is also the only place the dead-letter mechanism is exercised at all. A queue that only fills during a
 * real provider outage is a mechanism nobody has ever seen work, which is precisely why the simulated failure
 * rate exists.
 */
@TestPropertySource(properties = {
        // Every send fails. The point is the retry path, not the arithmetic.
        "aegis.notification.transports.simulated-failure-rate=1.0",
        // Start and sweep quickly only in the suite that verifies scheduled retries.
        "aegis.notification.retry.poll-interval=300ms",
        "aegis.notification.retry.initial-delay=300ms",
        // Small budget so a delivery reaches the dead-letter queue inside a test rather than half an hour
        // later. The relationship between the budget and the schedule is asserted by DeliveryBackoffTest.
        "aegis.notification.retry.max-attempts=3",
        "aegis.notification.retry.replay-attempt-grant=2"
})
class NotificationRetryIT extends AbstractNotificationIntegrationTest {

    @Autowired
    private NotificationProperties properties;

    /**
     * The in-app transport does not consult the failure rate — it has no external system to fail — so tests
     * here use email, and the in-app row is expected to succeed alongside it.
     */
    @AfterEach
    void restoreTransports() {
        properties.getTransports().setSimulatedFailureRate(1.0);
    }

    // ------------------------------------------------------------------------
    // failure becomes state
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("a failed send")
    class FailedSend {

        /**
         * <strong>The central contract.</strong> A send failure must not reach the Kafka error handler. If it
         * did, the partition would back off behind a slow provider, the redelivery would re-attempt channels
         * that already succeeded, and the event would finally land in a topic nothing drains.
         */
        @Test
        @DisplayName("is committed as a scheduled retry rather than propagated")
        void failureBecomesScheduledState() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.EMAIL);
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());
            String deliveryId = deliveryIdFor(notificationId, NotificationChannel.EMAIL);

            awaitUntil("the email delivery to fail", Duration.ofSeconds(30),
                    () -> "FAILED".equals(statusOf(deliveryId))
                            || "DEAD_LETTERED".equals(statusOf(deliveryId)));

            assertThat(jdbc.queryForObject("""
                    SELECT next_attempt_at IS NOT NULL FROM notification_deliveries
                    WHERE delivery_id = ? AND status = 'FAILED'
                    """, Boolean.class, deliveryId))
                    .satisfiesAnyOf(
                            scheduled -> assertThat(scheduled).isTrue(),
                            // Already dead by the time the assertion ran, with a budget of three.
                            scheduled -> assertThat(statusOf(deliveryId))
                                    .isEqualTo("DEAD_LETTERED"));
        }

        /**
         * One channel's failure must not affect another's. If retry were per-notification, a transient email
         * failure would re-send the in-app copy that already arrived.
         */
        @Test
        @DisplayName("does not disturb a channel that already succeeded")
        void channelsAreIndependent() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.EMAIL, NotificationChannel.IN_APP);
            requestNotification(payload);
            String notificationId = notificationIdFor(payload.deduplicationKey());

            awaitUntil("the in-app copy to be delivered", Duration.ofSeconds(30),
                    () -> "SENT".equals(deliveryStatus(notificationId,
                            NotificationChannel.IN_APP)));

            String inAppDelivery = deliveryIdFor(notificationId, NotificationChannel.IN_APP);
            sleepBriefly();

            assertThat(attemptCount(inAppDelivery))
                    .as("""
                            the in-app message arrived on the first try and must not be re-sent while \
                            the email is being retried""")
                    .isEqualTo(1);
        }

        /**
         * The attempt trail is what tells an operator whether a delivery failed four times the same way — a
         * host that is down — or four different ways, which is something else entirely.
         */
        @Test
        @DisplayName("records every attempt, not just the last")
        void everyAttemptIsRecorded() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.EMAIL);
            requestNotification(payload);
            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.EMAIL);

            awaitUntil("the retry budget to be spent", Duration.ofSeconds(60),
                    () -> "DEAD_LETTERED".equals(statusOf(deliveryId)));

            assertThat(countAttempts(deliveryId))
                    .as("one row per transport call, so a pattern of failures is visible")
                    .isEqualTo(3L);
            assertThat(jdbc.queryForList("""
                    SELECT error_code FROM notification_delivery_attempts
                    WHERE delivery_id = ? ORDER BY attempt_number
                    """, String.class, deliveryId))
                    .allSatisfy(code -> assertThat(code).isEqualTo("SIMULATED_SMTP_FAILURE"));
        }
    }

    // ------------------------------------------------------------------------
    // the worker
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the retry worker")
    class Worker {

        /**
         * The scheduled worker picks up a failed delivery without any event being redelivered. That is the
         * whole point of the design: recovery is a database sweep rather than a Kafka replay.
         */
        @Test
        @DisplayName("retries a failed delivery without the event being redelivered")
        void workerRetriesWithoutRedelivery() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.EMAIL);
            requestNotification(payload);
            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.EMAIL);

            awaitUntil("a second attempt", Duration.ofSeconds(60),
                    () -> attemptCount(deliveryId) >= 2);

            assertThat(attemptCount(deliveryId))
                    .as("nothing republished the event; the worker found the row itself")
                    .isGreaterThanOrEqualTo(2);
        }

        /**
         * Eventually gives up, and stops scheduling when it does. A row that kept being retried forever would
         * never appear in the dead-letter queue, so the failure would stay invisible.
         */
        @Test
        @DisplayName("dead-letters a delivery once the budget is spent, and stops scheduling it")
        void deadLettersAndStops() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.EMAIL);
            requestNotification(payload);
            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.EMAIL);

            awaitUntil("the delivery to be dead-lettered", Duration.ofSeconds(60),
                    () -> "DEAD_LETTERED".equals(statusOf(deliveryId)));

            assertThat(attemptCount(deliveryId)).isEqualTo(3);
            assertThat(jdbc.queryForObject("""
                    SELECT next_attempt_at FROM notification_deliveries WHERE delivery_id = ?
                    """, java.sql.Timestamp.class, deliveryId))
                    .as("a dead row must leave the work queue, or it never reaches the queue an "
                            + "operator reads")
                    .isNull();

            int countedAfter = attemptCount(deliveryId);
            sleepBriefly();
            assertThat(attemptCount(deliveryId))
                    .as("nothing retries a dead delivery on its own")
                    .isEqualTo(countedAfter);
        }
    }

    // ------------------------------------------------------------------------
    // the admin surface
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("dead-letter queue")
    class DeadLetterQueue {

        @Test
        @DisplayName("lists a dead delivery for an operator")
        void listsDeadLetters() {
            String deliveryId = aDeadDelivery();

            Response<Map> response = get("/api/admin/notifications/dead-letters",
                    adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat((Integer) response.body().get("totalElements")).isPositive();
            assertThat(response.body().toString()).contains(deliveryId);
        }

        /** Addresses are masked in the admin view as well as in logs. */
        @Test
        @DisplayName("masks the address in the dead-letter listing")
        void masksAddresses() {
            aDeadDelivery();

            Response<Map> response = get("/api/admin/notifications/dead-letters",
                    adminToken(), Map.class);

            assertThat(response.body().toString())
                    .doesNotContain("alice@example.com")
                    .contains("a***@example.com");
        }

        @Test
        @DisplayName("shows every attempt on a dead delivery")
        void showsAttemptHistory() {
            String deliveryId = aDeadDelivery();

            Response<Map> response = get(
                    "/api/admin/notifications/deliveries/" + deliveryId + "/attempts",
                    adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat((java.util.List<?>) response.body().get("attempts")).hasSize(3);
        }

        @Test
        @DisplayName("keeps the dead-letter queue staff-only")
        void staffOnly() {
            assertThat(get("/api/admin/notifications/dead-letters",
                    customerToken(newUserId()), Map.class).status())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/api/admin/notifications/dead-letters", null, Map.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * A replay puts the delivery back in the queue with a bounded extra budget. The transport is still
         * failing here, so it fails again — which is the useful assertion: the replay is a retry, not a
         * guarantee.
         */
        @Test
        @DisplayName("replays a dead delivery with a bounded extra budget")
        void replayGrantsBoundedBudget() {
            String deliveryId = aDeadDelivery();

            Response<Map> response = post(
                    "/api/admin/notifications/dead-letters/" + deliveryId + "/replay", null,
                    adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(statusOf(deliveryId)).isIn("FAILED", "DEAD_LETTERED");
            assertThat(attemptCount(deliveryId))
                    .as("""
                            a grant of two against a budget of three resumes from one, so a permanently \
                            broken destination cannot be replayed indefinitely""")
                    .isLessThan(3);
        }

        /**
         * A replay succeeds once the underlying cause is fixed. This is the case an operator actually cares
         * about, and it exercises the whole loop: dead, fixed, replayed, delivered.
         */
        @Test
        @DisplayName("delivers on replay once the transport is working again")
        void replaySucceedsAfterTheCauseIsFixed() {
            String deliveryId = aDeadDelivery();

            properties.getTransports().setSimulatedFailureRate(0.0);
            post("/api/admin/notifications/dead-letters/" + deliveryId + "/replay", null,
                    adminToken(), Map.class);

            awaitUntil("the replayed delivery to be sent", Duration.ofSeconds(60),
                    () -> "SENT".equals(statusOf(deliveryId)));

            assertThat(statusOf(deliveryId)).isEqualTo("SENT");
        }

        @Test
        @DisplayName("refuses to replay a delivery that is not dead")
        void refusesToReplayALiveDelivery() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            properties.getTransports().setSimulatedFailureRate(0.0);

            NotificationPayload payload = withChannels(userId, paymentId,
                    NotificationChannel.IN_APP);
            requestNotification(payload);
            String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                    NotificationChannel.IN_APP);

            awaitUntil("the delivery to be sent", Duration.ofSeconds(30),
                    () -> "SENT".equals(statusOf(deliveryId)));

            assertThat(post("/api/admin/notifications/dead-letters/" + deliveryId + "/replay",
                    null, adminToken(), Map.class).status())
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("returns 404 for a delivery that does not exist")
        void unknownDelivery() {
            assertThat(post("/api/admin/notifications/dead-letters/NDL-NOPE/replay", null,
                    adminToken(), Map.class).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------------
    // oversight
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("oversight")
    class Oversight {

        /**
         * The engine view states plainly that the transports are simulated and that PUSH cannot be delivered.
         * A service that let an operator assume otherwise would have them chasing a push notification that was
         * never going to arrive.
         */
        @Test
        @DisplayName("discloses that the transports are simulated and push is unavailable")
        void enginePublishesTheTruth() {
            Response<Map> response = get("/api/admin/notifications/engine", adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body().get("transportsSimulated")).isEqualTo(true);
            assertThat(response.body().get("unavailableChannels").toString()).contains("PUSH");
            assertThat((java.util.List<?>) response.body().get("templateKeys")).isNotEmpty();
        }

        @Test
        @DisplayName("counts dead letters in the overview")
        void statsCountDeadLetters() {
            aDeadDelivery();

            Response<Map> response = get("/api/admin/notifications/stats", adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat((Integer) response.body().get("deadLetteredTotal")).isPositive();
            assertThat(response.body().get("byChannelAndStatus").toString())
                    .contains("EMAIL:DEAD_LETTERED");
        }

        @Test
        @DisplayName("shows every notification sent about one payment")
        void showsNotificationsForAPayment() {
            String userId = newUserId();
            String paymentId = newPaymentId();
            AUTH.register(userId, "alice@example.com", "Alice Smith");
            requestNotification(withChannels(userId, paymentId, NotificationChannel.IN_APP));

            Response<Map> response = get("/api/admin/notifications/payments/" + paymentId,
                    adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat((Integer) response.body().get("totalElements")).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** Drives one email delivery all the way to the dead-letter queue. */
    private String aDeadDelivery() {
        String userId = newUserId();
        String paymentId = newPaymentId();
        AUTH.register(userId, "alice@example.com", "Alice Smith");

        NotificationPayload payload = withChannels(userId, paymentId, NotificationChannel.EMAIL);
        requestNotification(payload);
        String deliveryId = deliveryIdFor(notificationIdFor(payload.deduplicationKey()),
                NotificationChannel.EMAIL);

        awaitUntil("the delivery to be dead-lettered", Duration.ofSeconds(60),
                () -> "DEAD_LETTERED".equals(statusOf(deliveryId)));
        return deliveryId;
    }
}
