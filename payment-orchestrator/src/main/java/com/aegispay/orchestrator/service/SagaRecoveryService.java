package com.aegispay.orchestrator.service;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.domain.PaymentSaga;
import com.aegispay.orchestrator.repository.PaymentSagaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Picks up sagas whose orchestrating thread disappeared.
 *
 * <p><strong>Why this has to exist.</strong> A saga is driven by a thread holding no transaction
 * across four network calls. If that thread dies — a crash, a pod eviction, a redeploy mid-payment —
 * the saga is left in whatever state it reached, with a wallet reservation and possibly a provider
 * authorisation outstanding and nothing scheduled to resolve them. The customer's money stays
 * unspendable and the payment neither completes nor fails.
 *
 * <p>Without a sweep, the only remedies are the wallet's own hold-expiry (which frees the funds but
 * leaves the payment permanently in flight and the provider authorisation dangling) and manual
 * intervention. This is the difference between a saga that recovers and a saga that merely logs.
 *
 * <p>Every increment of the metric here means an orchestration was interrupted, so a non-zero rate is
 * worth alerting on rather than routine background noise.
 *
 * <h2>Staleness is measured on the last state change, not on age</h2>
 *
 * <p>A saga waiting on a slow provider is still making progress and must not be disturbed. One whose
 * last write was minutes ago has almost certainly lost its thread. Using {@code createdAt} instead
 * would compensate payments that are simply taking a while — which would release the reservation out
 * from under a charge that is about to be authorised.
 *
 * <h2>Safe to run on every replica</h2>
 *
 * <p>{@link SagaOrchestrator#resume} re-reads the saga under a row lock and drives it from its
 * persisted state, so two instances sweeping simultaneously cannot both advance the same saga. The
 * loser blocks, re-reads, and finds nothing left to do.
 */
@Service
public class SagaRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryService.class);

    /** Increments whenever an abandoned saga is picked up. Should stay at zero. */
    private static final String METRIC_RECOVERED = "aegis.saga.recovered";
    private static final String METRIC_RECOVERY_FAILED = "aegis.saga.recovery.failed";
    private static final String METRIC_RECOVERY_DEFERRED = "aegis.saga.recovery.deferred";
    private static final String METRIC_UNCOMPENSATED = "aegis.saga.uncompensated";

    private final PaymentSagaRepository sagas;
    private final SagaOrchestrator orchestrator;
    private final OrchestrationProperties properties;
    private final MeterRegistry meterRegistry;

    public SagaRecoveryService(PaymentSagaRepository sagas,
                              SagaOrchestrator orchestrator,
                              OrchestrationProperties properties,
                              MeterRegistry meterRegistry) {
        this.sagas = sagas;
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        meterRegistry.gauge(METRIC_UNCOMPENSATED, this,
                service -> service.sagas.countByCompensationComplete(false));
    }

    /**
     * Sweeps abandoned sagas.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a slow sweep cannot stack up
     * overlapping rounds all contending for the same saga rows.
     */
    @Scheduled(fixedDelayString = "${aegis.orchestration.recovery.sweep-interval:60s}",
            initialDelayString = "${aegis.orchestration.recovery.sweep-initial-delay:45s}")
    public void recoverStalledSagas() {
        if (!properties.getRecovery().isEnabled()) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException ex) {
            // A failing sweep must not take the service down, but it must be loud: a silent
            // recovery process is indistinguishable from one with nothing to do.
            log.error("Saga recovery sweep could not complete", ex);
        }
    }

    /**
     * Runs one sweep and reports how many sagas were picked up.
     *
     * <p>Public so tests and an operator endpoint can drive it directly rather than waiting for the
     * schedule.
     */
    public int sweep() {
        Instant staleBefore = Instant.now().minus(properties.getRecovery().getStalledAfter());
        List<PaymentSaga> stalled = findStalled(staleBefore);

        if (stalled.isEmpty()) {
            return 0;
        }

        log.warn("""
                        Found {} saga(s) with no state change since {}. Each one means an \
                        orchestration was interrupted, leaving a reservation or an authorisation \
                        outstanding.""",
                stalled.size(), staleBefore);

        int recovered = 0;
        for (PaymentSaga saga : stalled) {
            // Each saga gets its own correlation ID derived from the payment, so the recovery is
            // traceable back to the payment it rescued rather than appearing as orphaned log lines.
            String correlationId = "recovery-" + saga.getPaymentId();
            String previous = CorrelationContext.get();
            try {
                CorrelationContext.set(correlationId);
                boolean completedThisAttempt = orchestrator.resume(saga);
                if (completedThisAttempt) {
                    meterRegistry.counter(METRIC_RECOVERED,
                            "fromStatus", saga.getStatus().name()).increment();
                    recovered++;
                } else {
                    meterRegistry.counter(METRIC_RECOVERY_DEFERRED,
                            "fromStatus", saga.getStatus().name()).increment();
                    log.warn("Recovery of saga {} was deferred because a downstream side effect "
                                    + "could not yet be reconciled safely", saga.getSagaId());
                }

            } catch (RuntimeException ex) {
                // One stuck saga must not abort the rest of the batch. Logged at ERROR because a
                // saga that cannot even be recovered needs a human.
                log.error("Could not recover saga {} for payment {} (status {})",
                        saga.getSagaId(), saga.getPaymentId(), saga.getStatus(), ex);
                meterRegistry.counter(METRIC_RECOVERY_FAILED,
                        "fromStatus", saga.getStatus().name()).increment();
            } finally {
                CorrelationContext.set(previous);
                if (previous == null) {
                    CorrelationContext.clear();
                }
            }
        }

        log.info("Recovered {} of {} stalled saga(s)", recovered, stalled.size());
        return recovered;
    }

    /**
     * Reads the batch in its own read-only transaction, so the sweep does not hold one open while
     * driving sagas across the network.
     */
    @Transactional(readOnly = true)
    public List<PaymentSaga> findStalled(Instant staleBefore) {
        return sagas.findStalled(staleBefore, Limit.of(properties.getRecovery().getBatchSize()));
    }

    /**
     * Sagas that failed with a side effect possibly still outstanding.
     *
     * <p>Expected to be empty. Every entry is money that may be reserved or authorised with nothing
     * scheduled to release it, so this is what an operator alert points at. Deliberately not
     * auto-retried: by this point the automated paths have already failed, and repeating them would
     * bury the incident rather than resolve it.
     */
    @Transactional(readOnly = true)
    public List<PaymentSaga> findUncompensated() {
        return sagas.findUncompensated(Limit.of(properties.getRecovery().getBatchSize()));
    }
}
