package com.aegispay.common.events.payload;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The fraud engine's verdict, published on {@code fraud-checked}.
 *
 * <p>Contains both the model output and the deterministic rule hits, because the
 * two are combined: a hard rule violation escalates the decision regardless of
 * what the model says, and a model outage falls back to rules alone.
 *
 * <p>{@code modelVersion} and {@code featureSnapshot} exist for auditability. If
 * a customer disputes a hold six months later, the exact model build and the
 * exact feature values that produced the score must be reproducible. This is a
 * regulatory expectation for automated decision-making, not a nice-to-have.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudCheckedPayload(

        String paymentId,

        String transactionId,

        /** Calibrated probability in [0,1] that this transaction is fraudulent. */
        double fraudProbability,

        RiskLevel riskLevel,

        FraudDecision decision,

        /** Human-readable reasons, ordered by contribution. Shown in the UI. */
        List<String> reasons,

        /** IDs of deterministic rules that fired, e.g. {@code RULE_NEW_DEVICE_HIGH_VALUE}. */
        List<String> ruleHits,

        /** Model build identifier, e.g. {@code fraud_lgbm-20260901T104500Z}. */
        String modelVersion,

        /**
         * The feature vector actually scored. Retained for dispute resolution and
         * model-drift analysis.
         */
        Map<String, Object> featureSnapshot,

        /** End-to-end scoring latency, tracked against the fraud SLO. */
        long scoringLatencyMs,

        /**
         * True when the ML service was unreachable and the verdict came from the
         * rule engine alone. Distinguishes "model said low risk" from
         * "model was down and rules found nothing", which are very different
         * levels of assurance.
         */
        boolean degradedMode,

        Instant evaluatedAt
) {
}
