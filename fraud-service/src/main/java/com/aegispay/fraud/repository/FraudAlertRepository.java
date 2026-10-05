package com.aegispay.fraud.repository;

import com.aegispay.fraud.domain.FraudAlert;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    Optional<FraudAlert> findByAlertId(String alertId);

    Optional<FraudAlert> findByPaymentId(String paymentId);

    boolean existsByPaymentId(String paymentId);

    /**
     * Loads an alert with its row locked, for a decision.
     *
     * <p>Resolving an alert is read-modify-write on its status, and two analysts deciding at the same
     * moment must not both succeed — one would release the payment and the other reject it, and
     * whichever committed second would win by accident. The lock makes the second wait, re-read, and
     * find the alert already decided.
     *
     * <p>A three-second timeout rather than waiting indefinitely: an analyst clicking a button should
     * get a clear conflict rather than a hung request.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT a FROM FraudAlert a WHERE a.alertId = :alertId")
    Optional<FraudAlert> findByAlertIdForUpdate(@Param("alertId") String alertId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT a FROM FraudAlert a WHERE a.paymentId = :paymentId")
    Optional<FraudAlert> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);

    /**
     * The work queue: unresolved alerts, most urgent first.
     *
     * <p>Ordered by {@code blocksPayment} before risk score, deliberately. A held payment is a
     * customer waiting right now with their funds reserved; a review on a payment that has already
     * settled can wait. Sorting by score alone would bury the first behind the second, and the
     * customer would feel it.
     */
    @Query("""
            SELECT a FROM FraudAlert a
            WHERE a.status <> com.aegispay.fraud.domain.FraudAlert$Status.RESOLVED
            ORDER BY a.blocksPayment DESC, a.riskScore DESC, a.createdAt ASC
            """)
    Page<FraudAlert> findQueue(Pageable pageable);

    @Query("""
            SELECT a FROM FraudAlert a
            WHERE a.status <> com.aegispay.fraud.domain.FraudAlert$Status.RESOLVED
              AND a.blocksPayment = true
            ORDER BY a.riskScore DESC, a.createdAt ASC
            """)
    Page<FraudAlert> findBlockingQueue(Pageable pageable);

    Page<FraudAlert> findByStatusOrderByCreatedAtDesc(FraudAlert.Status status, Pageable pageable);

    Page<FraudAlert> findByInitiatorUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByStatus(FraudAlert.Status status);

    /**
     * Open alerts that are holding a payment.
     *
     * <p>Expected to be small and short-lived. Every one is a customer whose funds are reserved
     * waiting on a human, so this is the number an operator alert should point at rather than the
     * total queue depth.
     */
    @Query("""
            SELECT COUNT(a) FROM FraudAlert a
            WHERE a.status <> com.aegispay.fraud.domain.FraudAlert$Status.RESOLVED
              AND a.blocksPayment = true
            """)
    long countBlockingOpen();
}
