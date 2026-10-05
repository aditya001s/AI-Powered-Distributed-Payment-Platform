package com.aegispay.orchestrator.provider;

import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.provider.PaymentProvider.AuthorizationRequest;
import com.aegispay.orchestrator.provider.PaymentProvider.AuthorizationResult;
import com.aegispay.orchestrator.provider.SimulationControls.FaultMode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the resilience wrapper around provider calls.
 *
 * <p>The composition is {@code CircuitBreaker(Retry(TimeLimiter(call)))}, and the order is the
 * design rather than a preference. What these tests protect is the consequences of that order:
 *
 * <ul>
 *   <li>Each <em>attempt</em> has its own deadline, so a first attempt that hangs cannot consume the
 *       whole budget and leave no time to fall back.</li>
 *   <li>The breaker sees one outcome per <em>logical call</em>, not one per attempt. Wrapped the
 *       other way round, three retries of a dead provider would register as three failures and the
 *       configured threshold would silently mean something different from what it says.</li>
 *   <li>An open breaker rejects before any time or thread is spent. That is the whole point of
 *       having one: not to fail gracefully, but to stop asking.</li>
 * </ul>
 *
 * <p>The registries here are built with a deliberately small window ({@code minimumNumberOfCalls=2})
 * so the breaker opens on a countable number of failures. The production thresholds are tuned for
 * real traffic volume and are not what is under test.
 */
class ProviderGatewayTest {

    private OrchestrationProperties properties;
    private CircuitBreakerRegistry circuitBreakers;
    private ScheduledExecutorService executor;
    private ProviderGateway gateway;
    private NexusPayProvider provider;

    @BeforeEach
    void setUp() {
        properties = new OrchestrationProperties();
        // Short enough that a SLOW call (2s baseline) blows the deadline promptly.
        properties.getProviders().setCallTimeout(Duration.ofMillis(600));
        properties.getProviders().setProbeTimeout(Duration.ofMillis(600));
        properties.getProviders().setRetryInitialInterval(Duration.ofMillis(1));

        circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                // Mirrors the shared configuration: a capability mismatch is our routing bug, not
                // the provider's fault, and must not count against it.
                .ignoreExceptions(IllegalArgumentException.class)
                .build());

        executor = Executors.newScheduledThreadPool(4);
        gateway = new ProviderGateway(circuitBreakers, RetryRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(), executor, new SimpleMeterRegistry(), properties);

        provider = new NexusPayProvider();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Nested
    @DisplayName("what counts as a failure")
    class FailureAccounting {

        /**
         * <strong>A decline must be invisible to the breaker.</strong>
         *
         * <p>A provider refusing a charge has worked correctly. Counting that against it would take a
         * perfectly healthy rail out of service because a run of customers happened to be short of
         * funds — and the router would then send their traffic to a dearer provider for no reason.
         */
        @Test
        @DisplayName("a decline does not count against the provider")
        void declineIsNotAFailure() {
            provider.controls().injectFaultIndefinitely(FaultMode.DECLINE);

            for (int call = 0; call < 6; call++) {
                AuthorizationResult result = gateway.authorize(provider, request());
                assertThat(result.isApproved()).isFalse();
            }

            assertThat(gateway.breakerState(provider.id()))
                    .as("six declines in a row must leave the breaker closed")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }

        /**
         * A capability mismatch is the router's mistake. Recording it against the provider would turn
         * one routing bug into an outage on a rail that did nothing wrong.
         */
        @Test
        @DisplayName("a capability mismatch does not count against the provider")
        void mismatchIsNotAFailure() {
            for (int call = 0; call < 4; call++) {
                assertThatIllegalArgumentException().isThrownBy(() ->
                        gateway.authorize(provider, request(Money.of("100.00", "EUR"))));
            }

            assertThat(gateway.breakerState(provider.id())).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        /**
         * Faults count, and the breaker opens at the configured threshold — after which the failure
         * mode changes from {@code ProviderFaultException} to
         * {@link ProviderGateway.ProviderUnavailableException}.
         *
         * <p>That change of type is the observable difference between "we tried and it broke" and "we
         * did not try", and the saga acts on it: the second lets it fall back immediately instead of
         * spending another timeout to learn what is already known.
         */
        @Test
        @DisplayName("a fault does count, and the breaker opens at the threshold")
        void faultTripsTheBreaker() {
            provider.controls().injectFaultIndefinitely(FaultMode.UPSTREAM_ERROR);

            // Two failures fill the window (minimumNumberOfCalls=2) at a 100% failure rate.
            for (int call = 0; call < 2; call++) {
                assertThatExceptionOfType(ProviderFaultException.class)
                        .isThrownBy(() -> gateway.authorize(provider, request()));
            }
            assertThat(gateway.breakerState(provider.id())).isEqualTo(CircuitBreaker.State.OPEN);

            assertThatExceptionOfType(ProviderGateway.ProviderUnavailableException.class)
                    .as("once open, the call is refused rather than attempted and failed")
                    .isThrownBy(() -> gateway.authorize(provider, request()));
        }
    }

