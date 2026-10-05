package com.aegispay.notification.domain;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The delivery state machine, and the invariants that keep the retry queue honest.
 *
 * <p>Most of these guard a single property: the set of rows the worker will look at again must be exactly
 * the set of rows that should be retried. A terminal row with a schedule still attached would be retried
 * forever and never appear in the dead-letter queue, so the failure would be permanently invisible. A
 * failed row with no schedule would be the opposite — never retried and never reported.
 */
class NotificationDeliveryTest {

    @Nested
    @DisplayName("creation")
    class Creation {

        /**
         * Due immediately, not null. If the process dies between the insert and the first send, the row is
         * already durable and the worker picks it up — without a schedule that notification would be lost
         * with nothing recording that it should have existed.
         */
        @Test
        @DisplayName("schedules a new delivery immediately so a crash cannot lose it")
        void pendingIsDueImmediately() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(delivery.getNextAttemptAt()).isNotNull()
                    .isBeforeOrEqualTo(Instant.now().plusSeconds(1));
            assertThat(delivery.getAttemptCount()).isZero();
        }

        @Test
        @DisplayName("copies the recipient and priority from the notification")
        void copiesParentFields() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);

            assertThat(delivery.getRecipientUserId()).isEqualTo("USR-1");
            assertThat(delivery.getPriority()).isEqualTo(NotificationPriority.NORMAL);
            assertThat(delivery.getDeliveryId()).startsWith("NDL-");
        }

        /**
         * A suppressed delivery carries no schedule, so the worker never sees it. Nothing is going to change
         * its outcome.
         */
        @Test
        @DisplayName("leaves a suppressed delivery unscheduled")
        void suppressedIsNotScheduled() {
            NotificationDelivery delivery = NotificationDelivery.suppressed(
                    notification(NotificationPriority.NORMAL), NotificationChannel.SMS,
                    SuppressionReason.SMS_NOT_OPTED_IN);

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUPPRESSED);
            assertThat(delivery.getNextAttemptAt()).isNull();
            assertThat(delivery.getSuppressedReason()).isEqualTo(SuppressionReason.SMS_NOT_OPTED_IN);
        }

        /** A suppressed delivery with no reason is indistinguishable from a bug. */
        @Test
        @DisplayName("refuses to suppress without a reason")
        void suppressionNeedsAReason() {
            assertThatThrownBy(() -> NotificationDelivery.suppressed(
                    notification(NotificationPriority.NORMAL), NotificationChannel.SMS, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must say why");
        }
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        /**
         * Success clears the schedule and the last error. Clearing the error matters: a delivery that
         * succeeded on its third attempt with the first two errors still attached reads, in a support view,
         * as though something is still wrong. The attempt history keeps them.
         */
        @Test
        @DisplayName("clears the schedule and the last error on success")
        void successClearsRetryState() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);
            delivery.beginAttempt();
            delivery.markFailed("SMTP_TIMEOUT: slow", Instant.now().plusSeconds(30));
            delivery.beginAttempt();
            delivery.markSent();

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
            assertThat(delivery.getSentAt()).isNotNull();
            assertThat(delivery.getNextAttemptAt()).isNull();
            assertThat(delivery.getLastError())
                    .as("a resolved failure must not keep reading as an open one")
                    .isNull();
            assertThat(delivery.getAttemptCount()).isEqualTo(2);
        }

        /**
         * A FAILED row with no schedule would never be retried and never be reported as dead — the one
         * outcome worse than either. Refused at the aggregate rather than trusted to every caller.
         */
        @Test
        @DisplayName("refuses a retryable failure with no schedule")
        void failureMustBeScheduled() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);

            assertThatThrownBy(() -> delivery.markFailed("boom", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("markDeadLettered");
        }

        /**
         * Dead-lettering clears the schedule. A row that kept being retried forever would never appear in
         * the dead-letter queue, so the failure would stay invisible — which is the whole point of having
         * the state.
         */
        @Test
        @DisplayName("stops scheduling once a delivery is dead-lettered")
        void deadLetterClearsSchedule() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);
            delivery.beginAttempt();
            delivery.markDeadLettered("SMTP_TIMEOUT: gave up");

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
            assertThat(delivery.isDeadLettered()).isTrue();
            assertThat(delivery.getNextAttemptAt()).isNull();
            assertThat(delivery.getLastError()).contains("gave up");
        }

        @Test
        @DisplayName("counts attempts from one")
        void attemptsAreOneBased() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);

            assertThat(delivery.beginAttempt()).isEqualTo(1);
            assertThat(delivery.beginAttempt()).isEqualTo(2);
        }

        @Test
        @DisplayName("truncates an error too long for the column")
        void truncatesLongErrors() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);
            delivery.markDeadLettered("x".repeat(2_000));

            assertThat(delivery.getLastError()).hasSizeLessThanOrEqualTo(512);
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        /**
         * The attempt count is set, not zeroed. Zeroing would let a permanently broken destination be
         * replayed indefinitely, each replay looking like a fresh start and costing a full run of attempts.
         */
        @Test
        @DisplayName("resumes from a stated budget rather than starting again")
        void replayGrantsABudget() {
            NotificationDelivery delivery = exhausted(8);

            delivery.requeue(Instant.now(), 5);

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(delivery.getAttemptCount())
                    .as("three more attempts against a maximum of eight")
                    .isEqualTo(5);
            assertThat(delivery.getNextAttemptAt()).isNotNull();
        }

        /**
         * Only ever moved backwards. A replay that raised the count would shorten the budget rather than
         * extend it — the opposite of what the operator asked for, and invisible until the delivery died
         * immediately.
         */
        @Test
        @DisplayName("never shortens the budget it was asked to extend")
        void replayNeverRaisesTheCount() {
            NotificationDelivery delivery = exhausted(3);

            delivery.requeue(Instant.now(), 5);

            assertThat(delivery.getAttemptCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses to replay a delivery that is not dead")
        void onlyDeadDeliveriesCanBeReplayed() {
            NotificationDelivery sent = pending(NotificationChannel.EMAIL);
            sent.beginAttempt();
            sent.markSent();

            assertThatThrownBy(() -> sent.requeue(Instant.now(), 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SENT");
        }

        @Test
        @DisplayName("refuses a negative budget")
        void rejectsNegativeBudget() {
            assertThatThrownBy(() -> exhausted(8).requeue(Instant.now(), -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("inbox")
    class Inbox {

        @Test
        @DisplayName("marks an in-app notification read once")
        void markReadIsIdempotent() {
            NotificationDelivery delivery = pending(NotificationChannel.IN_APP);
            delivery.markRead();
            Instant first = delivery.getReadAt();

            delivery.markRead();

            assertThat(delivery.getReadAt())
                    .as("the first read is the one that counts")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("reports an unread in-app entry as unread")
        void unreadFlag() {
            NotificationDelivery delivery = pending(NotificationChannel.IN_APP);

            assertThat(delivery.isUnread()).isTrue();
            delivery.markRead();
            assertThat(delivery.isUnread()).isFalse();
        }

        /**
         * Nothing on this platform tracks whether an email was opened, so nothing may claim it was. Mirrors
         * the database CHECK, which is what stops a direct write making the same false claim.
         */
        @Test
        @DisplayName("refuses to mark an email read")
        void emailCannotBeRead() {
            NotificationDelivery delivery = pending(NotificationChannel.EMAIL);

            assertThatThrownBy(delivery::markRead)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EMAIL");
            assertThat(delivery.isUnread()).isFalse();
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        /**
         * The retryable set is exactly what the worker's partial index covers. If these disagreed, the index
         * would either miss due work or accumulate rows that can never progress.
         */
        @Test
        @DisplayName("treats only PENDING and FAILED as retryable")
        void retryableSet() {
            assertThat(DeliveryStatus.PENDING.isRetryable()).isTrue();
            assertThat(DeliveryStatus.FAILED.isRetryable()).isTrue();
            assertThat(DeliveryStatus.SENT.isRetryable()).isFalse();
            assertThat(DeliveryStatus.SUPPRESSED.isRetryable()).isFalse();
            assertThat(DeliveryStatus.DEAD_LETTERED.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("treats SENT, SUPPRESSED and DEAD_LETTERED as terminal")
        void terminalSet() {
            assertThat(DeliveryStatus.SENT.isTerminal()).isTrue();
            assertThat(DeliveryStatus.SUPPRESSED.isTerminal()).isTrue();
            assertThat(DeliveryStatus.DEAD_LETTERED.isTerminal()).isTrue();
            assertThat(DeliveryStatus.PENDING.isTerminal()).isFalse();
            assertThat(DeliveryStatus.FAILED.isTerminal()).isFalse();
        }

        /** Terminal and retryable must be exact complements, or a row could be both or neither. */
        @Test
        @DisplayName("makes terminal and retryable exact opposites")
        void terminalAndRetryableAreComplements() {
            for (DeliveryStatus status : DeliveryStatus.values()) {
                assertThat(status.isRetryable())
                        .as("%s", status)
                        .isNotEqualTo(status.isTerminal());
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Notification notification(NotificationPriority priority) {
        return Notification.of("PAY-1:sent:USR-1", "USR-1", "payment.sent", priority,
                "PAY-1", "{}", "evt-1", "payment-service");
    }

    private static NotificationDelivery pending(NotificationChannel channel) {
        return NotificationDelivery.pending(notification(NotificationPriority.NORMAL), channel,
                channel == NotificationChannel.EMAIL ? "alice@example.com" : "USR-1",
                "Subject", "Body");
    }

    private static NotificationDelivery exhausted(int attempts) {
        NotificationDelivery delivery = pending(NotificationChannel.EMAIL);
        for (int i = 0; i < attempts; i++) {
            delivery.beginAttempt();
        }
        delivery.markDeadLettered("SMTP_TIMEOUT: gave up");
        return delivery;
    }

    @Test
    @DisplayName("keeps the recipient and the body out of toString, which reaches logs")
    void toStringOmitsPersonalData() {
        NotificationDelivery delivery = pending(NotificationChannel.EMAIL);

        assertThat(delivery.toString())
                .doesNotContain("alice@example.com")
                .doesNotContain("Body")
                .contains(delivery.getDeliveryId());
    }

    @Test
    @DisplayName("stamps updated_at when the status moves")
    void touchesOnTransition() {
        NotificationDelivery delivery = pending(NotificationChannel.EMAIL);
        Instant before = delivery.getUpdatedAt().minus(1, ChronoUnit.SECONDS);

        delivery.beginAttempt();

        assertThat(delivery.getUpdatedAt()).isAfter(before);
    }
}
