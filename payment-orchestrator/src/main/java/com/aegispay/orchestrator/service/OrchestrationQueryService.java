package com.aegispay.orchestrator.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.money.Money;
import com.aegispay.orchestrator.api.OrchestratorDtos.OrchestrationStatsView;
import com.aegispay.orchestrator.api.OrchestratorDtos.ProviderHealthView;
import com.aegispay.orchestrator.api.OrchestratorDtos.ProviderThroughputView;
import com.aegispay.orchestrator.api.OrchestratorDtos.RoutingCandidateView;
import com.aegispay.orchestrator.api.OrchestratorDtos.RoutingExplanationView;
import com.aegispay.orchestrator.api.OrchestratorDtos.SagaView;
import com.aegispay.orchestrator.domain.PaymentSaga;
import com.aegispay.orchestrator.domain.SagaStatus;
import com.aegispay.orchestrator.provider.ProviderHealthMonitor;
import com.aegispay.orchestrator.provider.ProviderRouter;
import com.aegispay.orchestrator.repository.PaymentSagaRepository;
import com.aegispay.orchestrator.repository.ProviderAttemptRepository;
import com.aegispay.orchestrator.repository.SagaStepRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Read-only views over saga and provider state.
 *
 * <p>Separate from {@link SagaMutationService} so the write path stays small and every method here can
 * be {@code readOnly = true}, which lets the driver and Hibernate skip dirty-checking and flushing on
 * paths that never write.
 *
 * <p>There is deliberately no method that changes a saga. An operator inspecting a stuck payment must
 * not be able to nudge it from the same surface — a saga is advanced by the orchestrator or by the
 * recovery sweep, both of which drive it through the state machine.
 */
@Service
public class OrchestrationQueryService {

    private final PaymentSagaRepository sagas;
    private final SagaStepRepository steps;
    private final ProviderAttemptRepository providerAttempts;
    private final ProviderRouter router;
    private final ProviderHealthMonitor healthMonitor;

    public OrchestrationQueryService(PaymentSagaRepository sagas,
                                     SagaStepRepository steps,
                                     ProviderAttemptRepository providerAttempts,
                                     ProviderRouter router,
                                     ProviderHealthMonitor healthMonitor) {
        this.sagas = sagas;
        this.steps = steps;
        this.providerAttempts = providerAttempts;
        this.router = router;
        this.healthMonitor = healthMonitor;
    }

    // ----------------------------------------------------------------- sagas

    /**
     * A saga in full, with its step log and every provider call.
     *
     * <p>Assembled in one place because an incident needs all three together: the state says where the
     * payment stopped, the steps say what was attempted, and the provider calls say why.
     */
    @Transactional(readOnly = true)
    public SagaView byPaymentId(String paymentId) {
        PaymentSaga saga = sagas.findByPaymentId(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                        "No saga exists for payment " + paymentId));
        return assemble(saga);
    }

    @Transactional(readOnly = true)
    public SagaView bySagaId(String sagaId) {
        PaymentSaga saga = sagas.findBySagaId(sagaId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No saga exists with id " + sagaId));
        return assemble(saga);
    }

    private SagaView assemble(PaymentSaga saga) {
        return SagaView.from(saga,
                steps.findBySagaIdOrderByStartedAtAsc(saga.getSagaId()),
                providerAttempts.findBySagaIdOrderByCreatedAtAsc(saga.getSagaId()));
    }

    @Transactional(readOnly = true)
    public Page<PaymentSaga> list(SagaStatus status, String providerId, Pageable pageable) {
        if (status != null) {
            return sagas.findByStatusOrderByCreatedAtDesc(status, pageable);
        }
        if (providerId != null && !providerId.isBlank()) {
            return sagas.findByProviderIdOrderByCreatedAtDesc(providerId.toUpperCase(), pageable);
        }
        return sagas.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * Sagas that failed with a side effect possibly still outstanding.
     *
     * <p>Expected to be empty. Every entry is money that may be reserved or authorised with nothing
     * scheduled to release it, which is why it has its own endpoint rather than being buried in a
     * filtered list.
     */
    @Transactional(readOnly = true)
    public List<PaymentSaga> uncompensated() {
        return sagas.findUncompensated(org.springframework.data.domain.Limit.of(200));
    }

    /**
     * Payments parked awaiting a fraud analyst, longest-waiting first.
     *
     * <p>Its own view rather than a status filter, because these are the one non-terminal state the
     * recovery sweep ignores. That exclusion is correct — a held payment is waiting on a person, not
     * abandoned — but it also means nothing else reports them: a broken decision consumer produces no
     * errors and no failures, just a growing list of customers whose funds are reserved. This is where
     * that shows up.
     */
    @Transactional(readOnly = true)
    public List<PaymentSaga> heldForReview() {
        return sagas.findHeldForReview(org.springframework.data.domain.Limit.of(200));
    }

    // ------------------------------------------------------------- providers

    @Transactional(readOnly = true)
    public List<ProviderHealthView> providerHealth() {
        return healthMonitor.currentHealth().stream()
                .map(health -> ProviderHealthView.from(health,
                        router.byId(health.providerId()).orElse(null)))
                .toList();
    }

    /**
     * How the router would rank the providers for a given charge.
     *
     * <p>Exists because "why did this payment go to the expensive provider" is otherwise answerable
     * only by reading the scoring code and guessing at the inputs.
     */
    @Transactional(readOnly = true)
    public RoutingExplanationView explainRouting(Money amount, PaymentType paymentType) {
        List<RoutingCandidateView> candidates = router.explain(amount, paymentType).stream()
                .map(RoutingCandidateView::from)
                .toList();

        return new RoutingExplanationView(
                com.aegispay.orchestrator.api.OrchestratorDtos.MoneyView.of(amount),
                paymentType,
                candidates,
                Instant.now());
    }

    // ----------------------------------------------------------------- stats

    @Transactional(readOnly = true)
    public OrchestrationStatsView stats() {
        long inFlight = java.util.Arrays.stream(SagaStatus.values())
                .filter(status -> !status.isTerminal())
                .mapToLong(sagas::countByStatus)
                .sum();

        List<ProviderThroughputView> throughput = sagas.countCompletedByProvider().stream()
                .map(row -> new ProviderThroughputView(
                        (String) row[0], ((Number) row[1]).longValue()))
                .sorted(java.util.Comparator.comparing(ProviderThroughputView::providerId))
                .toList();

        return new OrchestrationStatsView(
                inFlight,
                sagas.countByStatus(SagaStatus.COMPLETED),
                sagas.countByStatus(SagaStatus.FAILED),
                sagas.countByStatus(SagaStatus.REJECTED),
                sagas.countByStatus(SagaStatus.RISK_HELD),
                // An hour is well beyond a worked queue and well within a plausible review, so a
                // non-zero count means the queue is not being worked rather than that one payment is
                // taking a while.
                sagas.countByStatusAndRiskHeldAtBefore(SagaStatus.RISK_HELD,
                        Instant.now().minus(java.time.Duration.ofHours(1))),
                sagas.countByCompensationComplete(false),
                sagas.countWithFallback(),
                throughput,
                Instant.now());
    }
}