    @Nested
    @DisplayName("the open breaker")
    class OpenBreaker {

        /**
         * The value the breaker adds is not graceful failure, it is <em>not asking</em>.
         *
         * <p>{@link ProviderGateway.ProviderUnavailableException} is a distinct type precisely so the
         * saga can tell "we did not try" from "we tried and it broke", and fall back immediately
         * rather than waiting out another timeout.
         */
        @Test
        @DisplayName("rejects without contacting the provider at all")
        void rejectsWithoutCalling() {
            gateway.forceOpen(provider.id());
            provider.controls().clearFaults();

            assertThatExceptionOfType(ProviderGateway.ProviderUnavailableException.class)
                    .isThrownBy(() -> gateway.authorize(provider, request()))
                    .satisfies(ex -> assertThat(ex.providerId()).isEqualTo(provider.id()));

            assertThat(provider.outstandingAuthorizationCount())
                    .as("the provider must not have been contacted")
                    .isZero();
        }

        /**
         * The router consults this to skip a provider it knows would be rejected.
         *
         * <p>An optimisation rather than the protection: the breaker itself still rejects the call if
         * the state changes between the check and the attempt. It matters that asking does not consume
         * a half-open trial permit, or the router's own question would starve the real call.
         */
        @Test
        @DisplayName("reports itself as not permitting calls, without consuming a trial permit")
        void reportsPermission() {
            assertThat(gateway.isCallPermitted(provider.id())).isTrue();
            assertThat(gateway.isCallPermitted(provider.id()))
                    .as("asking twice must not exhaust anything")
                    .isTrue();

            gateway.forceOpen(provider.id());
            assertThat(gateway.isCallPermitted(provider.id())).isFalse();
        }

