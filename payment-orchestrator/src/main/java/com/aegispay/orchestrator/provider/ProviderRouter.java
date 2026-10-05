package com.aegispay.orchestrator.provider;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.domain.ProviderState;
import com.aegispay.orchestrator.repository.ProviderAttemptRepository;
import com.aegispay.orchestrator.repository.ProviderStateRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chooses which providers to try, in what order.
 *
 * <p>Returns an <strong>ordered list</strong> rather than a single choice, which is what makes
 * fallback trivial: the saga walks the list until one provider authorises. A router that returned
 * only its favourite would force the caller to ask again after every failure, and the second answer
 * would be computed from the same stale signals that produced the first.
 *
 * <h2>Three stages, in this order</h2>
 *
 * <ol>
 *   <li><strong>Eligibility.</strong> Can this provider even process this charge — currency,
 *       amount limits, payment type? A capability mismatch is not a preference, so it is filtered
 *       out before anything is scored. Scoring first and filtering after would let an ineligible
 *       provider win and then be discarded, silently promoting a worse one.</li>
 *   <li><strong>Availability.</strong> Has an operator disabled it, and would its circuit breaker
 *       let a call through? Both are hard exclusions.</li>
 *   <li><strong>Scoring.</strong> Whatever survives is ranked on observed reliability, price and
 *       latency.</li>
 * </ol>
 *
 * <h2>Why weights rather than a priority order</h2>
 *
 * <p>A fixed order cannot express the trade-off that actually matters. The cheapest rail is the
 * right choice while it is healthy and the wrong one the moment it is not, and no static ordering
 * captures that. Reliability carries the heaviest weight because a failed payment costs far more
 * than an expensive one: the customer's money is stuck mid-saga and somebody has to explain it.
 *
 * <h2>Health comes from the breaker, not from the database</h2>
 *
 * <p>Routing reads the live circuit breaker state and recent attempt history. {@code ProviderState}
 * is consulted only for the operator kill switch, because a stored health value is stale the moment
 * it is written — routing on it would send a payment to a provider that failed a second ago.
 */
