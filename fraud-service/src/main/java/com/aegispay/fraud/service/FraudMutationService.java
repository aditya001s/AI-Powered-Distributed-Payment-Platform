package com.aegispay.fraud.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.AnalystDecisionPayload;
import com.aegispay.common.events.payload.FraudCheckedPayload;
import com.aegispay.common.events.payload.InvestigationPayloads.InvestigationRequestedPayload;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.money.Money;
import com.aegispay.common.outbox.OutboxRecorder;
import com.aegispay.fraud.domain.AnalystDecision;
import com.aegispay.fraud.domain.FraudAlert;
import com.aegispay.fraud.domain.FraudAssessment;
import com.aegispay.fraud.domain.PaymentObservation;
import com.aegispay.fraud.repository.AnalystDecisionRepository;
import com.aegispay.fraud.repository.FraudAlertRepository;
import com.aegispay.fraud.repository.FraudAssessmentRepository;
import com.aegispay.fraud.repository.PaymentObservationRepository;
import com.aegispay.fraud.rules.FraudContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every transaction that writes fraud state, plus the events that accompany it.
 *
 * <h2>Why this is a separate bean from {@link FraudScoringService}</h2>
 *
 * <p>Scoring makes an HTTP call to ml-service. A transaction spanning it would hold a database
 * connection open across the network, and under load the pool empties and the whole service stalls
 * waiting on a dependency it cannot hurry. So the orchestration is non-transactional and every write
 * lives here, behind a real bean boundary — a {@code this.method()} call would bypass Spring's proxy
 * and run untransacted without any symptom.
 *
 * <h2>Why the state change and its event share a transaction</h2>
 *
 * <p>The verdict and the {@code fraud-checked} announcement land in one commit through the outbox.
 * Publishing directly to Kafka would either lose the event on a crash after commit — leaving analytics
 * and the investigation queue permanently unaware of a held payment — or announce a verdict that then
 * rolled back.
 */
@Service
public class FraudMutationService {

    private static final Logger log = LoggerFactory.getLogger(FraudMutationService.class);

    private static final String SOURCE = "fraud-service";
    private static final String AGGREGATE = "Payment";

    private final FraudAssessmentRepository assessments;
    private final FraudAlertRepository alerts;
    private final AnalystDecisionRepository decisions;
    private final PaymentObservationRepository observations;
    private final OutboxRecorder outbox;
    private final tools.jackson.databind.ObjectMapper json;

    public FraudMutationService(FraudAssessmentRepository assessments,
                                FraudAlertRepository alerts,
                                AnalystDecisionRepository decisions,
                                PaymentObservationRepository observations,
                                OutboxRecorder outbox,
                                tools.jackson.databind.ObjectMapper json) {
        this.assessments = assessments;
        this.alerts = alerts;
        this.decisions = decisions;
        this.observations = observations;
        this.outbox = outbox;
        this.json = json;
    }

    // -------------------------------------------------------------- assessment

