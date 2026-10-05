package com.aegispay.payment.repository;

import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByTransactionId(String transactionId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * Loads a payment with its row locked, for a state transition.
     *
     * <p>State changes are read-modify-write against the status column, so they need the
     * same protection as a balance. Without the lock, two concurrent events — a saga step
     * and a duplicate Kafka delivery, say — could both read {@code AUTHORIZED}, both find
     * the transition to {@code CAPTURED} legal, and both apply it. The state machine alone
     * cannot prevent that; it only rejects illegal transitions from the state it was shown.
     *
     * <p>{@code @Version} would also catch it, but as a failed optimistic lock after the
     * fact. Taking the row lock up front turns a retry-and-fail into a short wait, which is
     * the better outcome on the payment path.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT p FROM Payment p WHERE p.paymentId = :paymentId")
    Optional<Payment> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);

    /** Payment history for the authenticated user, newest first. */
    Page<Payment> findByInitiatorUserIdOrderByCreatedAtDesc(String initiatorUserId, Pageable pageable);

    Page<Payment> findByInitiatorUserIdAndStatusOrderByCreatedAtDesc(
            String initiatorUserId, PaymentStatus status, Pageable pageable);

    /**
     * Everything touching a wallet, in either direction.
     *
     * <p>A transfer appears in the payer's and the payee's history, so filtering on
     * {@code initiator_user_id} alone would hide received payments from the recipient.
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.sourceWalletId = :walletId OR p.destinationWalletId = :walletId
            ORDER BY p.createdAt DESC
            """)
    Page<Payment> findByWallet(@Param("walletId") String walletId, Pageable pageable);

    /**
     * Payments stuck mid-flight past a cutoff.
     *
     * <p>Feeds the stale-payment monitor. A payment that has been in a non-terminal state
     * for longer than the saga's budget means something was lost — a crashed coordinator,
     * a dropped event — and it is holding a customer's funds reserved while it sits there.
     *
     * <p>Bounded by {@link Limit} so the monitor cannot pull an unbounded result set during
     * an incident, which is exactly when the backlog is largest.
     */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status IN ('CREATED', 'FUNDS_RESERVED', 'RISK_ASSESSED', 'AUTHORIZED', 'CAPTURED')
              AND p.createdAt < :before
            ORDER BY p.createdAt
            """)
    List<Payment> findStalled(@Param("before") Instant before, Limit limit);

    long countByStatus(PaymentStatus status);

    @Query("""
            SELECT COALESCE(SUM(p.amountMinor), 0) FROM Payment p
            WHERE p.status = 'COMPLETED' AND p.currency = :currency AND p.createdAt >= :since
            """)
    long completedVolumeMinor(@Param("currency") String currency, @Param("since") Instant since);
}
