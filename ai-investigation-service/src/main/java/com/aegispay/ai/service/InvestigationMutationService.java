package com.aegispay.ai.service;

import com.aegispay.ai.domain.Investigation;
import com.aegispay.ai.domain.InvestigationCitation;
import com.aegispay.ai.domain.InvestigationReview;
import com.aegispay.ai.domain.Recommendation;
import com.aegispay.ai.repository.InvestigationCitationRepository;
import com.aegispay.ai.repository.InvestigationRepository;
import com.aegispay.ai.repository.InvestigationReviewRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.InvestigationPayloads.InvestigationCompletedPayload;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.outbox.OutboxRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every transaction that writes investigation state, plus the events that accompany it.
 *
 * <h2>Why this is a separate bean from {@link InvestigationService}</h2>
 *
 * <p>Generating a report makes an HTTP call to a language model with a twenty-second budget. A transaction
 * spanning that would pin a database connection across the network for the duration, and under load the
 * pool empties and the service stalls waiting on a dependency it cannot hurry. So the orchestration is
 * non-transactional and every write lives here, behind a real bean boundary — a {@code this.method()} call
 * would bypass Spring's proxy and run untransacted with no symptom.
 *
 * <h2>Why the report, its citations and its event share one transaction</h2>
 *
 * <p>A report committed without its citations would be a narrative whose claims cannot be checked, and
 * checking them is the entire reason the citations exist. A report committed without its event would leave
 * anything downstream permanently unaware that the investigation happened. Both land in one commit, and
 * the event goes through the outbox so "the report exists" and "the world was told" are atomic without a
 * distributed transaction across PostgreSQL and Kafka.
 */
