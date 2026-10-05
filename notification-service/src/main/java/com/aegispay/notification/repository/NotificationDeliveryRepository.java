package com.aegispay.notification.repository;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.notification.domain.DeliveryStatus;
import com.aegispay.notification.domain.NotificationDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    Optional<NotificationDelivery> findByDeliveryId(String deliveryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM NotificationDelivery d WHERE d.deliveryId = :deliveryId")
    Optional<NotificationDelivery> findByDeliveryIdForUpdate(@Param("deliveryId") String deliveryId);

    List<NotificationDelivery> findByNotificationIdOrderByChannelAsc(String notificationId);

    Optional<NotificationDelivery> findByNotificationIdAndChannel(String notificationId,
                                                                  NotificationChannel channel);

    /**
     * Claims a batch of due deliveries for this worker.
     *
     * <p><strong>{@code FOR UPDATE SKIP LOCKED} is what makes the worker safe to run on every
     * instance.</strong> Without it, two pods would read the same rows, both send, and the customer
     * would get the message twice — the one failure mode this whole service is built to avoid. With a
     * plain {@code FOR UPDATE} they would not duplicate but would serialise: the second pod would
     * block on the first pod's rows for the length of an SMTP timeout, so adding instances would make
     * the backlog drain no faster.
     *
     * <p><strong>Ordered by priority, then by due time.</strong> A security alert must not queue behind
     * ten thousand routine retries that happen to have come due first. The rank is a CASE rather than
     * an {@code ORDER BY priority} because the column stores the enum name, and alphabetically
     * {@code HIGH} sorts before {@code LOW} before {@code NORMAL} — which would put the least urgent
     * notifications in the middle and look approximately right in testing.
     *
     * <p>A native query because JPQL cannot express {@code SKIP LOCKED}. Hibernate can be coaxed into
     * it with a lock-timeout hint of -2, which is an undocumented magic number in a place nobody would
     * think to look; the SQL says what it does.
     *
     * @param now       cutoff; a row scheduled for the future is not due
     * @param batchSize bounded so one round cannot hold a transaction open across thousands of
     *                  transport calls
     */
    @Query(value = """
            SELECT * FROM notification_deliveries
            WHERE status IN ('PENDING', 'FAILED')
              AND next_attempt_at IS NOT NULL
              AND next_attempt_at <= :now
            ORDER BY CASE priority
                         WHEN 'HIGH' THEN 0
                         WHEN 'NORMAL' THEN 1
                         ELSE 2
                     END,
                     next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationDelivery> claimDue(@Param("now") Instant now,
                                        @Param("batchSize") int batchSize);

    /**
     * How much work is waiting.
     *
     * <p>Feeds a gauge rather than an alert on its own. A backlog is normal; a backlog that stops
     * falling is not, and only the trend distinguishes them.
     */
    @Query("""
            SELECT COUNT(d) FROM NotificationDelivery d
            WHERE d.status IN (com.aegispay.notification.domain.DeliveryStatus.PENDING,
                               com.aegispay.notification.domain.DeliveryStatus.FAILED)
            """)
    long countRetryBacklog();

    // ------------------------------------------------------------------- inbox

    /**
     * The in-app inbox, newest first.
     *
     * <p>Filtered to IN_APP in the query rather than left to the caller. An inbox that accidentally
     * listed EMAIL rows would show the customer their own email copy, including the rendered subject
     * line, which is both wrong and confusing.
     */
    Page<NotificationDelivery> findByRecipientUserIdAndChannelOrderByCreatedAtDesc(
            String recipientUserId, NotificationChannel channel, Pageable pageable);

    Page<NotificationDelivery> findByRecipientUserIdAndChannelAndReadAtIsNullOrderByCreatedAtDesc(
            String recipientUserId, NotificationChannel channel, Pageable pageable);

    long countByRecipientUserIdAndChannelAndReadAtIsNull(String recipientUserId,
                                                          NotificationChannel channel);

    // -------------------------------------------------------------- oversight

    Page<NotificationDelivery> findByStatusOrderByUpdatedAtDesc(DeliveryStatus status,
                                                                 Pageable pageable);

    Page<NotificationDelivery> findByChannelAndStatusOrderByUpdatedAtDesc(
            NotificationChannel channel, DeliveryStatus status, Pageable pageable);

    long countByStatus(DeliveryStatus status);

    long countByChannelAndStatus(NotificationChannel channel, DeliveryStatus status);

    long countByStatusAndUpdatedAtAfter(DeliveryStatus status, Instant since);

    long countByCreatedAtAfter(Instant since);

    /**
     * Delivery outcomes grouped for the overview.
     *
     * <p>A projection rather than loading rows and counting in Java: this runs on an admin dashboard
     * over the whole table, and the difference between one aggregate query and a full scan into memory
     * is the difference between a page that loads and one that times out.
     */
    @Query("""
            SELECT d.channel, d.status, COUNT(d)
            FROM NotificationDelivery d
            WHERE d.createdAt > :since
            GROUP BY d.channel, d.status
            """)
    List<Object[]> summariseSince(@Param("since") Instant since);
}
