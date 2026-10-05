package com.aegispay.notification.domain;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * One channel's attempt to deliver one notification.
 *
 * <p><strong>This is the work queue.</strong> Unlike {@link Notification} it is mutable by design: a
 * delivery is attempted, fails, waits, and is attempted again. The row moves through
 * {@link DeliveryStatus} and carries its own retry schedule.
 *
 * <h2>Why retry lives here and not on the notification</h2>
 *
 * <p>Because a notification fans out. If the schedule were per-notification, a transient SMS failure
 * would re-send the email that already arrived — the customer would get one SMS and four emails, and
 * every one of those emails would be correct, which is precisely why nobody would spot it in a log.
 *
 * <h2>Why the in-app inbox is this table</h2>
 *
 * <p>An IN_APP delivery already holds the rendered body, the recipient and the timestamp. A separate
 * inbox table would be a second copy of all three, kept in step by hand. {@code read_at} is
 * therefore here, with a database CHECK restricting it to IN_APP — an email cannot be marked read,
 * because nothing on this platform tracks whether one was opened, and a nullable column that quietly
 * meant "unknown" for three channels would invite exactly that false claim.
 */
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false, updatable = false, length = 48)
    private String deliveryId;

    @Column(name = "notification_id", nullable = false, updatable = false, length = 48)
    private String notificationId;

    /**
     * Copied from the parent notification.
     *
     * <p>Denormalised so the retry worker and the inbox query need no join. Safe to copy precisely
     * because the parent is immutable: the usual objection to denormalising is that the copies drift,
     * and a row that can never change cannot.
     */
    @Column(name = "recipient_user_id", nullable = false, updatable = false, length = 40)
    private String recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, updatable = false, length = 16)
    private NotificationPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    /**
     * Where it was sent.
     *
     * <p>Not updatable. "We sent it to that address" is the answer to a support question, and a
     * customer changing their email next week must not rewrite what happened last week.
     */
    @Column(name = "destination", updatable = false, length = 254)
    private String destination;

    /**
     * What was actually sent.
     *
     * <p>Not updatable either, and for the same reason: if the copy is reworded next month, the record
     * of what this customer read must not change with it.
     */
    @Column(name = "rendered_subject", updatable = false, length = 200)
    private String renderedSubject;

    @Column(name = "rendered_body", updatable = false, columnDefinition = "text")
    private String renderedBody;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /**
     * When the retry worker should next pick this up.
     *
     * <p>Null once nothing further will happen. The worker's index is partial on the retryable
     * statuses, so a terminal row with a stale timestamp would sit in the queue forever — which is why
     * the database also CHECKs that this is null unless the status is PENDING or FAILED.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "suppressed_reason", length = 48)
    private SuppressionReason suppressedReason;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** Inbox read receipt. IN_APP only, enforced by CHECK. */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock.
     *
     * <p>The retry worker claims rows with {@code FOR UPDATE SKIP LOCKED}, which already prevents two
     * instances picking the same row. This guards the other race: the worker holding a row while an
     * operator replays it through the admin API. Without it the later write would silently win, and a
     * replay could reset an attempt count that had just been incremented.
     */
    @Version
    @Column(name = "version")
    private Long version;

    protected NotificationDelivery() {
        // JPA
    }

    /** A delivery about to be attempted for the first time. */
    public static NotificationDelivery pending(Notification notification,
                                               NotificationChannel channel,
                                               String destination,
                                               String renderedSubject,
                                               String renderedBody) {

        NotificationDelivery delivery = base(notification, channel);
        delivery.status = DeliveryStatus.PENDING;
        delivery.destination = destination;
        delivery.renderedSubject = renderedSubject;
        delivery.renderedBody = renderedBody;
        // Due immediately. If this process dies before the first attempt, the row is already durable
        // and the worker picks it up rather than the notification being lost.
        delivery.nextAttemptAt = Instant.now();
        return delivery;
    }

    /**
     * A delivery that will never be attempted, and the reason.
     *
     * <p>Recorded rather than skipped. A missing row and a suppressed row look identical to a
     * customer and completely different to support, and "why did I not get an SMS" needs an answer.
     */
    public static NotificationDelivery suppressed(Notification notification,
                                                  NotificationChannel channel,
                                                  SuppressionReason reason) {

        if (reason == null) {
            throw new IllegalArgumentException(
                    "a suppressed delivery must say why, or it cannot be told apart from a bug");
        }
        NotificationDelivery delivery = base(notification, channel);
        delivery.status = DeliveryStatus.SUPPRESSED;
        delivery.suppressedReason = reason;
        delivery.nextAttemptAt = null;
        return delivery;
    }

    private static NotificationDelivery base(Notification notification, NotificationChannel channel) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.deliveryId = newDeliveryId();
        delivery.notificationId = notification.getNotificationId();
        delivery.recipientUserId = notification.getRecipientUserId();
        delivery.priority = notification.getPriority();
        delivery.channel = channel;
        delivery.attemptCount = 0;
        delivery.createdAt = Instant.now();
        delivery.updatedAt = delivery.createdAt;
        return delivery;
    }

    private static String newDeliveryId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "NDL-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    // ------------------------------------------------------------- transitions

    /**
     * A transport accepted this message.
     *
     * <p>Clears the retry schedule and the last error. Clearing the error matters: a delivery that
     * succeeded on its third attempt with the first two errors still attached reads, in a support
     * view, as though something is still wrong. The attempt history keeps those errors.
     */
    public void markSent() {
        this.status = DeliveryStatus.SENT;
        this.sentAt = Instant.now();
        this.nextAttemptAt = null;
        this.lastError = null;
        touch();
    }

    /**
     * A transport refused this message, and it will be tried again.
     *
     * @param nextAttemptAt when. Non-null: a FAILED row with no schedule is a delivery that will
     *                      never be retried and never be reported as dead, which is the one outcome
     *                      worse than either.
     */
    public void markFailed(String errorMessage, Instant nextAttemptAt) {
        if (nextAttemptAt == null) {
            throw new IllegalArgumentException(
                    "a retryable failure must be scheduled; use markDeadLettered to give up");
        }
        this.status = DeliveryStatus.FAILED;
        this.lastError = truncate(errorMessage);
        this.nextAttemptAt = nextAttemptAt;
        touch();
    }

    /**
     * Every retry was exhausted.
     *
     * <p>Terminal until an operator replays it. The schedule is cleared so the worker stops looking at
     * it — a row that kept being retried forever would never appear in the dead-letter queue, so the
     * failure would stay invisible.
     */
    public void markDeadLettered(String errorMessage) {
        this.status = DeliveryStatus.DEAD_LETTERED;
        this.lastError = truncate(errorMessage);
        this.nextAttemptAt = null;
        touch();
    }

    /** Records that an attempt was made, whatever its outcome. */
    public int beginAttempt() {
        this.attemptCount += 1;
        touch();
        return this.attemptCount;
    }

    /**
     * Records an attempt and leases it to the caller until the outcome is persisted.
     *
     * <p>The lease keeps every other worker from sending the same delivery after the short row lock
     * is released. If this process dies during transport I/O, the row becomes due again when the
     * lease expires.
     */
    public int beginAttempt(Instant leaseUntil) {
        if (leaseUntil == null || !leaseUntil.isAfter(Instant.now())) {
            throw new IllegalArgumentException("an attempt lease must expire in the future");
        }
        int number = beginAttempt();
        this.nextAttemptAt = leaseUntil;
        return number;
    }

    /**
     * Puts a dead delivery back in the queue with a stated budget.
     *
     * <p>The attempt count is <em>set</em>, not reset to zero. A caller granting three more attempts
     * against a maximum of eight passes 5, and the delivery dies again after three. Zeroing it would let
     * a permanently broken destination be replayed indefinitely, each replay looking like a fresh start
     * and each one costing a full run of attempts.
     *
     * <p>The count is only ever moved backwards. A replay that raised it would shorten the budget rather
     * than extend it, which is the opposite of what the operator asked for and would be invisible until
     * the delivery died immediately.
     *
     * @param resumeAttemptCount the attempt count to resume from; clamped so it cannot exceed the count
     *                           already recorded
     */
    public void requeue(Instant when, int resumeAttemptCount) {
        if (status != DeliveryStatus.DEAD_LETTERED) {
            throw new IllegalStateException(
                    "only a dead-lettered delivery can be replayed; this one is " + status);
        }
        if (resumeAttemptCount < 0) {
            throw new IllegalArgumentException(
                    "an attempt count cannot be negative: " + resumeAttemptCount);
        }
        this.attemptCount = Math.min(this.attemptCount, resumeAttemptCount);
        this.status = DeliveryStatus.FAILED;
        this.nextAttemptAt = when == null ? Instant.now() : when;
        touch();
    }

    /**
     * Marks an inbox entry read.
     *
     * <p>Idempotent: the first read is the one that counts, and a second call must not move the
     * timestamp. Refused for any other channel, matching the database CHECK, so a client cannot
     * assert that an email was opened.
     */
    public void markRead() {
        if (channel != NotificationChannel.IN_APP) {
            throw new IllegalStateException(
                    "only an in-app notification can be read; nothing here tracks whether a "
                            + channel + " message was opened");
        }
        if (readAt == null) {
            this.readAt = Instant.now();
            touch();
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }

    // ---------------------------------------------------------------- queries

    public boolean isSent() {
        return status == DeliveryStatus.SENT;
    }

    public boolean isDeadLettered() {
        return status == DeliveryStatus.DEAD_LETTERED;
    }

    public boolean isUnread() {
        return channel == NotificationChannel.IN_APP && readAt == null;
    }

    public Long getId() {
        return id;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public String getRecipientUserId() {
        return recipientUserId;
    }

    public NotificationPriority getPriority() {
        return priority;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public String getDestination() {
        return destination;
    }

    public String getRenderedSubject() {
        return renderedSubject;
    }

    public String getRenderedBody() {
        return renderedBody;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public SuppressionReason getSuppressedReason() {
        return suppressedReason;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        // No destination and no body: this reaches logs, and both are personal data.
        return "NotificationDelivery{" + deliveryId + ", " + channel + ", " + status
                + ", attempts=" + attemptCount + '}';
    }
}
