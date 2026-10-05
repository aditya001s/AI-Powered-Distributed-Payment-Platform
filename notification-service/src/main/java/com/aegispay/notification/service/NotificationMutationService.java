package com.aegispay.notification.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.notification.domain.DeliveryAttempt;
import com.aegispay.notification.domain.DeliveryStatus;
import com.aegispay.notification.domain.Notification;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.domain.NotificationPreferences;
import com.aegispay.notification.repository.DeliveryAttemptRepository;
import com.aegispay.notification.repository.NotificationDeliveryRepository;
import com.aegispay.notification.repository.NotificationPreferencesRepository;
import com.aegispay.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every transaction that writes notification state.
 *
 * <h2>Why this is a separate bean from {@link NotificationDeliveryService}</h2>
 *
 * <p>Sending a message is a network call. A transaction spanning it would pin a database connection for
 * the length of an SMTP timeout, and under a provider outage the pool empties and the service stops
 * being able to record anything — including the failures it is in the middle of discovering.
 *
 * <p>So the orchestration is non-transactional and every write lives here, behind a real bean boundary.
 * A {@code this.method()} call would bypass Spring's proxy and run untransacted with no symptom at all;
 * that trap has already cost this project twice, in auth-service's refresh-token detection and in the
 * knowledge-base seeder.
 */