    /**
     * Persists a verdict, raises an alert if a human is needed, and announces it.
     *
     * <p>All three in one transaction, deliberately. A verdict recorded without its alert would leave
     * a customer's payment held with nothing in any queue to release it — which is the worst outcome
     * this service can produce, because it is invisible: nothing failed, and the payment simply never
     * moves again.
     */
    @Transactional
    public FraudAssessment recordAssessment(FraudContext context, RiskVerdict verdict) {
        String paymentId = context.paymentId();
        String initiatorUserId = context.initiatorUserId();
        Money amount = context.amount();

        FraudAssessment assessment = assessments.save(FraudAssessment.record(
                paymentId,
                context.transactionId(),
                initiatorUserId,
                amount,
                verdict.fraudProbability(),
                verdict.riskLevel(),
                verdict.decision(),
                verdict.reasons(),
                verdict.ruleHits(),
                verdict.modelVersion(),
                verdict.featureSnapshot(),
                verdict.scoringLatencyMs(),
                verdict.degraded()));

        if (verdict.needsAnalyst()) {
            raiseAlert(assessment, verdict);
        }

        outbox.record(KafkaTopics.FRAUD_CHECKED, AGGREGATE, paymentId,
                EventEnvelope.of(KafkaTopics.FRAUD_CHECKED, AGGREGATE, paymentId, SOURCE,
                        toPayload(assessment, verdict)));

        // A HIGH or CRITICAL payment gets an AI investigation. The request is emitted here because
        // the risk decision is what triggers it; nothing consumes it until Phase 7, and a topic with
        // no consumer is a queue rather than a loss.
        if (verdict.riskLevel().requiresInvestigation()) {
            outbox.record(KafkaTopics.INVESTIGATION_REQUESTED, AGGREGATE, paymentId,
                    EventEnvelope.of(KafkaTopics.INVESTIGATION_REQUESTED, AGGREGATE, paymentId,
                            SOURCE, new InvestigationRequestedPayload(
                                    paymentId,
                                    context.transactionId(),
                                    initiatorUserId,
                                    amount,
                                    verdict.riskLevel(),
                                    verdict.fraudProbability(),
                                    verdict.reasons(),
                                    verdict.ruleHits(),
                                    snapshotAsMap(verdict),
                                    // The context the customer actually transacted in, carried so the
                                    // investigation can ground its narrative in evidence rather than
                                    // re-deriving device and location data that would by then differ.
                                    context.signals(),
                                    verdict.modelVersion(),
                                    Instant.now())));
        }

        if (verdict.degraded()) {
            log.error("""
                            Payment {} was assessed WITHOUT the model: ml-service was unreachable, so \
                            only the deterministic rules ran. Verdict {}/{} rests on rules alone.""",
                    paymentId, verdict.riskLevel(), verdict.decision());
        } else {
            log.info("Payment {} scored {} ({}), decision {}{}",
                    paymentId, assessment.getRiskScore(), verdict.riskLevel(), verdict.decision(),
                    verdict.ruleHits().isEmpty() ? "" : " with rules " + verdict.ruleHits());
        }
        return assessment;
    }

    private void raiseAlert(FraudAssessment assessment, RiskVerdict verdict) {
        // Guarded because the unique constraint on payment_id would otherwise abort the whole
        // transaction, losing the assessment as well as the alert. Reaching here twice means a
        // redelivery raced the idempotency check, which is expected rather than exceptional.
        if (alerts.existsByPaymentId(assessment.getPaymentId())) {
            log.debug("An alert already exists for payment {}; not raising a second",
                    assessment.getPaymentId());
            return;
        }
        FraudAlert alert = alerts.save(FraudAlert.raise(assessment, verdict.summary()));

        if (alert.isBlocksPayment()) {
            log.warn("Payment {} is HELD pending review: alert {} (score {}). The customer's funds "
                            + "stay reserved until an analyst decides.",
                    assessment.getPaymentId(), alert.getAlertId(), assessment.getRiskScore());
        }
    }

    private FraudCheckedPayload toPayload(FraudAssessment assessment, RiskVerdict verdict) {
        return new FraudCheckedPayload(
                assessment.getPaymentId(),
                assessment.getTransactionId(),
                verdict.fraudProbability(),
                verdict.riskLevel(),
                verdict.decision(),
                verdict.reasons(),
                verdict.ruleHits(),
                verdict.modelVersion(),
                snapshotAsMap(verdict),
                verdict.scoringLatencyMs(),
                verdict.degraded(),
                assessment.getEvaluatedAt());
    }

