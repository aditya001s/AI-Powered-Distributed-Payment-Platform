package com.aegispay.notification.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.common.security.Roles;
import com.aegispay.notification.api.NotificationDtos.DeliveryView;
import com.aegispay.notification.api.NotificationDtos.EngineView;
import com.aegispay.notification.api.NotificationDtos.StatsView;
import com.aegispay.notification.config.NotificationProperties;
import com.aegispay.notification.domain.DeliveryStatus;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.service.DeliveryBackoff;
import com.aegispay.notification.service.DeliveryRetryWorker;
import com.aegispay.notification.service.NotificationMutationService;
import com.aegispay.notification.service.NotificationQueryService;
import com.aegispay.notification.template.TemplateCatalogue;
import com.aegispay.notification.transport.TransportRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Staff oversight of notification delivery.
 *
 * <p>Under {@code /api/admin/**}, so the shared security chain already restricts it to ADMIN and
 * FRAUD_ANALYST before any annotation here applies. The class-level {@code @PreAuthorize} is a second,
 * independent control: this is the surface that lists which customers were not reached and can re-trigger
 * messages to them, and one misconfigured gateway route should not be all that stands in front of it.
 *
 * <p>Separate from {@link NotificationController} deliberately. The customer surface takes its user id
 * from the token and never from a parameter; this one reads across every customer. Keeping them in one
 * class would leave those two rules a single missing annotation apart.
 */
@RestController
@RequestMapping("/api/admin/notifications")
@Tag(name = "Notification administration",
        description = "Dead-letter queue, replay and delivery statistics")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
public class NotificationAdminController {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdminController.class);

    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationQueryService queries;
    private final NotificationMutationService mutations;
    private final TransportRegistry transports;
    private final TemplateCatalogue catalogue;
    private final DeliveryBackoff backoff;
    private final DeliveryRetryWorker worker;
    private final NotificationProperties properties;
    private final MeterRegistry meterRegistry;

    public NotificationAdminController(NotificationQueryService queries,
                                       NotificationMutationService mutations,
                                       TransportRegistry transports,
                                       TemplateCatalogue catalogue,
                                       DeliveryBackoff backoff,
                                       DeliveryRetryWorker worker,
                                       NotificationProperties properties,
                                       MeterRegistry meterRegistry) {
        this.queries = queries;
        this.mutations = mutations;
        this.transports = transports;
        this.catalogue = catalogue;
        this.backoff = backoff;
        this.worker = worker;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    // ------------------------------------------------------- dead-letter queue

    @GetMapping("/dead-letters")
    @Operation(summary = "Deliveries that exhausted every retry",
            description = """
                    **The queue that matters.** Every row is a customer who was never told something the \
                    platform decided to tell them — and because the payment itself succeeded, nothing \
                    else in the system reports it.

                    Ordered by when the delivery gave up rather than when it was created, which is both \
                    the order an operator works through and the order a new outage appears in.

                    Addresses are masked.""")
    public ResponseEntity<Map<String, Object>> deadLetters(
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Page<NotificationDelivery> found = queries.deadLettered(channel,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE)));

        return ResponseEntity.ok(Map.of(
                "content", found.getContent().stream().map(DeliveryView::from).toList(),
                "page", found.getNumber(),
                "size", found.getSize(),
                "totalElements", found.getTotalElements()));
    }

    @GetMapping("/deliveries/{deliveryId}/attempts")
    @Operation(summary = "Every attempt on one delivery",
            description = """
                    The view that answers "why did this keep failing". Four identical connection timeouts \
                    is a host that is down; four different errors is something else, and an attempt count \
                    alone cannot tell them apart.

                    Kept even across a replay: replaying overwrites the delivery's last error, so without \
                    this the history would be destroyed by the act of investigating it.""")
    @ApiResponse(responseCode = "404", description = "No such delivery",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Map<String, Object>> attempts(@PathVariable String deliveryId) {
        NotificationDelivery delivery = queries.requireDelivery(deliveryId);
        return ResponseEntity.ok(Map.of(
                "delivery", DeliveryView.from(delivery),
                "attempts", queries.attemptsOf(deliveryId).stream()
                        .map(NotificationDtos.AttemptView::from).toList()));
    }

    @PostMapping("/dead-letters/{deliveryId}/replay")
    @Operation(summary = "Put a dead delivery back in the queue",
            description = """
                    For use once the underlying cause is fixed — a corrected address, a provider back \
                    online, copy written for a template that had none.

                    The attempt budget is **extended, not reset**. A delivery that already failed eight \
                    times resumes with a smaller allowance, because a full reset would let a permanently \
                    broken destination be replayed indefinitely with every replay looking like a fresh \
                    start.

                    Replaying does not re-render the message. What is retried is exactly the text that \
                    was composed originally, which is why a template fix needs the notification to be \
                    re-emitted rather than replayed.""")
    @ApiResponse(responseCode = "200", description = "Requeued")
    @ApiResponse(responseCode = "409", description = "The delivery is not dead-lettered",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such delivery",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<DeliveryView> replay(@PathVariable String deliveryId) {
        NotificationDelivery replayed = mutations.replay(deliveryId,
                properties.getRetry().getReplayAttemptGrant(), backoff.maxAttempts());

        meterRegistry.counter(MetricNames.NOTIFICATION_REPLAYED,
                MetricNames.TAG_CHANNEL, replayed.getChannel().name()).increment();

        // Attributed to the operator from their token, never from a request field. "Who re-sent this"
        // is the first question if a replay turns out to have been a mistake.
        log.info("Operator {} replayed {} delivery {} (attempt count resumed at {})",
                CurrentUser.requireUserId(), replayed.getChannel(), deliveryId,
                replayed.getAttemptCount());

        return ResponseEntity.ok(DeliveryView.from(replayed));
    }

    // -------------------------------------------------------------- oversight

    @GetMapping("/engine")
    @Operation(summary = "Delivery configuration and transport status",
            description = """
                    States plainly which channels can actually be delivered and which cannot.

                    `transportsSimulated` is true on this platform: no SMTP relay and no SMS gateway is \
                    contacted, exactly as no real bank is. Everything around the send — rendering, \
                    escaping, per-channel idempotency, retry, the dead-letter queue and the audit trail \
                    — behaves identically either way.

                    PUSH appears under `unavailableChannels`: there is no mobile application and no \
                    device-token registry, so requests for it are recorded as suppressed with a reason \
                    rather than silently dropped.""")
    public ResponseEntity<EngineView> engine() {
        List<NotificationChannel> deliverable = transports.deliverableChannels();
        List<NotificationChannel> unavailable = Arrays.stream(NotificationChannel.values())
                .filter(channel -> !deliverable.contains(channel))
                .toList();

        return ResponseEntity.ok(new EngineView(
                deliverable,
                unavailable,
                true,
                catalogue.keys(),
                backoff.maxAttempts(),
                properties.getRetry().getInitialBackoff().toString(),
                properties.getRetry().getMaxBackoff().toString(),
                properties.getRetry().getBatchSize(),
                worker.backlog()));
    }

    @GetMapping("/stats")
    @Operation(summary = "Delivery overview for the last 24 hours",
            description = """
                    `deadLettered24h` is the number to alert on. Each one is a customer who was not \
                    reached, and it produces no error anywhere else.

                    `byChannelAndStatus` is keyed `CHANNEL:STATUS`. A large SUPPRESSED count is normal — \
                    most customers have not opted in to SMS — but a rising one with reason NO_TEMPLATE \
                    means a producer is emitting notifications nobody wrote copy for.""")
    public ResponseEntity<StatsView> stats() {
        Instant since = Instant.now().minus(Duration.ofHours(24));

        return ResponseEntity.ok(new StatsView(
                queries.countNotificationsSince(since),
                queries.countDeliveriesSince(since),
                queries.countDeadLetteredSince(since),
                queries.countByStatus(DeliveryStatus.DEAD_LETTERED),
                worker.backlog(),
                queries.summarise(since)));
    }

    @GetMapping("/templates")
    @Operation(summary = "The copy this service can render",
            description = """
                    A template key that is not listed here is suppressed with reason NO_TEMPLATE rather \
                    than sent as a generic message. A vague message about somebody's money either alarms \
                    them for no reason or reassures them when it should not, and a notification cannot be \
                    recalled.""")
    public ResponseEntity<Map<String, Object>> templates() {
        return ResponseEntity.ok(Map.of(
                "keys", catalogue.keys(),
                "count", catalogue.size()));
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "Every notification sent about one payment",
            description = "The support view: who was told what, on which channel, and whether it arrived.")
    public ResponseEntity<Map<String, Object>> forPayment(@PathVariable String paymentId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {

        Page<com.aegispay.notification.domain.Notification> found = queries.forPayment(paymentId,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE)));

        return ResponseEntity.ok(Map.of(
                "content", found.getContent().stream()
                        .map(notification -> NotificationDtos.NotificationView.from(notification,
                                queries.deliveriesOf(notification.getNotificationId())))
                        .toList(),
                "totalElements", found.getTotalElements()));
    }
}