@Service
public class NotificationMutationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationMutationService.class);
    private static final Duration ATTEMPT_LEASE = Duration.ofMinutes(5);

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final NotificationPreferencesRepository preferences;

    public NotificationMutationService(NotificationRepository notifications,
                                       NotificationDeliveryRepository deliveries,
                                       DeliveryAttemptRepository attempts,
                                       NotificationPreferencesRepository preferences) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.preferences = preferences;
    }

    /** What {@link #createIfAbsent} found or created. */
    public record Created(Notification notification, List<NotificationDelivery> deliveries,
                          boolean alreadyExisted) {
    }

    /**
     * Records a notification and its per-channel deliveries, once.
     *
     * <p><strong>This is where at-least-once event delivery becomes at-most-once messaging.</strong> The
     * check is a read followed by an insert, and the read is only an optimisation: two consumer threads
     * on two instances would both find nothing and both insert. The UNIQUE constraint on
     * {@code deduplication_key} is what actually arbitrates, and the {@code DataIntegrityViolationException}
     * below is the expected outcome of losing that race rather than an error path.
     *
     * <p>Losing the race is treated as success, because it is: the notification exists, some other
     * thread is delivering it, and this one has nothing left to do.
     *
     * @param plan the deliveries to create alongside the notification. Built by the caller, because
     *             deciding which channels apply needs the user's preferences and the template catalogue.
     */
    @Transactional
    public Created createIfAbsent(Notification candidate,
                                  java.util.function.Function<Notification,
                                          List<NotificationDelivery>> plan) {

        Optional<Notification> existing =
                notifications.findByDeduplicationKey(candidate.getDeduplicationKey());
        if (existing.isPresent()) {
            return new Created(existing.get(),
                    deliveries.findByNotificationIdOrderByChannelAsc(
                            existing.get().getNotificationId()),
                    true);
        }

        try {
            // Flushed before the deliveries are written: a delivery references its notification by the
            // public id, and the foreign key needs the parent row to exist.
            Notification saved = notifications.saveAndFlush(candidate);
            List<NotificationDelivery> rows = plan.apply(saved);
            List<NotificationDelivery> persisted = rows.isEmpty()
                    ? List.of()
                    : deliveries.saveAll(rows);
            return new Created(saved, persisted, false);

        } catch (DataIntegrityViolationException ex) {
            // Another instance won. Re-read rather than retry: the winner has already created the
            // deliveries, and creating a second set would violate uq_delivery_per_channel anyway.
            Notification winner = notifications
                    .findByDeduplicationKey(candidate.getDeduplicationKey())
                    .orElseThrow(() -> ex);

            log.debug("Notification for dedup key {} was created concurrently; using the stored one",
                    candidate.getDeduplicationKey());

            return new Created(winner,
                    deliveries.findByNotificationIdOrderByChannelAsc(winner.getNotificationId()),
                    true);
        }
    }

    /**
     * Records the outcome of one attempt.
     *
     * <p>{@code REQUIRES_NEW} so the attempt record and the delivery's new status commit together and
     * independently of anything the caller is doing. The caller is looping over a batch, and one
     * delivery's failure must not roll back the successes recorded before it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String deliveryId, int attemptNumber, String providerRef,
                              long durationMs) {
        NotificationDelivery delivery = require(deliveryId);
        delivery.markSent();
        attempts.save(DeliveryAttempt.success(delivery, nextAttemptOrdinal(deliveryId), providerRef,
                durationMs));
        deliveries.save(delivery);
    }

    /** Records a failure and schedules another attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRetryableFailure(String deliveryId, int attemptNumber, String errorCode,
                                       String errorMessage, long durationMs, Instant nextAttemptAt) {
        NotificationDelivery delivery = require(deliveryId);
        delivery.markFailed(errorCode + ": " + errorMessage, nextAttemptAt);
        attempts.save(DeliveryAttempt.failure(delivery, nextAttemptOrdinal(deliveryId), errorCode,
                errorMessage, durationMs));
        deliveries.save(delivery);
    }

    /** Records a failure that ends the delivery, whether the budget ran out or the fault is permanent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeadLetter(String deliveryId, int attemptNumber, String errorCode,
                                 String errorMessage, long durationMs) {
        NotificationDelivery delivery = require(deliveryId);
        delivery.markDeadLettered(errorCode + ": " + errorMessage);
        attempts.save(DeliveryAttempt.failure(delivery, nextAttemptOrdinal(deliveryId), errorCode,
                errorMessage, durationMs));
        deliveries.save(delivery);
    }

    /**
     * Atomically leases one due delivery before its transport is called.
     *
     * <p>The row lock is held only while the attempt count and lease are committed. Network I/O still
     * happens outside the transaction, but another listener or worker now sees a future due time and
     * cannot send the same message. A crashed claimant is recovered when the lease expires.
     *
     * @return the 1-based attempt number, or zero when another worker owns the delivery
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int beginAttempt(String deliveryId) {
        NotificationDelivery delivery = deliveries.findByDeliveryIdForUpdate(deliveryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No delivery with id " + deliveryId));
        Instant now = Instant.now();
        if (!delivery.getStatus().isRetryable()
                || delivery.getNextAttemptAt() == null
                || delivery.getNextAttemptAt().isAfter(now)) {
            return 0;
        }
        int attemptNumber = delivery.beginAttempt(now.plus(ATTEMPT_LEASE));
        deliveries.save(delivery);
        return attemptNumber;
    }

    /**
     * Claims a batch of due deliveries.
     *
     * <p>Returns the ids rather than the entities. The rows are locked by {@code FOR UPDATE SKIP LOCKED}
     * only until this transaction commits, and holding that lock across a series of transport calls is
     * exactly what this design set out to avoid. Each delivery is then re-read inside its own short
     * transaction, and the optimistic-lock column catches the one race that leaves: an operator replaying
     * a row between the claim and the send.
     */
    @Transactional
    public List<String> claimDue(int batchSize) {
        return deliveries.claimDue(Instant.now(), batchSize).stream()
                .map(NotificationDelivery::getDeliveryId)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<NotificationDelivery> findDelivery(String deliveryId) {
        return deliveries.findByDeliveryId(deliveryId);
    }

    @Transactional(readOnly = true)
    public long retryBacklog() {
        return deliveries.countRetryBacklog();
    }

    // ---------------------------------------------------------------- inbox

    /**
     * Marks an in-app notification read.
     *
     * <p>Ownership is checked here rather than trusted from the path. The delivery id is opaque, but
     * "unguessable" is not "authorised", and an inbox that returned somebody else's notification because
     * the caller knew its id would be a data leak with an audit trail showing a successful request.
     */
    @Transactional
    public NotificationDelivery markRead(String deliveryId, String requestingUserId) {
        NotificationDelivery delivery = require(deliveryId);

        if (!delivery.getRecipientUserId().equals(requestingUserId)) {
            // Reported as not-found rather than forbidden. A 403 would confirm that this id exists and
            // belongs to somebody, which is more than the caller is entitled to learn.
            throw new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                    "No notification with id " + deliveryId);
        }
        delivery.markRead();
        return deliveries.save(delivery);
    }

    /**
     * Marks every unread in-app notification read.
     *
     * <p>Loaded and mutated rather than issued as a bulk {@code UPDATE}. The volume per user is small,
     * and a bulk update would bypass the entity — which is where the IN_APP-only restriction on
     * {@code read_at} is enforced in Java, and where the optimistic-lock column is maintained.
     */
    @Transactional
    public int markAllRead(String userId) {
        List<NotificationDelivery> unread = deliveries
                .findByRecipientUserIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(
                        userId, NotificationChannel.IN_APP,
                        org.springframework.data.domain.PageRequest.of(0, 500))
                .getContent();

        unread.forEach(NotificationDelivery::markRead);
        deliveries.saveAll(unread);
        return unread.size();
    }

    // ---------------------------------------------------------------- replay

    /**
     * Puts a dead-lettered delivery back in the queue.
     *
     * <p>The attempt budget is extended rather than reset — see {@link NotificationDelivery#requeue} for
     * why a reset would let a permanently broken destination be replayed indefinitely.
     *
     * @param extraAttempts how much further budget this replay is granted
     */
    @Transactional
    public NotificationDelivery replay(String deliveryId, int extraAttempts, int maxAttempts) {
        NotificationDelivery delivery = require(deliveryId);

        if (!delivery.isDeadLettered()) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Delivery " + deliveryId + " is " + delivery.getStatus()
                            + ", not DEAD_LETTERED. Only a delivery that exhausted its retries can "
                            + "be replayed; anything else is either still in progress or already sent.");
        }

        // The budget is an attempt count, so granting N more attempts means resuming from
        // maxAttempts - N. Floored at zero for the case where the grant exceeds the whole budget.
        int resumeFrom = Math.max(0, maxAttempts - Math.max(1, extraAttempts));

        delivery.requeue(Instant.now(), resumeFrom);
        return deliveries.save(delivery);
    }

    // ----------------------------------------------------------- preferences

    /**
     * The stored preferences, creating the default row on first write.
     *
     * <p>Created on write and not on read: a read-time insert would make the delivery path take a write
     * lock on this table, and would fill it with rows recording that nobody had chosen anything.
     */
    @Transactional
    public NotificationPreferences savePreferences(NotificationPreferences updated) {
        return preferences.save(updated);
    }

    @Transactional(readOnly = true)
    public Optional<NotificationPreferences> findPreferences(String userId) {
        return preferences.findByUserId(userId);
    }

    // ---------------------------------------------------------------- helpers

    private int nextAttemptOrdinal(String deliveryId) {
        return attempts.maxAttemptNumber(deliveryId) + 1;
    }

    private NotificationDelivery require(String deliveryId) {
        return deliveries.findByDeliveryId(deliveryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No delivery with id " + deliveryId));
    }

    @Transactional(readOnly = true)
    public long countDeadLettered() {
        return deliveries.countByStatus(DeliveryStatus.DEAD_LETTERED);
    }
}