    /**
     * The feature snapshot as a map, for the event payloads that ask for one.
     *
     * <p>Empty rather than null in degraded mode, because nothing was scored — and an empty map is
     * unambiguous where a null invites a consumer to dereference it. The JSON is parsed here rather
     * than carried as a string because both payload contracts declare a {@code Map}, and honouring
     * that is better than smuggling a string through under a single key.
     */
    private Map<String, Object> snapshotAsMap(RiskVerdict verdict) {
        if (verdict.featureSnapshot() == null || verdict.featureSnapshot().isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(verdict.featureSnapshot(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (RuntimeException ex) {
            // The snapshot is still safely on the assessment row, so losing it from the event costs
            // an investigation some grounding rather than the audit trail.
            log.warn("Could not parse the feature snapshot for the event payload: {}",
                    ex.getMessage());
            return Map.of();
        }
    }

    // ---------------------------------------------------------------- analyst

    /**
     * Applies an analyst's decision: records it, closes the alert, and announces the outcome.
     *
     * <p>The decision row is written first and is append-only, so even if closing the alert failed the
     * record of who decided what would survive. That ordering is the point: the workflow state is
     * recoverable, the audit trail is not.
     */
    @Transactional
    public AnalystDecision applyDecision(String alertId, AnalystDecision.Action action,
                                         String reason, String analystUserId) {

        FraudAlert alert = alerts.findByAlertIdForUpdate(alertId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No fraud alert with id " + alertId));

        AnalystDecision decision = decisions.save(AnalystDecision.record(
                alert.getAlertId(), alert.getPaymentId(), action, reason, analystUserId));

        alert.resolve(analystUserId);
        alerts.save(alert);

        // The saga is parked in RISK_HELD with the customer's funds reserved, and this event is the
        // only thing that will move it. Recorded to the outbox in the same transaction as the
        // decision, deliberately: calling the orchestrator over HTTP from here could leave the alert
        // resolved with the payment parked forever, and it would be invisible — nothing would have
        // failed, and the payment would simply never move again.
        outbox.record(KafkaTopics.FRAUD_DECISION_APPLIED, AGGREGATE, alert.getPaymentId(),
                EventEnvelope.of(KafkaTopics.FRAUD_DECISION_APPLIED, AGGREGATE,
                        alert.getPaymentId(), SOURCE, new AnalystDecisionPayload(
                                alert.getPaymentId(),
                                assessments.findByPaymentId(alert.getPaymentId())
                                        .map(FraudAssessment::getTransactionId).orElse(null),
                                alert.getAlertId(),
                                alert.getAssessmentId(),
                                action == AnalystDecision.Action.RELEASE
                                        ? AnalystDecisionPayload.ACTION_RELEASE
                                        : AnalystDecisionPayload.ACTION_REJECT,
                                reason,
                                analystUserId,
                                decision.getDecidedAt())));

        // A rejection is confirmed fraud, and that feeds the feature pipeline: the account's
        // confirmed-fraud count is what the platform uses in place of chargebacks, which it has no
        // card network to produce.
        if (action == AnalystDecision.Action.REJECT) {
            observations.findByPaymentId(alert.getPaymentId()).ifPresent(observation -> {
                observation.confirmFraud();
                observations.save(observation);
            });
        }

        log.warn("Analyst {} chose {} on payment {} (alert {}): {}",
                analystUserId, action, alert.getPaymentId(), alertId, reason);
        return decision;
    }

    /** Assigns an alert to an analyst so two people do not work the same payment. */
    @Transactional
    public FraudAlert takeAlert(String alertId, String analystUserId) {
        FraudAlert alert = alerts.findByAlertIdForUpdate(alertId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No fraud alert with id " + alertId));
        alert.takeFor(analystUserId);
        return alerts.save(alert);
    }

    // ------------------------------------------------------------ observations

    /**
     * Records a payment in the read model.
     *
     * <p>Idempotent on the payment id: Kafka delivers at least once, and a second insert would abort
     * the transaction on the primary key. Returns the existing row instead, because the first
     * observation is the true one — a redelivery carries the same facts.
     */
    @Transactional
    public PaymentObservation observe(
            com.aegispay.common.events.payload.PaymentCreatedPayload payment) {

        Optional<PaymentObservation> existing = observations.findByPaymentId(payment.paymentId());
        if (existing.isPresent()) {
            return existing.get();
        }
        return observations.save(PaymentObservation.from(payment));
    }

    /** Records what became of a payment, so the velocity features can tell attempts from failures. */
    @Transactional
    public void recordOutcome(String paymentId, PaymentObservation.Outcome outcome) {
        observations.findByPaymentId(paymentId).ifPresentOrElse(
                observation -> {
                    observation.resolved(outcome);
                    observations.save(observation);
                },
                // The outcome arrived before the creation event, which cross-topic ordering permits.
                // Logged at debug rather than treated as an error: the creation event will follow and
                // the row will simply start at PENDING, which costs one payment's worth of accuracy
                // in one feature rather than anything a customer would notice.
                () -> log.debug("No observation for payment {} yet; its {} outcome arrived first",
                        paymentId, outcome));
    }

    /** Reads an assessment without locking, for query paths. */
    @Transactional(readOnly = true)
    public Optional<FraudAssessment> findAssessment(String paymentId) {
        return assessments.findByPaymentId(paymentId);
    }

    /** Every decision made on one payment, for the audit view. */
    @Transactional(readOnly = true)
    public List<AnalystDecision> decisionsFor(String paymentId) {
        return decisions.findByPaymentIdOrderByDecidedAtAsc(paymentId);
    }
}
