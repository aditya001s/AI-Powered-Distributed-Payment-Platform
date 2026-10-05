package com.aegispay.orchestrator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Orchestration and routing settings.
 *
 * <p>Every value is tunable per environment, because the right number depends on real traffic: a
 * failure threshold that is meaningful at a thousand payments a second is pure noise at two.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.orchestration")
public class OrchestrationProperties {

    private final Providers providers = new Providers();
    private final Routing routing = new Routing();
    private final Recovery recovery = new Recovery();
    private final Fraud fraud = new Fraud();

    /**
     * How the saga treats the risk step.
     *
     * <p>Distinct from {@code aegis.fraud.*}, which tunes the fraud engine itself. These settings
     * govern what the <em>orchestrator</em> does with a verdict, or with the absence of one.
     */
    public static class Fraud {

        /**
         * Hard ceiling on the risk assessment call.
         *
         * <p>Wider than fraud-service's own model timeout, because fraud-service is expected to
         * absorb a slow model and still answer: it runs its rules, gives up on ml-service, and
         * returns a degraded verdict. This budget therefore covers that whole sequence rather than
         * just the fast path, and a breach here means fraud-service itself is unwell.
         */
        @NotNull
        private Duration callTimeout = Duration.ofSeconds(4);

        /**
         * Attempts against fraud-service, including the first.
         *
         * <p>Safe to retry: assessment is idempotent on the payment id, so a duplicate call returns
         * the stored verdict rather than producing a second one.
         */
        @Min(1)
        @Max(4)
        private int maxAttempts = 2;

        /**
         * Whether a payment may proceed when fraud-service cannot be reached at all.
         *
         * <p><strong>False by default, and the reasoning is different from
         * {@code aegis.fraud.ml.reject-when-unavailable}.</strong> When only the <em>model</em> is
         * down, fraud-service still runs its deterministic rules — there is a real compensating
         * control, which is why falling back is the right default there. When <em>fraud-service</em>
         * is down there is no control at all: no score, no rules, nothing. Letting a payment through
         * then is not a degraded assessment, it is an unassessed payment, and recording it as
         * "assessed and approved" would put a false statement in the audit trail of an automated
         * decision.
         *
         * <p>So the default fails the payment as a retryable technical fault. Nothing was charged,
         * compensation releases the reservation, and the customer can try again — and the outage
         * shows up as failed payments rather than hiding as unprotected ones.
         *
         * <p>Set true for an environment where completing payments matters more than assessing them.
         * The saga then records the verdict explicitly as unassessed and degraded, so the choice is
         * visible on the payment rather than implied by configuration.
         */
        private boolean allowUnassessedPayments;

        /**
         * How long a reservation is extended for when a payment is held for review.
         *
         * <p>The saga's default reservation lives fifteen minutes, which is right for a payment that
         * runs to completion and badly wrong for one waiting on a human. Without the extension the
         * wallet's expiry sweeper would release the funds while the saga still believed they were
         * held, and the analyst releasing the payment would capture against a balance that had moved.
         *
         * <p>Two days, not seven: long enough to cover a weekend, short enough that a forgotten alert
         * frees the customer's money rather than sitting on it. The extension is reapplied whenever a
         * held payment is resumed.
         */
        @NotNull
        private Duration reviewHoldTtl = Duration.ofDays(2);

        public Duration getCallTimeout() {
            return callTimeout;
        }

        public void setCallTimeout(Duration callTimeout) {
            this.callTimeout = callTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public boolean isAllowUnassessedPayments() {
            return allowUnassessedPayments;
        }

        public void setAllowUnassessedPayments(boolean allowUnassessedPayments) {
            this.allowUnassessedPayments = allowUnassessedPayments;
        }

        public Duration getReviewHoldTtl() {
            return reviewHoldTtl;
        }

        public void setReviewHoldTtl(Duration reviewHoldTtl) {
            this.reviewHoldTtl = reviewHoldTtl;
        }
    }

    public static class Providers {

        /**
         * Hard ceiling on one provider call.
         *
         * <p>Applied per attempt rather than across the retry sequence, so a first attempt that
         * hangs cannot consume the whole budget and leave no time to fall back.
         */
        @NotNull
        private Duration callTimeout = Duration.ofSeconds(3);

