package com.aegispay.ai.repository;

import com.aegispay.ai.domain.InvestigationReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Reads and appends human verdicts on recommendations.
 *
 * <p>Deliberately exposes no update or delete beyond what {@link JpaRepository} inherits, and the table
 * refuses both by trigger regardless. Whether an analyst agreed with a machine about a customer's
 * payment must not be rewritable through any route.
 */
public interface InvestigationReviewRepository extends JpaRepository<InvestigationReview, Long> {

    /**
     * Every review of one report, oldest first.
     *
     * <p>Plural on purpose. A report normally has one review, but the audit question is "what did
     * people make of this", and a list answers that even when a second analyst revisited it.
     */
    List<InvestigationReview> findByInvestigationIdOrderByReviewedAtAsc(String investigationId);

    List<InvestigationReview> findByPaymentIdOrderByReviewedAtAsc(String paymentId);

    boolean existsByInvestigationId(String investigationId);

    /** One analyst's recent reviews, for oversight of the people doing the reviewing. */
    List<InvestigationReview> findByReviewedByOrderByReviewedAtDesc(String reviewedBy);

    long countByAgreedAndReviewedAtAfter(boolean agreed, Instant since);

    /**
     * The agreement rate, split by what the AI recommended.
     *
     * <p><strong>The measure of whether this service earns its keep.</strong> An overall rate hides the
     * thing worth knowing: a model that is trusted on {@code MONITOR_ACCOUNT} and overruled on every
     * {@code RECOMMEND_REJECT_AND_REVIEW_ACCOUNT} is not 70% useful, it is useless exactly where the
     * stakes are highest.
     *
     * @return {@code [recommendation, reviews, agreements]}
     */
    @Query("""
            SELECT i.recommendation, COUNT(r), SUM(CASE WHEN r.agreed THEN 1 ELSE 0 END)
            FROM InvestigationReview r, com.aegispay.ai.domain.Investigation i
            WHERE r.investigationId = i.investigationId
              AND r.reviewedAt >= :since
            GROUP BY i.recommendation
            """)
    List<Object[]> agreementByRecommendationSince(@Param("since") Instant since);
}