        /**
         * Resetting clears the window as well as the state, so a provider returned to service gets a
         * genuine trial rather than being judged on failures from before the incident was fixed.
         */
        @Test
        @DisplayName("a reset restores normal operation and clears the window")
        void resetClearsTheWindow() {
            provider.controls().injectFault(FaultMode.UPSTREAM_ERROR, 2);
            for (int call = 0; call < 2; call++) {
                assertThatExceptionOfType(ProviderFaultException.class)
                        .isThrownBy(() -> gateway.authorize(provider, request()));
            }
            assertThat(gateway.breakerState(provider.id())).isEqualTo(CircuitBreaker.State.OPEN);

            gateway.reset(provider.id());

            assertThat(gateway.breakerState(provider.id())).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(gateway.authorize(provider, request()).isApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("retry")
    class Retry {

        /**
         * <strong>The retry predicate is the safety property, not the retry itself.</strong>
         *
         * <p>{@code authorize} is not idempotent, so a blind retry risks charging twice. A retry
         * therefore happens only for a fault known to have occurred <em>before</em> the request
         * reached the provider. {@code UNREACHABLE} qualifies; the second attempt succeeds.
         */
        @Test
        @DisplayName("retries a fault that happened before the request arrived")
        void retriesUnreachable() {
            properties.getProviders().setMaxAttemptsPerProvider(2);
            provider.controls().injectFault(FaultMode.UNREACHABLE, 1);

            AuthorizationResult result = gateway.authorize(provider, request());

            assertThat(result.isApproved())
                    .as("nothing arrived on the first attempt, so retrying was safe")
                    .isTrue();
        }

        /**
         * A timeout means the charge may exist and only the acknowledgement was lost, so retrying the
         * same provider could authorise it twice. The gateway must give up and let the saga fall back
         * and void whatever was left behind.
         */
        @Test
        @DisplayName("does not retry a timeout on the same provider")
        void doesNotRetryTimeout() {
            properties.getProviders().setMaxAttemptsPerProvider(3);
            provider.controls().injectFault(FaultMode.TIMEOUT, 1);

            assertThatExceptionOfType(ProviderFaultException.class)
                    .isThrownBy(() -> gateway.authorize(provider, request()));

            assertThat(provider.outstandingAuthorizationCount())
                    .as("exactly one orphaned charge: a retry would have created a second")
                    .isEqualTo(1);
        }

        /**
         * The breaker must see one outcome per logical call.
         *
         * <p>With two attempts per call and a threshold reached at two failures, a breaker that
         * counted attempts would open after a single logical call. Asserting it is still closed after
         * one is what pins the wrapping order.
         */
        @Test
        @DisplayName("a retried call registers as one failure with the breaker, not two")
        void retriesCountOnce() {
            properties.getProviders().setMaxAttemptsPerProvider(2);
            provider.controls().injectFault(FaultMode.UNREACHABLE, 2);

            assertThatExceptionOfType(ProviderFaultException.class)
                    .isThrownBy(() -> gateway.authorize(provider, request()));

            assertThat(gateway.breakerState(provider.id()))
                    .as("two attempts of one call must not fill a two-call window")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("the time limiter")
    class TimeLimit {

        /**
         * A provider that answers too slowly is reported as a timeout, and marked unsafe to retry on
         * the same provider — the work may have completed on the far side after we gave up.
         */
        @Test
        @DisplayName("turns an over-long call into a timeout fault")
        void enforcesTheDeadline() {
            properties.getProviders().setMaxAttemptsPerProvider(1);
            provider.controls().injectFaultIndefinitely(FaultMode.SLOW);

            assertThatExceptionOfType(ProviderFaultException.class)
                    .isThrownBy(() -> gateway.authorize(provider, request()))
                    .satisfies(fault -> {
                        assertThat(fault.faultCode()).isEqualTo("PROVIDER_TIMEOUT");
                        assertThat(fault.isSafeToRetrySameProvider())
                                .as("the call may have landed after we stopped waiting")
                                .isFalse();
                    });
        }
    }

    @Nested
    @DisplayName("compensation calls")
    class Compensation {

        /**
         * A void runs on the failure path, where the caller is already handling one problem. A second
         * exception here would mask the original cause, so failure is reported through the return
         * value and logged loudly instead.
         */
        @Test
        @DisplayName("a failed void is reported, not thrown")
        void voidReportsRatherThanThrows() {
            var approved = (AuthorizationResult.Approved) gateway.authorize(provider, request());

            // Two faults, because UNREACHABLE is safe to retry and the policy allows two attempts.
            // Arming one would let the retry succeed, which is correct behaviour but not the case
            // under test here.
            provider.controls().injectFault(FaultMode.UNREACHABLE,
                    properties.getProviders().getMaxAttemptsPerProvider());

            assertThat(gateway.voidQuietly(provider, approved.authorizationId()))
                    .as("the saga needs to know it could not clean up, not to be derailed by it")
                    .isFalse();
        }

        @Test
        @DisplayName("a successful void is confirmed")
        void voidConfirmsSuccess() {
            var approved = (AuthorizationResult.Approved) gateway.authorize(provider, request());

            assertThat(gateway.voidQuietly(provider, approved.authorizationId())).isTrue();
            assertThat(provider.holdsAuthorization(approved.authorizationId())).isFalse();
        }
    }

    @Nested
    @DisplayName("probing")
    class Probing {

        /**
         * <strong>Probes deliberately bypass the breaker.</strong>
         *
         * <p>A probe exists to discover whether a provider has recovered. Routing it through an open
         * breaker would guarantee it never finds out: every probe would be rejected and the provider
         * would stay excluded indefinitely, with the admin view reporting an outage that had already
         * ended.
         */
        @Test
        @DisplayName("succeeds even while the breaker is open")
        void probeBypassesTheBreaker() {
            gateway.forceOpen(provider.id());

            assertThat(gateway.probe(provider))
                    .as("an open breaker must not blind the health monitor")
                    .isPresent();
        }

        @Test
        @DisplayName("reports an empty result when the provider is not answering")
        void probeReportsFailure() {
            provider.controls().injectFault(FaultMode.UNREACHABLE, 1);

            assertThat(gateway.probe(provider)).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AuthorizationRequest request() {
        return request(Money.of("2000.00", "INR"));
    }

    /** A fresh idempotency key per request, so the simulation does not collapse them. */
    private static AuthorizationRequest request(Money amount) {
        return new AuthorizationRequest(
                "PAY-TEST0001", "TXN0001", amount, PaymentType.P2P_TRANSFER,
                "WAL-SOURCE", "WAL-DEST", "Dinner split",
                "SAGA-" + UUID.randomUUID());
    }
}