@Service
public class InvestigationMutationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationMutationService.class);

    private static final String SOURCE = "ai-investigation-service";
    private static final String AGGREGATE = "Payment";

    private final InvestigationRepository investigations;
    private final InvestigationCitationRepository citations;
    private final InvestigationReviewRepository reviews;
    private final OutboxRecorder outbox;

    public InvestigationMutationService(InvestigationRepository investigations,
                                        InvestigationCitationRepository citations,
                                        InvestigationReviewRepository reviews,
                                        OutboxRecorder outbox) {
        this.investigations = investigations;
        this.citations = citations;
        this.reviews = reviews;
        this.outbox = outbox;
    }

    /**
     * Persists a report with its citations and announces it.
     *
     * <p>The investigation is flushed before the citations are written, because a citation names the
     * investigation it belongs to by its public id and the foreign key needs the parent row to exist.
     *
     * @param builder     a fully-populated builder; the aggregate validates it
     * @param citationsOf a function from the assigned investigation id to its citation rows, because a
     *                    citation cannot be constructed until the id it references has been generated
     */
    @Transactional
    public Investigation record(Investigation.Builder builder,
                                java.util.function.Function<String, List<InvestigationCitation>>
                                        citationsOf) {

        Investigation investigation = investigations.saveAndFlush(builder.build());
        List<InvestigationCitation> rows = citationsOf.apply(investigation.getInvestigationId());

        if (!rows.isEmpty()) {
            citations.saveAll(rows);
        }

        outbox.record(KafkaTopics.INVESTIGATION_COMPLETED, AGGREGATE, investigation.getPaymentId(),
                EventEnvelope.of(KafkaTopics.INVESTIGATION_COMPLETED, AGGREGATE,
                        investigation.getPaymentId(), SOURCE, toPayload(investigation, rows)));

        if (investigation.hasHallucinatedCitations()) {
            log.error("""
                            Investigation {} for payment {} cited {} source(s) that were never \
                            retrieved. The report is stored with those citations marked as \
                            hallucinated. A generator that invents a citation is producing something \
                            that looks like evidence and is not.""",
                    investigation.getInvestigationId(), investigation.getPaymentId(),
                    investigation.getHallucinatedCitations());
        }
        if (!investigation.isGrounded()) {
            log.warn("""
                            Investigation {} for payment {} found no policy above the retrieval \
                            threshold, so nothing in its narrative rests on written policy. A rising \
                            rate of these means the knowledge base has drifted away from the traffic.""",
                    investigation.getInvestigationId(), investigation.getPaymentId());
        }

        log.info("Investigation {} for payment {} recommends {} (confidence {}, {} policy chunk(s), "
                        + "generated by {})",
                investigation.getInvestigationId(), investigation.getPaymentId(),
                investigation.getRecommendation(),
                String.format("%.2f", investigation.getConfidence()),
                investigation.getRetrievedChunks(), investigation.getGeneratedBy());

        return investigation;
    }

    /**
     * Builds the completed-investigation payload.
     *
     * <p><strong>{@code citedRuleIds} carries only verified citations.</strong> A hallucinated id is
     * recorded on the investigation for audit but is deliberately excluded from the event: a consumer
     * receiving it would have no way to know it was invented, and would treat it as a real source. The
     * audit trail keeps the fabrication; the wire contract does not propagate it.
     */
    private static InvestigationCompletedPayload toPayload(Investigation investigation,
                                                           List<InvestigationCitation> citations) {
        List<String> verified = citations.stream()
                .filter(InvestigationCitation::isVerifiable)
                .map(InvestigationCitation::getDocumentId)
                .distinct()
                .toList();

        return new InvestigationCompletedPayload(
                investigation.getInvestigationId(),
                investigation.getPaymentId(),
                investigation.getTransactionId(),
                investigation.getUserId(),
                investigation.getNarrative(),
                investigation.findingList(),
                // A string on the wire rather than an enum, so no consumer can deserialise it into
                // something it then switches on as a command. The authority boundary is in the shape of
                // the contract, not only in a comment about it.
                investigation.getRecommendation().name(),
                investigation.getRecommendationRationale(),
                investigation.getConfidence(),
                verified,
                investigation.getGeneratedBy(),
                investigation.isDeterministicFallback(),
                investigation.getGenerationLatencyMs(),
                investigation.getCreatedAt());
    }

    // ---------------------------------------------------------------- reviews

    /**
     * Records an analyst's verdict on a recommendation.
     *
     * <p>No event is emitted, deliberately. A review is a fact about how much the AI is trusted, not an
     * instruction to anything: acting on the analyst's conclusion happens when they record a decision in
     * fraud-service, attributed to their token. Announcing it here would create a second, ambiguous
     * channel through which something might appear to have been authorised.
     */
    @Transactional
    public InvestigationReview review(String investigationId, Recommendation analystAssessment,
                                      String notes, String analystUserId) {

        Investigation investigation = investigations.findByInvestigationId(investigationId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No investigation with id " + investigationId));

        if (reviews.existsByInvestigationId(investigationId)) {
            // One review per report. A second would make the agreement rate — the only honest measure of
            // whether this service is worth running — depend on how many people happened to look.
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Investigation " + investigationId + " has already been reviewed. Record a new "
                            + "investigation if the payment needs re-examining.");
        }

        InvestigationReview review = reviews.save(InvestigationReview.record(
                investigationId, investigation.getPaymentId(), investigation.getRecommendation(),
                analystAssessment, notes, analystUserId));

        if (review.isAgreed()) {
            log.info("Analyst {} agreed with {} on investigation {}",
                    analystUserId, investigation.getRecommendation(), investigationId);
        } else {
            // Logged at WARN, and the delta is included. A model consistently one step too cautious needs
            // the opposite fix to one consistently too relaxed, and the sign is the only thing that
            // distinguishes them.
            log.warn("""
                            Analyst {} DISAGREED with investigation {}: the AI recommended {}, the \
                            analyst assessed {} (severity delta {}). Sustained disagreement in one \
                            direction is a signal to retune the prompt or the corpus.""",
                    analystUserId, investigationId, investigation.getRecommendation(),
                    analystAssessment, review.severityDelta(investigation.getRecommendation()));
        }
        return review;
    }

    // ------------------------------------------------------------- idempotency

    /**
     * The automatic investigation for a payment, if one exists.
     *
     * <p>The idempotency check for the event path. Kafka delivers at least once, and a second report would
     * cost another model call and give two analysts differently-worded narratives about the same payment
     * with no way to tell which the other had read.
     */
    @Transactional(readOnly = true)
    public Optional<Investigation> findAutomatic(String paymentId) {
        return investigations.findAutomaticForPayment(paymentId);
    }

    /**
     * Whether the analyst's own re-run should be allowed to proceed.
     *
     * <p>Always true today, and a method rather than an inline {@code true} because the question is real:
     * an unbounded number of on-demand re-runs is a way to spend money on model calls. The bound belongs
     * here if one is ever needed, and callers already ask.
     */
    @Transactional(readOnly = true)
    public boolean canReinvestigate(String paymentId) {
        return paymentId != null && !paymentId.isBlank();
    }

    /** How long a payment has been waiting for a report, for the overview. */
    @Transactional(readOnly = true)
    public long countSince(Instant since) {
        return investigations.countByCreatedAtAfter(since);
    }
}
