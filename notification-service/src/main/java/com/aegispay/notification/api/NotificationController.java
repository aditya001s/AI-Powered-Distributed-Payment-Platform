package com.aegispay.notification.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.common.security.Roles;
import com.aegispay.notification.api.NotificationDtos.InboxEntryView;
import com.aegispay.notification.api.NotificationDtos.PreferencesView;
import com.aegispay.notification.api.NotificationDtos.SmsOptIn;
import com.aegispay.notification.api.NotificationDtos.UnreadCountView;
import com.aegispay.notification.api.NotificationDtos.UpdatePreferences;
import com.aegispay.notification.domain.Notification;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.domain.NotificationPreferences;
import com.aegispay.notification.service.NotificationMutationService;
import com.aegispay.notification.service.NotificationQueryService;
import com.aegispay.notification.service.RecipientResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The customer-facing notification surface: an in-app inbox and channel preferences.
 *
 * <p><strong>Everything here is scoped to the caller.</strong> Not by filtering a shared query, but by
 * taking the user id from the validated token and never from the request — there is no path parameter
 * naming a user on any endpoint below. An inbox that accepted a user id would be one authorisation bug
 * away from letting any customer read another's payment notifications, and those contain amounts,
 * references and a free-text note from a counterparty.
 *
 * <p>Staff endpoints live in {@link NotificationAdminController} rather than here, so the two are not one
 * {@code @PreAuthorize} slip apart.
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications",
        description = "In-app inbox and channel preferences for the authenticated user")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    /** Bounded so a client cannot ask for an unbounded page and turn the inbox into a full scan. */
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationQueryService queries;
    private final NotificationMutationService mutations;
    private final RecipientResolver recipients;

    public NotificationController(NotificationQueryService queries,
                                  NotificationMutationService mutations,
                                  RecipientResolver recipients) {
        this.queries = queries;
        this.mutations = mutations;
        this.recipients = recipients;
    }

    // ------------------------------------------------------------------- inbox

    @GetMapping
    @Operation(summary = "The caller's in-app inbox",
            description = """
                    Newest first. Scoped to the authenticated user by the token, so there is no way to \
                    request somebody else's.

                    An in-app notification is the delivery row itself: the body is the text that was \
                    rendered when the notification was created, retained verbatim. Re-wording a template \
                    later does not change what is shown here.""")
    public ResponseEntity<Map<String, Object>> inbox(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userId = CurrentUser.requireUserId();
        Page<NotificationDelivery> found = queries.inbox(userId, unreadOnly,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE)));

        List<InboxEntryView> entries = found.getContent().stream()
                .map(delivery -> {
                    Notification notification =
                            queries.requireNotification(delivery.getNotificationId());
                    return InboxEntryView.from(delivery, notification.getTemplateKey(),
                            notification.getPaymentId());
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "content", entries,
                "page", found.getNumber(),
                "size", found.getSize(),
                "totalElements", found.getTotalElements(),
                "unread", queries.unreadCount(userId)));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many in-app notifications the caller has not read",
            description = "Its own endpoint so a client can poll a badge count without fetching bodies.")
    public ResponseEntity<UnreadCountView> unreadCount() {
        return ResponseEntity.ok(
                new UnreadCountView(queries.unreadCount(CurrentUser.requireUserId())));
    }

    @PostMapping("/{deliveryId}/read")
    @Operation(summary = "Mark one in-app notification read",
            description = """
                    Idempotent: the first read is the one recorded, and a second call does not move the \
                    timestamp.

                    Returns 404 rather than 403 for a notification belonging to somebody else. A 403 \
                    would confirm that the id exists and belongs to someone, which is more than the \
                    caller is entitled to learn.""")
    @ApiResponse(responseCode = "404", description = "No such notification for this user",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<NotificationDtos.DeliveryView> markRead(@PathVariable String deliveryId) {
        NotificationDelivery updated =
                mutations.markRead(deliveryId, CurrentUser.requireUserId());
        return ResponseEntity.ok(NotificationDtos.DeliveryView.from(updated));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every unread in-app notification read")
    public ResponseEntity<Map<String, Object>> markAllRead() {
        int updated = mutations.markAllRead(CurrentUser.requireUserId());
        return ResponseEntity.ok(Map.of("marked", updated));
    }

    // ------------------------------------------------------------- preferences

    @GetMapping("/preferences")
    @Operation(summary = "The caller's notification preferences",
            description = """
                    Returns the defaults for a user who has never set any, without creating a row: a \
                    read that wrote would put a write lock on this table in the delivery path.

                    The phone number is masked even for its owner. The customer already knows it, so \
                    returning it in full adds nothing and puts a phone number in every cache and proxy \
                    log that handles the response.""")
    public ResponseEntity<PreferencesView> preferences() {
        return ResponseEntity.ok(PreferencesView.from(
                recipients.preferencesFor(CurrentUser.requireUserId())));
    }

    @PutMapping("/preferences")
    @Operation(summary = "Update the caller's notification preferences",
            description = """
                    Only the fields present in the body are changed.

                    **Channel preferences govern routine notifications only.** A HIGH-priority \
                    notification — a payment outcome, a security event — is sent regardless. A customer \
                    who could switch those off would be able to hide an account takeover from \
                    themselves, so the exception is compiled in rather than configurable.

                    SMS is not settable here: turning it on needs a phone number and a consent record. \
                    Use `POST /api/notifications/preferences/sms`.""")
    public ResponseEntity<PreferencesView> updatePreferences(
            @Valid @RequestBody UpdatePreferences request) {

        String userId = CurrentUser.requireUserId();
        NotificationPreferences preferences = mutations.findPreferences(userId)
                .orElseGet(() -> NotificationPreferences.defaultsFor(userId));

        if (request.emailEnabled() != null) {
            preferences.setChannelEnabled(NotificationChannel.EMAIL, request.emailEnabled());
        }
        if (request.inAppEnabled() != null) {
            preferences.setChannelEnabled(NotificationChannel.IN_APP, request.inAppEnabled());
        }
        if (request.locale() != null) {
            preferences.setLocale(request.locale());
        }
        return ResponseEntity.ok(
                PreferencesView.from(mutations.savePreferences(preferences)));
    }

    @PostMapping("/preferences/sms")
    @Operation(summary = "Opt in to SMS notifications",
            description = """
                    Records the number and the consent together, because one without the other is either \
                    a phone number held for no stated purpose or an SMS setting that can never send.

                    E.164 is required. A national-format number is ambiguous about country, and the \
                    wrong guess sends a stranger a message about your payment.""")
    @ApiResponse(responseCode = "200", description = "SMS enabled")
    @ApiResponse(responseCode = "400", description = "Not a valid E.164 number",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PreferencesView> optInToSms(@Valid @RequestBody SmsOptIn request) {
        String userId = CurrentUser.requireUserId();
        NotificationPreferences preferences = mutations.findPreferences(userId)
                .orElseGet(() -> NotificationPreferences.defaultsFor(userId));

        preferences.optInToSms(request.phoneE164());
        return ResponseEntity.ok(
                PreferencesView.from(mutations.savePreferences(preferences)));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/preferences/sms")
    @Operation(summary = "Withdraw SMS consent",
            description = """
                    Turns SMS off and forgets the number. The number is deleted rather than kept for \
                    convenience: its only justification was the consent, and holding personal data whose \
                    stated purpose has been withdrawn is what the consent record exists to prevent.""")
    public ResponseEntity<PreferencesView> optOutOfSms() {
        String userId = CurrentUser.requireUserId();
        NotificationPreferences preferences = mutations.findPreferences(userId)
                .orElseGet(() -> NotificationPreferences.defaultsFor(userId));

        preferences.disableSms();
        return ResponseEntity.ok(
                PreferencesView.from(mutations.savePreferences(preferences)));
    }

    // ---------------------------------------------------------------- history

    @GetMapping("/history")
    @Operation(summary = "Every notification sent to the caller, across all channels",
            description = """
                    Distinct from the inbox: this includes the email and SMS copies, with each channel's \
                    status. It answers "did you email me about this" without support having to look.

                    Addresses are masked.""")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String userId = CurrentUser.requireUserId();
        Page<Notification> found = queries.forRecipient(userId,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE)));

        List<NotificationDtos.NotificationView> views = found.getContent().stream()
                .map(notification -> NotificationDtos.NotificationView.from(notification,
                        queries.deliveriesOf(notification.getNotificationId())))
                .toList();

        return ResponseEntity.ok(Map.of(
                "content", views,
                "page", found.getNumber(),
                "size", found.getSize(),
                "totalElements", found.getTotalElements()));
    }

    /**
     * A staff-only read of anybody's notification, for support.
     *
     * <p>Kept on this controller rather than the admin one because it is a read of a single record by
     * opaque id, and the {@code @PreAuthorize} is explicit. The shared security chain restricts
     * {@code /api/admin/**}, and this path is not under it — so the annotation is doing real work rather
     * than duplicating a chain rule.
     */
    @GetMapping("/{notificationId}/detail")
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    @Operation(summary = "Everything about one notification, including what was actually sent",
            description = """
                    Staff only. Includes the rendered subject and body of every channel and the full \
                    attempt history, which is what answers "what exactly did we tell this customer, and \
                    when".

                    Interpolation variables are shown as stored, with credential-looking keys already \
                    redacted — the redaction happens on the way in, not on the way out, so this response \
                    cannot leak something the database is holding.""")
    @ApiResponse(responseCode = "404", description = "No such notification",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<NotificationDtos.NotificationDetailView> detail(
            @PathVariable String notificationId) {

        Notification notification = queries.requireNotification(notificationId);
        List<NotificationDtos.RenderedDeliveryView> deliveries =
                queries.deliveriesOf(notificationId).stream()
                        .map(delivery -> NotificationDtos.RenderedDeliveryView.from(delivery,
                                queries.attemptsOf(delivery.getDeliveryId())))
                        .toList();

        return ResponseEntity.ok(new NotificationDtos.NotificationDetailView(
                notification.getNotificationId(),
                notification.getDeduplicationKey(),
                notification.getTemplateKey(),
                notification.getPriority(),
                notification.getRecipientUserId(),
                notification.getPaymentId(),
                notification.getVariables(),
                notification.getSourceEventId(),
                notification.getSource(),
                notification.getCreatedAt(),
                deliveries));
    }
}
