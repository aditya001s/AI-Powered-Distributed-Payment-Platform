package com.aegispay.orchestrator.provider;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * PAYBRIDGE — the expensive, slow, always-eligible rail. <strong>Simulated.</strong>
 *
 * <p>The last resort. Highest fee, highest latency, widest capability: every currency the platform
 * supports, no meaningful ceiling, every payment type, and no decline rules of its own.
 *
 * <p>Its role is to make the fallback chain terminate in something useful. A router whose
 * candidates are all narrow or all fragile has nowhere to go when the preferred rails are out, and
 * the payment fails for want of an option rather than for want of funds. Paying more to complete a
 * payment is almost always better than not completing it — which is precisely the trade the fee
 * weighting in {@code ProviderRouter} is there to express, and why this provider is scored last
 * rather than excluded.
 *
 * <p>Nothing here contacts a real payment rail. The name is invented.
 */
@Component
public class PayBridgeProvider extends SimulatedPaymentProvider {

    public static final String ID = "PAYBRIDGE";

    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(
            Set.of("INR", "USD", "EUR", "GBP"),
            // Effectively unbounded next to the wallet API's own per-transaction cap.
            1_00_00_000_00L,
            1L,
            Set.of(PaymentType.values()),
            // 95 bp = 0.95%, the dearest of the three.
            95);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "PayBridge (simulated)";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected Duration baselineLatency() {
        return Duration.ofMillis(320);
    }

    /** Declines nothing: a last resort that refused things would not be one. */
    @Override
    protected Optional<AuthorizationResult.Declined> declineRule(AuthorizationRequest request,
                                                                 long latencyMs) {
        return Optional.empty();
    }
}
