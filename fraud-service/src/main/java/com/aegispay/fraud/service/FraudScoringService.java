package com.aegispay.fraud.service;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.fraud.client.MlScoringClient;
import com.aegispay.fraud.config.FraudProperties;
import com.aegispay.fraud.domain.FraudAssessment;
import com.aegispay.fraud.rules.FraudContext;
import com.aegispay.fraud.rules.RuleEngine;
import com.aegispay.fraud.rules.RuleHit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Assesses one payment: features, rules, model, verdict.
 *
 * <pre>
 *   0. already assessed?     -&gt; return the stored verdict unchanged
 *   1. build the context     (local SQL over this service's own read model)
 *   2. run the rules         (deterministic, in-process)
 *   3. score with the model  (HTTP, short timeout, breaker; may fail)
 *   4. combine               (model proposes, rules floor)
 *   5. persist + announce    (one transaction, through the outbox)
 * </pre>
 *
 * <h2>Why this class holds no transaction</h2>
 *
 * <p>Step 3 is a network call. A transaction spanning it would pin a database connection across the
 * network, and under load the pool empties and the service stalls waiting on a dependency it cannot
 * hurry. Every write therefore happens in {@link FraudMutationService}, behind a real bean boundary —
 * a {@code this.method()} call would bypass Spring's proxy and run untransacted with no symptom.
 *
 * <h2>Why the assessment is idempotent</h2>
 *
 * <p>The saga can legitimately ask twice: a Kafka redelivery, or the recovery sweep resuming an
 * interrupted saga. Returning the stored verdict rather than re-scoring is not just an optimisation —
 * a payment whose risk verdict changed between attempts would be indefensible in a dispute, and would
 * mean a customer could be held on one attempt and approved on the next purely by timing.
 *
 * <h2>Why an unreachable model does not fail the payment</h2>
 *
 * <p>Step 3 is allowed to fail. When it does, the rules alone decide and the verdict is marked
 * {@code degraded}. Refusing every payment while the model is down would convert a fraud-service
 * outage into a total payment outage — a far larger incident than the fraud it prevents — and the
 * rules cover precisely the archetypes the model is weakest on. An operator who prefers the other
 * trade-off sets {@code aegis.fraud.ml.reject-when-unavailable}.
 */
@Service
public class FraudScoringService {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringService.class);

    private final FeatureBuilder features;
    private final RuleEngine rules;
    private final MlScoringClient ml;
    private final FraudMutationService mutations;
    private final FraudProperties properties;
    private final MeterRegistry meterRegistry;
    private final tools.jackson.databind.ObjectMapper json;

    public FraudScoringService(FeatureBuilder features,
                               RuleEngine rules,
                               MlScoringClient ml,
                               FraudMutationService mutations,
                               FraudProperties properties,
                               MeterRegistry meterRegistry,
                               tools.jackson.databind.ObjectMapper json) {
        this.features = features;
        this.rules = rules;
        this.ml = ml;
        this.mutations = mutations;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.json = json;
    }

    /**
     * Assesses a payment, at most once.
     *
     * @param occurredAt when the payment was created, so the aggregates are windowed from the moment
     *                   the customer transacted rather than from whenever this call happens to run
     */
    public FraudAssessment assess(String paymentId,
                                  String transactionId,
                                  String initiatorUserId,
                                  String destinationWalletId,
                                  Money amount,
                                  PaymentType paymentType,
                                  RiskSignals signals,
                                  Instant occurredAt) {

        Optional<FraudAssessment> existing = mutations.findAssessment(paymentId);
        if (existing.isPresent()) {
            log.debug("Payment {} was already assessed as {}; returning the stored verdict",
                    paymentId, existing.get().getDecision());
            meterRegistry.counter(MetricNames.PAYMENT_IDEMPOTENT_REPLAY,
                    "scope", "fraud.assess", "layer", "assessment").increment();
            return existing.get();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        long started = System.nanoTime();

        FraudContext context = features.build(paymentId, transactionId, initiatorUserId,
                destinationWalletId, amount, paymentType, signals, occurredAt);

        // Rules first, and unconditionally. They are in-process and cannot fail the way the model can,
        // so running them before the network call means a model outage never costs their coverage.
        List<RuleHit> hits = rules.evaluate(context);

        Optional<MlScoringClient.ScoreResponse> score = ml.score(features.toScoreRequest(context));

        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        RiskVerdict verdict = RiskVerdict.combine(
                score, hits, snapshotJson(score), properties, elapsedMs);

        FraudAssessment assessment;
        try {
            assessment = mutations.recordAssessment(context, verdict);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Another thread assessed the same payment first. Losing that race is a normal outcome —
            // two consumer threads handling the same redelivered record — so it is recovered from by
            // re-reading rather than treated as an error. The unique constraint on payment_id is what
            // makes one assessment per payment true rather than merely likely.
            log.debug("Payment {} was assessed concurrently; using the committed verdict", paymentId);
            assessment = mutations.findAssessment(paymentId).orElseThrow(() ->
                    new IllegalStateException("assessment for payment " + paymentId
                            + " could neither be created nor found", ex));
        }

        sample.stop(meterRegistry.timer(MetricNames.FRAUD_DECISION_LATENCY,
                MetricNames.TAG_DECISION, verdict.decision().name()));

        meterRegistry.counter(MetricNames.FRAUD_DECISION,
                MetricNames.TAG_DECISION, verdict.decision().name(),
                MetricNames.TAG_RISK_LEVEL, verdict.riskLevel().name(),
                "degraded", String.valueOf(verdict.degraded())).increment();

        if (verdict.degraded()) {
            // Its own counter as well as a tag, so an alert can fire on the rate directly rather than
            // having to sum a tagged series.
            meterRegistry.counter(MetricNames.FRAUD_DEGRADED_DECISION).increment();
        } else {
            // The drift signal. A fraud model degrades silently: it keeps returning numbers, and the
            // shape of their distribution is what moves first.
            meterRegistry.summary(MetricNames.FRAUD_SCORE_DISTRIBUTION)
                    .record(assessment.getRiskScore());
        }
        return assessment;
    }

    /**
     * The feature snapshot as JSON, for storage on the assessment.
     *
     * <p>Serialised from what ml-service returned rather than rebuilt from the context, so the stored
     * vector is the one the model actually scored. Rebuilding it here would be a second derivation
     * that could disagree — which would make the snapshot useless for the one job it has.
     */
    private String snapshotJson(Optional<MlScoringClient.ScoreResponse> score) {
        return score.map(MlScoringClient.ScoreResponse::features)
                .map(snapshot -> {
                    try {
                        return json.writeValueAsString(snapshot);
                    } catch (RuntimeException ex) {
                        log.warn("Could not serialise the feature snapshot; the assessment will "
                                + "record the verdict without it: {}", ex.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }
}
