package com.aegispay.ai.repository;

import com.aegispay.ai.domain.Investigation;
import com.aegispay.ai.domain.Recommendation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    Optional<Investigation> findByInvestigationId(String investigationId);

    /**
     * The idempotency lookup for the automatic path.
     *
     * <p>Kafka delivers at least once, so {@code investigation-requested} will be seen twice. A second
     * report would cost another LLM call and — worse — give two analysts two differently-worded
     * narratives about the same payment, with no way to tell which one the other had read.
     *
     * <p>Scoped to {@code onDemand = false} so an analyst can still legitimately re-run an
     * investigation without the automatic pass being treated as already done.
     */
    @Query("SELECT i FROM Investigation i WHERE i.paymentId = :paymentId AND i.onDemand = false")
    Optional<Investigation> findAutomaticForPayment(@Param("paymentId") String paymentId);

    /** Every investigation on one payment, automatic and on-demand, newest first. */
    List<Investigation> findByPaymentIdOrderByCreatedAtDesc(String paymentId);

    boolean existsByPaymentIdAndOnDemandFalse(String paymentId);

    // ------------------------------------------------------------- reporting

    Page<Investigation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Investigation> findByRecommendationOrderByCreatedAtDesc(Recommendation recommendation,
                                                                 Pageable pageable);

    Page<Investigation> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByCreatedAtAfter(Instant since);

    /**
     * Reports written with no policy behind them.
     *
     * <p>Operators watch this. A rising count means the knowledge base has drifted away from the
     * traffic, so narratives are being produced with nothing written to support them — and because an
     * ungrounded report reads exactly like a grounded one, nothing else would report it.
     */
    long countByRetrievedChunksAndCreatedAtAfter(int retrievedChunks, Instant since);

    /**
     * Reports where the generator cited evidence that was never retrieved.
     *
     * <p>Expected to be zero. Every one is a report containing something that looks like a source and
     * is not, which is the most damaging output this service can produce.
     */
    @Query("SELECT COUNT(i) FROM Investigation i "
            + "WHERE i.hallucinatedCitations > 0 AND i.createdAt >= :since")
    long countWithHallucinations(@Param("since") Instant since);

    long countByDeterministicFallbackAndCreatedAtAfter(boolean deterministicFallback, Instant since);

    /** Recommendation mix in a window, for the overview. */
    @Query("""
            SELECT i.recommendation, COUNT(i) FROM Investigation i
            WHERE i.createdAt >= :since
            GROUP BY i.recommendation
            """)
    List<Object[]> countByRecommendationSince(@Param("since") Instant since);

    /**
     * Mean confidence per generator in a window.
     *
     * <p>Grouped by generator so a change can be attributed to a model swap rather than to the world —
     * and because the offline composer's confidence and an LLM's are not the same kind of number, so
     * averaging them together would produce a figure describing neither.
     */
    @Query("""
            SELECT i.generatedBy, COUNT(i), AVG(i.confidence), AVG(i.retrievedChunks)
            FROM Investigation i
            WHERE i.createdAt >= :since
            GROUP BY i.generatedBy
            """)
    List<Object[]> summariseByGenerator(@Param("since") Instant since);

    @Query("""
            SELECT COALESCE(AVG(i.generationLatencyMs), 0) FROM Investigation i
            WHERE i.createdAt >= :since
            """)
    double averageGenerationLatencyMs(@Param("since") Instant since);
}