        /** Shorter than a real call: a probe that hangs must not delay the health sweep. */
        @NotNull
        private Duration probeTimeout = Duration.ofSeconds(2);

        /**
         * Attempts against a single provider, including the first.
         *
         * <p>Kept low deliberately. Retries multiply load on a dependency that is already
         * struggling, and a payment has a hard user-facing latency budget. Breadth — falling back
         * to another provider — beats depth.
         *
         * <p>Note that this applies only to faults known to have happened before the request
         * reached the provider. A timeout is never retried on the same provider, because the charge
         * may have gone through.
         */
        @Min(1)
        @Max(4)
        private int maxAttemptsPerProvider = 2;

        @NotNull
        private Duration retryInitialInterval = Duration.ofMillis(150);

        @Min(1)
        private double retryMultiplier = 2.0;

        /** Randomisation applied to each wait, so simultaneous failures do not retry in lockstep. */
        @Min(0)
        @Max(1)
        private double retryJitterFactor = 0.3;

        /** How often each provider is probed. */
        @NotNull
        private Duration healthCheckInterval = Duration.ofSeconds(30);

        @NotNull
        private Duration healthCheckInitialDelay = Duration.ofSeconds(10);

        /**
         * Consecutive failed probes before a provider is called unhealthy.
         *
         * <p>Above one on purpose: a single failed probe is far more likely to be a blip in the
         * prober than an outage at the provider, and reacting to it would flap traffic between
         * providers on noise.
         */
        @Min(1)
        @Max(10)
        private int failuresBeforeUnhealthy = 2;

        /** Threads available for provider calls; also the cap on concurrent in-flight charges. */
        @Min(2)
        @Max(64)
        private int callPoolSize = 8;

        public Duration getCallTimeout() {
            return callTimeout;
        }

        public void setCallTimeout(Duration callTimeout) {
            this.callTimeout = callTimeout;
        }

        public Duration getProbeTimeout() {
            return probeTimeout;
        }

        public void setProbeTimeout(Duration probeTimeout) {
            this.probeTimeout = probeTimeout;
        }

        public int getMaxAttemptsPerProvider() {
            return maxAttemptsPerProvider;
        }

        public void setMaxAttemptsPerProvider(int maxAttemptsPerProvider) {
            this.maxAttemptsPerProvider = maxAttemptsPerProvider;
        }

        public Duration getRetryInitialInterval() {
            return retryInitialInterval;
        }

        public void setRetryInitialInterval(Duration retryInitialInterval) {
            this.retryInitialInterval = retryInitialInterval;
        }

        public double getRetryMultiplier() {
            return retryMultiplier;
        }

        public void setRetryMultiplier(double retryMultiplier) {
            this.retryMultiplier = retryMultiplier;
        }

        public double getRetryJitterFactor() {
            return retryJitterFactor;
        }

        public void setRetryJitterFactor(double retryJitterFactor) {
            this.retryJitterFactor = retryJitterFactor;
        }

        public Duration getHealthCheckInterval() {
            return healthCheckInterval;
        }

        public void setHealthCheckInterval(Duration healthCheckInterval) {
            this.healthCheckInterval = healthCheckInterval;
        }

        public Duration getHealthCheckInitialDelay() {
            return healthCheckInitialDelay;
        }

        public void setHealthCheckInitialDelay(Duration healthCheckInitialDelay) {
            this.healthCheckInitialDelay = healthCheckInitialDelay;
        }

        public int getFailuresBeforeUnhealthy() {
            return failuresBeforeUnhealthy;
        }

        public void setFailuresBeforeUnhealthy(int failuresBeforeUnhealthy) {
            this.failuresBeforeUnhealthy = failuresBeforeUnhealthy;
        }

        public int getCallPoolSize() {
            return callPoolSize;
        }

        public void setCallPoolSize(int callPoolSize) {
            this.callPoolSize = callPoolSize;
        }
    }

