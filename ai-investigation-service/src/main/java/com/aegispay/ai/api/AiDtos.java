package com.aegispay.ai.api;

import com.aegispay.ai.domain.Investigation;
import com.aegispay.ai.domain.InvestigationCitation;
import com.aegispay.ai.domain.InvestigationReview;
import com.aegispay.ai.domain.Recommendation;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies for ai-investigation-service.
 *
 * <p><strong>There is no request body that sets a recommendation, and no response field any consumer
 * executes.</strong> A recommendation is produced from evidence and retrieved policy; an analyst's
 * disagreement is recorded beside it as a review rather than as an edit, so both the machine's reading
 * and the human's judgement about it survive.
 */
public final class AiDtos {

    private AiDtos() {
    }

    // ------------------------------------------------------------------ input

    /**
     * Asks for an investigation of a payment that was not automatically referred.
     *
     * <p>The evidence is supplied by the caller rather than fetched. This service holds no credentials for
     * any internal API — which is what makes the authority boundary structural rather than a convention —
     * so it cannot look a payment up, and an analyst re-running an investigation has to provide what they
     * are asking about.
     */
    @Schema(description = "Request an investigation of a payment on demand")
    public record RequestInvestigation(

            @NotBlank
            @Size(max = 48)
            String paymentId,

            @Size(max = 48)
            String transactionId,

            @NotBlank
            @Size(max = 40)
            String userId,

            @NotNull
            @DecimalMin("0.01")
            @Digits(integer = 12, fraction = 2)
            BigDecimal amount,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$")
            String currency,

            @Schema(description = "The risk verdict this payment received, so the report does not "
                    + "recommend less caution than the control that stopped it")
            RiskLevel riskLevel,

            @Schema(description = "Calibrated probability from the fraud model. Leave at 0 with no "
                    + "model version if the verdict was reached in degraded mode: the report reads "
                    + "that combination as 'unknown', not 'low'.")
            double fraudProbability,

            List<String> ruleHits,
            List<String> modelReasons,

            @Size(max = 64)
            String modelVersion,

            @Schema(description = "The exact feature vector scored. Null values mean unknown and are "
                    + "reported as such, never as zero.")
            Map<String, Object> featureSnapshot,

            @Schema(description = "Device and location context captured when the customer transacted")
            com.aegispay.common.events.payload.RiskSignals riskSignals,

            @Size(max = 512)
            @Schema(description = "The payment description, if known. Treated as UNTRUSTED customer "
                    + "text: fenced in the prompt, capped, and never followed as an instruction.")
            String paymentDescription
    ) {
        public Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    /**
     * An analyst's verdict on a recommendation.
     *
     * <p>Note what is absent: an {@code agreed} flag. Agreement is derived from whether the assessment
     * matches the recommendation, because a client that could set both independently would let them
     * disagree — and the agreement rate is the only honest measure of whether this service earns its keep.
     */
    @Schema(description = "Record whether an analyst agreed with a recommendation")
    public record ReviewInvestigation(

            @NotNull
            @Schema(description = """
                    Your own assessment, in the same vocabulary as the recommendation. Recorded rather \
                    than a bare agree/disagree because the DIRECTION of a disagreement matters: a model \
                    consistently one step too cautious needs the opposite fix to one consistently too \
                    relaxed.""")
            Recommendation analystAssessment,

            @NotBlank
            @Size(max = 1024)
            @Schema(description = "Why. Mandatory, and enforced by the schema too: this is the only "
                    + "record of why a person accepted or overrode a machine's reading of a "
                    + "customer's payment.")
            String notes
    ) {
    }

    // ----------------------------------------------------------------- output

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A monetary amount in both decimal and minor-unit form")
    public record MoneyView(
            @Schema(example = "2000.00") BigDecimal amount,
            @Schema(example = "200000", description = "Exact value in minor units. Prefer this.")
            long amountMinor,
            @Schema(example = "INR") String currency
    ) {
        public static MoneyView of(Money money) {
            return new MoneyView(money.toDecimal(), money.amountMinor(), money.currency());
        }
    }

    /**
     * A policy passage a report used, or claimed to.
     *
     * <p>{@code hallucinated} is the field to read first. A citation with it set names a document that was
     * never retrieved, which means the generator invented it — and an invented citation is worse than a
     * missing one, because it looks like evidence.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A policy citation, verified or invented")
    public record CitationView(
            String documentId,
            String chunkId,
            @Schema(description = "The document version that was read. A citation resolves to this "
                    + "version, not to whatever the policy says today.")
            int documentVersion,
            @Schema(description = "Cosine similarity at retrieval time. Zero for an invented citation, "
                    + "because nothing was compared.")
            double similarity,
            int rank,
            @Schema(description = "TRUE only when the generated report explicitly named this document. "
                    + "FALSE means it was retrieved as context but not cited.")
            boolean citedInReport,
            @Schema(description = "TRUE means the generator cited a document that was never retrieved. "
                    + "Recorded for audit; must not be relied on as evidence.")
            boolean hallucinated
    ) {
        public static CitationView from(InvestigationCitation citation) {
            return new CitationView(
                    citation.getDocumentId(),
                    citation.getChunkId(),
                    citation.getDocumentVersion(),
                    citation.getSimilarity(),
                    citation.getRank(),
                    citation.isCitedInReport(),
                    citation.isHallucinated());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "An analyst's verdict on a recommendation. Append-only.")
    public record ReviewView(
            String reviewId,
            String investigationId,
            String paymentId,
            @Schema(description = "Derived from whether the assessment matched the recommendation, "
                    + "never taken from the client")
            boolean agreed,
            Recommendation analystAssessment,
            @Schema(description = "Signed severity difference. Positive means the analyst was more "
                    + "cautious than the AI.")
            int severityDelta,
            String notes,
            @Schema(description = "From the analyst's token, never from the request")
            String reviewedBy,
            Instant reviewedAt
    ) {
        public static ReviewView from(InvestigationReview review, Recommendation recommendation) {
            return new ReviewView(
                    review.getReviewId(),
                    review.getInvestigationId(),
                    review.getPaymentId(),
                    review.isAgreed(),
                    review.getAnalystAssessment(),
                    review.severityDelta(recommendation),
                    review.getNotes(),
                    review.getReviewedBy(),
                    review.getReviewedAt());
        }
    }

    /**
     * A generated investigation.
     *
     * <p>{@code trustCaveats} is assembled from three separate conditions and exists so a caller cannot
     * forget one of them. A report written without a model, or with no policy behind it, or with an
     * invented citation, reads identically to a fully grounded one unless something says otherwise.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "An AI investigation report. Advisory only.")
    public record InvestigationView(
            String investigationId,
            String paymentId,
            String transactionId,
            String userId,
            MoneyView amount,

            RiskLevel riskLevel,
            @Schema(description = "Read alongside the caveats: a probability of 0.0 with no model "
                    + "version means unknown, not safe.")
            double fraudProbability,
            List<String> ruleHits,
            List<String> modelReasons,
            String modelVersion,

            @Schema(description = "The narrative shown to an analyst. Markdown. Displayed as text and "
                    + "never interpreted by anything.")
            String narrative,

            @Schema(description = "Checkable statements, each naming its source")
            List<String> findings,

            @Schema(description = "Advice to a person. Nothing in this platform acts on it.")
            Recommendation recommendation,

            String recommendationRationale,

            @Schema(description = "The generator's own estimate of how well the evidence supports its "
                    + "report. Uncalibrated by construction — there is no ground truth to calibrate "
                    + "against — so useful for ordering a queue, not for a threshold.")
            double confidence,

            @Schema(description = "Which generator wrote this, e.g. local-composer/1")
            String generatedBy,

            @Schema(description = "TRUE when written by the offline composer rather than a language "
                    + "model. It restates the evidence and does not interpret beyond it.")
            boolean deterministicFallback,

            @Schema(description = "The embedder that retrieved the cited policy. Part of reproducing "
                    + "the report: the same prompt over a different retrieval set is a different "
                    + "investigation.")
            String embedder,

            @Schema(description = "Policy chunks that cleared the similarity floor. Zero means nothing "
                    + "in this report is supported by written policy.")
            int retrievedChunks,

            @Schema(description = "Citations the generator invented. Expected to be zero.")
            int hallucinatedCitations,

            long generationLatencyMs,

            @Schema(description = "TRUE when an analyst asked for this rather than a risk verdict "
                    + "referring it")
            boolean onDemand,

            String requestedBy,

            @Schema(description = "Reasons to read this report with reduced confidence. Empty is the "
                    + "good case. Assembled from three separate conditions so a caller cannot check "
                    + "one and miss another.")
            List<String> trustCaveats,

            Instant createdAt
    ) {
        public static InvestigationView from(Investigation investigation) {
            return new InvestigationView(
                    investigation.getInvestigationId(),
                    investigation.getPaymentId(),
                    investigation.getTransactionId(),
                    investigation.getUserId(),
                    MoneyView.of(investigation.amount()),
                    investigation.getRiskLevel(),
                    investigation.getFraudProbability(),
                    investigation.ruleHitList(),
                    investigation.modelReasonList(),
                    investigation.getModelVersion(),
                    investigation.getNarrative(),
                    investigation.findingList(),
                    investigation.getRecommendation(),
                    investigation.getRecommendationRationale(),
                    investigation.getConfidence(),
                    investigation.getGeneratedBy(),
                    investigation.isDeterministicFallback(),
                    investigation.getEmbedder(),
                    investigation.getRetrievedChunks(),
                    investigation.getHallucinatedCitations(),
                    investigation.getGenerationLatencyMs(),
                    investigation.isOnDemand(),
                    investigation.getRequestedBy(),
                    investigation.trustCaveats(),
                    investigation.getCreatedAt());
        }
    }

    /** A report with the evidence vector and citations, for a dispute or a second opinion. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A report plus everything needed to check it")
    public record InvestigationDetailView(
            InvestigationView investigation,

            @Schema(description = "The exact feature values the narrative was written from, as JSON. "
                    + "Null means the value was genuinely unknown, never zero.")
            String featureSnapshot,

            @Schema(description = "Every policy passage retrieved, cited or not. A report that ignored "
                    + "the most authoritative document available is worth a second look, and that is "
                    + "only visible here.")
            List<CitationView> citations,

            @Schema(description = "Human verdicts on this report, oldest first")
            List<ReviewView> reviews
    ) {
    }

    /** A policy document in the knowledge base. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A fraud policy an investigation may cite")
    public record PolicyView(
            String documentId,
            String title,
            String category,
            @Schema(description = "Fraud rules this policy is declared as explaining. The bridge "
                    + "between the machine vocabulary and the human policy.")
            List<String> relatedRuleIds,
            int version,
            String source,
            @Schema(description = "How many retrieval chunks this document produced")
            int chunks,
            Instant updatedAt
    ) {
    }

    /** One policy in full, for reading a citation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A fraud policy with its authored text")
    public record PolicyDetailView(PolicyView policy, String body) {
    }

    /**
     * How retrieval is configured and what it is capable of.
     *
     * <p>{@code semanticRetrieval} reaches the API deliberately. With a lexical embedder, "no policy
     * matched" means no policy shared vocabulary with the query — which is materially weaker than "no
     * relevant policy exists", and a caller reading a null result needs to know which claim it is.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Retrieval and generation configuration")
    public record EngineInfoView(
            @Schema(description = "The generator in use, e.g. local-composer/1")
            String generator,
            boolean deterministicGenerator,

            @Schema(description = "The embedder in use. Stored on every chunk; retrieval filters on it, "
                    + "because vectors from different embedders are not comparable.")
            String embedder,

            @Schema(description = "FALSE means retrieval matches shared vocabulary, not paraphrase. A "
                    + "null result then means no policy shared words with the query, NOT that no "
                    + "relevant policy exists.")
            boolean semanticRetrieval,

            @Schema(description = "Minimum cosine similarity for a passage to count as evidence. The "
                    + "floor is what makes 'no policy matched' an expressible outcome instead of the "
                    + "five least-irrelevant documents being cited.")
            double minimumSimilarity,

            int topK,

            @Schema(description = "Policy documents in the corpus")
            long documents,

            @Schema(description = "Chunks usable by the CURRENT embedder. Zero with a non-empty corpus "
                    + "means everything was embedded by a previous embedder and is invisible to "
                    + "retrieval.")
            long usableChunks,

            @Schema(description = "Every recommendation this service can produce. A closed vocabulary, "
                    + "and every value is advice to a person rather than a command.")
            List<String> recommendationVocabulary
    ) {
    }

    /**
     * Investigation overview.
     *
     * <p>{@code ungrounded24h} and {@code withHallucinations24h} are the two numbers worth alerting on.
     * The first means reports are being written with no written policy behind them; the second means a
     * generator is inventing sources. Neither produces an error, and an ungrounded or partly-invented
     * report reads exactly like a sound one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Investigation overview")
    public record StatsView(
            long investigations24h,

            @Schema(description = "Reports with no policy above the retrieval threshold. A rising count "
                    + "means the knowledge base has drifted away from the traffic.")
            long ungrounded24h,

            @Schema(description = "Reports citing a document that was never retrieved. Expected to be "
                    + "zero: every one contains something that looks like a source and is not.")
            long withHallucinations24h,

            @Schema(description = "Reports written by the offline composer rather than a language model")
            long deterministic24h,

            double averageGenerationLatencyMs,

            @Schema(description = "Recommendation mix over the window")
            List<RecommendationCount> byRecommendation,

            @Schema(description = "Mean confidence and grounding per generator, so a shift can be "
                    + "attributed to a model change rather than to the world")
            List<GeneratorSummary> byGenerator,

            @Schema(description = """
                    Agreement rate per recommendation. The measure of whether this service earns its \
                    keep - and split by recommendation because an overall rate hides the thing worth \
                    knowing: a model trusted on MONITOR_ACCOUNT and overruled on every recommended \
                    rejection is not 70% useful, it is useless where the stakes are highest.""")
            List<AgreementSummary> agreement,

            @Schema(description = "Policies actually being cited. A document never cited is either "
                    + "redundant or unreachable by retrieval, and those need opposite responses.")
            List<CitedDocument> citedDocuments,

            Instant takenAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecommendationCount(Recommendation recommendation, long count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratorSummary(String generatedBy, long investigations, double averageConfidence,
                                   double averageRetrievedChunks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgreementSummary(Recommendation recommendation, long reviews, long agreements,
                                   double agreementRate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CitedDocument(String documentId, long citations) {
    }
}
