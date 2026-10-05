package com.aegispay.orchestrator.provider;

import com.aegispay.common.observability.MetricNames;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Every call to a payment provider goes through here.
 *
 * <p>Three protections are applied in a specific order, and the order is the design:
 *
 * <pre>
 *   CircuitBreaker ( Retry ( TimeLimiter ( provider call ) ) )
 * </pre>
 *
 * <ul>
 *   <li><strong>TimeLimiter innermost</strong>, so each individual attempt has its own deadline.
 *       Outside the retry it would bound the whole sequence instead, and a first attempt that hung
 *       would consume the entire budget leaving no time to retry at all.</li>
 *   <li><strong>Retry in the middle</strong>, so the breaker sees one outcome per logical call
 *       rather than one per attempt. Wrapped the other way round, three retries of a dead provider
 *       would register as three failures and trip the breaker three times faster than its
 *       configured threshold — the threshold would silently mean something different from what it
 *       says.</li>
 *   <li><strong>CircuitBreaker outermost</strong>, so an open breaker rejects the call before any
 *       time or thread is spent on it. That is the whole point of having one: not to fail
 *       gracefully, but to stop asking.</li>
 * </ul>
 *
 * <h2>What counts as a failure</h2>
 *
 * <p>Only {@link ProviderFaultException} and timeouts. A <em>decline</em> is returned, not thrown,
 * so it is invisible to both the retry and the breaker — a provider refusing a charge has worked
 * correctly, and counting that against it would take a healthy rail out of service because a run
 * of customers were short of funds. A capability mismatch throws
 * {@link IllegalArgumentException}, which the shared configuration ignores, because that is the
 * router's mistake rather than the provider's.
 *
 * <h2>Retrying the same provider is deliberately restricted</h2>
 *
 * <p>{@link #authorize} is not idempotent, so a blind retry risks charging twice. The retry
 * predicate therefore fires only for faults that are known to have happened <em>before</em> the
 * request reached the provider. A read timeout is excluded: the charge may have been authorised and
 * only the acknowledgement lost, so the safe response is to fall back to a different provider and
 * void whatever the first one may have left behind.
 */
@Component
public class ProviderGateway {

    private static final Logger log = LoggerFactory.getLogger(ProviderGateway.class);

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final TimeLimiterRegistry timeLimiters;
    private final ScheduledExecutorService providerCallExecutor;
    private final MeterRegistry meterRegistry;
    private final OrchestrationProperties properties;

    public ProviderGateway(CircuitBreakerRegistry circuitBreakers,
                           RetryRegistry retries,
                           TimeLimiterRegistry timeLimiters,
                           ScheduledExecutorService providerCallExecutor,
                           MeterRegistry meterRegistry,
                           OrchestrationProperties properties) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.timeLimiters = timeLimiters;
        this.providerCallExecutor = providerCallExecutor;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    // ------------------------------------------------------------- operations

    /**
     * Authorises a charge with full protection.
     *
     * @throws ProviderFaultException      the provider failed to answer, after any retries
     * @throws ProviderUnavailableException the breaker is open, so nothing was attempted
     */
    public PaymentProvider.AuthorizationResult authorize(
            PaymentProvider provider, PaymentProvider.AuthorizationRequest request) {

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PaymentProvider.AuthorizationResult result =
                    execute(provider, "authorize", () -> provider.authorize(request));

            sample.stop(meterRegistry.timer(MetricNames.PROVIDER_CALL_LATENCY,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, result.isApproved()
                            ? MetricNames.OUTCOME_SUCCESS
                            : MetricNames.OUTCOME_REJECTED));
            meterRegistry.counter(MetricNames.PROVIDER_CALL,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, result.isApproved() ? "approved" : "declined")
                    .increment();
            return result;

        } catch (ProviderUnavailableException ex) {
            sample.stop(meterRegistry.timer(MetricNames.PROVIDER_CALL_LATENCY,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_CIRCUIT_OPEN));
            meterRegistry.counter(MetricNames.PROVIDER_CALL,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, "circuit_open").increment();
            throw ex;

        } catch (RuntimeException ex) {
            boolean timeout = ex instanceof ProviderFaultException fault
                    && "PROVIDER_TIMEOUT".equals(fault.faultCode());
            sample.stop(meterRegistry.timer(MetricNames.PROVIDER_CALL_LATENCY,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, timeout
                            ? MetricNames.OUTCOME_TIMEOUT
                            : MetricNames.OUTCOME_FAILURE));
            meterRegistry.counter(MetricNames.PROVIDER_CALL,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, timeout ? "timeout" : "failed").increment();
            throw ex;
        }
    }

    /**
     * Voids an authorisation, reporting failure rather than throwing.
     *
     * <p>Compensation runs on the failure path, where the caller is already handling one problem
     * and must not be derailed by a second. A void that could not be completed is logged loudly and
     * surfaced through the return value, so the saga can record that a side effect may still be
     * outstanding instead of silently claiming it cleaned up.
     *
     * @return whether the void was confirmed
     */
    public boolean voidQuietly(PaymentProvider provider, String authorizationId) {
        try {
            execute(provider, "void", () -> {
                provider.voidAuthorization(authorizationId);
                return Boolean.TRUE;
            });
            meterRegistry.counter(MetricNames.PROVIDER_CALL,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, "voided").increment();
            return true;

        } catch (RuntimeException ex) {
            log.error("""
                            COMPENSATION INCOMPLETE: could not void authorisation {} at {}. \
                            The provider may still be holding it. Reason: {}""",
                    authorizationId, provider.id(), ex.getMessage());
            meterRegistry.counter(MetricNames.PROVIDER_CALL,
                    MetricNames.TAG_PROVIDER, provider.id(),
                    MetricNames.TAG_OUTCOME, "void_failed").increment();
            return false;
        }
    }

    /**
     * Probes a provider's liveness.
     *
     * <p>Bypasses the circuit breaker deliberately. A probe's whole purpose is to discover whether
     * a provider has recovered, and routing it through an open breaker would guarantee it never
     * finds out — the breaker would reject every probe and the provider would stay excluded
     * forever. The breaker's own half-open trial calls handle recovery for real traffic; this
     * answers the operator's question.
     *
     * @return latency in milliseconds when the probe succeeded, or empty when it failed
     */
    public java.util.Optional<Long> probe(PaymentProvider provider) {
        long start = System.nanoTime();
        try {
            TimeLimiter timeLimiter = timeLimiters.timeLimiter(
                    "provider-probe-" + provider.id(),
                    () -> io.github.resilience4j.timelimiter.TimeLimiterConfig.custom()
                            .timeoutDuration(properties.getProviders().getProbeTimeout())
                            .cancelRunningFuture(true)
                            .build());

            timeLimiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(() -> {
                provider.probe();
                return Boolean.TRUE;
            }, providerCallExecutor));

            return java.util.Optional.of(
                    java.time.Duration.ofNanos(System.nanoTime() - start).toMillis());

        } catch (Exception ex) {
            log.debug("Health probe for {} failed: {}", provider.id(), ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    // ------------------------------------------------------------ breaker state

    /** The live breaker state, which is what routing consults rather than any stored health. */
    public CircuitBreaker.State breakerState(String providerId) {
        return breaker(providerId).getState();
    }

    /**
     * Whether the breaker would let a call through right now.
     *
     * <p>Used by the router to skip a provider it knows would be rejected. This is an
     * optimisation, not the protection: the breaker itself still rejects the call if the state
     * changes between this check and the attempt.
     */
    public boolean isCallPermitted(String providerId) {
        return breaker(providerId).tryAcquirePermission()
                // tryAcquirePermission consumes a permit in HALF_OPEN, so release it: the router
                // is only asking, and consuming trial permits here would starve the real call.
                ? releaseAndAllow(providerId)
                : false;
    }

    private boolean releaseAndAllow(String providerId) {
        breaker(providerId).releasePermission();
        return true;
    }

    /** Observed failure rate in the breaker's window, or -1 before the minimum call count. */
    public float failureRate(String providerId) {
        return breaker(providerId).getMetrics().getFailureRate();
    }

    /** Forces the breaker open, for an operator taking a provider out of service immediately. */
    public void forceOpen(String providerId) {
        breaker(providerId).transitionToForcedOpenState();
    }

    /** Returns the breaker to normal operation and clears its window. */
    public void reset(String providerId) {
        breaker(providerId).reset();
    }

    // --------------------------------------------------------------- internals

    /**
     * Applies breaker, retry and time limiter around one call.
     *
     * <p>Composed by hand rather than through annotations: the annotation-based starter resolves
     * breakers by name from YAML, so a typo would silently create a fresh breaker with default
     * settings and the protection would appear to be present while doing nothing. It also cannot
     * create a breaker per provider at runtime, which is the whole shape of this problem.
     */
    private <T> T execute(PaymentProvider provider, String operation, Supplier<T> call) {
        CircuitBreaker breaker = breaker(provider.id());
        Retry retry = retry(provider.id());
        TimeLimiter timeLimiter = timeLimiter(provider.id());

        Supplier<T> timeLimited = () -> {
            try {
                return timeLimiter.executeFutureSupplier(
                        () -> CompletableFuture.supplyAsync(call, providerCallExecutor));
            } catch (TimeoutException ex) {
                // Reported as a provider fault so the breaker counts it, and marked unsafe to
                // retry on the same provider: the charge may have gone through and only the
                // acknowledgement was lost.
                throw ProviderFaultException.timedOut(provider.id(),
                        provider.id() + " did not answer within the deadline");
            } catch (CompletionException | ExecutionException ex) {
                throw unwrap(provider.id(), ex);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw ProviderFaultException.upstreamError(provider.id(), ex.getMessage());
            }
        };

        Supplier<T> retried = Retry.decorateSupplier(retry, timeLimited);

        try {
            return breaker.decorateSupplier(retried).get();
        } catch (CallNotPermittedException ex) {
            // The breaker is open. Nothing was attempted, which is exactly the value it adds:
            // the saga can fall back immediately instead of waiting for another timeout.
            throw new ProviderUnavailableException(provider.id(),
                    provider.id() + " is not accepting calls: its circuit breaker is "
                            + breaker.getState());
        }
    }

    /**
     * Unwraps the executor's wrapper exceptions.
     *
     * <p>Without this a {@code ProviderFaultException} arrives as the cause of a
     * {@code CompletionException}, which matches neither the retry predicate nor the breaker's
     * failure predicate — so every fault would be recorded as an unrelated failure type and the
     * "safe to retry" distinction would be lost entirely.
     */
    private RuntimeException unwrap(String providerId, Exception ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof ProviderFaultException fault) {
            return fault;
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return ProviderFaultException.upstreamError(providerId,
                cause == null ? ex.getMessage() : cause.getMessage());
    }

    private CircuitBreaker breaker(String providerId) {
        // One breaker per provider, created on first use. Sharing one across providers would let a
        // single failing rail exclude all of them, which is the opposite of what routing is for.
        return circuitBreakers.circuitBreaker("provider-" + providerId);
    }

    private Retry retry(String providerId) {
        return retries.retry("provider-" + providerId, () -> providerRetryConfig());
    }

    private TimeLimiter timeLimiter(String providerId) {
        return timeLimiters.timeLimiter("provider-" + providerId,
                () -> io.github.resilience4j.timelimiter.TimeLimiterConfig.custom()
                        .timeoutDuration(properties.getProviders().getCallTimeout())
                        .cancelRunningFuture(true)
                        .build());
    }

    /**
     * Retry policy for provider calls.
     *
     * <p>A dedicated config rather than the shared default, because the predicate is the important
     * part: only a fault that is known to have happened before the request reached the provider is
     * retried. Retrying a timeout on the same provider risks a double authorisation, so the saga
     * falls back instead.
     *
     * <p>Exponential backoff with jitter. Plain exponential backoff synchronises every caller that
     * failed at the same instant into retrying at the same instant, reproducing the overload they
     * are recovering from.
     */
    private RetryConfig providerRetryConfig() {
        OrchestrationProperties.Providers config = properties.getProviders();
        return RetryConfig.custom()
                .maxAttempts(config.getMaxAttemptsPerProvider())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        config.getRetryInitialInterval(),
                        config.getRetryMultiplier(),
                        config.getRetryJitterFactor()))
                .retryOnException(throwable -> throwable instanceof ProviderFaultException fault
                        && fault.isSafeToRetrySameProvider())
                .failAfterMaxAttempts(true)
                .build();
    }

    /** The breaker refused the call, so the provider was never contacted. */
    public static class ProviderUnavailableException extends RuntimeException {

        private final String providerId;

        public ProviderUnavailableException(String providerId, String message) {
            super(message);
            this.providerId = providerId;
        }

        public String providerId() {
            return providerId;
        }
    }
}
