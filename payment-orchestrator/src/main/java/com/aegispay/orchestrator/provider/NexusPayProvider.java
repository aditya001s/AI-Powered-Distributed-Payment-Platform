package com.aegispay.orchestrator.provider;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * NEXUSPAY — the broad middle rail. <strong>Simulated.</strong>
 *
 * <p>Multi-currency, high ceiling, moderate fee and moderate latency. Usually the router's second
 * choice behind SWIFTPAY on price, and its first choice for anything SWIFTPAY cannot take: a large
 * transfer, or one denominated in USD.
 *
 * <p>Declines nothing on its own. That is the point of having it — with one rail that refuses on
 * amount and one that refuses on nothing, a test can tell a capability filter apart from a
 * decline, which is impossible if every provider behaves the same way.
 *
 * <p>Nothing here contacts a real payment rail. The name is invented.
 */
@Component
public class NexusPayProvider extends SimulatedPaymentProvider {

    public static final String ID = "NEXUSPAY";

    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(
            Set.of("INR", "USD"),
            // ₹50,00,000 — comfortably above the wallet API's own per-transaction cap.
            50_00_000_00L,
            100L,
            Set.of(PaymentType.P2P_TRANSFER, PaymentType.MERCHANT_PAYMENT,
                    PaymentType.WALLET_TOPUP, PaymentType.WALLET_WITHDRAWAL),
            // 55 bp = 0.55%.
            55);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "NexusPay (simulated)";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected Duration baselineLatency() {
        return Duration.ofMillis(120);
    }

    /**
     * Declines nothing.
     *
     * <p>Deliberate. A provider that always answers yes when it is reachable makes it possible to
     * attribute a failed payment unambiguously to routing, to a fault, or to another provider's
     * decline rule — rather than to some interaction between three sets of rules.
     */
    @Override
    protected Optional<AuthorizationResult.Declined> declineRule(AuthorizationRequest request,
                                                                 long latencyMs) {
        return Optional.empty();
    }
}