    /**
     * Routing weights.
     *
     * <p>Expressed as weights rather than a fixed priority order because the trade-off genuinely
     * changes with conditions: the cheapest rail is the right choice when it is healthy and the
     * wrong one when it is not, and a hard-coded order cannot express that.
     */
    public static class Routing {

        /**
         * Weight on observed reliability.
         *
         * <p>Highest of the three, and deliberately so. A failed payment costs far more than an
         * expensive one — the customer's money is stuck mid-saga and someone has to explain it —
         * so reliability outranks price whenever the two disagree.
         */
        @Min(0)
        @Max(1)
        private double healthWeight = 0.55;

        /** Weight on price. Real, but never enough to prefer a failing rail. */
        @Min(0)
        @Max(1)
        private double feeWeight = 0.25;

        /** Weight on observed latency, which is the customer's waiting time. */
        @Min(0)
        @Max(1)
        private double latencyWeight = 0.20;

        /** Window over which reliability and latency are measured. */
        @NotNull
        private Duration observationWindow = Duration.ofMinutes(5);

        /**
         * Providers tried before a payment is given up on.
         *
         * <p>Bounded: exhausting every rail on a payment that is going to fail anyway multiplies
         * the latency the customer waits and the load on providers that are already struggling.
         */
        @Min(1)
        @Max(5)
        private int maxProvidersPerPayment = 3;

        /**
         * Latency treated as the worst case when normalising the latency score.
         *
         * <p>Anything at or above this scores zero, so one pathological outlier cannot compress
         * every healthy provider into the same score.
         */
        @NotNull
        private Duration latencyCeiling = Duration.ofSeconds(2);

        public double getHealthWeight() {
            return healthWeight;
        }

        public void setHealthWeight(double healthWeight) {
            this.healthWeight = healthWeight;
        }

        public double getFeeWeight() {
            return feeWeight;
        }

        public void setFeeWeight(double feeWeight) {
            this.feeWeight = feeWeight;
        }

        public double getLatencyWeight() {
            return latencyWeight;
        }

        public void setLatencyWeight(double latencyWeight) {
            this.latencyWeight = latencyWeight;
        }

        public Duration getObservationWindow() {
            return observationWindow;
        }

        public void setObservationWindow(Duration observationWindow) {
            this.observationWindow = observationWindow;
        }

        public int getMaxProvidersPerPayment() {
            return maxProvidersPerPayment;
        }

        public void setMaxProvidersPerPayment(int maxProvidersPerPayment) {
            this.maxProvidersPerPayment = maxProvidersPerPayment;
        }

        public Duration getLatencyCeiling() {
            return latencyCeiling;
        }

        public void setLatencyCeiling(Duration latencyCeiling) {
            this.latencyCeiling = latencyCeiling;
        }
    }

    public static class Recovery {

        /** Whether the stuck-saga sweep runs. Off only for tests that drive it directly. */
        private boolean enabled = true;

        /**
         * How long a saga may go without a state change before it is treated as abandoned.
         *
         * <p>Comfortably longer than the slowest legitimate saga. Too short and a payment waiting
         * on a slow provider gets compensated out from under itself; too long and a customer's
         * funds stay reserved for no reason.
         */
        @NotNull
        private Duration stalledAfter = Duration.ofMinutes(2);

        @NotNull
        private Duration sweepInterval = Duration.ofSeconds(60);

        @NotNull
        private Duration sweepInitialDelay = Duration.ofSeconds(45);

        /** Sagas recovered per sweep, bounded so a large backlog cannot cause an outage. */
        @Min(1)
        @Max(500)
        private int batchSize = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getStalledAfter() {
            return stalledAfter;
        }

        public void setStalledAfter(Duration stalledAfter) {
            this.stalledAfter = stalledAfter;
        }

        public Duration getSweepInterval() {
            return sweepInterval;
        }

        public void setSweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
        }

        public Duration getSweepInitialDelay() {
            return sweepInitialDelay;
        }

        public void setSweepInitialDelay(Duration sweepInitialDelay) {
            this.sweepInitialDelay = sweepInitialDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public Providers getProviders() {
        return providers;
    }

    public Routing getRouting() {
        return routing;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public Fraud getFraud() {
        return fraud;
    }
}
