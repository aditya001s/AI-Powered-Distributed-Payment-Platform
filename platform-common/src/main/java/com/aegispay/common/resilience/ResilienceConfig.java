package com.aegispay.common.resilience;

import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.ForbiddenException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Programmatic Resilience4j registries.
 *
 * <p>Deliberately not using {@code resilience4j-spring-boot3}. The annotation-based
 * starter resolves breakers by name from YAML, which means a typo in
 * {@code @CircuitBreaker(name = "providerA")} silently creates a brand-new breaker
 * with default settings instead of failing — the protection appears to be there
 * and is not. Building the registries here makes every threshold explicit,
 * unit-testable without a Spring context, and impossible to mistype into
 * existence.
 *
 * <p>The other reason is dynamism: the orchestrator needs one breaker per payment
 * provider, created as providers are registered. A registry handles that
 * naturally; annotations cannot.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    /**
     * Shared circuit breaker defaults.
     *
     * <p>The {@code ignoreExceptions} list is the part that is most often wrong.
     * A breaker must trip on <em>technical</em> failure only. If business outcomes
     * counted as failures, a run of legitimate "insufficient funds" rejections
     * would open the breaker and take a perfectly healthy dependency out of
     * service. Equally, a 404 or a validation error is a correct answer from a
     * working system.
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(ResilienceProperties properties,
                                                        MeterRegistry meterRegistry) {
        ResilienceProperties.CircuitBreaker cb = properties.getCircuitBreaker();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cb.getFailureRateThreshold())
                .slowCallRateThreshold(cb.getSlowCallRateThreshold())
                .slowCallDurationThreshold(cb.getSlowCallDurationThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.getSlidingWindowSize())
                .minimumNumberOfCalls(cb.getMinimumNumberOfCalls())
                .waitDurationInOpenState(cb.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cb.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .ignoreExceptions(
                        // Business outcomes: the dependency worked correctly.
                        InsufficientFundsException.class,
                        NotFoundException.class,
                        BadRequestException.class,
                        ConflictException.class,
                        ForbiddenException.class,
                        UnauthorizedException.class,
                        IllegalArgumentException.class)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        // State transitions are operationally significant: log every one.
        registry.getEventPublisher().onEntryAdded(event -> {
            var breaker = event.getAddedEntry();
            breaker.getEventPublisher().onStateTransition(transition ->
                    log.warn("Circuit breaker '{}' transitioned {} -> {}",
                            breaker.getName(),
                            transition.getStateTransition().getFromState(),
                            transition.getStateTransition().getToState()));
            breaker.getEventPublisher().onCallNotPermitted(e ->
                    log.debug("Circuit breaker '{}' is OPEN; call rejected without attempting it",
                            breaker.getName()));
        });

        // Exposes resilience4j_circuitbreaker_state / _calls to Prometheus.
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    /**
     * Retry defaults with exponential backoff <em>and jitter</em>.
     *
     * <p>{@code ofExponentialRandomBackoff} rather than plain exponential: plain
     * backoff synchronises every client that failed at the same moment into
     * retrying at the same moment, which reproduces the overload. The random factor
     * spreads them out.
     */
    @Bean
    public RetryRegistry retryRegistry(ResilienceProperties properties, MeterRegistry meterRegistry) {
        ResilienceProperties.Retry retry = properties.getRetry();

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(retry.getMaxAttempts())
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialRandomBackoff(
                                retry.getInitialInterval(),
                                retry.getMultiplier(),
                                retry.getJitterFactor()))
                .retryExceptions(
                        // Transient, worth another attempt.
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.client.ResourceAccessException.class,
                        org.springframework.web.client.HttpServerErrorException.class)
                .ignoreExceptions(
                        // Retrying will produce the same answer.
                        InsufficientFundsException.class,
                        NotFoundException.class,
                        BadRequestException.class,
                        ConflictException.class,
                        ForbiddenException.class,
                        UnauthorizedException.class,
                        IllegalArgumentException.class)
                .failAfterMaxAttempts(true)
                .build();

        RetryRegistry registry = RetryRegistry.of(config);
        registry.getEventPublisher().onEntryAdded(event -> {
            var r = event.getAddedEntry();
            r.getEventPublisher().onRetry(e ->
                    log.debug("Retry '{}' attempt {} after {}",
                            r.getName(), e.getNumberOfRetryAttempts(), e.getWaitInterval()));
        });

        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry(ResilienceProperties properties) {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(properties.getTimeLimiter().getTimeout())
                // Interrupt the worker thread so a hung call actually releases its
                // thread instead of leaking one per timeout.
                .cancelRunningFuture(true)
                .build();
        return TimeLimiterRegistry.of(config);
    }

    /** Convenience factory for a breaker with a non-default timeout. */
    public static TimeLimiterConfig timeoutOf(Duration timeout) {
        return TimeLimiterConfig.custom()
                .timeoutDuration(timeout)
                .cancelRunningFuture(true)
                .build();
    }
}
