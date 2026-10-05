package com.aegispay.common.events.payload;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A request to notify a user, published on {@code notification-required}.
 *
 * <p>Carries a {@code templateKey} plus variables rather than pre-rendered text.
 * Keeping rendering inside notification-service means copy and localisation can
 * change without redeploying the services that emit notifications, and the same
 * event can render differently per channel (an SMS is not an email).
 *
 * <p>{@code deduplicationKey} makes delivery effectively-once. Kafka may deliver
 * {@code payment-completed} twice; the customer must not receive two "payment
 * successful" messages. notification-service treats this key as unique.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationPayload(

        String recipientUserId,

        /** Resolved by notification-service when absent, from the user directory. */
        String recipientEmail,

        String recipientPhone,

        /** Channels to attempt. Honoured subject to the user's preferences. */
        List<NotificationChannel> channels,

        /** e.g. {@code payment.completed}, {@code fraud.hold}, {@code security.new_device}. */
        String templateKey,

        /** Values interpolated into the template. Must not contain secrets. */
        Map<String, String> variables,

        NotificationPriority priority,

        /** Unique per logical notification; suppresses duplicate deliveries. */
        String deduplicationKey,

        /** Related payment, when applicable. Enables deep-linking from the message. */
        String paymentId
) {

    /** A high-priority, security-relevant notification (fraud hold, new device). */
    public static NotificationPayload security(String userId,
                                              String templateKey,
                                              Map<String, String> variables,
                                              String deduplicationKey,
                                              String paymentId) {
        return new NotificationPayload(
                userId, null, null,
                List.of(NotificationChannel.EMAIL, NotificationChannel.PUSH, NotificationChannel.IN_APP),
                templateKey, variables, NotificationPriority.HIGH, deduplicationKey, paymentId);
    }

    /** A routine transactional notification. */
    public static NotificationPayload transactional(String userId,
                                                    String templateKey,
                                                    Map<String, String> variables,
                                                    String deduplicationKey,
                                                    String paymentId) {
        return new NotificationPayload(
                userId, null, null,
                List.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP),
                templateKey, variables, NotificationPriority.NORMAL, deduplicationKey, paymentId);
    }
}
