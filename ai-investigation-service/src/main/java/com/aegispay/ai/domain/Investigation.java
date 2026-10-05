package com.aegispay.ai.domain;

import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A generated investigation report. Immutable once written.
 *
 * <p><strong>Why nothing here can be edited.</strong> An investigation is a statement about a payment
 * at a moment in time, produced from a specific evidence pack by a specific generator. Its value is
 * that it can be shown to a regulator alongside the inputs, and an editable narrative would allow the
 * most tempting and least defensible change available: quietly bringing the record of what the AI said
 * into line with what turned out to be true. Every column is {@code updatable = false} and a database
 * trigger refuses UPDATE and DELETE, so the guarantee holds against ad-hoc SQL too.
 *
 * <p>A revised view of the same payment is therefore a <em>new</em> investigation. A partial unique
 * index enforces one <em>automatic</em> investigation per payment while still allowing an analyst to
 * re-run one on demand.
 *
 * <p><strong>Three fields exist to stop the report being trusted more than it deserves.</strong>
 * {@code deterministicFallback} says whether an analyst is reading assembled evidence or model prose.
 * {@code retrievedChunks} at zero says the narrative had no policy behind it. And
 * {@code hallucinatedCitations} above zero says the generator cited something that was never
 * retrieved. Without all three, a fluent report reads identically whether it is grounded or invented.
 */
