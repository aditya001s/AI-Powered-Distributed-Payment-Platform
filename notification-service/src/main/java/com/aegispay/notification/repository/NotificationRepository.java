package com.aegispay.notification.repository;

import com.aegispay.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByNotificationId(String notificationId);

    /**
     * The idempotency lookup.
     *
     * <p>An optimisation, not the guarantee. Two consumer threads on two instances would both find
     * nothing here and both insert; the UNIQUE constraint on {@code deduplication_key} is what
     * actually arbitrates. This query exists so the common case — a redelivered event — costs a read
     * rather than a failed insert and a rolled-back transaction.
     */
    Optional<Notification> findByDeduplicationKey(String deduplicationKey);

    boolean existsByDeduplicationKey(String deduplicationKey);

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId,
                                                                 Pageable pageable);

    Page<Notification> findByPaymentIdOrderByCreatedAtDesc(String paymentId, Pageable pageable);

    long countByCreatedAtAfter(Instant since);
}
