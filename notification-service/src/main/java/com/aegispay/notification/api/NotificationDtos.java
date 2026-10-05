package com.aegispay.notification.api;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import com.aegispay.notification.domain.DeliveryAttempt;
import com.aegispay.notification.domain.DeliveryStatus;
import com.aegispay.notification.domain.Notification;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.domain.NotificationPreferences;
import com.aegispay.notification.domain.SuppressionReason;
import com.aegispay.notification.util.ContactMasking;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response shapes for the notification API. */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    // ------------------------------------------------------------------ input

    /**
     * A change to the caller's own channel preferences.
     *
     * <p>Note what is absent: SMS. Turning SMS on needs a phone number and a consent record, which is a
     * different request with different validation — see {@link SmsOptIn}. A single endpoint accepting
     * {@code smsEnabled: true} with no number would either have to fail confusingly or silently do
     * nothing, and a customer who believed they had enabled SMS and received none would have no way to
     * tell which.
     */
    @Schema(description = "Update the caller's notification preferences")
    public record UpdatePreferences(

            @Schema(description = """
                    Whether routine email notifications are sent. Security and payment-outcome messages \
                    are sent regardless: a customer cannot switch off being told that their payment \
                    failed.""")
            Boolean emailEnabled,

            Boolean inAppEnabled,

            @Size(max = 16)
            @Schema(description = "BCP-47 language tag. Only 'en' has copy today.", example = "en")
            String locale
    ) {
    }

    /** An SMS opt-in, with the number and the consent it justifies. */
    @Schema(description = "Opt in to SMS notifications")
    public record SmsOptIn(

            @NotNull
            @Pattern(regexp = "^\\+[1-9][0-9]{7,18}$",
                    message = "must be an E.164 number including the country code, e.g. +919876543210")
            @Schema(description = """
                    E.164, including the country code. A national-format number is ambiguous about \
                    country, and the wrong guess sends a stranger a message about your payment.""",
                    example = "+919876543210")
            String phoneE164
    ) {
    }

    // ----------------------------------------------------------------- output

    /**
     * One entry in the caller's in-app inbox.
     *
     * <p>Built from the IN_APP delivery row, which already holds the rendered body. There is no separate
     * inbox table to keep in step.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "An in-app notification")
    public record InboxEntryView(
            String deliveryId,
            String notificationId,
            String templateKey,
            NotificationPriority priority,
            String body,
            String paymentId,
            boolean read,
            Instant readAt,
            Instant createdAt
    ) {
        public static InboxEntryView from(NotificationDelivery delivery, String templateKey,
                                          String paymentId) {
            return new InboxEntryView(
                    delivery.getDeliveryId(),
                    delivery.getNotificationId(),
                    templateKey,
                    delivery.getPriority(),
                    delivery.getRenderedBody(),
                    paymentId,
                    delivery.getReadAt() != null,
                    delivery.getReadAt(),
                    delivery.getCreatedAt());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Unread in-app notification count")
    public record UnreadCountView(long unread) {
    }

    /**
     * One channel's delivery.
     *
     * <p>{@code destination} is masked. This view is returned to the recipient and to staff, and neither
     * needs the full address: the customer already knows it, and an operator only needs enough to confirm
     * which address was used.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One channel's delivery of a notification")
    public record DeliveryView(
            String deliveryId,
            NotificationChannel channel,
            DeliveryStatus status,

            @Schema(description = "Masked address, e.g. a***@example.com")
            String destination,

            @Schema(description = "Why nothing was attempted. Set only when the status is SUPPRESSED.")
            SuppressionReason suppressedReason,

            int attemptCount,

            @Schema(description = "When the next retry is due. Null once nothing further will happen.")
            Instant nextAttemptAt,

            String lastError,
            Instant sentAt,
            Instant readAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static DeliveryView from(NotificationDelivery delivery) {
            return new DeliveryView(
                    delivery.getDeliveryId(),
                    delivery.getChannel(),
                    delivery.getStatus(),
                    ContactMasking.mask(delivery.getChannel(), delivery.getDestination()),
                    delivery.getSuppressedReason(),
                    delivery.getAttemptCount(),
                    delivery.getNextAttemptAt(),
                    delivery.getLastError(),
                    delivery.getSentAt(),
                    delivery.getReadAt(),
                    delivery.getCreatedAt(),
                    delivery.getUpdatedAt());
        }
    }

    /** One transport call. The view that answers "why did this keep failing". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One delivery attempt")
    public record AttemptView(
            int attemptNumber,
            boolean succeeded,

            @Schema(description = "The transport's reference. Prefixed 'sim-' by the simulated "
                    + "transports, because no real provider was contacted.")
            String providerRef,

            String errorCode,
            String errorMessage,
            long durationMs,
            Instant attemptedAt
    ) {
        public static AttemptView from(DeliveryAttempt attempt) {
            return new AttemptView(
                    attempt.getAttemptNumber(),
                    attempt.isSucceeded(),
                    attempt.getProviderRef(),
                    attempt.getErrorCode(),
                    attempt.getErrorMessage(),
                    attempt.getDurationMs(),
                    attempt.getAttemptedAt());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A notification and every channel it was sent on")
    public record NotificationView(
            String notificationId,
            String templateKey,
            NotificationPriority priority,
            String recipientUserId,
            String paymentId,
            Instant createdAt,
            List<DeliveryView> deliveries
    ) {
        public static NotificationView from(Notification notification,
                                            List<NotificationDelivery> deliveries) {
            return new NotificationView(
                    notification.getNotificationId(),
                    notification.getTemplateKey(),
                    notification.getPriority(),
                    notification.getRecipientUserId(),
                    notification.getPaymentId(),
                    notification.getCreatedAt(),
                    deliveries.stream().map(DeliveryView::from).toList());
        }
    }

    /**
     * The full record of one notification, for support and disputes.
     *
     * <p>Includes the variables and every attempt. The rendered body lives on each delivery, so this is
     * the view that answers "what exactly did we send this customer, and when".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A notification, its rendered messages and every delivery attempt")
    public record NotificationDetailView(
            String notificationId,
            String deduplicationKey,
            String templateKey,
            NotificationPriority priority,
            String recipientUserId,
            String paymentId,

            @Schema(description = "Interpolation values as stored. Credential-looking keys are "
                    + "redacted before storage, not just before rendering.")
            String variables,

            String sourceEventId,
            String source,
            Instant createdAt,
            List<RenderedDeliveryView> deliveries
    ) {
    }

    /** A delivery with the text that was actually sent, and its attempt history. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A delivery, what it said, and every attempt")
    public record RenderedDeliveryView(
            DeliveryView delivery,

            @Schema(description = "Subject as sent. Email only.")
            String renderedSubject,

            @Schema(description = """
                    Body as sent, retained verbatim. Re-wording a template does not change what this \
                    customer read.""")
            String renderedBody,

            List<AttemptView> attempts
    ) {
        public static RenderedDeliveryView from(NotificationDelivery delivery,
                                                List<DeliveryAttempt> attempts) {
            return new RenderedDeliveryView(
                    DeliveryView.from(delivery),
                    delivery.getRenderedSubject(),
                    delivery.getRenderedBody(),
                    attempts.stream().map(AttemptView::from).toList());
        }
    }

    /**
     * The caller's preferences.
     *
     * <p>The phone number is masked even for its owner. The customer knows their own number, so returning
     * it in full adds nothing and puts a phone number in every browser cache and proxy log that handles
     * the response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Notification preferences")
    public record PreferencesView(
            String userId,
            boolean emailEnabled,
            boolean smsEnabled,
            boolean inAppEnabled,

            @Schema(description = "Masked, e.g. +91******3210")
            String phone,

            @Schema(description = "When SMS consent was recorded.")
            Instant smsOptInAt,

            String locale,

            @Schema(description = """
                    Channels that cannot be switched off. A customer must not be able to silence being \
                    told that their payment failed or that their account was accessed from a new \
                    device.""")
            List<String> alwaysOnFor
    ) {
        public static PreferencesView from(NotificationPreferences preferences) {
            return new PreferencesView(
                    preferences.getUserId(),
                    preferences.isEmailEnabled(),
                    preferences.isSmsEnabled(),
                    preferences.isInAppEnabled(),
                    ContactMasking.maskPhone(preferences.getPhoneE164()),
                    preferences.getSmsOptInAt(),
                    preferences.getLocale(),
                    List.of(NotificationPriority.HIGH.name()));
        }
    }

    /** What the delivery pipeline is configured to do. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Delivery configuration and transport status")
    public record EngineView(

            @Schema(description = "Channels with a registered, available transport.")
            List<NotificationChannel> deliverableChannels,

            @Schema(description = """
                    Channels with no transport. PUSH is expected here: there is no mobile application \
                    and no device-token registry on this platform, so requests for it are recorded as \
                    suppressed rather than dropped.""")
            List<NotificationChannel> unavailableChannels,

            @Schema(description = "True when email and SMS are simulated. No SMTP relay or SMS "
                    + "gateway is contacted.")
            boolean transportsSimulated,

            @Schema(description = "Template keys with copy. A key not listed here is suppressed with "
                    + "reason NO_TEMPLATE.")
            List<String> templateKeys,

            int maxAttempts,
            String initialBackoff,
            String maxBackoff,
            int batchSize,

            @Schema(description = "Deliveries currently waiting for a retry.")
            long retryBacklog
    ) {
    }

    /** Delivery outcomes over a window. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Notification delivery overview")
    public record StatsView(
            long notifications24h,
            long deliveries24h,

            @Schema(description = """
                    Deliveries that exhausted every retry in the window. THE number to alert on: each \
                    one is a customer who was never told something the platform decided to tell them, \
                    and the payment itself succeeded so nothing else reports it.""")
            long deadLettered24h,

            @Schema(description = "Deliveries currently dead, across all time, awaiting a replay.")
            long deadLetteredTotal,

            @Schema(description = "Deliveries waiting for a retry right now.")
            long retryBacklog,

            @Schema(description = "Counts keyed CHANNEL:STATUS over the window.")
            Map<String, Long> byChannelAndStatus
    ) {
    }
}
