package com.aegispay.notification.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.notification.domain.DeliveryAttempt;
import com.aegispay.notification.domain.DeliveryStatus;
import com.aegispay.notification.domain.Notification;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.repository.DeliveryAttemptRepository;
import com.aegispay.notification.repository.NotificationDeliveryRepository;
import com.aegispay.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads over notifications and their deliveries.
 *
 * <p>Separate from {@link NotificationMutationService} so every method here can be
 * {@code readOnly = true}. That is not decoration: a read-only transaction lets Hibernate skip dirty
 * checking and, more importantly, means an accidental write on a query path fails rather than committing.
 * The tables this service reads include two that are append-only at the database, so such a write would
 * fail anyway — but it would fail as a trigger exception inside an API request, which is a much worse
 * place to discover it than a compile-time boundary.
 */
@Service
public class NotificationQueryService {

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;

    public NotificationQueryService(NotificationRepository notifications,
                                    NotificationDeliveryRepository deliveries,
                                    DeliveryAttemptRepository attempts) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.attempts = attempts;
    }

    // ------------------------------------------------------------------- inbox

    /** The caller's in-app inbox, newest first. */
    @Transactional(readOnly = true)
    public Page<NotificationDelivery> inbox(String userId, boolean unreadOnly, Pageable pageable) {
        return unreadOnly
                ? deliveries.findByRecipientUserIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(
                        userId, NotificationChannel.IN_APP, pageable)
                : deliveries.findByRecipientUserIdAndChannelOrderByCreatedAtDesc(
                        userId, NotificationChannel.IN_APP, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(String userId) {
        return deliveries.countByRecipientUserIdAndChannelAndReadAtIsNull(
                userId, NotificationChannel.IN_APP);
    }

    // -------------------------------------------------------------- one record

    @Transactional(readOnly = true)
    public Notification requireNotification(String notificationId) {
        return notifications.findByNotificationId(notificationId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No notification with id " + notificationId));
    }

    @Transactional(readOnly = true)
    public NotificationDelivery requireDelivery(String deliveryId) {
        return deliveries.findByDeliveryId(deliveryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No delivery with id " + deliveryId));
    }

    @Transactional(readOnly = true)
    public List<NotificationDelivery> deliveriesOf(String notificationId) {
        return deliveries.findByNotificationIdOrderByChannelAsc(notificationId);
    }

    /**
     * Every attempt on a delivery, oldest first.
     *
     * <p>The view that answers "why did this fail". Four identical connection timeouts is a host that is
     * down; four different errors is something else, and the count alone cannot tell them apart.
     */
    @Transactional(readOnly = true)
    public List<DeliveryAttempt> attemptsOf(String deliveryId) {
        return attempts.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
    }

    @Transactional(readOnly = true)
    public Page<Notification> forRecipient(String userId, Pageable pageable) {
        return notifications.findByRecipientUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Notification> forPayment(String paymentId, Pageable pageable) {
        return notifications.findByPaymentIdOrderByCreatedAtDesc(paymentId, pageable);
    }

    // -------------------------------------------------------------- oversight

    /**
     * The dead-letter queue: customers who were not reached.
     *
     * <p>Ordered by when the delivery gave up rather than when it was created, because that is the order
     * an operator works through and the order in which a new outage appears.
     */
    @Transactional(readOnly = true)
    public Page<NotificationDelivery> deadLettered(NotificationChannel channel, Pageable pageable) {
        return channel == null
                ? deliveries.findByStatusOrderByUpdatedAtDesc(DeliveryStatus.DEAD_LETTERED, pageable)
                : deliveries.findByChannelAndStatusOrderByUpdatedAtDesc(
                        channel, DeliveryStatus.DEAD_LETTERED, pageable);
    }

    /**
     * Counts per channel and status over a window.
     *
     * <p>One aggregate query rather than loading rows and counting in Java. This runs on an admin
     * dashboard over the whole table, and the difference is a page that loads versus one that times out.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> summarise(Instant since) {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (Object[] row : deliveries.summariseSince(since)) {
            NotificationChannel channel = (NotificationChannel) row[0];
            DeliveryStatus status = (DeliveryStatus) row[1];
            summary.merge(channel.name() + ":" + status.name(), (Long) row[2], Long::sum);
        }
        return summary;
    }

    @Transactional(readOnly = true)
    public long countNotificationsSince(Instant since) {
        return notifications.countByCreatedAtAfter(since);
    }

    @Transactional(readOnly = true)
    public long countDeliveriesSince(Instant since) {
        return deliveries.countByCreatedAtAfter(since);
    }

    @Transactional(readOnly = true)
    public long countByStatus(DeliveryStatus status) {
        return deliveries.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public long countDeadLetteredSince(Instant since) {
        return deliveries.countByStatusAndUpdatedAtAfter(DeliveryStatus.DEAD_LETTERED, since);
    }
}
