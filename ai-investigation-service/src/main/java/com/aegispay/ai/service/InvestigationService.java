package com.aegispay.ai.service;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.Investigation;
import com.aegispay.ai.generation.CitationValidator;
import com.aegispay.ai.generation.EvidencePack;
import com.aegispay.ai.generation.GeneratedReport;
import com.aegispay.ai.generation.LocalReportComposer;
import com.aegispay.ai.generation.ReportGenerator;
import com.aegispay.ai.rag.PolicyRetriever;
import com.aegispay.ai.rag.RetrievedPolicy;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.events.payload.InvestigationPayloads.InvestigationRequestedPayload;
import com.aegispay.common.money.Money;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Produces one investigation: evidence, retrieval, generation, validation, persistence.
 *
 * <pre>
 *   0. already investigated?  -&gt; return the stored report unchanged
 *   1. build the evidence pack (untrusted text isolated and capped)
 *   2. retrieve policy         (declared rule mappings, then vector search)
 *   3. generate the narrative  (HTTP to a model, or the offline composer)
 *   4. validate the citations  (against the retrieval set; invented ones recorded)
 *   5. persist + announce      (one transaction, through the outbox)
 * </pre>
 *
 * <h2>Why this class holds no transaction</h2>
 *
 * <p>Step 3 can be a twenty-second network call. A transaction spanning it would pin a database connection
 * across the network, and under load the pool empties and the service stalls on a dependency it cannot
 * hurry. Every write therefore happens in {@link InvestigationMutationService}, behind a real bean
 * boundary — a {@code this.method()} call would bypass Spring's proxy and run untransacted with no symptom.
 *
 * <h2>Why the report is idempotent per payment</h2>
 *
 * <p>Kafka delivers at least once, so {@code investigation-requested} will be seen twice. A second report
 * would cost another model call and, worse, give two analysts differently-worded narratives about the same
 * payment with no way to tell which one the other had read. An analyst can still deliberately re-run an
 * investigation; that is recorded as on-demand and is a different fact from the automatic pass.
 *
 * <h2>Why a generation failure does not fail the request</h2>
 *
 * <p>An investigation is advisory. Dead-lettering the request would mean the payment quietly never gets
 * investigated, and an analyst would find an empty queue rather than a degraded report. So a failed hosted
 * generation falls back to the offline composer and the report says which wrote it.
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final PolicyRetriever retriever;
    private final ReportGenerator generator;
    private final LocalReportComposer fallback;
    private final CitationValidator citationValidator;
    private final InvestigationMutationService mutations;
    private final AiProperties properties;
    private final MeterRegistry meterRegistry;
    private final tools.jackson.databind.ObjectMapper json;

    public InvestigationService(PolicyRetriever retriever,
                                ReportGenerator generator,
                                LocalReportComposer fallback,
                                CitationValidator citationValidator,
                                InvestigationMutationService mutations,
                                AiProperties properties,
                                MeterRegistry meterRegistry,
                                tools.jackson.databind.ObjectMapper json) {
        this.retriever = retriever;
        this.generator = generator;
        this.fallback = fallback;
        this.citationValidator = citationValidator;
        this.mutations = mutations;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.json = json;
    }

    /**
     * Investigates a payment referred by a risk verdict, at most once.
     *
     * <p>The evidence comes entirely from the event. This service makes no call back into fraud-service or
     * payment-service, which is why it needs no service credentials at all — and holding none is what makes
     * the authority boundary a guarantee rather than a naming convention.
     */
    public Investigation investigate(InvestigationRequestedPayload request) {
        Optional<Investigation> existing = mutations.findAutomatic(request.paymentId());
        if (existing.isPresent()) {
            log.debug("Payment {} was already investigated as {}; returning the stored report",
                    request.paymentId(), existing.get().getInvestigationId());
            meterRegistry.counter(MetricNames.PAYMENT_IDEMPOTENT_REPLAY,
                    "scope", "ai.investigate", "layer", "investigation").increment();
            return existing.get();
        }

        EvidencePack evidence = EvidencePack.from(
                request.paymentId(),
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.requestedAt(),
                request.riskLevel(),
                request.fraudProbability(),
                request.ruleHits(),
                request.modelReasons(),
                request.modelVersion(),
                request.featureSnapshot(),
                request.riskSignals(),
                // The payment description is not on this event. Passed as null rather than reached for:
                // fetching it would mean a call into payment-service and a credential this service
                // deliberately does not have.
                null,
                properties.getGeneration().getMaxUntrustedFieldLength());

        return produce(evidence, null);
    }

    /**
     * Investigates on an analyst's request.
     *
     * <p>Recorded as on-demand and attributed, so a re-run is distinguishable from the automatic pass. That
     * distinction matters when comparing two reports on one payment: they may legitimately disagree if the
     * corpus or the generator changed between them, and knowing which was which is how somebody works out
     * why.
     */
    public Investigation investigateOnDemand(String paymentId, String transactionId, String userId,
                                             Money amount, RiskLevel riskLevel,
                                             double fraudProbability, List<String> ruleHits,
                                             List<String> modelReasons, String modelVersion,
                                             Map<String, Object> featureSnapshot,
                                             com.aegispay.common.events.payload.RiskSignals signals,
                                             String paymentDescription, String analystUserId) {

        EvidencePack evidence = EvidencePack.from(paymentId, transactionId, userId, amount,
                Instant.now(), riskLevel, fraudProbability, ruleHits, modelReasons, modelVersion,
                featureSnapshot, signals, paymentDescription,
                properties.getGeneration().getMaxUntrustedFieldLength());

        log.info("Analyst {} requested an investigation of payment {}", analystUserId, paymentId);
        return produce(evidence, analystUserId);
    }

    // ------------------------------------------------------------ the pipeline

    /**
     * Retrieves, generates, validates and persists.
     *
     * @param analystUserId non-null for an on-demand run, which marks the report as such
     */
    private Investigation produce(EvidencePack evidence, String analystUserId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long started = System.nanoTime();

        List<RetrievedPolicy> policies = retriever.retrieve(
                evidence.retrievalQuery(), evidence.ruleHits());

        Attempt attempt = generateWithFallback(evidence, policies);
        GeneratedReport report = attempt.report();

        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();

        Investigation.Builder builder = Investigation.builder()
                .payment(evidence.paymentId(), evidence.transactionId(), evidence.userId(),
                        evidence.amount())
                .evidence(evidence.riskLevel(), evidence.fraudProbability(), evidence.ruleHits(),
                        evidence.modelReasons(), evidence.modelVersion(), snapshotJson(evidence))
                .report(report.narrative(), report.findings(), report.recommendation(),
                        report.recommendationRationale(), report.confidence());

        if (analystUserId != null) {
            builder.requestedByAnalyst(analystUserId);
        }

        // Validated exactly once, before the write. The hallucination count has to be stored ON the
        // report — discovering it afterwards would need a second update to an append-only row — while the
        // rows themselves can only be built once an investigation id exists. Hence a single check whose
        // result is materialised inside the transaction.
        CitationValidator.Result validated = citationValidator.validate(
                evidence.paymentId(), report.citedDocumentIds(), policies);

        builder.provenance(report.generatedBy(), report.deterministic(), retriever.embedderId(),
                policies.size(), validated.hallucinated(), elapsedMs);

        Investigation investigation = mutations.record(builder, validated::citationsFor);

        sample.stop(meterRegistry.timer(MetricNames.AI_INVESTIGATION_LATENCY,
                "generator", report.generatedBy(),
                "fallback", String.valueOf(attempt.usedFallback())));

        meterRegistry.counter(MetricNames.AI_INVESTIGATION_COUNT,
                "provider", report.generatedBy(),
                "fallback", String.valueOf(report.deterministic()),
                "recommendation", report.recommendation().name(),
                "grounded", String.valueOf(investigation.isGrounded())).increment();

        return investigation;
    }

    private record Attempt(GeneratedReport report, boolean usedFallback) {
    }

    /**
     * Generates, falling back to the offline composer when a hosted model fails.
     *
     * <p>The fallback is attempted only when the primary generator is not already the composer, and its
     * report carries the composer's own id — so the stored provenance says what actually wrote the text
     * rather than what was configured to. A generator that quietly returned deterministic output under a
     * model's name would make every downstream trust signal false.
     *
     * <p>If the composer itself fails, that is a bug in this service rather than an external fault, and it
     * propagates. Swallowing it would produce an investigation with no narrative, which the aggregate
     * refuses anyway.
     */
    private Attempt generateWithFallback(EvidencePack evidence, List<RetrievedPolicy> policies) {
        try {
            return new Attempt(generator.generate(evidence, policies), false);

        } catch (ReportGenerator.GenerationException ex) {
            if (generator.isDeterministic() || !properties.getGeneration().isFallbackToLocal()) {
                throw ex;
            }
            log.error("""
                            Generator {} failed for payment {}, falling back to the offline composer: \
                            {}. The report will be flagged deterministicFallback so the analyst knows \
                            what they are reading.""",
                    generator.id(), evidence.paymentId(), ex.getMessage());

            return new Attempt(fallback.generate(evidence, policies), true);
        }
    }

    /**
     * The feature snapshot as JSON, for storage on the report.
     *
     * <p>Serialised from the evidence pack rather than re-fetched, so the stored vector is the one the
     * narrative was written from. A serialisation failure loses the snapshot and keeps the report: the
     * narrative and the citations are the parts an analyst reads, and refusing to store a report because
     * a diagnostic field would not serialise would be the wrong trade.
     */
    private String snapshotJson(EvidencePack evidence) {
        if (evidence.featureSnapshot().isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(evidence.featureSnapshot());
        } catch (RuntimeException ex) {
            log.warn("Could not serialise the feature snapshot for payment {}; the report will be "
                    + "stored without it: {}", evidence.paymentId(), ex.getMessage());
            return null;
        }
    }
}
