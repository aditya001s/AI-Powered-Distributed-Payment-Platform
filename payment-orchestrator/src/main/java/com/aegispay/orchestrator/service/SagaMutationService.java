package com.aegispay.orchestrator.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentApprovedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentFailedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRejectedPayload;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.money.Money;
import com.aegispay.common.outbox.OutboxRecorder;
import com.aegispay.orchestrator.domain.PaymentSaga;
import com.aegispay.orchestrator.domain.ProviderAttempt;
import com.aegispay.orchestrator.domain.SagaStatus;
import com.aegispay.orchestrator.domain.SagaStep;
import com.aegispay.orchestrator.repository.PaymentSagaRepository;
import com.aegispay.orchestrator.repository.ProviderAttemptRepository;
import com.aegispay.orchestrator.repository.SagaStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every transaction that writes saga state, plus the events that accompany it.
 *
 * <h2>Why this is a separate bean from {@link SagaOrchestrator}</h2>
 *
 * <p>The orchestrator makes HTTP calls to wallet-service, ledger-service and payment providers — one
 * of which is allowed to take three seconds. A transaction spanning those would hold a database
 * connection open across the network for the duration; under load the pool empties and the service
 * stalls waiting on a downstream it cannot hurry. So the orchestrator holds no transaction, and
 * every state change is a short one behind a real bean boundary.
 *
 * <p>The boundary must be a real bean rather than a private method. Spring applies transaction
 * advice through a proxy, so {@code this.markCaptured(...)} would silently run untransacted — the
 * trap that made the auth service's refresh-token revocation a no-op in Phase 2.
 *
 * <h2>Why state changes and events share a transaction</h2>
 *
 * <p>Each terminal method records its event to the <strong>transactional outbox</strong> in the same
 * transaction. That is the only way to make "the payment completed" and "the world was told"
 * atomic without a distributed transaction across PostgreSQL and Kafka. Publishing directly would
 * either lose the event on a crash after commit — leaving payment-service permanently showing a
 * payment as in-flight — or announce a completion that then rolled back.
 *
 * <h2>Two methods are deliberately {@link Propagation#REQUIRES_NEW}</h2>
 *
 * <p>{@link #beginStep} and {@link #recordProviderAttempt} must commit independently of whatever the
 * caller goes on to do. Both are explained at their declarations; both would be silently useless if
 * they shared the caller's transaction.
 */
@Service
public class SagaMutationService {

    private static final Logger log = LoggerFactory.getLogger(SagaMutationService.class);

    private static final String SOURCE = "payment-orchestrator";
    private static final String AGGREGATE = "Payment";

    private final PaymentSagaRepository sagas;
    private final SagaStepRepository steps;
    private final ProviderAttemptRepository providerAttempts;
    private final OutboxRecorder outbox;
    private final tools.jackson.databind.ObjectMapper json;

    public SagaMutationService(PaymentSagaRepository sagas,
                               SagaStepRepository steps,
                               ProviderAttemptRepository providerAttempts,
                               OutboxRecorder outbox,
                               tools.jackson.databind.ObjectMapper json) {
        this.sagas = sagas;
        this.steps = steps;
        this.providerAttempts = providerAttempts;
        this.outbox = outbox;
        this.json = json;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Persists a new saga.
     *
     * <p>Throws {@code DataIntegrityViolationException} when a saga already exists for the payment.
     * The caller recovers by re-reading; that cannot happen here because the violation has already
     * aborted this transaction.
     */
    @Transactional
    public PaymentSaga startSaga(PaymentCreatedPayload payment, String correlationId) {
        PaymentSaga saga = sagas.saveAndFlush(
                PaymentSaga.start(payment, correlationId, serialiseSignals(payment)));
        log.info("Started saga {} for payment {} ({})",
                saga.getSagaId(), saga.getPaymentId(), saga.amount());
        return saga;
    }

    /**
     * Serialises the event's risk context for storage.
     *
     * <p>Retained because a saga resumed by the recovery sweep no longer has the event, and
     * re-deriving device or location data later would describe a different situation than the one the
     * customer transacted in. A serialisation failure is logged and swallowed rather than failing the
     * saga: losing the device features from one payment's score is a far better outcome than refusing
     * the payment outright, and the loss is visible because fraud-service marks the missing signals
     * as unknown rather than as zero.
     */
    private String serialiseSignals(PaymentCreatedPayload payment) {
        if (payment.riskSignals() == null) {
            return null;
        }
        try {
            return json.writeValueAsString(payment.riskSignals());
        } catch (RuntimeException ex) {
            log.warn("Could not retain the risk context for payment {}; its assessment will score "
                            + "without device and location features: {}",
                    payment.paymentId(), ex.getMessage());
            return null;
        }
    }

    /** The stored risk context, or unknown signals when none was retained. */
    public RiskSignals riskSignalsOf(PaymentSaga saga) {
        if (saga.getRiskSignals() == null || saga.getRiskSignals().isBlank()) {
            // Explicitly "unknown" rather than null. fraud-service treats absence as its own signal;
            // a null object would risk a consumer inventing defaults, and a default country or a
            // default device is a false claim about where a customer was.
            return RiskSignals.unknown();
        }
        try {
            return json.readValue(saga.getRiskSignals(), RiskSignals.class);
        } catch (RuntimeException ex) {
            log.warn("Could not read the retained risk context for payment {}: {}",
                    saga.getPaymentId(), ex.getMessage());
            return RiskSignals.unknown();
        }
    }

    @Transactional(readOnly = true)
    public Optional<PaymentSaga> findByPaymentId(String paymentId) {
        return sagas.findByPaymentId(paymentId);
    }

    @Transactional(readOnly = true)
    public PaymentSaga require(String paymentId) {
        return sagas.findByPaymentId(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                        "No saga exists for payment " + paymentId));
    }

    // ------------------------------------------------------------ step log

    /**
     * Opens a step record and commits it immediately.
     *
     * <p>{@link Propagation#REQUIRES_NEW} because the record has to be durable <em>before</em> the
     * side effect it describes is attempted. A row left {@code ATTEMPTED} after a crash is what
     * tells the recovery sweep "this may have happened — check", and that is the only signal
     * distinguishing an interrupted step from one that never started. Sharing the caller's
     * transaction would make the record vanish on exactly the failure it exists to describe.
     *
     * @return the step's id, for {@link #finishStep}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long beginStep(PaymentSaga saga, SagaStep.Name stepName) {
        int attempt = steps.nextAttemptNumber(saga.getSagaId(), stepName);
        SagaStep step = steps.saveAndFlush(
                SagaStep.attempt(saga.getSagaId(), saga.getPaymentId(), stepName, attempt));
        return step.getId();
    }

    /** Records that a step succeeded. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stepSucceeded(Long stepId, String detail) {
        steps.findById(stepId).ifPresent(step -> {
            step.succeeded(detail);
            steps.save(step);
        });
    }

    /**
     * Records that a step failed.
     *
     * <p>Also {@code REQUIRES_NEW}, and for the same reason as the auth service's failed-login
     * counter: the caller is about to rethrow, and if this shared its transaction the record of why
     * the payment failed would be rolled back along with it. The saga would then be left with no
     * explanation at all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stepFailed(Long stepId, String errorCode, String reason) {
        steps.findById(stepId).ifPresent(step -> {
            step.failed(errorCode, reason);
            steps.save(step);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stepCompensated(String sagaId, SagaStep.Name stepName, String detail) {
        steps.findSucceeded(sagaId, stepName).ifPresent(step -> {
            step.compensated(detail);
            steps.save(step);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSkippedStep(PaymentSaga saga, SagaStep.Name stepName, String why) {
        steps.saveAndFlush(SagaStep.skipped(saga.getSagaId(), saga.getPaymentId(), stepName, why));
    }

    @Transactional(readOnly = true)
    public List<SagaStep> stepsOf(String sagaId) {
        return steps.findBySagaIdOrderByStartedAtAsc(sagaId);
    }

    // -------------------------------------------------------- provider history

    /**
     * Records one provider call.
     *
     * <p>{@link Propagation#REQUIRES_NEW}, and this one is load-bearing. Provider attempts are the
     * <em>only</em> input to the router's reliability and latency scoring. If this shared the
     * caller's transaction, every attempt recorded on a failure path would be rolled back with it —
     * so the router would see successes and never failures, would score a dead provider as perfectly
     * reliable, and would keep sending payments into it. The failures are precisely the data that
     * must survive.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProviderAttempt(ProviderAttempt attempt) {
        providerAttempts.saveAndFlush(attempt);
    }

    @Transactional(readOnly = true)
    public List<String> providersRequiringAuthorizationReconciliation(PaymentSaga saga) {
        List<ProviderAttempt> history =
                providerAttempts.findBySagaIdOrderByCreatedAtAsc(saga.getSagaId());
        java.util.Set<String> providersWithRecordedAuthorization = history.stream()
                .filter(attempt -> attempt.getOperation() == ProviderAttempt.Operation.AUTHORIZE)
                .map(ProviderAttempt::getProviderId)
                .collect(java.util.stream.Collectors.toSet());

        java.util.LinkedHashSet<String> unresolved = new java.util.LinkedHashSet<>();
        for (ProviderAttempt attempt : history) {
            if (attempt.getOperation() != ProviderAttempt.Operation.AUTHORIZE
                    || (attempt.getOutcome() != ProviderAttempt.Outcome.TIMEOUT
                    && attempt.getOutcome() != ProviderAttempt.Outcome.APPROVED)) {
                continue;
            }
            boolean laterVoid = history.stream().anyMatch(candidate ->
                    candidate.getOperation() == ProviderAttempt.Operation.VOID
                            && candidate.getOutcome() == ProviderAttempt.Outcome.VOIDED
                            && candidate.getProviderId().equals(attempt.getProviderId())
                            && candidate.getCreatedAt().isAfter(attempt.getCreatedAt()));
            if (!laterVoid) {
                unresolved.add(attempt.getProviderId());
            }
        }

        // noteProviderTried commits before the remote call. A provider present on the
        // saga but absent from attempt history means the process/database failed while
        // recording the outcome; treat that as ambiguous rather than rerouting blindly.
        for (String tried : saga.providerAttemptList()) {
            if (!providersWithRecordedAuthorization.contains(tried)) {
                unresolved.add(tried);
            }
        }
        return List.copyOf(unresolved);
    }

    // ------------------------------------------------------ state transitions

    @Transactional
    public PaymentSaga beginStepOn(String paymentId, SagaStep.Name stepName) {
        PaymentSaga saga = lock(paymentId);
        saga.beginStep(stepName);
        return sagas.save(saga);
    }

    @Transactional
    public PaymentSaga markFundsReserved(String paymentId, String holdId) {
        PaymentSaga saga = lock(paymentId);
        saga.reservedFunds(holdId);
        return sagas.save(saga);
    }

    /**
     * Records a risk verdict that cleared the payment, and announces it.
     *
     * <p>{@code payment-approved} means "risk controls let this through", not "the payment
     * succeeded". Emitted here rather than after the provider charge because that is what it
     * describes, and payment-service shows the customer a different state for each.
     */
    @Transactional
    public PaymentSaga markRiskAssessed(String paymentId, RiskLevel riskLevel, Double probability,
                                        FraudDecision decision) {
        return markRiskAssessed(paymentId, riskLevel, probability, decision, null, false);
    }

    /**
     * Records a risk verdict that cleared the payment, with its provenance, and announces it.
     *
     * @param assessmentId the fraud assessment that produced the verdict, stored so a disputed
     *                     payment can be traced back across the two services' separate databases
     * @param degraded     whether the verdict was reached without the model. Recorded on the saga
     *                     because it describes a decision <em>this</em> service made — it continued a
     *                     payment on a verdict of reduced assurance — and that is what an audit asks
     *                     about.
     */
    @Transactional
    public PaymentSaga markRiskAssessed(String paymentId, RiskLevel riskLevel, Double probability,
                                        FraudDecision decision, String assessmentId,
                                        boolean degraded) {
        PaymentSaga saga = lock(paymentId);
        saga.recordRiskVerdict(riskLevel, probability, decision, assessmentId, degraded);
        PaymentSaga saved = sagas.save(saga);

        outbox.record(KafkaTopics.PAYMENT_APPROVED, AGGREGATE, saved.getPaymentId(),
                EventEnvelope.of(KafkaTopics.PAYMENT_APPROVED, AGGREGATE, saved.getPaymentId(),
                        SOURCE, new PaymentApprovedPayload(
                                saved.getPaymentId(),
                                saved.getTransactionId(),
                                saved.getInitiatorUserId(),
                                saved.amount(),
                                riskLevel,
                                probability == null ? 0d : probability,
                                Instant.now())));
        return saved;
    }

    /**
     * Parks the payment pending human review.
     *
     * <p><strong>No event is emitted.</strong> {@code payment-approved} would be false — risk controls
     * did the opposite of approving — and {@code payment-rejected} would be worse, because
     * payment-service would show the customer a refusal for a payment that may yet complete and
     * notification-service would tell them so. fraud-service's own {@code fraud-checked} event already
     * describes the hold, and that is the service that owns the fact.
     *
     * <p>The customer's funds stay reserved throughout, which is why the caller extends the
     * reservation before parking: the default fifteen-minute hold is sized for a payment that
     * completes in seconds.
     */
    @Transactional
    public PaymentSaga markRiskHeld(String paymentId, RiskLevel riskLevel, Double probability,
                                    FraudDecision decision, String assessmentId, boolean degraded) {
        PaymentSaga saga = lock(paymentId);
        saga.heldForReview(riskLevel, probability, decision, assessmentId, degraded);
        PaymentSaga saved = sagas.save(saga);

        log.warn("""
                        Payment {} is HELD for fraud review ({} / {}, score {}). The customer's funds \
                        stay reserved until an analyst decides.{}""",
                paymentId, riskLevel, decision,
                probability == null ? 0 : Math.round(probability * 100),
                degraded ? " Verdict reached WITHOUT the model." : "");
        return saved;
    }

    /**
     * Applies an analyst's release so the payment can continue.
     *
     * <p>Returns empty when the saga was not parked. That is a normal outcome rather than an error: a
     * redelivered decision event, or a decision on an alert that only ever asked for a review and
     * never stopped the payment. Treating it as a failure would send those to the dead-letter topic
     * and make an analyst's perfectly valid action look broken.
     *
     * <p>The machine's original verdict is not overwritten — {@code fraudDecision} still reads
     * {@code HOLD} — so a later review can see both that the engine stopped this payment and who
     * decided otherwise.
     */
    @Transactional
    public Optional<PaymentSaga> markRiskReviewReleased(String paymentId, String analystUserId,
                                                        String reason) {
        PaymentSaga saga = lock(paymentId);
        if (!saga.riskReviewReleased(analystUserId, reason)) {
            log.info("Payment {} is {} rather than held; the release from {} changes nothing",
                    paymentId, saga.getStatus(), analystUserId);
            return Optional.empty();
        }
        PaymentSaga saved = sagas.save(saga);

        log.warn("Analyst {} RELEASED held payment {} after {}: {}",
                analystUserId, paymentId,
                saved.reviewWait().map(Duration::toString).orElse("an unknown wait"), reason);
        return Optional.of(saved);
    }

    /**
     * Attributes an analyst's refusal, without settling the saga.
     *
     * <p>Only records who refused it and why. The caller still has to compensate and then mark the
     * saga rejected, in that order, so {@code compensationComplete} on the {@code payment-rejected}
     * event is a statement of fact rather than an intention.
     *
     * @return empty when the saga was not parked, which is a normal redelivery outcome
     */
    @Transactional
    public Optional<PaymentSaga> markRiskReviewRefused(String paymentId, String analystUserId,
                                                       String reason) {
        PaymentSaga saga = lock(paymentId);
        if (!saga.riskReviewRefused(analystUserId, reason)) {
            log.info("Payment {} is {} rather than held; the refusal from {} changes nothing",
                    paymentId, saga.getStatus(), analystUserId);
            return Optional.empty();
        }
        PaymentSaga saved = sagas.save(saga);

        log.warn("Analyst {} REFUSED held payment {} after {}: {}",
                analystUserId, paymentId,
                saved.reviewWait().map(Duration::toString).orElse("an unknown wait"), reason);
        return Optional.of(saved);
    }

    /** Payments parked awaiting an analyst, longest-waiting first. */
    @Transactional(readOnly = true)
    public List<PaymentSaga> heldForReview(int limit) {
        return sagas.findHeldForReview(org.springframework.data.domain.Limit.of(limit));
    }

    @Transactional
    public PaymentSaga markAuthorized(String paymentId, String providerId, String authorizationId,
                                      String providerReference, Money fee) {
        PaymentSaga saga = lock(paymentId);
        saga.recordProviderAttempt(providerId);
        saga.authorized(providerId, authorizationId, providerReference, fee);
        return sagas.save(saga);
    }

    /** Notes that a provider was tried, whatever the outcome, without changing state. */
    @Transactional
    public PaymentSaga noteProviderTried(String paymentId, String providerId) {
        PaymentSaga saga = lock(paymentId);
        saga.recordProviderAttempt(providerId);
        return sagas.save(saga);
    }

    @Transactional
    public PaymentSaga markLedgerPosted(String paymentId, String ledgerTransactionId) {
        PaymentSaga saga = lock(paymentId);
        saga.ledgerPosted(ledgerTransactionId);
        return sagas.save(saga);
    }

    @Transactional
    public PaymentSaga markCaptured(String paymentId) {
        PaymentSaga saga = lock(paymentId);
        saga.capturedFunds();
        return sagas.save(saga);
    }

    /**
     * Completes the saga and announces it.
     *
     * <p>{@code payment-completed} is what payment-service consumes to move its own record to
     * COMPLETED, what ledger-service consumes to book the entry (a replay of what the saga already
     * posted synchronously), and what notification-service will consume in Phase 8. All three depend
     * on it landing in the same commit as the state change.
     */
    @Transactional
    public PaymentSaga markCompleted(String paymentId) {
        PaymentSaga saga = lock(paymentId);
        saga.completed();
        PaymentSaga saved = sagas.save(saga);

        outbox.record(KafkaTopics.PAYMENT_COMPLETED, AGGREGATE, saved.getPaymentId(),
                EventEnvelope.of(KafkaTopics.PAYMENT_COMPLETED, AGGREGATE, saved.getPaymentId(),
                        SOURCE, completedPayload(saved)));

        log.info("Saga {} completed payment {} via {} in {}ms",
                saved.getSagaId(), saved.getPaymentId(), saved.getProviderId(),
                latencyMs(saved));
        return saved;
    }

    @Transactional
    public PaymentSaga beginCompensating(String paymentId) {
        PaymentSaga saga = lock(paymentId);
        saga.beginCompensating();
        return sagas.save(saga);
    }

    @Transactional
    public PaymentSaga clearHold(String paymentId) {
        PaymentSaga saga = lock(paymentId);
        saga.holdReleased();
        return sagas.save(saga);
    }

    @Transactional
    public PaymentSaga clearAuthorization(String paymentId) {
        PaymentSaga saga = lock(paymentId);
        saga.authorizationVoided();
        return sagas.save(saga);
    }

    @Transactional
    public PaymentSaga clearLedgerPosting(String paymentId) {
        PaymentSaga saga = lock(paymentId);
        saga.ledgerReversed();
        return sagas.save(saga);
    }

    /**
     * Marks the saga failed and announces it.
     *
     * <p>{@code compensationComplete} travels on the event rather than only reaching a log, because
     * {@code false} means money may still be reserved or authorised somewhere and a human has to
     * look. Alerting and analytics both need to see it, and a log line is visible to neither.
     */
    @Transactional
    public PaymentSaga markFailed(String paymentId, String failureCode, String reason,
                                  SagaStep.Name failedStep, boolean compensationComplete) {
        PaymentSaga saga = lock(paymentId);

        if (!saga.failed(failureCode, reason, failedStep, compensationComplete)) {
            // Already terminal. No event, deliberately: announcing a failure for a payment that
            // completed would send the customer a "payment failed" message for money that
            // actually arrived, and no downstream consumer could tell which event to believe.
            log.warn("Saga for payment {} is already {}; not recording a second outcome ({})",
                    paymentId, saga.getStatus(), failureCode);
            return sagas.save(saga);
        }
        PaymentSaga saved = sagas.save(saga);

        outbox.record(KafkaTopics.PAYMENT_FAILED, AGGREGATE, saved.getPaymentId(),
                EventEnvelope.of(KafkaTopics.PAYMENT_FAILED, AGGREGATE, saved.getPaymentId(),
                        SOURCE, new PaymentFailedPayload(
                                saved.getPaymentId(),
                                saved.getTransactionId(),
                                saved.getInitiatorUserId(),
                                saved.amount(),
                                failureCode,
                                reason,
                                failedStep == null ? null : failedStep.name(),
                                saved.providerAttemptList(),
                                saved.getAttemptCount(),
                                compensationComplete,
                                Instant.now())));

        if (!compensationComplete) {
            log.error("""
                            Payment {} FAILED at step {} and compensation did NOT complete. A wallet \
                            reservation, a provider authorisation or a ledger posting may still be \
                            outstanding. Code={}""",
                    paymentId, failedStep, failureCode);
        } else {
            log.info("Payment {} failed at step {} ({}); all side effects were undone",
                    paymentId, failedStep, failureCode);
        }
        return saved;
    }

    /** Marks the saga refused by risk controls rather than by a fault. */
    @Transactional
    public PaymentSaga markRejected(String paymentId, String reasonCode, String reason,
                                    boolean fundsReleased) {
        PaymentSaga saga = lock(paymentId);

        if (!saga.rejected(reasonCode, reason, fundsReleased)) {
            log.warn("Saga for payment {} is already {}; not recording a second outcome ({})",
                    paymentId, saga.getStatus(), reasonCode);
            return sagas.save(saga);
        }
        PaymentSaga saved = sagas.save(saga);

        outbox.record(KafkaTopics.PAYMENT_REJECTED, AGGREGATE, saved.getPaymentId(),
                EventEnvelope.of(KafkaTopics.PAYMENT_REJECTED, AGGREGATE, saved.getPaymentId(),
                        SOURCE, new PaymentRejectedPayload(
                                saved.getPaymentId(),
                                saved.getTransactionId(),
                                saved.getInitiatorUserId(),
                                saved.amount(),
                                saved.getRiskLevel(),
                                saved.getFraudDecision(),
                                reasonCode,
                                reason,
                                List.of(reason),
                                fundsReleased,
                                false,
                                Instant.now())));
        return saved;
    }

    @Transactional
    public PaymentSaga recordRecoveryAttempt(String paymentId) {
        PaymentSaga saga = lock(paymentId);
        saga.recordRecoveryAttempt();
        return sagas.save(saga);
    }

    // ------------------------------------------------------------- payloads

    /**
     * Builds the completed-payment description used for both the ledger call and the event.
     *
     * <p>One builder for both on purpose. The saga posts to the ledger synchronously before
     * capturing, and ledger-service books the same payment again when the event arrives; if the two
     * descriptions could differ, the second would be a replay of a posting that no longer matched
     * the facts.
     */
    public PaymentCompletedPayload completedPayload(PaymentSaga saga) {
        return new PaymentCompletedPayload(
                saga.getPaymentId(),
                saga.getTransactionId(),
                saga.getInitiatorUserId(),
                saga.getDestinationUserId(),
                saga.getSourceWalletId(),
                saga.getDestinationWalletId(),
                saga.amount(),
                saga.fee(),
                saga.getProviderId(),
                saga.getProviderReference(),
                saga.providerAttemptList(),
                saga.getLedgerTransactionId(),
                latencyMs(saga),
                saga.getCompletedAt() == null ? Instant.now() : saga.getCompletedAt());
    }

    private static long latencyMs(PaymentSaga saga) {
        Instant end = saga.getCompletedAt() == null ? Instant.now() : saga.getCompletedAt();
        return Duration.between(saga.getCreatedAt(), end).toMillis();
    }

    // ------------------------------------------------------------- internals

    /**
     * Loads a saga with its row locked.
     *
     * <p>Every transition is read-modify-write on the status column. Without the lock a redelivered
     * event and the recovery sweep could both read {@code AUTHORIZED}, both find the move to
     * {@code LEDGER_POSTED} legal, and both apply it — posting the double entry twice. The state
     * machine only rejects transitions illegal from the state it was <em>shown</em>, so it cannot
     * catch that on its own.
     */
    private PaymentSaga lock(String paymentId) {
        return sagas.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                        "No saga exists for payment " + paymentId));
    }

    /** Exposed for the recovery sweep, which needs the terminal check without a transition. */
    public boolean isTerminal(SagaStatus status) {
        return status.isTerminal();
    }
}
