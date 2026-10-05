package com.aegispay.orchestrator.repository;

import com.aegispay.orchestrator.domain.ProviderAttempt;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ProviderAttemptRepository extends JpaRepository<ProviderAttempt, Long> {

    List<ProviderAttempt> findByPaymentIdOrderByCreatedAtAsc(String paymentId);

    List<ProviderAttempt> findBySagaIdOrderByCreatedAtAsc(String sagaId);

    /**
     * Recent authorisation attempts for one provider.
     *
     * <p>The router's raw signal. Scoped to {@code AUTHORIZE} because health probes have very
     * different latency characteristics from real charges, and mixing them would flatter a
     * provider that answers probes quickly while struggling with actual work.
     */
    @Query("""
            SELECT a FROM ProviderAttempt a
            WHERE a.providerId = :providerId
              AND a.operation = com.aegispay.orchestrator.domain.ProviderAttempt$Operation.AUTHORIZE
              AND a.createdAt >= :since
            ORDER BY a.createdAt DESC
            """)
    List<ProviderAttempt> findRecentAuthorizations(@Param("providerId") String providerId,
                                                   @Param("since") Instant since,
                                                   Limit limit);

    /**
     * Mean latency of recent successful authorisations.
     *
     * <p>Successful ones only. Including timeouts would report the timeout ceiling rather than how
     * fast the provider actually is, and a provider that fails instantly would score as the
     * fastest available.
     */
    @Query("""
            SELECT COALESCE(AVG(a.latencyMs), 0) FROM ProviderAttempt a
            WHERE a.providerId = :providerId
              AND a.operation = com.aegispay.orchestrator.domain.ProviderAttempt$Operation.AUTHORIZE
              AND a.outcome = com.aegispay.orchestrator.domain.ProviderAttempt$Outcome.APPROVED
              AND a.createdAt >= :since
            """)
    double averageApprovedLatencyMs(@Param("providerId") String providerId,
                                    @Param("since") Instant since);

    /**
     * Technical failures in the window.
     *
     * <p>Declines are deliberately excluded: a provider refusing a payment is working correctly,
     * and counting that against it would route traffic away from a healthy provider on the
     * strength of its customers' balances.
     */
    @Query("""
            SELECT COUNT(a) FROM ProviderAttempt a
            WHERE a.providerId = :providerId
              AND a.operation = com.aegispay.orchestrator.domain.ProviderAttempt$Operation.AUTHORIZE
              AND a.outcome IN (
                    com.aegispay.orchestrator.domain.ProviderAttempt$Outcome.FAILED,
                    com.aegispay.orchestrator.domain.ProviderAttempt$Outcome.TIMEOUT)
              AND a.createdAt >= :since
            """)
    long countTechnicalFailures(@Param("providerId") String providerId,
                                @Param("since") Instant since);

    @Query("""
            SELECT COUNT(a) FROM ProviderAttempt a
            WHERE a.providerId = :providerId
              AND a.operation = com.aegispay.orchestrator.domain.ProviderAttempt$Operation.AUTHORIZE
              AND a.createdAt >= :since
            """)
    long countAuthorizations(@Param("providerId") String providerId,
                             @Param("since") Instant since);

    long countByProviderIdAndOutcome(String providerId, ProviderAttempt.Outcome outcome);
}
