package com.aegispay.notification.domain;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One transport call, recorded whether it worked or not.
 *
 * <h2>Why this exists alongside an attempt counter</h2>
 *
 * <p>A counter says a delivery failed four times. These rows say it failed four times with the same
 * connection timeout — which is a host that is down — or with four different errors, which is
 * something else entirely and needs a different fix. The counter cannot tell those apart, and by the
 * time somebody is looking, the difference is the whole question.
 *
 * <p>There is a second, less obvious reason. Replaying a dead delivery overwrites
 * {@code last_error}. Without this table the history of why it died would be destroyed at exactly the
 * moment an operator started investigating it, by the action they took to investigate it.
 *
 * <p>Append-only, enforced by trigger: an attempt is a statement about something that already
 * happened.
 */
@Entity
@Table(name = "notification_delivery_attempts")
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false, updatable = false, length = 48)
    private String deliveryId;

    @Column(name = "notification_id", nullable = false, updatable = false, length = 48)
    private String notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private NotificationChannel channel;

    /** 1-based, matching what an operator reading a log expects. */
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "succeeded", nullable = false, updatable = false)
    private boolean succeeded;

    /**
     * The transport's reference for a successful send.
     *
     * <p>Simulated here, and the simulated transports say so in the value itself rather than
     * producing something that looks like a real provider id. A reference that could be mistaken for
     * a genuine message id is worse than none, because somebody will eventually try to look it up.
     */
    @Column(name = "provider_ref", updatable = false, length = 96)
    private String providerRef;

    @Column(name = "error_code", updatable = false, length = 64)
    private String errorCode;

    @Column(name = "error_message", updatable = false, length = 512)
    private String errorMessage;

    /**
     * How long the transport call took.
     *
     * <p>Recorded per attempt because a provider that is slow before it is broken shows up here first,
     * and a mean across all attempts would hide the tail that matters.
     */
    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt = Instant.now();

    protected DeliveryAttempt() {
        // JPA
    }

    public static DeliveryAttempt success(NotificationDelivery delivery, int attemptNumber,
                                          String providerRef, long durationMs) {
        DeliveryAttempt attempt = base(delivery, attemptNumber, durationMs);
        attempt.succeeded = true;
        attempt.providerRef = providerRef;
        return attempt;
    }

    public static DeliveryAttempt failure(NotificationDelivery delivery, int attemptNumber,
                                          String errorCode, String errorMessage, long durationMs) {
        DeliveryAttempt attempt = base(delivery, attemptNumber, durationMs);
        attempt.succeeded = false;
        // Defaulted rather than allowed to be null. The database CHECK pairs "failed" with "has an
        // error code", so a failure with no code would fail to insert and take the whole delivery
        // update down with it — turning a transport failure into a lost notification.
        attempt.errorCode = errorCode == null || errorCode.isBlank() ? "UNKNOWN" : errorCode;
        attempt.errorMessage = truncate(errorMessage);
        return attempt;
    }

    private static DeliveryAttempt base(NotificationDelivery delivery, int attemptNumber,
                                        long durationMs) {
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.deliveryId = delivery.getDeliveryId();
        attempt.notificationId = delivery.getNotificationId();
        attempt.channel = delivery.getChannel();
        attempt.attemptNumber = attemptNumber;
        // Clamped rather than rejected: a clock adjustment mid-call can produce a negative elapsed
        // time, and losing the attempt record over it would be the wrong trade.
        attempt.durationMs = Math.max(0L, durationMs);
        attempt.attemptedAt = Instant.now();
        return attempt;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
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

    public NotificationChannel getChannel() {
        return channel;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    @Override
    public String toString() {
        return "DeliveryAttempt{" + deliveryId + " #" + attemptNumber
                + (succeeded ? ", ok" : ", " + errorCode) + ", " + durationMs + "ms}";
    }
}
