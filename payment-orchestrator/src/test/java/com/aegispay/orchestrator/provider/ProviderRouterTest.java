package com.aegispay.orchestrator.provider;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.domain.ProviderState;
import com.aegispay.orchestrator.repository.ProviderAttemptRepository;
import com.aegispay.orchestrator.repository.ProviderStateRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for provider routing.
 *
 * <p>Three properties are being protected, and each of them is a bug that would be invisible in
 * production until it cost money:
 *
 * <ul>
 *   <li><strong>Eligibility is decided before scoring.</strong> If an ineligible provider could win
 *       the scoring and then be discarded, a worse provider would be silently promoted and payments
 *       would quietly get more expensive.</li>
 *   <li><strong>Availability is a hard exclusion.</strong> An operator's kill switch and an open
 *       breaker both remove a provider entirely; no score is high enough to override them.</li>
 *   <li><strong>The result is an ordered list, not a single choice.</strong> That is what makes
 *       fallback trivial, and it means the second candidate is computed from the same snapshot as
 *       the first rather than from signals that moved in between.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderRouterTest {

    @Mock
    private ProviderGateway gateway;
    @Mock
    private ProviderStateRepository states;
    @Mock
    private ProviderAttemptRepository attempts;

    private OrchestrationProperties properties;
    private SwiftPayProvider swiftPay;
    private NexusPayProvider nexusPay;
    private PayBridgeProvider payBridge;
    private ProviderRouter router;

    /** Provider states as the database would hold them, mutable so a test can disable one. */
    private final List<ProviderState> providerStates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new OrchestrationProperties();
        swiftPay = new SwiftPayProvider();
        nexusPay = new NexusPayProvider();
        payBridge = new PayBridgeProvider();

        providerStates.clear();
        providerStates.add(ProviderState.of(SwiftPayProvider.ID));
        providerStates.add(ProviderState.of(NexusPayProvider.ID));
        providerStates.add(ProviderState.of(PayBridgeProvider.ID));

        when(states.findAll()).thenReturn(providerStates);

        // Every breaker closed and every call permitted, unless a test says otherwise.
        when(gateway.isCallPermitted(anyString())).thenReturn(true);
        when(gateway.breakerState(anyString())).thenReturn(CircuitBreaker.State.CLOSED);

        // No history: the router must cope with a cold start rather than refusing to route.
        when(attempts.countAuthorizations(anyString(), any())).thenReturn(0L);
        when(attempts.countTechnicalFailures(anyString(), any())).thenReturn(0L);
        when(attempts.averageApprovedLatencyMs(anyString(), any())).thenReturn(0d);
        when(attempts.findRecentAuthorizations(anyString(), any(), any())).thenReturn(List.of());

        router = new ProviderRouter(List.of(swiftPay, nexusPay, payBridge), gateway, states,
                attempts, properties);
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        /**
         * With everything healthy and no history, price decides — because reliability and latency are
         * identical across the three, so the only term left that differs is the fee.
         *
         * <p>This is the shape the fee weighting is meant to produce: cheapest first while nothing is
         * wrong, and reorderable the moment something is.
         */
        @Test
        @DisplayName("prefers the cheapest rail when every provider is equally healthy")
        void cheapestFirstWhenAllHealthy() {
            List<ProviderRouter.RoutingCandidate> route =
                    router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER);

            assertThat(route).extracting(ProviderRouter.RoutingCandidate::providerId)
                    .containsExactly(SwiftPayProvider.ID, NexusPayProvider.ID,
                            PayBridgeProvider.ID);
        }

        /**
         * An ordered list rather than a single choice.
         *
         * <p>A router that returned only its favourite would force the saga to ask again after every
         * failure, and the second answer would be computed from signals that had already moved —
         * including the breaker the failed attempt just nudged.
         */
        @Test
        @DisplayName("returns every eligible provider so fallback needs no second decision")
        void returnsTheWholeChain() {
            assertThat(router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER))
                    .hasSize(3);
        }

        /**
         * Bounded on purpose. Exhausting every rail on a payment that is going to fail anyway
         * multiplies the latency the customer waits and the load on providers already struggling.
         */
        @Test
        @DisplayName("never returns more providers than the configured cap")
        void respectsTheCap() {
            properties.getRouting().setMaxProvidersPerPayment(2);

            assertThat(router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER))
                    .hasSize(2)
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .containsExactly(SwiftPayProvider.ID, NexusPayProvider.ID);
        }

        /**
         * Reliability outranks price, and this is the test that pins it.
         *
         * <p>A failed payment costs far more than an expensive one: the customer's money is stuck
         * mid-saga and somebody has to explain it. If price could outweigh a rail that is visibly
         * failing, the cheapest provider would keep being chosen while it was broken.
         */
        @Test
        @DisplayName("demotes the cheapest rail once it is visibly failing")
        void reliabilityOutweighsPrice() {
            // SwiftPay: 10 recent authorisations, 8 of them technical failures.
            when(attempts.countAuthorizations(eq(SwiftPayProvider.ID), any())).thenReturn(10L);
            when(attempts.countTechnicalFailures(eq(SwiftPayProvider.ID), any())).thenReturn(8L);

            List<ProviderRouter.RoutingCandidate> route =
                    router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER);

            assertThat(route).first()
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .as("a rail failing 80%% of calls must not stay first on price alone")
                    .isEqualTo(NexusPayProvider.ID);
        }

        /**
         * Declines must not look like failures.
         *
         * <p>A provider refusing charges is working correctly. Counting declines against it would
         * route traffic away from a healthy rail on the strength of its customers' balances — and a
         * provider serving a poorer segment would be permanently penalised for it.
         */
        @Test
        @DisplayName("a run of declines does not demote a working provider")
        void declinesDoNotAffectRanking() {
            when(attempts.countAuthorizations(eq(SwiftPayProvider.ID), any())).thenReturn(10L);
            when(attempts.countTechnicalFailures(eq(SwiftPayProvider.ID), any())).thenReturn(0L);
            when(attempts.findRecentAuthorizations(eq(SwiftPayProvider.ID), any(), any()))
                    .thenReturn(declines(10));

            List<ProviderRouter.RoutingCandidate> route =
                    router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER);

            assertThat(route).first()
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .as("the provider answered every time; it just said no")
                    .isEqualTo(SwiftPayProvider.ID);
        }
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        /**
         * Capability is checked before scoring, and this is what that buys.
         *
         * <p>₹1,20,000 is above SwiftPay's ceiling. Filtered out first, it cannot win on price and
         * then be dropped — which would leave the saga trying a provider guaranteed to throw a
         * capability error, spending a round trip to learn something already known.
         */
        @Test
        @DisplayName("excludes a provider that cannot process the amount")
        void excludesOnAmount() {
            List<ProviderRouter.RoutingCandidate> route =
                    router.route(Money.of("120000.00", "INR"), PaymentType.P2P_TRANSFER);

            assertThat(route).extracting(ProviderRouter.RoutingCandidate::providerId)
                    .doesNotContain(SwiftPayProvider.ID)
                    .containsExactly(NexusPayProvider.ID, PayBridgeProvider.ID);
        }

        @Test
        @DisplayName("excludes a provider that does not settle the currency")
        void excludesOnCurrency() {
            assertThat(router.route(Money.of("100.00", "USD"), PaymentType.P2P_TRANSFER))
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .containsExactly(NexusPayProvider.ID, PayBridgeProvider.ID);

            assertThat(router.route(Money.of("100.00", "EUR"), PaymentType.P2P_TRANSFER))
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .as("only the last resort reaches EUR")
                    .containsExactly(PayBridgeProvider.ID);
        }

        @Test
        @DisplayName("excludes a provider that does not handle the payment type")
        void excludesOnPaymentType() {
            assertThat(router.route(Money.of("2000.00", "INR"), PaymentType.WALLET_WITHDRAWAL))
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .doesNotContain(SwiftPayProvider.ID);
        }

        /**
         * An empty result is a distinct outcome from a decline: nothing was charged, so the customer
         * can safely be told to try again. The saga reports it as
         * {@code ALL_PROVIDERS_UNAVAILABLE} rather than as a rejection.
         */
        @Test
        @DisplayName("returns nothing when no rail can take the charge")
        void returnsEmptyWhenNothingFits() {
            assertThat(router.route(Money.of("100.00", "JPY"), PaymentType.P2P_TRANSFER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("availability")
    class Availability {

        /**
         * The operator kill switch is absolute.
         *
         * <p>It is the one signal that survives a restart, and it exists so an operator can mitigate
         * an incident without a deploy. A score that could override it would make the switch advisory,
         * which is exactly what it must not be.
         */
        @Test
        @DisplayName("excludes a provider an operator disabled, whatever it would have scored")
        void excludesDisabledProvider() {
            providerStates.getFirst().disable("Elevated timeouts on their status page", "USR-ADMIN");

            assertThat(router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER))
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .doesNotContain(SwiftPayProvider.ID);
        }

        /**
         * An open breaker means recent calls failed. Attempting one anyway would spend the customer's
         * latency budget learning what is already known — which is the entire value the breaker adds.
         */
        @Test
        @DisplayName("excludes a provider whose breaker is open")
        void excludesOpenBreaker() {
            when(gateway.isCallPermitted(SwiftPayProvider.ID)).thenReturn(false);
            when(gateway.breakerState(SwiftPayProvider.ID)).thenReturn(CircuitBreaker.State.OPEN);

            assertThat(router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER))
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .containsExactly(NexusPayProvider.ID, PayBridgeProvider.ID);
        }

        /**
         * Routing reads the live breaker, not the stored health value.
         *
         * <p>A stored value is stale the moment it is written, so routing on it would send a payment
         * to a provider that failed a second ago. The stored health is for the admin view; the breaker
         * is for the decision.
         */
        @Test
        @DisplayName("routes on the live breaker even when the stored health says otherwise")
        void breakerBeatsStoredHealth() {
            // Two failed probes make the stored health UNHEALTHY, but the breaker is closed.
            providerStates.getFirst().probeFailed(1);

            assertThat(router.route(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER))
                    .extracting(ProviderRouter.RoutingCandidate::providerId)
                    .as("the breaker is what this instance is actually seeing")
                    .contains(SwiftPayProvider.ID);
        }
    }

    @Nested
    @DisplayName("explanation")
    class Explanation {

        /**
         * Exists because "why did this payment go to the expensive provider" is otherwise answerable
         * only by reading the scoring code and guessing at the inputs.
         */
        @Test
        @DisplayName("reports every provider, eligible or not, with the reason for any exclusion")
        void explainsEveryProvider() {
            List<ProviderRouter.RoutingDecision> decisions =
                    router.explain(Money.of("120000.00", "INR"), PaymentType.P2P_TRANSFER);

            assertThat(decisions).hasSize(3);
            ProviderRouter.RoutingDecision swift = decisions.stream()
                    .filter(d -> d.providerId().equals(SwiftPayProvider.ID))
                    .findFirst().orElseThrow();

            assertThat(swift.eligible()).isFalse();
            assertThat(swift.exclusionReason())
                    .as("the reason has to say what was wrong, not merely that something was")
                    .contains("cannot process");
        }

        @Test
        @DisplayName("names the operator who disabled a provider as the reason")
        void explainsAnOperatorExclusion() {
            providerStates.get(1).disable("Contract suspended", "USR-ADMIN");

            ProviderRouter.RoutingDecision nexus =
                    router.explain(Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER).stream()
                            .filter(d -> d.providerId().equals(NexusPayProvider.ID))
                            .findFirst().orElseThrow();

            assertThat(nexus.eligible()).isFalse();
            assertThat(nexus.exclusionReason()).contains("Contract suspended");
        }

        @Test
        @DisplayName("puts eligible providers ahead of excluded ones")
        void ordersEligibleFirst() {
            List<ProviderRouter.RoutingDecision> decisions =
                    router.explain(Money.of("100.00", "EUR"), PaymentType.P2P_TRANSFER);

            assertThat(decisions.getFirst().eligible()).isTrue();
            assertThat(decisions.getFirst().providerId()).isEqualTo(PayBridgeProvider.ID);
        }
    }

    @Nested
    @DisplayName("lookups")
    class Lookups {

        @Test
        @DisplayName("finds a provider by id, case-insensitively")
        void findsById() {
            assertThat(router.byId("swiftpay")).containsSame(swiftPay);
            assertThat(router.byId(SwiftPayProvider.ID)).containsSame(swiftPay);
            assertThat(router.byId("NOPE")).isEmpty();
        }

        @Test
        @DisplayName("exposes every registered provider for the health sweep")
        void listsAll() {
            assertThat(router.allProviders()).hasSize(3);
        }
    }

    /**
     * A router with no providers must still construct.
     *
     * <p>Failing at startup would be defensible, but it would also mean a packaging mistake takes the
     * whole service down rather than surfacing as payments that cannot route — and the second is far
     * easier to diagnose from an alert. It logs at ERROR for that reason.
     */
    @Test
    @DisplayName("starts with no providers registered, and routes nothing")
    void survivesWithNoProviders() {
        ProviderRouter empty = new ProviderRouter(List.of(), gateway, states, attempts, properties);

        assertThat(empty.allProviders()).isEmpty();
        assertThat(empty.route(Money.of("100.00", "INR"), PaymentType.P2P_TRANSFER)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    /** Attempts that were all declines: answered correctly, just refused. */
    private static List<com.aegispay.orchestrator.domain.ProviderAttempt> declines(int count) {
        List<com.aegispay.orchestrator.domain.ProviderAttempt> attempts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            attempts.add(com.aegispay.orchestrator.domain.ProviderAttempt.declined(
                    "SAGA-1", "PAY-1", SwiftPayProvider.ID, 1,
                    Money.of("2000.00", "INR"), "VELOCITY_LIMIT", "declined", 40L));
        }
        return attempts;
    }
}
