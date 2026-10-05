package com.aegispay.orchestrator.repository;

import com.aegispay.orchestrator.domain.PaymentSaga;
import com.aegispay.orchestrator.domain.SagaStatus;
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

public interface PaymentSagaRepository extends JpaRepository<PaymentSaga, Long> {

    /**
     * Looks up the saga for a payment.
     *
     * <p>The first thing the listener does. A hit means this payment is already being
     * orchestrated and the event is a redelivery, so nothing new is started.
     */
    Optional<PaymentSaga> findByPaymentId(String paymentId);

    Optional<PaymentSaga> findBySagaId(String sagaId);

    boolean existsByPaymentId(String paymentId);

    /**
     * Loads a saga with its row locked.
     *
     * <p>Every state transition is read-modify-write on the status column. Without the lock a
     * redelivered event and the recovery sweep could both read {@code AUTHORIZED}, both find the
     * move to {@code LEDGER_POSTED} legal, and both apply it — posting the double entry twice.
     * The state machine only rejects transitions that are illegal from the state it was shown, so
     * it cannot catch that on its own.
     *
     * <p>A 3-second timeout rather than waiting indefinitely: a caller stuck behind a
     * long-running transaction should fail fast and let the retry handle it instead of holding a
     * consumer thread until the connection gives up.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM PaymentSaga s WHERE s.paymentId = :paymentId")
    Optional<PaymentSaga> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT s FROM PaymentSaga s WHERE s.sagaId = :sagaId")
    Optional<PaymentSaga> findBySagaIdForUpdate(@Param("sagaId") String sagaId);

    // ------------------------------------------------------------- recovery

    /**
     * Sagas that are neither finished nor progressing.
     *
     * <p>The recovery sweep's query. {@code updatedAt} rather than {@code createdAt}: a saga
     * legitimately waiting on a slow provider is still making progress and must not be
     * disturbed, whereas one whose last write was minutes ago has almost certainly lost its
     * orchestrating thread to a crash or a pod eviction.
     *
     * <p><strong>{@code RISK_HELD} is excluded, and that exclusion is load-bearing.</strong> A payment
     * held for fraud review is parked deliberately and may sit for hours waiting on an analyst; its
     * {@code updatedAt} stops moving precisely because it is behaving as designed. Without this
     * clause every held payment would be "recovered" on every sweep — each pass incrementing its
     * attempt count and re-entering a saga that has nothing to do — until a perfectly healthy held
     * payment looked like a chronic failure. Nothing would break loudly; the signal that distinguishes
     * an abandoned saga from a waiting one would just quietly stop working.
     *
     * <p>Batched via {@link Limit} so one sweep cannot lock an unbounded number of rows and stall
     * live payments while it works through a backlog.
     */
    @Query("""
            SELECT s FROM PaymentSaga s
            WHERE s.status NOT IN (
                    com.aegispay.orchestrator.domain.SagaStatus.COMPLETED,
                    com.aegispay.orchestrator.domain.SagaStatus.FAILED,
                    com.aegispay.orchestrator.domain.SagaStatus.REJECTED,
                    com.aegispay.orchestrator.domain.SagaStatus.RISK_HELD)
              AND s.updatedAt < :staleBefore
            ORDER BY s.updatedAt
            """)
    List<PaymentSaga> findStalled(@Param("staleBefore") Instant staleBefore, Limit limit);

    /**
     * Payments parked awaiting a fraud analyst, longest-waiting first.
     *
     * <p>Every row is a customer whose funds are reserved and who cannot spend them. Expected to be
     * small and short-lived, so this is what an operator alert points at rather than total in-flight
     * depth — and it is deliberately a separate query from {@link #findStalled} because these must
     * never be recovered, only reported.
     */
    @Query("""
            SELECT s FROM PaymentSaga s
            WHERE s.status = com.aegispay.orchestrator.domain.SagaStatus.RISK_HELD
            ORDER BY s.riskHeldAt
            """)
    List<PaymentSaga> findHeldForReview(Limit limit);

    long countByStatusAndRiskHeldAtBefore(SagaStatus status, Instant before);

    /**
     * Sagas that failed with a side effect possibly still outstanding.
     *
     * <p>Expected to be empty. Every row is money that may be reserved or authorised with nothing
     * scheduled to release it, so this is what an operator alert points at.
     */
    @Query("""
            SELECT s FROM PaymentSaga s
            WHERE s.compensationComplete = false
            ORDER BY s.createdAt DESC
            """)
    List<PaymentSaga> findUncompensated(Limit limit);

    // ------------------------------------------------------------- reporting

    Page<PaymentSaga> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PaymentSaga> findByStatusOrderByCreatedAtDesc(SagaStatus status, Pageable pageable);

    Page<PaymentSaga> findByProviderIdOrderByCreatedAtDesc(String providerId, Pageable pageable);

    long countByStatus(SagaStatus status);

    long countByCompensationComplete(boolean compensationComplete);

    /** How many sagas each provider settled, for the admin overview. */
    @Query("""
            SELECT s.providerId, COUNT(s) FROM PaymentSaga s
            WHERE s.providerId IS NOT NULL
              AND s.status = com.aegispay.orchestrator.domain.SagaStatus.COMPLETED
            GROUP BY s.providerId
            """)
    List<Object[]> countCompletedByProvider();

    /**
     * Sagas that needed more than one provider.
     *
     * <p>A non-zero count is the signal that routing actually did its job, which is otherwise
     * invisible: a payment that fell back still looks like an ordinary success.
     */
    @Query("""
            SELECT COUNT(s) FROM PaymentSaga s
            WHERE s.providerAttempts IS NOT NULL AND s.providerAttempts LIKE '%,%'
            """)
    long countWithFallback();
}
