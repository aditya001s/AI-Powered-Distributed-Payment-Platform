package com.aegispay.orchestrator.provider;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * SWIFTPAY — the cheap, fast, narrow rail. <strong>Simulated.</strong>
 *
 * <p>The router's default first choice: lowest fee and lowest latency. Its cost is reach — INR
 * only, and a modest per-transaction ceiling — so a large or foreign-currency payment is filtered
 * out on capability before routing even scores it.
 *
 * <p>Having one provider that is preferable but frequently ineligible is deliberate: it means the
 * capability filter does real work on ordinary traffic rather than only in contrived cases, and a
 * bug in it shows up immediately as fees that are higher than they should be.
 *
 * <p>Nothing here contacts a real payment rail. The name is invented.
 */
@Component
public class SwiftPayProvider extends SimulatedPaymentProvider {

    public static final String ID = "SWIFTPAY";

    /** ₹50,000. Above this the router must look elsewhere. */
    private static final long MAX_AMOUNT_MINOR = 50_000_00L;

    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(
            Set.of("INR"),
            MAX_AMOUNT_MINOR,
            100L,
            Set.of(PaymentType.P2P_TRANSFER, PaymentType.MERCHANT_PAYMENT),
            // 25 bp = 0.25%, the cheapest of the three.
            25);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "SwiftPay (simulated)";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    protected Duration baselineLatency() {
        return Duration.ofMillis(40);
    }

    /**
     * Declines round amounts at or above ₹25,000.
     *
     * <p>A stand-in for the velocity and structuring rules a real rail applies. Deterministic on
     * purpose: a probabilistic decline would make every routing and fallback test flaky, and a
     * genuine regression would be indistinguishable from an unlucky run.
     */
    @Override
    protected Optional<AuthorizationResult.Declined> declineRule(AuthorizationRequest request,
                                                                 long latencyMs) {
        long minor = request.amount().amountMinor();
        boolean roundThousands = minor % 1_000_00L == 0;

        if (minor >= 25_000_00L && roundThousands) {
            return Optional.of(new AuthorizationResult.Declined(
                    "VELOCITY_LIMIT",
                    "SwiftPay declines round amounts at or above 25,000 (simulated rule)",
                    latencyMs));
        }
        return Optional.empty();
    }
}
