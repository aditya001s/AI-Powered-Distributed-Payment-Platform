package com.aegispay.wallet.service;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.wallet.domain.WalletHold;
import com.aegispay.wallet.repository.WalletHoldRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Releases fund reservations that outlived their expiry.
 *
 * <p><strong>Why this must exist.</strong> A reservation makes money unspendable. If the
 * component that created it dies before capturing or releasing — a crashed orchestrator, a
 * pod evicted mid-saga, a lost Kafka message — the customer's funds stay frozen with
 * nothing scheduled to free them. Without a sweeper, one crash permanently reduces a
 * customer's usable balance and the only remedy is manual database surgery.
 *
 * <p>This is a safety net, not the normal path. Every increment of the metric it emits
 * means a payment saga failed to finish, so a non-zero rate is a signal worth alerting on
 * rather than routine background noise.
 *
 * <p><strong>Batched, one transaction per hold.</strong> Releasing every expired hold in a
 * single transaction would take row locks on many wallets at once and block live payments
 * on all of them. Each release is its own short transaction, and the batch is capped, so
 * the sweeper degrades gracefully under a large backlog instead of causing an outage while
 * cleaning one up.
 *
 * <p>Safe to run on every replica: {@code releaseExpired} re-reads the hold under a row
 * lock and does nothing if another instance already released it.
 */
@Component
public class WalletHoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(WalletHoldSweeper.class);

    /**
     * Maximum holds released per sweep.
     *
     * <p>Bounded so a large backlog is worked through over several cycles rather than in
     * one long-running transaction storm.
     */
    private static final int BATCH_SIZE = 100;

    private final WalletHoldRepository holds;
    private final WalletMutationService mutations;
    private final MeterRegistry meterRegistry;

    public WalletHoldSweeper(WalletHoldRepository holds,
                             WalletMutationService mutations,
                             MeterRegistry meterRegistry) {
        this.holds = holds;
        this.mutations = mutations;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Sweeps expired reservations.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate} so cycles cannot overlap: the
     * next sweep starts only after this one finishes, which prevents a slow batch from
     * stacking up concurrent sweepers all contending for the same wallet locks.
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void releaseExpiredHolds() {
        List<WalletHold> expired = holds.findExpired(Instant.now(), Limit.of(BATCH_SIZE));
        if (expired.isEmpty()) {
            return;
        }

        log.warn("Found {} expired fund reservation(s) to release. Each one means a payment "
                + "saga did not complete within its reservation window.", expired.size());

        int released = 0;
        for (WalletHold hold : expired) {
            // Each hold gets its own transaction AND its own correlation ID, so the
            // release is traceable back to the payment that stranded the funds.
            try {
                CorrelationContext.runWith("sweeper-" + hold.getPaymentId(), () -> {
                    if (mutations.releaseExpired(hold.getPaymentId())) {
                        meterRegistry.counter("aegis.wallet.hold.expired.released").increment();
                    }
                });
                released++;
            } catch (RuntimeException ex) {
                // One stuck hold must not abort the rest of the batch. Logged at ERROR
                // because frozen customer funds need attention.
                log.error("Failed to release expired hold {} on wallet {} (payment {})",
                        hold.getHoldId(), hold.getWalletId(), hold.getPaymentId(), ex);
                meterRegistry.counter("aegis.wallet.hold.expired.release.failed").increment();
            }
        }

        log.info("Released {} of {} expired reservation(s)", released, expired.size());
    }
}
