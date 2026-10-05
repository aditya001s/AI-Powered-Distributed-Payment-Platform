package com.aegispay.ai.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A human's verdict on an AI recommendation. Append-only.
 *
 * <p><strong>This table is the authority boundary made visible.</strong> The AI recommends; a person
 * agrees or disagrees, and their agreement is the only thing any action ever rests on. Recording that
 * decision here — rather than leaving it implicit in whatever the analyst went on to do in
 * fraud-service — makes one question answerable: <em>was the recommendation followed?</em>
 *
 * <p>That question is how anyone finds out whether this service is worth running. A recommendation
 * nobody follows is worse than no recommendation, because it costs an analyst a read and teaches them
 * to skip the next one. {@link #agreed} plus {@link #analystAssessment} makes the agreement rate a
 * query rather than a guess, and a falling rate is the signal to retune the prompt or the corpus.
 *
 * <p>Recording the analyst's own assessment in the <em>same</em> closed vocabulary as the
 * recommendation is deliberate. "Disagreed" alone says nothing about the direction of the
 * disagreement, and a model that is consistently one step too cautious needs a different fix from one
 * that is consistently too relaxed.
 */
@Entity
@Table(name = "investigation_reviews")
public class InvestigationReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "review_id", nullable = false, unique = true, updatable = false, length = 48)
    private String reviewId;

    @Column(name = "investigation_id", nullable = false, updatable = false, length = 48)
    private String investigationId;

    @Column(name = "payment_id", nullable = false, updatable = false, length = 48)
    private String paymentId;

    @Column(name = "agreed", nullable = false, updatable = false)
    private boolean agreed;

    @Enumerated(EnumType.STRING)
    @Column(name = "analyst_assessment", nullable = false, updatable = false, length = 48)
    private Recommendation analystAssessment;

    @Column(name = "notes", nullable = false, updatable = false, length = 1024)
    private String notes;

    /**
     * The analyst, from the validated token and never from a request field.
     *
     * <p>"Who signed off on this" is the first question asked when a recommendation was followed and
     * turned out to be wrong, and an answer the caller could choose for itself would be worthless.
     */
    @Column(name = "reviewed_by", nullable = false, updatable = false, length = 40)
    private String reviewedBy;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private Instant reviewedAt = Instant.now();

    protected InvestigationReview() {
        // JPA
    }

    /**
     * Records a review.
     *
     * <p>{@code agreed} is derived from whether the analyst's assessment matches the recommendation
     * rather than taken from the caller. A client that could set both independently would let the two
     * disagree — "agreed: true" alongside a different assessment — and the agreement rate, which is
     * the whole point of this table, would quietly stop meaning anything.
     *
     * @param recommendation what the investigation advised, for the comparison
     */
    public static InvestigationReview record(String investigationId, String paymentId,
                                             Recommendation recommendation,
                                             Recommendation analystAssessment, String notes,
                                             String reviewedBy) {
        if (investigationId == null || investigationId.isBlank()) {
            throw new IllegalArgumentException("a review must name the investigation it read");
        }
        if (analystAssessment == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "A review must state the analyst's own assessment, using the same vocabulary "
                            + "as the recommendation");
        }
        if (notes == null || notes.isBlank()) {
            // The only record of why a person accepted or overrode a machine's reading of a
            // customer's payment. Enforced here and by a schema CHECK, because support tooling also
            // writes to this table.
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "A review requires notes: they are the only record of why an analyst agreed "
                            + "or disagreed with the recommendation");
        }
        if (reviewedBy == null || reviewedBy.isBlank()) {
            throw new IllegalArgumentException(
                    "a review must name the analyst, taken from their token");
        }

        InvestigationReview review = new InvestigationReview();
        review.reviewId = "REV-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase();
        review.investigationId = investigationId;
        review.paymentId = paymentId;
        review.analystAssessment = analystAssessment;
        review.agreed = analystAssessment == recommendation;
        review.notes = notes.length() <= 1_024 ? notes : notes.substring(0, 1_021) + "...";
        review.reviewedBy = reviewedBy;
        review.reviewedAt = Instant.now();
        return review;
    }

    /**
     * How far the analyst departed from the recommendation, in severity steps.
     *
     * <p>Signed: positive means the analyst was more cautious than the AI, negative means less. The
     * sign is what distinguishes a model that under-calls risk from one that over-calls it, and those
     * need opposite fixes.
     */
    public int severityDelta(Recommendation recommendation) {
        if (recommendation == null) {
            return 0;
        }
        return analystAssessment.severity() - recommendation.severity();
    }

    public Long getId() {
        return id;
    }

    public String getReviewId() {
        return reviewId;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public boolean isAgreed() {
        return agreed;
    }

    public Recommendation getAnalystAssessment() {
        return analystAssessment;
    }

    public String getNotes() {
        return notes;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    @Override
    public String toString() {
        return "InvestigationReview{" + reviewId + ", " + (agreed ? "agreed" : "disagreed")
                + " -> " + analystAssessment + " by " + reviewedBy + '}';
    }
}