@Entity
@Table(name = "investigations")
public class Investigation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "investigation_id", nullable = false, unique = true, updatable = false, length = 48)
    private String investigationId;

    @Column(name = "payment_id", nullable = false, updatable = false, length = 48)
    private String paymentId;

    @Column(name = "transaction_id", updatable = false, length = 48)
    private String transactionId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 40)
    private String userId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    // ---- what fraud-service reported, retained verbatim ----

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, updatable = false, length = 16)
    private RiskLevel riskLevel;

    @Column(name = "fraud_probability", nullable = false, updatable = false)
    private double fraudProbability;

    @Column(name = "rule_hits", updatable = false, length = 512)
    private String ruleHits;

    @Column(name = "model_reasons", updatable = false, columnDefinition = "text")
    private String modelReasons;

    @Column(name = "model_version", updatable = false, length = 64)
    private String modelVersion;

    /**
     * The exact feature vector the fraud score came from.
     *
     * <p>Copied from the event rather than fetched from fraud-service, for the same reason the saga
     * copies the payment's facts: re-reading it later would show whatever that service says today,
     * and the question an investigation has to answer is what the evidence looked like when the
     * decision was made.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_snapshot", updatable = false)
    private String featureSnapshot;

    // ---- the generated report ----

    @Column(name = "narrative", nullable = false, updatable = false, columnDefinition = "text")
    private String narrative;

    @Column(name = "findings", updatable = false, columnDefinition = "text")
    private String findings;

    /**
     * Advice to a human. Never an instruction to a system.
     *
     * <p>Constrained to {@link Recommendation}'s closed vocabulary here and by a database CHECK, so a
     * generator cannot invent an action and a successful prompt injection cannot produce one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", nullable = false, updatable = false, length = 48)
    private Recommendation recommendation;

    @Column(name = "recommendation_rationale", nullable = false, updatable = false,
            columnDefinition = "text")
    private String recommendationRationale;

    @Column(name = "confidence", nullable = false, updatable = false)
    private double confidence;

    // ---- provenance ----

    @Column(name = "generated_by", nullable = false, updatable = false, length = 96)
    private String generatedBy;

    @Column(name = "deterministic_fallback", nullable = false, updatable = false)
    private boolean deterministicFallback;

    @Column(name = "embedder", updatable = false, length = 64)
    private String embedder;

    @Column(name = "retrieved_chunks", nullable = false, updatable = false)
    private int retrievedChunks;

    @Column(name = "hallucinated_citations", nullable = false, updatable = false)
    private int hallucinatedCitations;

    @Column(name = "generation_latency_ms", nullable = false, updatable = false)
    private long generationLatencyMs;

    @Column(name = "on_demand", nullable = false, updatable = false)
    private boolean onDemand;

    @Column(name = "requested_by", updatable = false, length = 40)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Investigation() {
        // JPA
    }

    /**
     * Records a completed investigation.
     *
     * <p>Every invariant the schema enforces is checked here too. Duplication is deliberate: the
     * constraint is the guarantee, but a violation surfacing as a readable exception at the point of
     * construction is far more useful than one surfacing as a constraint name at flush time.
     */
    public static Investigation record(Builder builder) {
        if (builder.paymentId == null || builder.paymentId.isBlank()) {
            throw new IllegalArgumentException("an investigation must name the payment it examined");
        }
        if (builder.amount == null || !builder.amount.isPositive()) {
            throw new IllegalArgumentException(
                    "cannot investigate payment " + builder.paymentId
                            + " with a non-positive amount " + builder.amount);
        }
        if (!Double.isFinite(builder.fraudProbability)
                || builder.fraudProbability < 0.0 || builder.fraudProbability > 1.0) {
            throw new IllegalArgumentException(
                    "fraud probability must be in [0,1], got " + builder.fraudProbability);
        }
        if (!Double.isFinite(builder.confidence)
                || builder.confidence < 0.0 || builder.confidence > 1.0) {
            throw new IllegalArgumentException(
                    "confidence must be in [0,1], got " + builder.confidence);
        }
        if (builder.narrative == null || builder.narrative.isBlank()) {
            // A report with no narrative is an empty page that still carries a recommendation, which
            // an analyst would act on without being able to see why.
            throw new IllegalArgumentException(
                    "investigation of " + builder.paymentId + " has no narrative, so its "
                            + "recommendation would be unexplained");
        }
        if (builder.recommendation == null) {
            throw new IllegalArgumentException("an investigation must reach a recommendation");
        }
        if (builder.recommendationRationale == null || builder.recommendationRationale.isBlank()) {
            throw new IllegalArgumentException(
                    "investigation of " + builder.paymentId + " must say why it recommends "
                            + builder.recommendation);
        }
        if (builder.generatedBy == null || builder.generatedBy.isBlank()) {
            // An unattributed report cannot be reproduced, and cannot be blamed on a model build when
            // a pattern of bad advice shows up.
            throw new IllegalArgumentException(
                    "an investigation must name the generator that produced it");
        }
        if (builder.deterministicFallback && !builder.generatedBy.startsWith("local")) {
            throw new IllegalArgumentException(
                    "a deterministic report must not claim a language model wrote it, got "
                            + builder.generatedBy);
        }
        if (builder.onDemand && (builder.requestedBy == null || builder.requestedBy.isBlank())) {
            throw new IllegalArgumentException(
                    "an on-demand investigation must name the analyst who asked for it");
        }

        Investigation investigation = new Investigation();
        investigation.investigationId = "INV-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase();
        investigation.paymentId = builder.paymentId;
        investigation.transactionId = builder.transactionId;
        investigation.userId = builder.userId;
        investigation.amountMinor = builder.amount.amountMinor();
        investigation.currency = builder.amount.currency();

        investigation.riskLevel = builder.riskLevel == null ? RiskLevel.LOW : builder.riskLevel;
        investigation.fraudProbability = builder.fraudProbability;
        investigation.ruleHits = join(builder.ruleHits, ",", 512);
        investigation.modelReasons = join(builder.modelReasons, "\n", 8_000);
        investigation.modelVersion = truncate(builder.modelVersion, 64);
        investigation.featureSnapshot = builder.featureSnapshot;

        investigation.narrative = builder.narrative;
        investigation.findings = join(builder.findings, "\n", 8_000);
        investigation.recommendation = builder.recommendation;
        investigation.recommendationRationale = builder.recommendationRationale;
        investigation.confidence = builder.confidence;

        investigation.generatedBy = truncate(builder.generatedBy, 96);
        investigation.deterministicFallback = builder.deterministicFallback;
        investigation.embedder = truncate(builder.embedder, 64);
        investigation.retrievedChunks = Math.max(0, builder.retrievedChunks);
        investigation.hallucinatedCitations = Math.max(0, builder.hallucinatedCitations);
        investigation.generationLatencyMs = Math.max(0L, builder.generationLatencyMs);
        investigation.onDemand = builder.onDemand;
        investigation.requestedBy = truncate(builder.requestedBy, 40);

        investigation.createdAt = Instant.now();
        return investigation;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ queries

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public List<String> ruleHitList() {
        return split(ruleHits, ",");
    }

    public List<String> modelReasonList() {
        return split(modelReasons, "\n");
    }

    public List<String> findingList() {
        return split(findings, "\n");
    }

    /**
     * Whether this report rests on any written policy at all.
     *
     * <p>False is a legitimate outcome, not a bug: it means the knowledge base had nothing above the
     * similarity floor and the narrative said so. It is also the field that decides how much weight a
     * reader should give the rest.
     */
    public boolean isGrounded() {
        return retrievedChunks > 0;
    }

    /** Whether the generator cited evidence that was never retrieved. Expected to be false. */
    public boolean hasHallucinatedCitations() {
        return hallucinatedCitations > 0;
    }

    /**
     * Whether this report should be read with reduced confidence, and why, for display.
     *
     * <p>One method rather than three checks at every call site, because a caller that forgot one of
     * them would present an ungrounded or partly-invented report as though it were evidence.
     */
    public List<String> trustCaveats() {
        List<String> caveats = new java.util.ArrayList<>();
        if (deterministicFallback) {
            caveats.add("Written by the offline deterministic composer, not a language model. "
                    + "It restates the evidence and does not interpret beyond it.");
        }
        if (!isGrounded()) {
            caveats.add("No fraud policy matched this payment above the retrieval threshold, so "
                    + "nothing in this report is supported by written policy.");
        }
        if (hasHallucinatedCitations()) {
            caveats.add("The generator cited " + hallucinatedCitations + " source(s) that were "
                    + "never retrieved. Those citations are recorded but must not be relied on.");
        }
        return List.copyOf(caveats);
    }

    private static List<String> split(String value, String separator) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(java.util.regex.Pattern.quote(separator)))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /**
     * Joins and truncates.
     *
     * <p>Truncating beats failing the write. Model reasons and rule lists come from upstream and are
     * not fully under this service's control, and losing the tail of a diagnostic is a far better
     * outcome than losing the record of what was concluded about a customer's payment.
     */
    private static String join(List<String> parts, String separator, int maxLength) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        return truncate(String.join(separator, parts), maxLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    public Long getId() {
        return id;
    }

    public String getInvestigationId() {
        return investigationId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public double getFraudProbability() {
        return fraudProbability;
    }

    public String getRuleHits() {
        return ruleHits;
    }

    public String getModelReasons() {
        return modelReasons;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getFeatureSnapshot() {
        return featureSnapshot;
    }

    public String getNarrative() {
        return narrative;
    }

    public String getFindings() {
        return findings;
    }

    public Recommendation getRecommendation() {
        return recommendation;
    }

    public String getRecommendationRationale() {
        return recommendationRationale;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }

    public boolean isDeterministicFallback() {
        return deterministicFallback;
    }

    public String getEmbedder() {
        return embedder;
    }

    public int getRetrievedChunks() {
        return retrievedChunks;
    }

    public int getHallucinatedCitations() {
        return hallucinatedCitations;
    }

    public long getGenerationLatencyMs() {
        return generationLatencyMs;
    }

    public boolean isOnDemand() {
        return onDemand;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Investigation{" + investigationId + ", payment=" + paymentId + ", "
                + recommendation + (deterministicFallback ? ", DETERMINISTIC" : "")
                + (isGrounded() ? "" : ", UNGROUNDED") + '}';
    }

    /**
     * Builder, because an investigation has twenty fields and a positional constructor of that size
     * is a defect waiting to happen: two adjacent {@code String} parameters swapped would compile,
     * pass every type check, and put the narrative in the rationale column.
     */
    public static final class Builder {

        private String paymentId;
        private String transactionId;
        private String userId;
        private Money amount;

        private RiskLevel riskLevel;
        private double fraudProbability;
        private List<String> ruleHits;
        private List<String> modelReasons;
        private String modelVersion;
        private String featureSnapshot;

        private String narrative;
        private List<String> findings;
        private Recommendation recommendation;
        private String recommendationRationale;
        private double confidence;

        private String generatedBy;
        private boolean deterministicFallback;
        private String embedder;
        private int retrievedChunks;
        private int hallucinatedCitations;
        private long generationLatencyMs;
        private boolean onDemand;
        private String requestedBy;

        private Builder() {
        }

        public Builder payment(String paymentId, String transactionId, String userId, Money amount) {
            this.paymentId = paymentId;
            this.transactionId = transactionId;
            this.userId = userId;
            this.amount = amount;
            return this;
        }

        public Builder evidence(RiskLevel riskLevel, double fraudProbability, List<String> ruleHits,
                                List<String> modelReasons, String modelVersion,
                                String featureSnapshot) {
            this.riskLevel = riskLevel;
            this.fraudProbability = fraudProbability;
            this.ruleHits = ruleHits;
            this.modelReasons = modelReasons;
            this.modelVersion = modelVersion;
            this.featureSnapshot = featureSnapshot;
            return this;
        }

        public Builder report(String narrative, List<String> findings, Recommendation recommendation,
                              String recommendationRationale, double confidence) {
            this.narrative = narrative;
            this.findings = findings;
            this.recommendation = recommendation;
            this.recommendationRationale = recommendationRationale;
            this.confidence = confidence;
            return this;
        }

        public Builder provenance(String generatedBy, boolean deterministicFallback, String embedder,
                                  int retrievedChunks, int hallucinatedCitations,
                                  long generationLatencyMs) {
            this.generatedBy = generatedBy;
            this.deterministicFallback = deterministicFallback;
            this.embedder = embedder;
            this.retrievedChunks = retrievedChunks;
            this.hallucinatedCitations = hallucinatedCitations;
            this.generationLatencyMs = generationLatencyMs;
            return this;
        }

        public Builder requestedByAnalyst(String analystUserId) {
            this.onDemand = true;
            this.requestedBy = analystUserId;
            return this;
        }

        public Investigation build() {
            return Investigation.record(this);
        }
    }
}
