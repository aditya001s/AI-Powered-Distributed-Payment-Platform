package com.aegispay.fraud.repository;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.fraud.domain.FraudAssessment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FraudAssessmentRepository extends JpaRepository<FraudAssessment, Long> {

    /**
     * The idempotency lookup.
     *
     * <p>The first thing the scoring path does. A hit means this payment has already been assessed
     * and the stored verdict is returned unchanged — the saga can legitimately ask twice, and a
     * payment whose risk verdict changed between attempts would be indefensible in a dispute.
     */
    Optional<FraudAssessment> findByPaymentId(String paymentId);

    Optional<FraudAssessment> findByAssessmentId(String assessmentId);

    boolean existsByPaymentId(String paymentId);

    // ------------------------------------------------------------- reporting

    Page<FraudAssessment> findAllByOrderByEvaluatedAtDesc(Pageable pageable);

    Page<FraudAssessment> findByRiskLevelOrderByEvaluatedAtDesc(RiskLevel riskLevel,
                                                                Pageable pageable);

    Page<FraudAssessment> findByDecisionOrderByEvaluatedAtDesc(FraudDecision decision,
                                                               Pageable pageable);

    Page<FraudAssessment> findByInitiatorUserIdOrderByEvaluatedAtDesc(String userId,
                                                                      Pageable pageable);

    long countByDecision(FraudDecision decision);

    long countByRiskLevel(RiskLevel riskLevel);

    /**
     * Verdicts reached without the model in a window.
     *
     * <p>Operators watch this. A rising count means ml-service is unreachable and every decision is
     * being made on rules alone — which is a degraded fraud posture that produces no errors and would
     * otherwise be invisible.
     */
    long countByDegradedModeAndEvaluatedAtAfter(boolean degradedMode, Instant since);

    /**
     * Mean risk score per model build in a window.
     *
     * <p>The drift query. A fraud model degrades silently — it keeps returning numbers — and the
     * first thing that moves is the distribution of those numbers. Grouped by version so a shift can
     * be attributed to a deployment rather than to the world.
     *
     * @return {@code [modelVersion, count, avgRiskScore]}
     */
    @Query("""
            SELECT a.modelVersion, COUNT(a), AVG(a.riskScore) FROM FraudAssessment a
            WHERE a.evaluatedAt >= :since
              AND a.modelVersion IS NOT NULL
            GROUP BY a.modelVersion
            """)
    List<Object[]> summariseScoresByModelVersion(@Param("since") Instant since);

    /** Decision mix in a window, for the admin overview. */
    @Query("""
            SELECT a.decision, COUNT(a) FROM FraudAssessment a
            WHERE a.evaluatedAt >= :since
            GROUP BY a.decision
            """)
    List<Object[]> countByDecisionSince(@Param("since") Instant since);

    /**
     * Mean scoring latency in a window.
     *
     * <p>Tracked because this call sits on the payment's critical path: if it slows down, every
     * payment slows down, and the saga's provider timeout budget shrinks accordingly.
     */
    @Query("""
            SELECT COALESCE(AVG(a.scoringLatencyMs), 0) FROM FraudAssessment a
            WHERE a.evaluatedAt >= :since
            """)
    double averageScoringLatencyMs(@Param("since") Instant since);
}
