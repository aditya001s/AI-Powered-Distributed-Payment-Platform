package com.aegispay.orchestrator.api;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.aegispay.orchestrator.domain.PaymentSaga;
import com.aegispay.orchestrator.domain.ProviderAttempt;
import com.aegispay.orchestrator.domain.ProviderState;
import com.aegispay.orchestrator.domain.SagaStatus;
import com.aegispay.orchestrator.domain.SagaStep;
import com.aegispay.orchestrator.provider.ProviderHealthMonitor;
import com.aegispay.orchestrator.provider.ProviderRouter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies for payment-orchestrator.
 *
 * <p>The whole surface is operational: inspecting a saga, reading provider health, taking a provider
 * out of service, and driving the simulation. There is deliberately no endpoint that starts a
 * payment — orchestration is triggered by {@code payment-created} and by nothing else, so there is
 * exactly one way in and no second path to keep consistent with it.
 */
public final class OrchestratorDtos {

    private OrchestratorDtos() {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One attempt at one saga step")
    public record SagaStepView(
            SagaStep.Name step,
            @Schema(description = "One row per attempt, so a retried step shows both")
            int attempt,
            SagaStep.Status status,
            String detail,
            String errorCode,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs
    ) {
        public static SagaStepView from(SagaStep step) {
            return new SagaStepView(
                    step.getStepName(),
                    step.getAttempt(),
                    step.getStatus(),
                    step.getDetail(),
                    step.getErrorCode(),
                    step.getStartedAt(),
                    step.getFinishedAt(),
                    step.getDurationMs());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One call to a payment provider")
    public record ProviderAttemptView(
            String providerId,
            @Schema(description = "Routing position. Above 1 means the router fell back.")
            int attemptNumber,
            ProviderAttempt.Operation operation,
            @Schema(description = "DECLINED is a working provider saying no; FAILED/TIMEOUT is a "
                    + "broken one. Never the same thing.")
            ProviderAttempt.Outcome outcome,
            String authorizationId,
            String errorCode,
            String errorMessage,
            long latencyMs,
            Instant createdAt
    ) {
        public static ProviderAttemptView from(ProviderAttempt attempt) {
            return new ProviderAttemptView(
                    attempt.getProviderId(),
                    attempt.getAttemptNumber(),
                    attempt.getOperation(),
                    attempt.getOutcome(),
                    attempt.getAuthorizationId(),
                    attempt.getErrorCode(),
                    attempt.getErrorMessage(),
                    attempt.getLatencyMs(),
                    attempt.getCreatedAt());
        }
    }

    /**
     * A saga in full, with its step log and provider history.
     *
     * <p>Answers the two questions an incident actually raises: how far did this payment get, and what
     * is still outstanding. The compensation handles are exposed for exactly that reason — a non-null
     * {@code authorizationId} on a failed saga means a charge is still sitting at a provider.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Full saga state, step log and provider history")
    public record SagaView(
            String sagaId,
            String paymentId,
            String transactionId,
            SagaStatus status,
            @Schema(description = "The step currently being attempted")
            SagaStep.Name currentStep,

            MoneyView amount,
            MoneyView fee,
            PaymentType paymentType,

            String sourceWalletId,
            String destinationWalletId,
            String initiatorUserId,

            @Schema(description = "Provider that settled the payment")
            String providerId,
            @Schema(description = "Providers tried, in routing order. More than one means a fallback.")
            List<String> providerAttempts,

            @Schema(description = "Outstanding wallet reservation, if any. Non-null on a failed "
                    + "saga means the customer's funds are still held.")
            String holdId,
            @Schema(description = "Outstanding provider authorisation, if any. Non-null on a failed "
                    + "saga means a charge is still sitting at the provider.")
            String authorizationId,
            @Schema(description = "Ledger posting, if any")
            String ledgerTransactionId,

            RiskLevel riskLevel,
            FraudDecision fraudDecision,
            Double fraudProbability,

            @Schema(description = "The fraud assessment that produced the verdict. The two services "
                    + "have separate databases, so this is what links a disputed payment back to "
                    + "the score, the feature vector and the model build.")
            String riskAssessmentId,

            @Schema(description = "True when the saga continued on a verdict reached WITHOUT the ML "
                    + "model, on deterministic rules alone. A LOW risk level then means 'nothing "
                    + "was found', not 'nothing is there'.")
            boolean riskDegraded,

            @Schema(description = "When fraud controls parked this payment. Null unless it was held.")
            Instant riskHeldAt,
            Instant riskReviewedAt,
            @Schema(description = "The analyst who released or refused it, from their token")
            String riskReviewedBy,
            String riskReviewReason,

            @Schema(description = "How long the customer waited, or has been waiting, on a review")
            Long reviewWaitSeconds,

            String failureCode,
            String failureReason,
            SagaStep.Name failedStep,
            @Schema(description = "False means a side effect may still be outstanding and an "
                    + "operator must look")
            boolean compensationComplete,

            @Schema(description = "How many times this saga has been picked up, including by recovery")
            int attemptCount,
            String correlationId,

            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,

            List<SagaStepView> steps,
            List<ProviderAttemptView> providerCalls
    ) {
        public static SagaView from(PaymentSaga saga, List<SagaStep> steps,
                                    List<ProviderAttempt> providerCalls) {
            return new SagaView(
                    saga.getSagaId(),
                    saga.getPaymentId(),
                    saga.getTransactionId(),
                    saga.getStatus(),
                    saga.getCurrentStep(),
                    MoneyView.of(saga.amount()),
                    MoneyView.of(saga.fee()),
                    saga.getPaymentType(),
                    saga.getSourceWalletId(),
                    saga.getDestinationWalletId(),
                    saga.getInitiatorUserId(),
                    saga.getProviderId(),
                    saga.providerAttemptList(),
                    saga.getHoldId(),
                    saga.getAuthorizationId(),
                    saga.getLedgerTransactionId(),
                    saga.getRiskLevel(),
                    saga.getFraudDecision(),
                    saga.getFraudProbability(),
                    saga.getRiskAssessmentId(),
                    saga.isRiskDegraded(),
                    saga.getRiskHeldAt(),
                    saga.getRiskReviewedAt(),
                    saga.getRiskReviewedBy(),
                    saga.getRiskReviewReason(),
                    saga.reviewWait().map(java.time.Duration::toSeconds).orElse(null),
                    // riskSignals is deliberately absent. It is the customer's device, IP and
                    // coordinates, and this endpoint is read by staff investigating other people's
                    // payments — the fraud console shows it where there is a reason to, and nothing
                    // here needs it.
                    saga.getFailureCode(),
                    saga.getFailureReason(),
                    saga.getFailedStep(),
                    saga.isCompensationComplete(),
                    saga.getAttemptCount(),
                    saga.getCorrelationId(),
                    saga.getCreatedAt(),
                    saga.getUpdatedAt(),
                    saga.getCompletedAt(),
                    steps.stream().map(SagaStepView::from).toList(),
                    providerCalls.stream().map(ProviderAttemptView::from).toList());
        }
    }

    /** Compact form for listings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Saga listing entry")
    public record SagaSummary(
            String sagaId,
            String paymentId,
            SagaStatus status,
            SagaStep.Name currentStep,
            MoneyView amount,
            String providerId,
            boolean compensationComplete,
            Instant createdAt,
            Instant completedAt
    ) {
        public static SagaSummary from(PaymentSaga saga) {
            return new SagaSummary(
                    saga.getSagaId(),
                    saga.getPaymentId(),
                    saga.getStatus(),
                    saga.getCurrentStep(),
                    MoneyView.of(saga.amount()),
                    saga.getProviderId(),
                    saga.isCompensationComplete(),
                    saga.getCreatedAt(),
                    saga.getCompletedAt());
        }
    }

    /**
     * A payment parked awaiting a fraud analyst.
     *
     * <p>{@code waitingSeconds} is the field that matters. Every row here is a customer who cannot
     * spend their own money, and how long that has been true is the number an operator acts on —
     * not the risk score, which is why the payment was held rather than why it is still held.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A payment held for fraud review, with the customer's funds reserved")
    public record HeldPaymentView(
            String sagaId,
            String paymentId,
            String initiatorUserId,
            MoneyView amount,
            RiskLevel riskLevel,
            FraudDecision fraudDecision,
            Double fraudProbability,
            String riskAssessmentId,
            @Schema(description = "True when the hold came from rules alone, with no model score")
            boolean riskDegraded,
            @Schema(description = "The reservation still holding the customer's funds")
            String holdId,
            Instant riskHeldAt,
            @Schema(description = "How long this customer has been waiting")
            Long waitingSeconds
    ) {
        public static HeldPaymentView from(PaymentSaga saga) {
            return new HeldPaymentView(
                    saga.getSagaId(),
                    saga.getPaymentId(),
                    saga.getInitiatorUserId(),
                    MoneyView.of(saga.amount()),
                    saga.getRiskLevel(),
                    saga.getFraudDecision(),
                    saga.getFraudProbability(),
                    saga.getRiskAssessmentId(),
                    saga.isRiskDegraded(),
                    saga.getHoldId(),
                    saga.getRiskHeldAt(),
                    saga.reviewWait().map(java.time.Duration::toSeconds).orElse(null));
        }
    }

    /**
     * A provider's status as an operator sees it.
     *
     * <p>{@code routable} is the answer that decides routing, reported explicitly so nobody has to
     * combine the operator switch, the observed health and the breaker state correctly in their head
     * during an incident.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Provider health and availability")
    public record ProviderHealthView(
            String providerId,
            String displayName,
            @Schema(description = "The operator kill switch. Survives restarts.")
            boolean enabled,
            @Schema(description = "Last probe result. Advisory: routing consults the breaker.")
            ProviderState.Health observedHealth,
            @Schema(description = "Live circuit breaker state, which is what routing actually reads")
            String circuitBreakerState,
            float breakerFailureRate,
            Long lastLatencyMs,
            int consecutiveFailures,
            Instant lastCheckedAt,
            String disabledReason,
            @Schema(description = "Whether the router will consider this provider right now")
            boolean routable,

            @Schema(description = "Fee in basis points (1 bp = 0.01%)")
            int feeBasisPoints,
            List<String> supportedCurrencies,
            long maxAmountMinor
    ) {
        public static ProviderHealthView from(ProviderHealthMonitor.ProviderHealth health,
                                              com.aegispay.orchestrator.provider.PaymentProvider
                                                      provider) {
            return new ProviderHealthView(
                    health.providerId(),
                    health.displayName(),
                    health.enabled(),
                    health.observedHealth(),
                    health.circuitBreakerState(),
                    health.breakerFailureRate(),
                    health.lastLatencyMs(),
                    health.consecutiveFailures(),
                    health.lastCheckedAt(),
                    health.disabledReason(),
                    health.routable(),
                    provider == null ? 0 : provider.capabilities().feeBasisPoints(),
                    provider == null ? List.of()
                            : provider.capabilities().supportedCurrencies().stream().sorted().toList(),
                    provider == null ? 0L : provider.capabilities().maxAmountMinor());
        }
    }

    /**
     * Why the router would rank the providers the way it does, for a hypothetical charge.
     *
     * <p>Exists because "why did this payment go to the expensive provider" is otherwise answerable
     * only by reading the code and guessing at the numbers.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Routing explanation for a hypothetical amount")
    public record RoutingExplanationView(
            MoneyView amount,
            PaymentType paymentType,
            List<RoutingCandidateView> candidates,
            Instant evaluatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One provider's routing verdict")
    public record RoutingCandidateView(
            String providerId,
            String displayName,
            boolean eligible,
            @Schema(description = "Why this provider was excluded, when it was")
            String exclusionReason,
            String circuitBreakerState,
            @Schema(description = "Weighted score. Higher is preferred.")
            double score,
            MoneyView fee,
            @Schema(description = "Share of recent non-declined attempts that did not fault")
            double reliability,
            long observedLatencyMs
    ) {
        public static RoutingCandidateView from(ProviderRouter.RoutingDecision decision) {
            return new RoutingCandidateView(
                    decision.providerId(),
                    decision.displayName(),
                    decision.eligible(),
                    decision.exclusionReason(),
                    decision.circuitBreakerState(),
                    decision.score(),
                    decision.fee() == null ? null : MoneyView.of(decision.fee()),
                    decision.reliability(),
                    decision.observedLatencyMs());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Orchestration overview")
    public record OrchestrationStatsView(
            @Schema(description = "Non-terminal sagas, including those parked for fraud review. "
                    + "Read it next to heldForReview: a rising inFlight that is entirely held "
                    + "payments is a stuck review queue, not slow processing.")
            long inFlight,
            long completed,
            long failed,
            long rejected,

            @Schema(description = "Payments parked awaiting a fraud analyst. Each one is a customer "
                    + "whose funds are reserved. The stalled-saga sweep deliberately ignores these, "
                    + "so a broken decision consumer shows up here and nowhere else.")
            long heldForReview,

            @Schema(description = "Held payments waiting longer than an hour. Non-zero means the "
                    + "review queue is not being worked, or the decision consumer has stopped.")
            long heldOverAnHour,

            @Schema(description = "Expected to be zero. Each one is money that may be stranded.")
            long uncompensated,
            @Schema(description = "Payments that needed more than one provider. Routing working.")
            long fallbackCount,
            List<ProviderThroughputView> throughputByProvider,
            Instant takenAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderThroughputView(String providerId, long completedPayments) {
    }

    /**
     * What a simulated provider is currently rigged to do.
     *
     * <p>Read back before asserting, so a test that fails does so against the state it actually
     * arranged rather than the one it meant to. {@code outstandingAuthorizations} is the one to
     * watch after a timeout: a non-zero count means a charge is still sitting at the fake rail, and
     * whether the saga finds and voids it is exactly what the orphaned-authorisation path is for.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Current simulation state of one simulated provider")
    public record SimulationStateView(
            String providerId,
            String displayName,
            com.aegispay.orchestrator.provider.SimulationControls.FaultMode armedFault,
            @Schema(description = "Calls still affected. -1 means until cleared.")
            int remainingFaults,
            long extraLatencyMs,
            @Schema(description = "Authorisations the fake rail still holds. Non-zero after a "
                    + "TIMEOUT means an orphaned charge the saga has to void.")
            int outstandingAuthorizations
    ) {
        public static SimulationStateView from(
                com.aegispay.orchestrator.provider.SimulatedPaymentProvider provider) {
            return new SimulationStateView(
                    provider.id(),
                    provider.displayName(),
                    provider.controls().currentFaultMode(),
                    provider.controls().remainingFaults(),
                    provider.controls().extraLatencyMs(),
                    provider.outstandingAuthorizationCount());
        }
    }

    // ------------------------------------------------------------------ input

    @Schema(description = "Take a provider out of service")
    public record DisableProviderRequest(
            @NotBlank
            @Size(max = 256)
            @Schema(description = """
                    Why. Mandatory, and enforced by the schema too: it is what decides whether
                    re-enabling the provider later is safe.""",
                    example = "Elevated timeouts reported by the provider's status page")
            String reason
    ) {
    }

    /**
     * Drives a simulated provider's behaviour.
     *
     * <p><strong>Only meaningful because the providers are simulated.</strong> A real integration
     * would have no such endpoint. It changes what the fake rail does, never what the orchestrator
     * does with the answer, and it exists so that fallback, retry and circuit-breaker behaviour can be
     * exercised deterministically rather than waiting for a genuine outage.
     */
    @Schema(description = "Simulation control: inject a fault into a simulated provider")
    public record InjectFaultRequest(

            @NotNull
            @Schema(description = """
                    NONE clears. UNREACHABLE is safe to retry. TIMEOUT is not — it leaves an
                    orphaned authorisation the saga has to find and void. DECLINE is a working
                    provider saying no. SLOW trips the breaker's slow-call detection.""",
                    example = "TIMEOUT")
            com.aegispay.orchestrator.provider.SimulationControls.FaultMode mode,

            @Min(-1)
            @Max(1000)
            @Schema(description = "Calls to affect. -1 means until cleared.", example = "1")
            int calls,

            @Min(0)
            @Max(60_000)
            @Schema(description = "Extra latency on every call, in milliseconds", example = "0")
            long extraLatencyMs
    ) {
    }
}
