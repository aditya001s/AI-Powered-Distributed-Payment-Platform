package com.aegispay.orchestrator.repository;

import com.aegispay.orchestrator.domain.SagaStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

    List<SagaStep> findBySagaIdOrderByStartedAtAsc(String sagaId);

    List<SagaStep> findByPaymentIdOrderByStartedAtAsc(String paymentId);

    /**
     * The next attempt number for a step.
     *
     * <p>Read rather than tracked in memory, so a saga resumed by the recovery sweep continues the
     * numbering instead of restarting at 1 and colliding with the row its predecessor already
     * wrote.
     */
    @Query("""
            SELECT COALESCE(MAX(s.attempt), 0) + 1 FROM SagaStep s
            WHERE s.sagaId = :sagaId AND s.stepName = :stepName
            """)
    int nextAttemptNumber(@Param("sagaId") String sagaId,
                          @Param("stepName") SagaStep.Name stepName);

    /**
     * The most recent successful attempt at a step, if there was one.
     *
     * <p>Used when resuming: a step that already succeeded must not run again, because most of
     * them are not idempotent from the saga's point of view even where the callee is.
     */
    @Query("""
            SELECT s FROM SagaStep s
            WHERE s.sagaId = :sagaId AND s.stepName = :stepName
              AND s.status = com.aegispay.orchestrator.domain.SagaStep$Status.SUCCEEDED
            ORDER BY s.attempt DESC
            LIMIT 1
            """)
    Optional<SagaStep> findSucceeded(@Param("sagaId") String sagaId,
                                     @Param("stepName") SagaStep.Name stepName);

    /**
     * Steps left in flight.
     *
     * <p>Each one means the side effect may or may not have happened, which is exactly what
     * compensation has to assume. Used by the recovery sweep to decide what to undo.
     */
    @Query("""
            SELECT s FROM SagaStep s
            WHERE s.sagaId = :sagaId
              AND s.status = com.aegispay.orchestrator.domain.SagaStep$Status.ATTEMPTED
            ORDER BY s.startedAt
            """)
    List<SagaStep> findInFlight(@Param("sagaId") String sagaId);

    long countBySagaId(String sagaId);
}
