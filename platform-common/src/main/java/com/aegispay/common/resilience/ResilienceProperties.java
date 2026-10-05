package com.aegispay.common.resilience;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Circuit breaker, retry and time limiter defaults.
 *
 * <p>Every value is tunable per environment, because the right threshold depends
 * on real traffic volume: a 50% failure threshold over a 20-call window is
 * meaningful at 1,000 rps and pure noise at 2 rps.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.resilience")
public class ResilienceProperties {

    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Retry retry = new Retry();
    private final TimeLimiter timeLimiter = new TimeLimiter();

    public static class CircuitBreaker {

        /**
         * Percentage of failed calls in the window that trips the breaker.
         *
         * <p>Not lower: payment providers legitimately decline transactions, and a
         * decline is a business outcome, not a provider fault. Only technical
         * failures (timeouts, 5xx, connection errors) are recorded as failures.
         */
        @Min(1)
        @Max(100)
        private float failureRateThreshold = 50f;

        /** Percentage of "slow but successful" calls that also trips the breaker. */
        @Min(1)
        @Max(100)
        private float slowCallRateThreshold = 60f;

        /**
         * Above this, a call counts as slow. A provider that answers in 3s has not
         * failed, but it is consuming the user's latency budget and should be
         * routed around.
         */
        @NotNull
        private Duration slowCallDurationThreshold = Duration.ofMillis(1500);

        /** Calls considered when computing the failure rate. */
        @Min(4)
        private int slidingWindowSize = 20;

        /**
         * Minimum calls before the rate is evaluated at all. Without this, the first
         * two calls failing would open the breaker on a 100% "rate".
         */
        @Min(2)
        private int minimumNumberOfCalls = 8;

        /** How long the breaker stays OPEN before probing with a trial call. */
        @NotNull
        private Duration waitDurationInOpenState = Duration.ofSeconds(15);

        /** Trial calls allowed in HALF_OPEN to decide whether the dependency recovered. */
        @Min(1)
        private int permittedNumberOfCallsInHalfOpenState = 4;

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public float getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(float slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }

        public Duration getSlowCallDurationThreshold() {
            return slowCallDurationThreshold;
        }

        public void setSlowCallDurationThreshold(Duration slowCallDurationThreshold) {
            this.slowCallDurationThreshold = slowCallDurationThreshold;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int value) {
            this.permittedNumberOfCallsInHalfOpenState = value;
        }
    }

    public static class Retry {

        /**
         * Total attempts including the first.
         *
         * <p>Kept at 3. Retries multiply load on a dependency that is already
         * struggling, and a payment has a hard user-facing latency budget. Breadth
         * (fall back to another provider) beats depth (hammer the same one).
         */
        @Min(1)
        @Max(5)
        private int maxAttempts = 3;

        @NotNull
        private Duration initialInterval = Duration.ofMillis(200);

        @Min(1)
        private double multiplier = 2.0;

        /**
         * Randomisation factor applied to each wait. Without jitter, all clients
         * that failed at the same instant retry at the same instant and re-create
         * the overload they are recovering from.
         */
        @Min(0)
        @Max(1)
        private double jitterFactor = 0.3;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getJitterFactor() {
            return jitterFactor;
        }

        public void setJitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
        }
    }

    public static class TimeLimiter {

        /** Hard ceiling on a single dependency call. */
        @NotNull
        private Duration timeout = Duration.ofSeconds(3);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Retry getRetry() {
        return retry;
    }

    public TimeLimiter getTimeLimiter() {
        return timeLimiter;
    }
}
