package com.aegispay.fraud.api;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import com.aegispay.fraud.domain.AnalystDecision;
import com.aegispay.fraud.domain.FraudAlert;
import com.aegispay.fraud.domain.FraudAssessment;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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

/**
 * Request and response bodies for fraud-service.
 *
 * <p>Two surfaces. The internal one is called by the saga at its risk step and returns a verdict; the
 * staff one lets an analyst read assessments, work the alert queue and decide a held payment.
 *
 * <p>There is deliberately no endpoint that sets a risk score or a decision directly. A verdict is
 * produced by the rules and the model, and an analyst's override is recorded as a
 * {@link AnalystDecision} rather than as an edit — so the original verdict survives alongside the
 * human judgement about it.
 */
public final class FraudDtos {

    private FraudDtos() {
    }

    // ------------------------------------------------------------------ input

    /**
     * Asks for a risk verdict on one payment.
     *
     * <p>Carries the payment's facts and the risk context captured at the edge. It does <em>not</em>
     * carry behavioural aggregates: fraud-service computes those from its own history, so a caller
     * cannot influence the score by choosing what to report.
     */
    @Schema(description = "Ask for a risk verdict on one payment")
    public record AssessPaymentRequest(

            @NotBlank
            @Size(max = 48)
            String paymentId,

            @Size(max = 48)
            String transactionId,

            @NotBlank
            @Size(max = 40)
            String initiatorUserId,

            @NotBlank
            @Size(max = 40)
            String destinationWalletId,

            @NotNull
            @DecimalMin("0.01")
            @Digits(integer = 12, fraction = 2)
            BigDecimal amount,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$")
            String currency,

            PaymentType paymentType,

            @Schema(description = "Device and location context captured when the customer "
                    + "transacted. Never re-derived later: the model must score the situation the "
                    + "customer was actually in.")
            RiskSignals riskSignals,

            @Schema(description = "When the payment was created. Windows the behavioural aggregates "
                    + "from the moment the customer transacted rather than from whenever this call "
                    + "runs.")
            Instant occurredAt
    ) {
        public Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    /** An analyst's decision on a held or flagged payment. */
    @Schema(description = "Release or refuse a payment an analyst has reviewed")
    public record AnalystDecisionRequest(

            @NotNull
            @Schema(description = "RELEASE lets the payment continue and charge a provider. "
                    + "REJECT refuses it terminally and releases the reserved funds.")
            AnalystDecision.Action action,

            @NotBlank
            @Size(max = 512)
            @Schema(description = """
                    Why. Mandatory, and enforced by the schema too. This is the only record of why a
                    customer's payment was released or refused, and it is what an audit reads.""",
                    example = "Customer confirmed the payment by phone using a known contact number")
            String reason
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
     * A risk verdict.
     *
     * <p>{@code degradedMode} changes how every other field should be read, which is why it is on the
     * response rather than only in a log: a LOW risk level with {@code degradedMode: true} means "the
     * model was unreachable and the rules found nothing", not "this payment is safe".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "The fraud engine's verdict on one payment")
    public record AssessmentView(
            String assessmentId,
            String paymentId,
            String transactionId,

            @Schema(description = "Calibrated probability that this payment is fraudulent. Read it "
                    + "only alongside degradedMode.")
            double fraudProbability,

            @Schema(description = "The same number in percentage points, 0-100", example = "62")
            int riskScore,

            RiskLevel riskLevel,

            @Schema(description = "APPROVE continues, REVIEW settles but alerts, HOLD stops and "
                    + "waits for a human, REJECT refuses terminally")
            FraudDecision decision,

            MoneyView amount,

            @Schema(description = "Ordered, human-readable. Rule findings first, then what moved the "
                    + "model's score.")
            List<String> reasons,

            @Schema(description = "Deterministic rules that fired, by stable id")
            List<String> ruleHits,

            @Schema(description = "The model build that produced the score. Null in degraded mode.")
            String modelVersion,

            long scoringLatencyMs,

            @Schema(description = "True when the model was unreachable and the rules alone decided. "
                    + "A LOW verdict then means 'nothing was found', not 'nothing is there'.")
            boolean degradedMode,

            Instant evaluatedAt
    ) {
        public static AssessmentView from(FraudAssessment assessment) {
            return new AssessmentView(
                    assessment.getAssessmentId(),
                    assessment.getPaymentId(),
                    assessment.getTransactionId(),
                    assessment.getFraudProbability(),
                    assessment.getRiskScore(),
                    assessment.getRiskLevel(),
                    assessment.getDecision(),
                    MoneyView.of(assessment.amount()),
                    assessment.reasonList(),
                    assessment.ruleHitList(),
                    assessment.getModelVersion(),
                    assessment.getScoringLatencyMs(),
                    assessment.isDegradedMode(),
                    assessment.getEvaluatedAt());
        }
    }

    /** An assessment with the feature vector that produced it, for a dispute or an investigation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "An assessment plus the exact vector the model scored")
    public record AssessmentDetailView(
            AssessmentView assessment,

            @Schema(description = "The exact feature values scored, as JSON. Null means the value "
                    + "was genuinely unknown, never zero. This is what makes a verdict reproducible.")
            String featureSnapshot,

            @Schema(description = "Every human decision on this payment, oldest first")
            List<AnalystDecisionView> decisions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "An item in the analyst work queue")
    public record AlertView(
            String alertId,
            String paymentId,
            String assessmentId,
            String initiatorUserId,
            RiskLevel riskLevel,
            int riskScore,
            FraudDecision decision,
            String summary,
            FraudAlert.Status status,
            String assignedTo,

            @Schema(description = "True when a customer's payment is held waiting on this. Orders "
                    + "the queue ahead of risk score: a held payment is costing somebody something "
                    + "right now.")
            boolean blocksPayment,

            Instant createdAt,
            Instant resolvedAt
    ) {
        public static AlertView from(FraudAlert alert) {
            return new AlertView(
                    alert.getAlertId(),
                    alert.getPaymentId(),
                    alert.getAssessmentId(),
                    alert.getInitiatorUserId(),
                    alert.getRiskLevel(),
                    alert.getRiskScore(),
                    alert.getDecision(),
                    alert.getSummary(),
                    alert.getStatus(),
                    alert.getAssignedTo(),
                    alert.isBlocksPayment(),
                    alert.getCreatedAt(),
                    alert.getResolvedAt());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A human decision on a fraud alert. Append-only.")
    public record AnalystDecisionView(
            String decisionId,
            String alertId,
            String paymentId,
            AnalystDecision.Action action,
            String reason,
            @Schema(description = "Taken from the analyst's token, never from the request")
            String decidedBy,
            Instant decidedAt
    ) {
        public static AnalystDecisionView from(AnalystDecision decision) {
            return new AnalystDecisionView(
                    decision.getDecisionId(),
                    decision.getAlertId(),
                    decision.getPaymentId(),
                    decision.getAction(),
                    decision.getReason(),
                    decision.getDecidedBy(),
                    decision.getDecidedAt());
        }
    }

    /**
     * Fraud overview.
     *
     * <p>{@code degradedAssessments} and {@code blockingAlerts} are the two numbers worth alerting on.
     * The first means verdicts are being made without the model; the second means customers' funds are
     * reserved waiting on a human.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Fraud engine overview")
    public record FraudStatsView(
            long assessments24h,
            long approvals24h,
            long reviews24h,
            long holds24h,
            long rejections24h,

            @Schema(description = "Verdicts reached without the model. Expected to be zero; a rising "
                    + "count is a degraded fraud posture that produces no errors.")
            long degradedAssessments24h,

            long openAlerts,

            @Schema(description = "Open alerts holding a payment. Every one is a customer waiting.")
            long blockingAlerts,

            @Schema(description = "Mean end-to-end assessment latency. This call is on the payment's "
                    + "critical path, so it is also the saga's headroom.")
            double averageScoringLatencyMs,

            @Schema(description = "Mean risk score per model build, for drift monitoring")
            List<ModelScoreSummary> scoresByModelVersion,

            Instant takenAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelScoreSummary(String modelVersion, long assessments, double averageRiskScore) {
    }

    /** What the engine is configured to do, and what the model reports about itself. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Engine configuration and model provenance")
    public record EngineInfoView(
            @Schema(description = "Probability at or above which a payment is held")
            double highRiskThreshold,
            @Schema(description = "Probability at or above which an analyst is alerted")
            double mediumRiskThreshold,

            @Schema(description = "Every deterministic rule this engine can apply")
            List<String> ruleIds,

            @Schema(description = "Whether an unreachable model refuses payments rather than "
                    + "falling back to rules")
            boolean rejectWhenModelUnavailable,

            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "ml-service's own model metadata, including the training metrics "
                    + "and the synthetic-data caveat. Null when the model is unreachable.")
            Object model
    ) {
    }
}
