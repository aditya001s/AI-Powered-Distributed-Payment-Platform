package com.aegispay.notification.repository;

import com.aegispay.notification.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Reads over the attempt history.
 *
 * <p>Deliberately declares no modifying query and no delete. The table is append-only at the database
 * too, so a repository method that tried would fail at runtime; not having one means it fails at
 * compile time instead.
 */
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

    List<DeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(String deliveryId);

    List<DeliveryAttempt> findByNotificationIdOrderByAttemptedAtAsc(String notificationId);

    @Query("SELECT COALESCE(MAX(a.attemptNumber), 0) FROM DeliveryAttempt a "
            + "WHERE a.deliveryId = :deliveryId")
    int maxAttemptNumber(@Param("deliveryId") String deliveryId);
}
