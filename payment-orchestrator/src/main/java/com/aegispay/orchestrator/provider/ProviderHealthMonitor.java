package com.aegispay.orchestrator.provider;

import com.aegispay.common.observability.MetricNames;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.domain.ProviderState;
import com.aegispay.orchestrator.repository.ProviderStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Probes every provider on a schedule so an outage is discovered before a customer's payment
 * discovers it.
 *
 * <p><strong>Why probe at all when there is a circuit breaker.</strong> A breaker learns only from
 * real traffic, so it cannot notice a provider that has gone dark until a customer's payment is
 * routed into it and pays the latency cost of finding out. At low volume that is the common case:
 * the first payment in ten minutes becomes the probe. Probing separately means the router already
 * knows, and it also means a provider that recovered gets picked up promptly instead of waiting for
 * the breaker's next half-open trial.
 *
 * <p>The two signals do different jobs and neither replaces the other. The breaker is the
 * protection — it stops calls. This is the observation, and it feeds the admin view and the
 * operator's alerts.
 *
 * <p>Probes deliberately bypass the breaker. Routing a liveness check through an open breaker would
 * guarantee it never discovers recovery, because the breaker would reject every probe and the
 * provider would stay excluded indefinitely.
 */
@Service
public class ProviderHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthMonitor.class);

    private final ProviderRouter router;
    private final ProviderGateway gateway;
    private final ProviderStateRepository states;
    private final ProviderHealthRecorder recorder;
    private final OrchestrationProperties properties;
    private final MultiGauge healthGauge;

    public ProviderHealthMonitor(ProviderRouter router,
                                 ProviderGateway gateway,
                                 ProviderStateRepository states,
                                 ProviderHealthRecorder recorder,
                                 OrchestrationProperties properties,
                                 MeterRegistry meterRegistry) {
        this.router = router;
        this.gateway = gateway;
        this.states = states;
        this.recorder = recorder;
        this.properties = properties;
        this.healthGauge = MultiGauge.builder(MetricNames.PROVIDER_HEALTH).register(meterRegistry);
    }

    /**
     * Probes every provider.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a slow sweep cannot stack up
     * overlapping probe rounds all contending for the same provider.
     */
    @Scheduled(fixedDelayString = "${aegis.orchestration.providers.health-check-interval:30s}",
            initialDelayString = "${aegis.orchestration.providers.health-check-initial-delay:10s}")
    public void probeAll() {
        try {
            for (PaymentProvider provider : router.allProviders()) {
                probeOne(provider);
            }
            publishGauges();
        } catch (RuntimeException ex) {
            // A failing monitor must not take the service down, but it must be loud: a silent
            // monitor is indistinguishable from one reporting everything healthy.
            log.error("Provider health sweep could not complete", ex);
        }
    }

    /**
     * Probes one provider and records the result through a separate transactional bean.
     * Each provider therefore gets an independent short transaction and row lock.
     */
    public void probeOne(PaymentProvider provider) {
        Optional<Long> latency = gateway.probe(provider);
        boolean slow = latency
                .map(millis -> millis >= properties.getProviders().getProbeTimeout().toMillis() / 2)
                .orElse(false);

        recorder.record(provider.id(), latency, slow);
    }

    /**
     * Publishes one gauge per provider: 1 when routable, 0 when not.
     *
     * <p>A {@link MultiGauge} rather than a gauge per provider registered at startup, because
     * "routable" combines the operator switch, the observed health and the live breaker state, and
     * all three change at runtime.
     */
    private void publishGauges() {
        List<ProviderState> all = states.findAllByOrderByProviderIdAsc();

        healthGauge.register(all.stream()
                .map(state -> MultiGauge.Row.of(
                        Tags.of(MetricNames.TAG_PROVIDER, state.getProviderId()),
                        routable(state) ? 1 : 0))
                .toList(), true);
    }

    private boolean routable(ProviderState state) {
        return state.isRoutable()
                && gateway.breakerState(state.getProviderId())
                        != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
    }

    /** Health as the admin view reports it, combining stored state with the live breaker. */
    @Transactional(readOnly = true)
    public List<ProviderHealth> currentHealth() {
        return states.findAllByOrderByProviderIdAsc().stream()
                .map(this::toHealth)
                .toList();
    }

    /**
     * Combines one stored row with the live breaker into the shape the admin view reports.
     *
     * <p>Public so the admin service can render exactly the same view straight after flipping the
     * kill switch, rather than re-deriving {@code routable} from three fields and eventually
     * disagreeing with this method about what it means.
     */
    public ProviderHealth toHealth(ProviderState state) {
        return new ProviderHealth(
                state.getProviderId(),
                router.byId(state.getProviderId())
                        .map(PaymentProvider::displayName)
                        .orElse(state.getProviderId()),
                state.isEnabled(),
                state.getLastHealth(),
                gateway.breakerState(state.getProviderId()).name(),
                gateway.failureRate(state.getProviderId()),
                state.getLastLatencyMs(),
                state.getConsecutiveFailures(),
                state.getLastCheckedAt(),
                state.getDisabledReason(),
                routable(state));
    }

    /**
     * A provider's status as an operator sees it.
     *
     * @param routable the answer that actually decides routing: enabled, not unhealthy, and its
     *                 breaker is not open. Reported explicitly so nobody has to combine three
     *                 fields correctly in their head during an incident.
     */
    public record ProviderHealth(
            String providerId,
            String displayName,
            boolean enabled,
            ProviderState.Health observedHealth,
            String circuitBreakerState,
            float breakerFailureRate,
            Long lastLatencyMs,
            int consecutiveFailures,
            java.time.Instant lastCheckedAt,
            String disabledReason,
            boolean routable
    ) {
    }
}
