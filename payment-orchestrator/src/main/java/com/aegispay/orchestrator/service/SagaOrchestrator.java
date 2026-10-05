package com.aegispay.orchestrator.service;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.exception.AegisException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.exception.DomainExceptions.UpstreamTransportException;
import com.aegispay.common.money.Money;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.orchestrator.client.FraudClient;
import com.aegispay.orchestrator.client.LedgerClient;
import com.aegispay.orchestrator.client.WalletClient;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.domain.PaymentSaga;
import com.aegispay.orchestrator.domain.ProviderAttempt;
import com.aegispay.orchestrator.domain.SagaStep;
import com.aegispay.orchestrator.provider.PaymentProvider;
import com.aegispay.orchestrator.provider.ProviderFaultException;
import com.aegispay.orchestrator.provider.ProviderGateway;
import com.aegispay.orchestrator.provider.ProviderRouter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Drives a payment through the saga, falling back across providers and compensating on failure.
 *
 * <h2>The sequence, and why it is in this order</h2>
 *
 * <pre>
 *   RESERVE_FUNDS  → wallet-service      make the money unspendable
 *   ASSESS_RISK    → (auto-approved)     Phase 6 replaces the verdict, not the shape
 *   AUTHORIZE      → provider            route, retry, fall back
 *   POST_LEDGER    → ledger-service      write the books BEFORE the money moves
 *   CAPTURE_FUNDS  → wallet-service      debit payer, credit payee, one transaction
 *   COMPLETE       → outbox              announce it
 * </pre>
 *
 * <p>Two orderings are load-bearing:
 *
 * <ul>
 *   <li><strong>Reserve before authorise.</strong> Asking a provider to authorise money the customer
 *       does not have wastes a provider call and produces an avoidable decline. The reservation is
 *       also what stops a second concurrent payment claiming the same funds mid-saga.</li>
 *   <li><strong>Post before capture.</strong> Money should never move that has not been recorded. A
 *       crash between the two leaves books that claim a transfer the wallets have not made —
 *       detectable by the ledger's own reconciliation and undone by a reversal. The opposite order
 *       moves real money with no record of why, which is far worse to discover.</li>
 * </ul>
 *
 * <h2>Why this class holds no transaction</h2>
 *
 * <p>Four of the six steps are network calls, and one of them is allowed three seconds. A transaction
 * spanning them would hold a database connection open across the network; under load the pool empties
 * and the service stalls waiting on a downstream it cannot hurry. Every state change therefore
 * happens in a short transaction inside {@link SagaMutationService}, behind a real bean boundary —
 * a {@code this.method()} call would bypass Spring's proxy and run untransacted.
 *
 * <p>The cost of that choice is honesty about partial failure: between steps, the saga and the
 * outside world can disagree. That is what {@link #compensate} and the wallet's hold-expiry sweeper
 * exist for, and it is why the reservation model is used at all rather than debiting immediately.
 *
 * <h2>Resumption is free</h2>
 *
 * <p>{@link #advance} is a loop over the saga's persisted status rather than a straight-line
 * sequence. A saga picked up by the recovery sweep after a crash re-enters at whatever state it
 * reached, with no separate resumption path to keep in step with the happy path — which is where
 * that kind of bug lives.
 */
@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    /**
     * Reservation lifetime requested from wallet-service.
     *
     * <p>Longer than this saga needs, so a slow provider never has the customer's funds swept out
     * from under a payment still legitimately in progress. Short enough that a crash frees them in
     * minutes rather than days.
     */
    private static final Duration HOLD_TTL = Duration.ofMinutes(15);

    private final SagaMutationService mutations;
    private final WalletClient walletClient;
    private final LedgerClient ledgerClient;
    private final FraudClient fraudClient;
    private final ProviderRouter router;
    private final ProviderGateway gateway;
    private final OrchestrationProperties properties;
    private final MeterRegistry meterRegistry;

    public SagaOrchestrator(SagaMutationService mutations,
                            WalletClient walletClient,
                            LedgerClient ledgerClient,
                            FraudClient fraudClient,
                            ProviderRouter router,
                            ProviderGateway gateway,
                            OrchestrationProperties properties,
                            MeterRegistry meterRegistry) {
        this.mutations = mutations;
        this.walletClient = walletClient;
        this.ledgerClient = ledgerClient;
        this.fraudClient = fraudClient;
        this.router = router;
        this.gateway = gateway;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    // --------------------------------------------------------------- entry

    /**
     * Orchestrates a payment, at most once.
     *
     * <p>Safe to call repeatedly with the same event. Kafka delivers at least once, so this will see
     * duplicates after any rebalance or retry; a saga already exists for the payment and the
     * duplicate is recognised rather than starting a second one that would reserve the customer's
     * funds twice.
     */
    public void orchestrate(PaymentCreatedPayload payment) {
        PaymentSaga saga = findOrStart(payment);

        if (saga.isTerminal()) {
            log.debug("Payment {} already reached {}; ignoring the redelivered event",
                    saga.getPaymentId(), saga.getStatus());
            meterRegistry.counter(MetricNames.PAYMENT_IDEMPOTENT_REPLAY,
                    "scope", "saga.orchestrate", "layer", "saga").increment();
            return;
        }
        advance(saga);
    }

    /**
     * Resumes a saga the recovery sweep found abandoned.
     *
     * <p>The same driver as the normal path, deliberately. A separate resumption routine would be a
     * second implementation of the same sequence, and the two would drift — with the divergence
     * showing up only during an incident, which is the worst possible time to discover it.
     */
    public boolean resume(PaymentSaga saga) {
        if (saga.isTerminal()) {
            return true;
        }
        if (saga.isParked()) {
            // Belt and braces: the sweep's query already excludes RISK_HELD. Guarded here too because
            // "recovering" a held payment would increment its attempt count on every pass and make a
            // payment that is behaving exactly as designed look like a chronic failure — and nothing
            // would fail loudly enough to notice.
            log.debug("Saga {} for payment {} is parked awaiting an analyst; not resuming it",
                    saga.getSagaId(), saga.getPaymentId());
            return true;
        }
        log.warn("Resuming abandoned saga {} for payment {} from {} (attempt {})",
                saga.getSagaId(), saga.getPaymentId(), saga.getStatus(), saga.getAttemptCount());
        return advance(mutations.recordRecoveryAttempt(saga.getPaymentId()));
    }

    /**
     * Finds the existing saga or starts one.
     *
     * <p>The lookup cannot win the race on its own; the unique constraint on {@code payment_id} is
     * what makes a second saga impossible. Losing that race is a normal outcome — two consumer
     * threads handling the same redelivered record — so it is recovered from by re-reading rather
     * than treated as an error.
     */
    private PaymentSaga findOrStart(PaymentCreatedPayload payment) {
        Optional<PaymentSaga> existing = mutations.findByPaymentId(payment.paymentId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return mutations.startSaga(payment, CorrelationContext.get());
        } catch (DataIntegrityViolationException ex) {
            // Another thread started it first. Re-read in a fresh transaction; the failed insert
            // has already aborted its own.
            return mutations.findByPaymentId(payment.paymentId())
                    .orElseThrow(() -> new IllegalStateException(
                            "saga for payment " + payment.paymentId()
                                    + " could neither be created nor found", ex));
        }
    }

    // ------------------------------------------------------------- the driver

    /**
     * Drives the saga to a terminal state.
     *
     * <p>A loop over the persisted status rather than a straight-line sequence, so resumption after a
     * crash needs no separate code path. Each case advances exactly one step and returns the updated
     * aggregate.
     */
    private boolean advance(PaymentSaga saga) {
        Timer.Sample sample = Timer.start(meterRegistry);
        PaymentSaga current = saga;

        try {
            // Stops on parked as well as terminal. RISK_HELD is waiting on a person, so there is
            // nothing to advance; without this the loop would spin on a state that returns itself.
            while (!current.isTerminal() && !current.isParked()) {
                current = switch (current.getStatus()) {
                    case STARTED -> reserveFunds(current);
                    case FUNDS_RESERVED -> assessRisk(current);
                    case RISK_ASSESSED -> authorize(current);
                    case AUTHORIZED -> postLedger(current);
                    case LEDGER_POSTED -> captureFunds(current);
                    case CAPTURED -> complete(current);
                    // Reached only when a previous run crashed mid-compensation. Finish it.
                    case COMPENSATING -> finishCompensation(current);
                    case RISK_HELD, COMPLETED, FAILED, REJECTED -> current;
                };
            }

            if (current.isParked()) {
                // Not an outcome, so it is not recorded as one. Timing it as a success would put
                // an analyst's review time into the payment latency histogram and make every
                // percentile meaningless; counting it as a failure would be worse still, because
                // the payment may yet complete.
                log.info("Payment {} is parked at {} awaiting a fraud analyst",
                        current.getPaymentId(), current.getStatus());
                return true;
            }

            sample.stop(meterRegistry.timer(MetricNames.PAYMENT_LATENCY,
                    MetricNames.TAG_OUTCOME, current.getStatus() == com.aegispay.orchestrator.domain
                            .SagaStatus.COMPLETED
                            ? MetricNames.OUTCOME_SUCCESS
                            : MetricNames.OUTCOME_FAILURE));
            meterRegistry.counter(MetricNames.PAYMENT_OUTCOME,
                    MetricNames.TAG_OUTCOME, current.getStatus().name().toLowerCase(),
                    MetricNames.TAG_PAYMENT_TYPE, String.valueOf(current.getPaymentType()))
                    .increment();
            return true;

        } catch (SagaFailure failure) {
            sample.stop(meterRegistry.timer(MetricNames.PAYMENT_LATENCY,
                    MetricNames.TAG_OUTCOME, failure.rejection
                            ? MetricNames.OUTCOME_REJECTED
                            : MetricNames.OUTCOME_FAILURE));
            settle(current, failure);
            return true;

        } catch (RecoveryRequired recovery) {
            // A downstream side effect succeeded but its local saga transition did not.
            // Do not compensate from stale state. The recovery sweep re-enters the same
            // step and every downstream operation uses a deterministic idempotency key.
            log.error("Payment {} requires recovery after {}: {}",
                    current.getPaymentId(), recovery.step, recovery.getMessage(), recovery);
            return false;

        } catch (RuntimeException ex) {
            // An unexpected fault, not one a step reported. Treated the same way: compensate, then
            // record. Swallowing it would leave the saga in flight forever and the customer's funds
            // reserved until the wallet's sweeper released them.
            sample.stop(meterRegistry.timer(MetricNames.PAYMENT_LATENCY,
                    MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_FAILURE));
            log.error("Unexpected failure orchestrating payment {}", current.getPaymentId(), ex);
            settle(current, new SagaFailure(current.getCurrentStep(), "INTERNAL_ERROR",
                    ex.getMessage(), false));
            return true;
        }
    }

    // ---------------------------------------------------------------- steps

    /** Makes the money unspendable without moving it. */
    private PaymentSaga reserveFunds(PaymentSaga saga) {
        mutations.beginStepOn(saga.getPaymentId(), SagaStep.Name.RESERVE_FUNDS);
        Long stepId = mutations.beginStep(saga, SagaStep.Name.RESERVE_FUNDS);

        WalletClient.HoldView hold;
        try {
            hold = walletClient.reserve(
                    saga.getPaymentId(), saga.getSourceWalletId(), saga.amount(), HOLD_TTL);

        } catch (InsufficientFundsException ex) {
            // A business decline, not a fault. No reservation was taken, so there is nothing to
            // compensate, and it must not be retried: the answer would be the same.
            mutations.stepFailed(stepId, "INSUFFICIENT_FUNDS", ex.getMessage());
            throw new SagaFailure(SagaStep.Name.RESERVE_FUNDS, "INSUFFICIENT_FUNDS",
                    ex.getMessage(), true);

        } catch (RuntimeException ex) {
            mutations.stepFailed(stepId, codeOf(ex), ex.getMessage());
            // Deliberately does NOT assume nothing was reserved. A failed reserve may be a lost
            // response with the hold created, and from here the two are indistinguishable; release
            // is idempotent, so the harmless call beats the optimistic assumption.
            throw new SagaFailure(SagaStep.Name.RESERVE_FUNDS, codeOf(ex), ex.getMessage(), false);
        }

        try {
            mutations.stepSucceeded(stepId, "hold " + hold.holdId());
            return mutations.markFundsReserved(saga.getPaymentId(), hold.holdId());
        } catch (RuntimeException persistenceFailure) {
            throw new RecoveryRequired(SagaStep.Name.RESERVE_FUNDS,
                    "wallet reservation succeeded but saga state did not persist", persistenceFailure);
        }
    }

    /**
     * Obtains a fraud verdict and acts on it.
     *
     * <p>Four outcomes, and the differences are the point:
     *
     * <ul>
     *   <li><strong>APPROVE</strong> — continue.</li>
     *   <li><strong>REVIEW</strong> — continue, but fraud-service has raised an alert. The payment
     *       settles and an analyst looks afterwards. Holding here instead would make a customer wait
     *       on a signal that was, by construction, not strong enough to stop them.</li>
     *   <li><strong>HOLD</strong> — park at {@code RISK_HELD} with the funds still reserved and wait
     *       for a person. The reservation is extended first, because the default fifteen minutes is
     *       sized for a payment that completes in seconds and the wallet's sweeper would otherwise
     *       release the funds while this saga still believed they were held.</li>
     *   <li><strong>REJECT</strong> — refuse terminally and compensate. Reachable only from a rule
     *       resting on prior confirmed fraud, never from a probability, however high.</li>
     * </ul>
     *
     * <p>The risk context is read from the saga rather than from the triggering event, so a saga
     * resumed after a crash scores the situation the customer was actually in. Re-deriving device or
     * location data here would score a fabricated context, silently.
     */
    private PaymentSaga assessRisk(PaymentSaga saga) {
        mutations.beginStepOn(saga.getPaymentId(), SagaStep.Name.ASSESS_RISK);
        Long stepId = mutations.beginStep(saga, SagaStep.Name.ASSESS_RISK);

        FraudClient.AssessmentView verdict;
        try {
            verdict = fraudClient.assess(
                    saga.getPaymentId(),
                    saga.getTransactionId(),
                    saga.getInitiatorUserId(),
                    saga.getDestinationWalletId(),
                    saga.amount(),
                    saga.getPaymentType(),
                    mutations.riskSignalsOf(saga),
                    // The payment's own creation time, so the behavioural windows are anchored to
                    // when the customer transacted rather than to whenever this step happens to run —
                    // which differs by however long the saga queued, and by far more after a recovery.
                    saga.getCreatedAt());

        } catch (RuntimeException ex) {
            return handleUnassessable(saga, stepId, ex);
        }

        try {
            mutations.stepSucceeded(stepId, "%s (%s, score %d)%s".formatted(
                    verdict.decision(), verdict.riskLevel(), verdict.riskScore(),
                    verdict.degradedMode() ? " DEGRADED: scored without the model" : ""));

            if (verdict.degradedMode()) {
                // Logged at WARN on the payment's own path, not only in fraud-service. This service chose
                // to act on a verdict of reduced assurance, and that decision belongs in this service's
                // logs too.
                log.warn("""
                                Payment {} was assessed WITHOUT the fraud model: the verdict {}/{} rests \
                                on deterministic rules alone.""",
                        saga.getPaymentId(), verdict.riskLevel(), verdict.decision());
            }

            return switch (verdict.decision()) {
                case APPROVE, REVIEW -> mutations.markRiskAssessed(saga.getPaymentId(),
                        verdict.riskLevel(), verdict.fraudProbability(), verdict.decision(),
                        verdict.assessmentId(), verdict.degradedMode());

                case HOLD -> holdForReview(saga, verdict);

                // A business refusal, not a fault. Nothing was charged, so the customer can be told
                // plainly rather than asked to retry into the same answer.
                case REJECT -> throw new SagaFailure(SagaStep.Name.ASSESS_RISK,
                        "REFUSED_BY_FRAUD_CONTROLS", verdict.summary(), true);
            };
        } catch (SagaFailure rejection) {
            throw rejection;
        } catch (RuntimeException persistenceFailure) {
            throw new RecoveryRequired(SagaStep.Name.ASSESS_RISK,
                    "fraud assessment succeeded but saga state did not persist", persistenceFailure);
        }
    }

    /**
     * Parks a payment for human review, extending the reservation first.
     *
     * <p>Order matters. The extension happens before the park, so a crash between the two leaves a
     * saga that has not yet parked — recoverable by the sweep — rather than one parked with a
     * reservation about to be swept out from under it.
     *
     * <p>A failed extension does <em>not</em> fail the payment. The hold is still valid for its
     * original window, and refusing a payment because a lifetime extension could not be recorded would
     * be a worse outcome than the risk it avoids: if the reservation does expire before an analyst
     * decides, the eventual capture fails and the saga compensates, so the failure mode is a failed
     * payment rather than money moving without funds behind it.
     */
    private PaymentSaga holdForReview(PaymentSaga saga, FraudClient.AssessmentView verdict) {
        Duration reviewTtl = properties.getFraud().getReviewHoldTtl();

        if (!walletClient.extendQuietly(saga.getPaymentId(), reviewTtl,
                "Held for fraud review: " + verdict.summary())) {
            log.error("""
                            Could not extend the reservation for held payment {}. It stays valid for \
                            its original window only, so if the review outruns that the capture will \
                            fail and the saga will compensate. An operator should look.""",
                    saga.getPaymentId());
        }

        meterRegistry.counter(MetricNames.FRAUD_DECISION,
                MetricNames.TAG_DECISION, verdict.decision().name(),
                MetricNames.TAG_RISK_LEVEL, verdict.riskLevel().name(),
                "degraded", String.valueOf(verdict.degradedMode())).increment();

        return mutations.markRiskHeld(saga.getPaymentId(), verdict.riskLevel(),
                verdict.fraudProbability(), verdict.decision(), verdict.assessmentId(),
                verdict.degradedMode());
    }

    /**
     * Decides what to do when no verdict could be obtained at all.
     *
     * <p><strong>Fails the payment by default, and this is stricter than fraud-service's own fallback
     * on purpose.</strong> When only the model is down, fraud-service still runs its deterministic
     * rules, which cover the archetypes the model scores worst on — a real, if reduced, assessment.
     * When fraud-service itself is unreachable there is no control at all, and continuing would record
     * an unassessed payment as an approved one: a false statement in the audit trail of an automated
     * decision. Failing is recoverable — nothing was charged, compensation releases the reservation,
     * the customer retries — and it makes the outage visible as failed payments rather than invisible
     * as unprotected ones.
     *
     * <p>An operator who prefers availability sets
     * {@code aegis.orchestration.fraud.allow-unassessed-payments}. The verdict is then recorded
     * explicitly as {@code degraded} with no assessment id, so the choice is visible on the payment
     * itself rather than implied by configuration.
     */
    private PaymentSaga handleUnassessable(PaymentSaga saga, Long stepId, RuntimeException ex) {
        String code = codeOf(ex);
        meterRegistry.counter(MetricNames.FRAUD_DEGRADED_DECISION, "cause", "fraud_service_down")
                .increment();

        if (!properties.getFraud().isAllowUnassessedPayments()) {
            mutations.stepFailed(stepId, code, ex.getMessage());
            log.error("""
                            Payment {} could not be assessed for fraud at all: {}. Failing it rather \
                            than letting an unassessed payment through — nothing was charged, and the \
                            reservation will be released.""",
                    saga.getPaymentId(), ex.getMessage());
            throw new SagaFailure(SagaStep.Name.ASSESS_RISK, code, ex.getMessage(), false);
        }

        // Availability was chosen over assessment. Recorded as APPROVE because the payment is
        // proceeding, but with degraded=true and no assessment id, so the row says plainly that
        // nothing assessed it.
        mutations.stepSucceeded(stepId, "UNASSESSED: fraud-service unavailable (" + code + ")");
        log.error("""
                        Payment {} is proceeding UNASSESSED: fraud-service was unreachable ({}) and \
                        allow-unassessed-payments is enabled. No fraud control was applied to this \
                        payment.""",
                saga.getPaymentId(), code);

        return mutations.markRiskAssessed(saga.getPaymentId(), RiskLevel.LOW, null,
                FraudDecision.APPROVE, null, true);
    }

    // ------------------------------------------------------- analyst decisions

    /**
     * Resumes a payment an analyst has decided on.
     *
     * <p>Driven by {@code fraud-decision-applied} rather than by fraud-service calling in
     * synchronously. The decision and the intent to resume commit together in fraud-service's outbox,
     * so a lost call cannot leave an alert resolved with the payment parked forever — which would be
     * invisible, because nothing would have failed and the payment would simply never move again.
     *
     * <p>Idempotent, and it has to be: Kafka delivers at least once. A redelivery finds the saga no
     * longer parked and does nothing, which is also the correct response to a decision on an alert that
     * only ever asked for a review and never stopped the payment.
     */
    public void applyAnalystDecision(String paymentId, boolean release, String analystUserId,
                                     String reason) {

        Optional<PaymentSaga> saga = mutations.findByPaymentId(paymentId);
        if (saga.isEmpty()) {
            // A decision on a payment this orchestrator never saw. Possible for a payment created
            // before this service existed, or one whose saga was purged. Logged rather than thrown:
            // retrying would never succeed, and dead-lettering it would bury a valid analyst action.
            log.warn("Analyst {} decided payment {}, but no saga exists for it", analystUserId,
                    paymentId);
            return;
        }
        if (!saga.get().isParked()) {
            log.info("Payment {} is {} rather than held; the analyst decision changes nothing",
                    paymentId, saga.get().getStatus());
            return;
        }

        if (release) {
            resumeAfterRelease(paymentId, analystUserId, reason);
        } else {
            refuseAfterReview(paymentId, analystUserId, reason);
        }
    }

    /**
     * Continues a released payment from where it was parked.
     *
     * <p>The reservation is extended again before the payment moves. It may have been sitting for
     * days, and the remaining window could be too short to finish a provider round trip — better to
     * ask for more time than to discover that at capture.
     */
    private void resumeAfterRelease(String paymentId, String analystUserId, String reason) {
        Optional<PaymentSaga> released =
                mutations.markRiskReviewReleased(paymentId, analystUserId, reason);
        if (released.isEmpty()) {
            return;
        }

        if (!walletClient.extendQuietly(paymentId, HOLD_TTL,
                "Released from fraud review by " + analystUserId)) {
            log.warn("""
                            Could not refresh the reservation for released payment {}; continuing on \
                            the remaining window. A capture failure will compensate.""",
                    paymentId);
        }
        advance(released.get());
    }

    /**
     * Refuses a payment an analyst rejected, releasing the reserved funds.
     *
     * <p>Goes through the ordinary settle path so compensation runs before the saga is marked
     * terminal, which is what makes {@code compensationComplete} on the {@code payment-rejected}
     * event a statement of fact.
     */
    private void refuseAfterReview(String paymentId, String analystUserId, String reason) {
        Optional<PaymentSaga> refused =
                mutations.markRiskReviewRefused(paymentId, analystUserId, reason);
        if (refused.isEmpty()) {
            return;
        }
        settle(refused.get(), new SagaFailure(SagaStep.Name.ASSESS_RISK,
                "REFUSED_BY_ANALYST", reason, true));
    }

    /**
     * Routes to a provider and obtains an authorisation, falling back as needed.
     *
     * <p>The router returns an ordered list, so fallback is simply the next candidate. Each outcome is
     * treated differently and the differences are the whole point:
     *
     * <ul>
     *   <li><strong>Approved</strong> — done.</li>
     *   <li><strong>Declined</strong> — a working provider said no. Try the next one, because rails
     *       genuinely differ in what they accept. If every provider declines, the payment is
     *       <em>rejected</em> rather than failed: nothing was charged and the customer can be told
     *       plainly.</li>
     *   <li><strong>Breaker open</strong> — not attempted at all, so move on immediately without
     *       spending the customer's latency budget learning what is already known.</li>
     *   <li><strong>Fault</strong> — the gateway has already retried where retrying was safe. If the
     *       fault might have left a charge behind, look for it and void it before falling back.</li>
     * </ul>
     */
    private PaymentSaga authorize(PaymentSaga saga) {
        mutations.beginStepOn(saga.getPaymentId(), SagaStep.Name.AUTHORIZE);

        // A prior run may have timed out after the provider committed but before
        // cleanup or local state persistence. Reconcile every persisted attempted
        // provider before routing again, even if its breaker now excludes it.
        for (String attemptedProviderId :
                mutations.providersRequiringAuthorizationReconciliation(saga)) {
            PaymentProvider attempted = router.byId(attemptedProviderId)
                    .orElseThrow(() -> new RecoveryRequired(SagaStep.Name.AUTHORIZE,
                            "previously attempted provider " + attemptedProviderId
                                    + " is unavailable for orphan reconciliation", null));
            if (!releaseOrphanedAuthorization(saga, attempted)) {
                throw new RecoveryRequired(SagaStep.Name.AUTHORIZE,
                        "previous provider authorization remains unresolved; rerouting is unsafe", null);
            }
        }

        List<ProviderRouter.RoutingCandidate> candidates =
                router.route(saga.amount(), saga.getPaymentType());

        if (candidates.isEmpty()) {
            // No rail can take this payment: every provider is ineligible, disabled, or broken.
            // Distinct from a decline — nothing was charged, so a retry later may well succeed.
            throw new SagaFailure(SagaStep.Name.AUTHORIZE, "ALL_PROVIDERS_UNAVAILABLE",
                    "No payment provider could accept " + saga.amount(), false);
        }

        boolean everyoneDeclined = true;
        String lastFailure = null;
        int position = 0;

        for (ProviderRouter.RoutingCandidate candidate : candidates) {
            position++;
            PaymentProvider provider = candidate.provider();
            Long stepId = mutations.beginStep(saga, SagaStep.Name.AUTHORIZE);
            mutations.noteProviderTried(saga.getPaymentId(), provider.id());

            PaymentProvider.AuthorizationRequest request = new PaymentProvider.AuthorizationRequest(
                    saga.getPaymentId(),
                    saga.getTransactionId(),
                    saga.amount(),
                    saga.getPaymentType(),
                    saga.getSourceWalletId(),
                    saga.getDestinationWalletId(),
                    saga.getDescription(),
                    // The saga id is the provider-facing idempotency key, so a resubmission after a
                    // lost response is collapsed rather than charged twice.
                    saga.getSagaId());

            try {
                PaymentProvider.AuthorizationResult result = gateway.authorize(provider, request);

                if (result instanceof PaymentProvider.AuthorizationResult.Approved approved) {
                    try {
                        mutations.recordProviderAttempt(ProviderAttempt.approved(
                                saga.getSagaId(), saga.getPaymentId(), provider.id(), position,
                                saga.amount(), approved.fee(), approved.authorizationId(),
                                approved.providerReference(), approved.latencyMs()));
                        mutations.stepSucceeded(stepId,
                                provider.id() + " approved: " + approved.authorizationId());

                        if (position > 1) {
                            meterRegistry.counter(MetricNames.PROVIDER_FALLBACK,
                                    "to", provider.id()).increment();
                            log.info("Payment {} authorised by {} after falling back past {} provider(s)",
                                    saga.getPaymentId(), provider.id(), position - 1);
                        }
                        return mutations.markAuthorized(saga.getPaymentId(), provider.id(),
                                approved.authorizationId(), approved.providerReference(),
                                approved.fee());
                    } catch (RuntimeException persistenceFailure) {
                        throw new RecoveryRequired(SagaStep.Name.AUTHORIZE,
                                "provider authorization succeeded but saga state did not persist",
                                persistenceFailure);
                    }
                }

                PaymentProvider.AuthorizationResult.Declined declined =
                        (PaymentProvider.AuthorizationResult.Declined) result;
                mutations.recordProviderAttempt(ProviderAttempt.declined(
                        saga.getSagaId(), saga.getPaymentId(), provider.id(), position,
                        saga.amount(), declined.declineCode(), declined.reason(),
                        declined.latencyMs()));
                mutations.stepFailed(stepId, declined.declineCode(), declined.reason());
                lastFailure = declined.declineCode() + ": " + declined.reason();

            } catch (ProviderGateway.ProviderUnavailableException ex) {
                everyoneDeclined = false;
                mutations.recordProviderAttempt(ProviderAttempt.failed(
                        saga.getSagaId(), saga.getPaymentId(), provider.id(), position,
                        ProviderAttempt.Operation.AUTHORIZE, ProviderAttempt.Outcome.CIRCUIT_OPEN,
                        saga.amount(), "CIRCUIT_OPEN", ex.getMessage(), 0L));
                mutations.stepFailed(stepId, "CIRCUIT_OPEN", ex.getMessage());
                lastFailure = ex.getMessage();

            } catch (ProviderFaultException ex) {
                everyoneDeclined = false;
                boolean timeout = "PROVIDER_TIMEOUT".equals(ex.faultCode());
                // The charge may have gone through with the acknowledgement lost. Fallback is
                // allowed only after lookup proves there is no authorization or a discovered
                // authorization is confirmed voided. Otherwise a second provider could also
                // authorize while the first unresolved hold remains live.
                bestEffortProviderAttempt(ProviderAttempt.failed(
                        saga.getSagaId(), saga.getPaymentId(), provider.id(), position,
                        ProviderAttempt.Operation.AUTHORIZE,
                        timeout ? ProviderAttempt.Outcome.TIMEOUT : ProviderAttempt.Outcome.FAILED,
                        saga.amount(), ex.faultCode(), ex.getMessage(), 0L));
                bestEffortStepFailed(stepId, ex.faultCode(), ex.getMessage());
                lastFailure = ex.faultCode() + ": " + ex.getMessage();

                if (timeout && !releaseOrphanedAuthorization(saga, provider)) {
                    throw new RecoveryRequired(SagaStep.Name.AUTHORIZE,
                            "provider authorization outcome remains unresolved; fallback is unsafe", ex);
                }

            } catch (IllegalArgumentException ex) {
                // A capability mismatch: the router selected a provider that cannot take this
                // charge. The provider did nothing wrong, so it is not recorded against its health.
                everyoneDeclined = false;
                mutations.stepFailed(stepId, "ROUTING_ERROR", ex.getMessage());
                log.error("Router selected {} for payment {}, which it cannot process: {}",
                        provider.id(), saga.getPaymentId(), ex.getMessage());
                lastFailure = ex.getMessage();
            }
        }

        if (everyoneDeclined) {
            // Every rail refused. A business outcome: nothing was charged, so the customer can be
            // told plainly rather than being asked to retry into the same answer.
            throw new SagaFailure(SagaStep.Name.AUTHORIZE, "DECLINED_BY_ALL_PROVIDERS",
                    "Every provider declined the payment. Last: " + lastFailure, true);
        }
        throw new SagaFailure(SagaStep.Name.AUTHORIZE, "ALL_PROVIDERS_UNAVAILABLE",
                "No provider could authorise the payment. Last: " + lastFailure, false);
    }

    /**
     * Looks for a charge a timed-out provider may still be holding, and releases it.
     *
     * <p>This is the case that makes naive compensation wrong. A provider that authorises and then
     * fails to answer leaves a real charge with no handle on our side — the saga has no
     * authorisation id, so there is nothing to void and nothing to reconcile against. Every real rail
     * offers a lookup by merchant reference for exactly this reason; using it is the difference
     * between compensation that works and compensation that hopes.
     */
    private boolean releaseOrphanedAuthorization(PaymentSaga saga, PaymentProvider provider) {
        final Optional<String> orphaned;
        try {
            orphaned = provider.findAuthorizationId(saga.getSagaId());
        } catch (RuntimeException ex) {
            log.error("Could not check {} for an orphaned authorisation on payment {}: {}. "
                            + "Fallback is blocked until reconciliation succeeds.",
                    provider.id(), saga.getPaymentId(), ex.getMessage());
            return false;
        }

        if (orphaned.isEmpty()) {
            return true;
        }
        log.warn("""
                        {} timed out on payment {} but is holding authorisation {}; voiding it \
                        before fallback""",
                provider.id(), saga.getPaymentId(), orphaned.get());

        if (!gateway.voidQuietly(provider, orphaned.get())) {
            log.error("""
                            Could not void orphaned authorisation {} at {} for payment {}. Fallback \
                            is blocked so a second provider cannot also authorise the payment.""",
                    orphaned.get(), provider.id(), saga.getPaymentId());
            return false;
        }

        bestEffortProviderAttempt(ProviderAttempt.voided(
                saga.getSagaId(), saga.getPaymentId(), provider.id(), orphaned.get(), 0L));
        return true;
    }

    /** Writes the books, before any money moves. */
    private PaymentSaga postLedger(PaymentSaga saga) {
        mutations.beginStepOn(saga.getPaymentId(), SagaStep.Name.POST_LEDGER);
        Long stepId = mutations.beginStep(saga, SagaStep.Name.POST_LEDGER);

        LedgerClient.JournalEntryView posting;
        try {
            posting = ledgerClient.bookPayment(mutations.completedPayload(saga));
        } catch (UpstreamTransportException ambiguousFailure) {
            bestEffortStepFailed(stepId, codeOf(ambiguousFailure), ambiguousFailure.getMessage());
            throw new RecoveryRequired(SagaStep.Name.POST_LEDGER,
                    "ledger posting outcome is uncertain; retry by deterministic posting key",
                    ambiguousFailure);
        } catch (RuntimeException ex) {
            mutations.stepFailed(stepId, codeOf(ex), ex.getMessage());
            throw new SagaFailure(SagaStep.Name.POST_LEDGER, codeOf(ex), ex.getMessage(), false);
        }

        try {
            mutations.stepSucceeded(stepId, "posting #" + posting.journalSequence()
                    + (posting.duplicate() ? " (replay)" : ""));
            return mutations.markLedgerPosted(saga.getPaymentId(),
                    posting.ledgerTransactionId());
        } catch (RuntimeException persistenceFailure) {
            throw new RecoveryRequired(SagaStep.Name.POST_LEDGER,
                    "ledger posting succeeded but saga state did not persist", persistenceFailure);
        }
    }

    /** Moves the money: debit the payer, credit the payee, one transaction inside wallet-service. */
    private PaymentSaga captureFunds(PaymentSaga saga) {
        mutations.beginStepOn(saga.getPaymentId(), SagaStep.Name.CAPTURE_FUNDS);
        Long stepId = mutations.beginStep(saga, SagaStep.Name.CAPTURE_FUNDS);

        WalletClient.CaptureView capture;
        try {
            capture = walletClient.capture(saga.getPaymentId(), saga.getSourceWalletId(),
                    saga.getDestinationWalletId(), saga.getDescription());
        } catch (UpstreamTransportException ambiguousFailure) {
            bestEffortStepFailed(stepId, codeOf(ambiguousFailure), ambiguousFailure.getMessage());
            throw new RecoveryRequired(SagaStep.Name.CAPTURE_FUNDS,
                    "wallet capture outcome is uncertain; retry by payment id instead of compensating",
                    ambiguousFailure);
        } catch (RuntimeException ex) {
            mutations.stepFailed(stepId, codeOf(ex), ex.getMessage());
            throw new SagaFailure(SagaStep.Name.CAPTURE_FUNDS, codeOf(ex), ex.getMessage(), false);
        }

        try {
            mutations.stepSucceeded(stepId, "captured " + saga.amount()
                    + " as " + capture.debitTransactionId() + "/" + capture.creditTransactionId());
            return mutations.markCaptured(saga.getPaymentId());
        } catch (RuntimeException persistenceFailure) {
            throw new RecoveryRequired(SagaStep.Name.CAPTURE_FUNDS,
                    "wallet capture succeeded but saga state did not persist", persistenceFailure);
        }
    }

    /**
     * Announces the completed payment.
     *
     * <p>Past the capture, compensation is no longer available: the money has moved, and releasing a
     * reservation that was already captured would hand back funds legitimately taken. So a failure
     * here is <em>not</em> compensated — it is retried, by the recovery sweep if necessary, until the
     * outcome is recorded. Failing forward is the only correct direction once real money has moved.
     */
    private PaymentSaga complete(PaymentSaga saga) {
        Long stepId = mutations.beginStep(saga, SagaStep.Name.COMPLETE);
        try {
            PaymentSaga completed = mutations.markCompleted(saga.getPaymentId());
            mutations.stepSucceeded(stepId, "completed");
            return completed;
        } catch (RuntimeException ex) {
            bestEffortStepFailed(stepId, codeOf(ex), ex.getMessage());
            // Leave CAPTURED for the recovery sweep. Compensation is forbidden once
            // money moved, and marking the saga failed would publish a false outcome.
            throw new RecoveryRequired(SagaStep.Name.COMPLETE,
                    "funds are captured but completion state did not persist", ex);
        }
    }

    // -------------------------------------------------------- compensation

    /**
     * Records the outcome, compensating first when there is anything to undo.
     *
     * <p>The order matters. Compensation runs before the saga is marked terminal, so
     * {@code compensationComplete} on the {@code payment-failed} event is a statement of fact rather
     * than an intention.
     */
    private void settle(PaymentSaga saga, SagaFailure failure) {
        PaymentSaga current = mutations.findByPaymentId(saga.getPaymentId()).orElse(saga);

        if (current.fundsCaptured()) {
            // Should be unreachable: the state machine forbids CAPTURED -> COMPENSATING. Logged at
            // ERROR because reaching it would mean money moved and something still tried to undo it.
            log.error("""
                            Payment {} failed after capture, so compensation is NOT available. The \
                            money has moved; the outcome must be recorded, not reversed.""",
                    current.getPaymentId());
            mutations.markFailed(current.getPaymentId(), failure.code, failure.reason,
                    failure.step, false);
            return;
        }

        boolean compensated = compensate(current, failure);

        if (failure.rejection) {
            mutations.markRejected(current.getPaymentId(), failure.code, failure.reason,
                    compensated);
        } else {
            mutations.markFailed(current.getPaymentId(), failure.code, failure.reason,
                    failure.step, compensated);
        }
    }

    /**
     * Undoes every side effect, in reverse order of creation.
     *
     * <p><strong>Driven by the handles, not by the status.</strong> A crash between "provider
     * approved" and "status updated" leaves a real authorisation outstanding while the status still
     * says {@code RISK_ASSESSED}; compensating on the status would miss it. So each step asks "is
     * there a handle for this" instead of "did we get that far".
     *
     * <p>Never throws. The caller is already handling a failure, and a second one here would mask the
     * original cause and leave the saga in flight forever.
     *
     * @return whether every outstanding effect was confirmed undone
     */
    private boolean compensate(PaymentSaga saga, SagaFailure failure) {
        // A technical reserve failure is ambiguous: the wallet may have created the hold and only
        // lost its response. In that one case the absence of a persisted handle does not prove that
        // nothing happened, so the idempotent release below must still run. A definite business
        // rejection (for example insufficient funds) remains eligible for this fast path.
        boolean uncertainReserve = failure.step == SagaStep.Name.RESERVE_FUNDS
                && !failure.rejection;
        if (!uncertainReserve
                && !saga.getStatus().hasExternalEffects()
                && !saga.hasOutstandingHold()
                && !saga.hasOutstandingAuthorization()
                && !saga.hasLedgerPosting()) {
            // Nothing happened outside this service, so there is nothing to undo.
            return true;
        }

        PaymentSaga current = mutations.beginCompensating(saga.getPaymentId());
        boolean allClean = true;

        // 1. The ledger first, because it was written last. Leaving it while releasing the
        //    reservation would leave books claiming a transfer that can no longer happen.
        if (current.hasLedgerPosting()) {
            if (ledgerClient.reverseQuietly(current.getLedgerTransactionId(),
                    "Payment failed at " + failure.step + ": " + failure.code)) {
                mutations.stepCompensated(current.getSagaId(), SagaStep.Name.POST_LEDGER,
                        "reversed");
                current = mutations.clearLedgerPosting(current.getPaymentId());
            } else {
                allClean = false;
            }
        }

        // 2. The provider authorisation, so the charge is not left sitting on the rail.
        if (current.hasOutstandingAuthorization()) {
            Optional<PaymentProvider> provider = router.byId(current.getProviderId());
            if (provider.isEmpty()) {
                log.error("Cannot void authorisation {} for payment {}: provider {} is not registered",
                        current.getAuthorizationId(), current.getPaymentId(),
                        current.getProviderId());
                allClean = false;
            } else if (gateway.voidQuietly(provider.get(), current.getAuthorizationId())) {
                mutations.recordProviderAttempt(ProviderAttempt.voided(
                        current.getSagaId(), current.getPaymentId(), current.getProviderId(),
                        current.getAuthorizationId(), 0L));
                mutations.stepCompensated(current.getSagaId(), SagaStep.Name.AUTHORIZE, "voided");
                current = mutations.clearAuthorization(current.getPaymentId());
            } else {
                allClean = false;
            }
        }

        // 3. The wallet reservation last, so the customer's funds become spendable only once
        //    nothing else could still need them.
        //
        //    Attempted even when no hold id was recorded. A failed reserve may be a lost response
        //    with the hold created, and from here the two are indistinguishable; release is
        //    idempotent and tolerates a hold that never existed, so the harmless call is strictly
        //    better than the optimistic assumption.
        boolean releaseNeeded = current.hasOutstandingHold()
                || failure.step == SagaStep.Name.RESERVE_FUNDS;

        if (releaseNeeded) {
            if (walletClient.releaseQuietly(current.getPaymentId(),
                    "Payment failed at step " + failure.step)) {
                mutations.stepCompensated(current.getSagaId(), SagaStep.Name.RESERVE_FUNDS,
                        "released");
                mutations.clearHold(current.getPaymentId());
            } else {
                allClean = false;
            }
        }

        meterRegistry.counter(MetricNames.SAGA_COMPENSATION,
                MetricNames.TAG_STEP, String.valueOf(failure.step),
                "result", allClean ? "complete" : "incomplete").increment();
        if (!allClean) {
            meterRegistry.counter(MetricNames.SAGA_COMPENSATION_FAILED).increment();
        }
        return allClean;
    }

    /**
     * Finishes compensation a previous run was interrupted during.
     *
     * <p>Reached only when a saga is found in {@code COMPENSATING}, which means the process died
     * partway through undoing its side effects. The handles say what is still outstanding, so the
     * work simply resumes.
     */
    private PaymentSaga finishCompensation(PaymentSaga saga) {
        log.warn("Saga {} was interrupted while compensating; finishing it", saga.getSagaId());

        SagaStep.Name failedStep = saga.getFailedStep() == null
                ? saga.getCurrentStep()
                : saga.getFailedStep();

        boolean compensated = compensate(saga, new SagaFailure(failedStep,
                saga.getFailureCode() == null ? "RECOVERED_AFTER_CRASH" : saga.getFailureCode(),
                saga.getFailureReason() == null
                        ? "compensation was interrupted and has been completed by the recovery sweep"
                        : saga.getFailureReason(),
                false));

        return mutations.markFailed(saga.getPaymentId(),
                saga.getFailureCode() == null ? "RECOVERED_AFTER_CRASH" : saga.getFailureCode(),
                saga.getFailureReason() == null
                        ? "saga was abandoned mid-compensation and recovered"
                        : saga.getFailureReason(),
                failedStep, compensated);
    }

    // ------------------------------------------------------------- internals

    private void bestEffortStepFailed(Long stepId, String code, String message) {
        try {
            mutations.stepFailed(stepId, code, message);
        } catch (RuntimeException diagnosticFailure) {
            log.error("Could not persist failed-step diagnostics for step {}: {}. "
                            + "Preserving the original recovery classification.",
                    stepId, diagnosticFailure.getMessage(), diagnosticFailure);
        }
    }

    private void bestEffortProviderAttempt(ProviderAttempt attempt) {
        try {
            mutations.recordProviderAttempt(attempt);
        } catch (RuntimeException diagnosticFailure) {
            log.error("Could not persist provider-attempt diagnostics for payment {}: {}. "
                            + "Preserving the original recovery classification.",
                    attempt.getPaymentId(), diagnosticFailure.getMessage(), diagnosticFailure);
        }
    }

    private static String codeOf(RuntimeException ex) {
        if (ex instanceof AegisException aegis) {
            return aegis.errorCode().name();
        }
        if (ex instanceof ProviderFaultException fault) {
            return fault.faultCode();
        }
        return "INTERNAL_ERROR";
    }

    /**
     * A remote side effect may have committed but the corresponding local saga
     * transition did not. Compensation from stale state is unsafe; leave the saga
     * nonterminal so the recovery sweep retries the idempotent step.
     */
    private static final class RecoveryRequired extends RuntimeException {
        private final SagaStep.Name step;

        private RecoveryRequired(SagaStep.Name step, String message, Throwable cause) {
            super(message, cause);
            this.step = step;
        }
    }

    /**
     * A step reported a failure and named its own outcome.
     *
     * <p>Carries {@code rejection} so the orchestrator can tell a business refusal from a technical
     * fault without inspecting exception types. The distinction reaches the customer: a rejection
     * means "this will not work", a failure means "try again".
     */
    private static final class SagaFailure extends RuntimeException {

        private final SagaStep.Name step;
        private final String code;
        private final String reason;
        private final boolean rejection;

        private SagaFailure(SagaStep.Name step, String code, String reason, boolean rejection) {
            // No stack trace: these are expected control flow, and capturing a trace for every
            // declined payment is measurable overhead for information nobody reads.
            super(reason, null, false, false);
            this.step = step;
            this.code = code;
            this.reason = reason;
            this.rejection = rejection;
        }
    }
}
