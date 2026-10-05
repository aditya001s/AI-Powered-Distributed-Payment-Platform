package com.aegispay.notification.domain;

import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * One thing a customer was told.
 *
 * <p><strong>Immutable, and every column says so.</strong> Once a message has been sent, editing the
 * record of it only makes the record wrong — the customer still read what they read. A corrected
 * message is a new notification with its own deduplication key. The database refuses UPDATE by
 * trigger; {@code updatable = false} means Hibernate never generates one to be refused.
 *
 * <p><strong>What is not here: status.</strong> A notification fans out to several channels, and
 * asking whether "the notification" was sent is the wrong question when the email arrived and the SMS
 * did not. Status belongs to {@link NotificationDelivery}, one row per channel.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Opaque public identifier.
     *
     * <p>Random rather than sequential, like every other public id on the platform: a customer who
     * can see {@code NTF-000042} learns how many notifications the platform has ever sent.
     */
    @Column(name = "notification_id", nullable = false, updatable = false, length = 48)
    private String notificationId;

    /**
     * The producer's logical identity for this notification.
     *
     * <p>The idempotency guarantee, and the reason it is a column with a UNIQUE constraint rather
     * than a lookup in application code. Kafka delivers {@code payment-completed} at least once, and
     * two consumer threads on two instances would both pass a check-then-insert. Only the database
     * can arbitrate between them.
     */
    @Column(name = "deduplication_key", nullable = false, updatable = false, length = 160)
    private String deduplicationKey;

    @Column(name = "recipient_user_id", nullable = false, updatable = false, length = 40)
    private String recipientUserId;

    /**
     * Which copy to render, e.g. {@code payment.sent}.
     *
     * <p>The key, not the text. Copy and wording change without redeploying the services that decide
     * a notification is warranted, and one key renders differently per channel because an SMS is not
     * an email.
     */
    @Column(name = "template_key", nullable = false, updatable = false, length = 64)
    private String templateKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, updatable = false, length = 16)
    private NotificationPriority priority;

    @Column(name = "payment_id", updatable = false, length = 48)
    private String paymentId;

    /**
     * Interpolation values as JSON, already redacted.
     *
     * <p>Kept as a JSON string because this service only renders and returns the value. The explicit
     * JSON JDBC type is still required so PostgreSQL receives a JSONB value rather than VARCHAR.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String variables = "{}";

    /** Which event produced this, so a customer complaint traces back to the payment. */
    @Column(name = "source_event_id", updatable = false, length = 64)
    private String sourceEventId;

    @Column(name = "source", updatable = false, length = 64)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {
        // JPA
    }

    /**
     * Records a notification that is about to be delivered.
     *
     * @param deduplicationKey the producer's logical identity; required, because without it
     *                         at-least-once event delivery becomes at-least-once messaging
     * @param variablesJson    already-redacted JSON
     */
    public static Notification of(String deduplicationKey,
                                  String recipientUserId,
                                  String templateKey,
                                  NotificationPriority priority,
                                  String paymentId,
                                  String variablesJson,
                                  String sourceEventId,
                                  String source) {

        if (deduplicationKey == null || deduplicationKey.isBlank()) {
            // Refused rather than defaulted to a random value. A generated key would make every
            // redelivery a new notification, so the customer would be told again on every Kafka
            // retry — and the failure would look like a broken producer rather than a broken
            // default.
            throw new IllegalArgumentException(
                    "a notification needs a deduplication key, or at-least-once event delivery "
                            + "becomes at-least-once messaging");
        }
        if (recipientUserId == null || recipientUserId.isBlank()) {
            throw new IllegalArgumentException("a notification must name its recipient");
        }
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("a notification must name the copy to render");
        }

        Notification notification = new Notification();
        notification.notificationId = newNotificationId();
        notification.deduplicationKey = deduplicationKey;
        notification.recipientUserId = recipientUserId;
        notification.templateKey = templateKey;
        notification.priority = priority == null ? NotificationPriority.NORMAL : priority;
        notification.paymentId = paymentId;
        notification.variables = variablesJson == null || variablesJson.isBlank()
                ? "{}"
                : variablesJson;
        notification.sourceEventId = sourceEventId;
        notification.source = source;
        notification.createdAt = Instant.now();
        return notification;
    }

    private static String newNotificationId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "NTF-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    /**
     * Whether a user's channel opt-out may be honoured for this notification.
     *
     * <p>False for HIGH priority. A customer must not be able to switch off being told that their
     * payment failed or that their account was accessed from a new device — the preference exists to
     * stop routine noise, and treating "tell me nothing" as covering a security alert would turn a
     * convenience setting into a way to hide an account takeover from its victim.
     */
    public boolean respectsChannelOptOut() {
        return priority != NotificationPriority.HIGH;
    }

    public Long getId() {
        return id;
    }

    public String getNotificationId() {
        return notificationId;
    }

    public String getDeduplicationKey() {
        return deduplicationKey;
    }

    public String getRecipientUserId() {
        return recipientUserId;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public NotificationPriority getPriority() {
        return priority;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getVariables() {
        return variables;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        // Deliberately no recipient and no variables: this string reaches logs, and both are
        // personal data.
        return "Notification{" + notificationId + ", " + templateKey + ", " + priority + '}';
    }
}