@Service
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    /** Attempts sampled per provider when measuring reliability. */
    private static final int SAMPLE_SIZE = 50;

    /**
     * Reliability assumed for a provider with no recent history.
     *
     * <p>Optimistic on purpose. Assuming the worst would exclude every provider on a cold start —
     * a self-inflicted outage — and would also permanently exclude any provider that recovered,
     * since it can only build history by being given traffic.
     */
    private static final double DEFAULT_RELIABILITY = 0.95;

    private final List<PaymentProvider> providers;
    private final ProviderGateway gateway;
    private final ProviderStateRepository states;
    private final ProviderAttemptRepository attempts;
    private final OrchestrationProperties properties;

    public ProviderRouter(List<PaymentProvider> providers,
                          ProviderGateway gateway,
                          ProviderStateRepository states,
                          ProviderAttemptRepository attempts,
                          OrchestrationProperties properties) {
        this.providers = List.copyOf(providers);
        this.gateway = gateway;
        this.states = states;
        this.attempts = attempts;
        this.properties = properties;

        if (providers.isEmpty()) {
            // Nothing would ever route. Better to say so at startup than to discover it on the
            // first payment.
            log.error("No payment providers are registered; every payment will fail to route");
        } else {
            log.info("Provider router initialised with {}: {}", providers.size(),
                    providers.stream().map(PaymentProvider::id).toList());
        }
    }

    /**
     * The providers to try for a charge, best first.
     *
     * <p>An empty result means the payment cannot be routed at all, which the saga reports as
     * {@code ALL_PROVIDERS_UNAVAILABLE}. That is a distinct outcome from a decline: nothing was
     * charged, so the customer can safely be told to try again.
     */
    @Transactional(readOnly = true)
    public List<RoutingCandidate> route(Money amount, PaymentType paymentType) {
        Map<String, ProviderState> stateById = states.findAll().stream()
                .collect(Collectors.toMap(ProviderState::getProviderId, Function.identity()));

        Instant since = Instant.now().minus(properties.getRouting().getObservationWindow());
        List<RoutingCandidate> candidates = new ArrayList<>();

        for (PaymentProvider provider : providers) {
            Exclusion exclusion = exclusionFor(provider, amount, paymentType, stateById);
            if (exclusion != null) {
                log.debug("Excluding {} for {}: {}", provider.id(), amount, exclusion.reason());
                continue;
            }
            candidates.add(score(provider, amount, since));
        }

        candidates.sort(Comparator.comparingDouble(RoutingCandidate::score).reversed());

        int limit = Math.min(candidates.size(), properties.getRouting().getMaxProvidersPerPayment());
        List<RoutingCandidate> chosen = List.copyOf(candidates.subList(0, limit));

        if (chosen.isEmpty()) {
            log.warn("No provider can route {} of type {}", amount, paymentType);
        } else if (log.isDebugEnabled()) {
            log.debug("Routing {} via {}", amount,
                    chosen.stream().map(c -> c.providerId() + "=" + String.format("%.3f", c.score()))
                            .toList());
        }
        return chosen;
    }

    /** Diagnostics for the admin view: every provider with its score and any exclusion. */
    @Transactional(readOnly = true)
    public List<RoutingDecision> explain(Money amount, PaymentType paymentType) {
        Map<String, ProviderState> stateById = states.findAll().stream()
                .collect(Collectors.toMap(ProviderState::getProviderId, Function.identity()));
        Instant since = Instant.now().minus(properties.getRouting().getObservationWindow());

        return providers.stream()
                .map(provider -> {
                    Exclusion exclusion = exclusionFor(provider, amount, paymentType, stateById);
                    RoutingCandidate scored = exclusion == null
                            ? score(provider, amount, since)
                            : null;
                    return new RoutingDecision(
                            provider.id(),
                            provider.displayName(),
                            exclusion == null,
                            exclusion == null ? null : exclusion.reason(),
                            gateway.breakerState(provider.id()).name(),
                            scored == null ? 0d : scored.score(),
                            scored == null ? null : scored.fee(),
                            scored == null ? 0d : scored.reliability(),
                            scored == null ? 0L : scored.observedLatencyMs());
                })
                .sorted(Comparator.comparing(RoutingDecision::eligible).reversed()
                        .thenComparing(Comparator.comparingDouble(RoutingDecision::score).reversed()))
                .toList();
    }

    // --------------------------------------------------------------- filtering

    /** Returns why a provider cannot be used, or {@code null} when it can. */
    private Exclusion exclusionFor(PaymentProvider provider, Money amount, PaymentType paymentType,
                                   Map<String, ProviderState> stateById) {

        // Stage 1: capability. Checked first so an ineligible provider can never win the scoring
        // and then be discarded, which would silently promote a worse candidate.
        if (!provider.capabilities().supports(amount, paymentType)) {
            return new Exclusion("cannot process " + amount + " of type " + paymentType);
        }

        // Stage 2a: the operator kill switch, which is the one signal that must survive a restart.
        ProviderState state = stateById.get(provider.id());
        if (state != null && !state.isEnabled()) {
            return new Exclusion("disabled by an operator: " + state.getDisabledReason());
        }

        // Stage 2b: the live breaker. An open breaker means recent calls failed, so attempting
        // one would waste the customer's latency budget to learn what is already known.
        if (!gateway.isCallPermitted(provider.id())) {
            return new Exclusion("circuit breaker is " + gateway.breakerState(provider.id()));
        }
        return null;
    }

    // ---------------------------------------------------------------- scoring

    private RoutingCandidate score(PaymentProvider provider, Money amount, Instant since) {
        OrchestrationProperties.Routing weights = properties.getRouting();

        double reliability = observedReliability(provider.id(), since);
        long latencyMs = observedLatencyMs(provider.id(), since);
        Money fee = provider.capabilities().feeFor(amount);

        double reliabilityScore = reliability;
        double latencyScore = latencyScore(latencyMs, weights);
        double feeScore = feeScore(provider);

        double total = weights.getHealthWeight() * reliabilityScore
                + weights.getLatencyWeight() * latencyScore
                + weights.getFeeWeight() * feeScore;

        return new RoutingCandidate(provider, total, fee, reliability, latencyMs);
    }

    /**
     * Share of recent authorisation attempts that were not technical failures.
     *
     * <p><strong>Declines are excluded from the denominator as well as the numerator.</strong> A
     * provider that refuses a charge has worked correctly, so counting declines against it would
     * route traffic away from a healthy rail on the strength of its customers' balances — and a
     * provider serving a poorer segment would be permanently penalised for it.
     */
    private double observedReliability(String providerId, Instant since) {
        long total = attempts.countAuthorizations(providerId, since);
        if (total == 0) {
            return DEFAULT_RELIABILITY;
        }
        long failures = attempts.countTechnicalFailures(providerId, since);
        long declines = attempts.findRecentAuthorizations(providerId, since, Limit.of(SAMPLE_SIZE))
                .stream()
                .filter(attempt -> attempt.getOutcome()
                        == com.aegispay.orchestrator.domain.ProviderAttempt.Outcome.DECLINED)
                .count();

        long judged = total - declines;
        if (judged <= 0) {
            // Everything in the window was a decline, which says nothing about reliability.
            return DEFAULT_RELIABILITY;
        }
        return Math.clamp((double) (judged - failures) / judged, 0d, 1d);
    }

    /**
     * Mean latency of recent successful authorisations, falling back to a neutral midpoint before
     * any history exists.
     */
    private long observedLatencyMs(String providerId, Instant since) {
        double observed = attempts.averageApprovedLatencyMs(providerId, since);
        if (observed > 0) {
            return Math.round(observed);
        }
        // No history yet. Zero would score as instant and unfairly promote an untried provider
        // above one with a proven record, so the breaker's slow-call threshold is used as a
        // neutral midpoint instead.
        return properties.getRouting().getLatencyCeiling().toMillis() / 2;
    }

    /** 1.0 at zero latency, falling linearly to 0.0 at the configured ceiling. */
    private double latencyScore(long latencyMs, OrchestrationProperties.Routing weights) {
        long ceiling = Math.max(1L, weights.getLatencyCeiling().toMillis());
        return Math.clamp(1d - ((double) latencyMs / ceiling), 0d, 1d);
    }

    /**
     * 1.0 for the cheapest registered provider, falling to 0.0 for the dearest.
     *
     * <p>Relative rather than absolute, so the score means "cheap for this platform" rather than
     * being calibrated against a fee level that would go stale the moment a provider repriced.
     */
    private double feeScore(PaymentProvider provider) {
        int cheapest = providers.stream()
                .mapToInt(candidate -> candidate.capabilities().feeBasisPoints())
                .min().orElse(0);
        int dearest = providers.stream()
                .mapToInt(candidate -> candidate.capabilities().feeBasisPoints())
                .max().orElse(0);

        if (dearest == cheapest) {
            // All providers cost the same, so price carries no information.
            return 1d;
        }
        int fee = provider.capabilities().feeBasisPoints();
        return Math.clamp(1d - ((double) (fee - cheapest) / (dearest - cheapest)), 0d, 1d);
    }

    // ------------------------------------------------------------- accessors

    public List<PaymentProvider> allProviders() {
        return providers;
    }

    public java.util.Optional<PaymentProvider> byId(String providerId) {
        return providers.stream()
                .filter(provider -> provider.id().equalsIgnoreCase(providerId))
                .findFirst();
    }

    // ----------------------------------------------------------------- types

    private record Exclusion(String reason) {
    }

    /** A provider the saga may try, with the numbers behind its position. */
    public record RoutingCandidate(
            PaymentProvider provider,
            double score,
            Money fee,
            double reliability,
            long observedLatencyMs
    ) {
        public String providerId() {
            return provider.id();
        }
    }

    /** One provider's routing verdict, for the admin view. */
    public record RoutingDecision(
            String providerId,
            String displayName,
            boolean eligible,
            String exclusionReason,
            String circuitBreakerState,
            double score,
            Money fee,
            double reliability,
            long observedLatencyMs
    ) {
    }

    /** Convenience for reading a breaker state without reaching for the gateway. */
    public CircuitBreaker.State breakerState(String providerId) {
        return gateway.breakerState(providerId);
    }
}
