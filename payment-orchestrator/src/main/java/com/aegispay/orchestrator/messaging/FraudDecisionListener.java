package com.aegispay.orchestrator.messaging;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.AnalystDecisionPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import com.aegispay.orchestrator.service.SagaOrchestrator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Unparks a payment an analyst has decided on.
 *
 * <p>A {@code HOLD} verdict stops the saga at {@code RISK_HELD} with the customer's funds still
 * reserved, and the recovery sweep deliberately ignores that state. This listener is therefore the
 * <strong>only</strong> thing that will ever move a held payment. If it stops working, held payments
 * accumulate silently: nothing errors, and each one is a customer whose money is reserved
 * indefinitely. That is what {@code countByStatusAndRiskHeldAtBefore} exists to alert on.
 *
 * <h2>Why an event rather than fraud-service calling in</h2>
 *
 * <p>fraud-service records the decision and this event in one commit through its outbox. A synchronous
 * call could fail after the alert was resolved, leaving the payment parked forever with nothing to
 * point at — the alert would read as handled and the payment would simply never move. It also means an
 * orchestrator redeployment does not stop analysts working the queue: the event waits.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Kafka delivers at least once. A redelivered decision finds the saga no longer parked and does
 * nothing — the saga's state machine is the guarantee, not the de-duplication cache in front of it.
 * The same no-op covers a decision on an alert that only ever asked for a review and never stopped the
 * payment, which is a legitimate thing for an analyst to do.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Rethrows, so the container retries and eventually dead-letters. Swallowing would commit the offset
 * for a decision that was never applied, and the held payment would stay held with the alert already
 * closed — invisible from both ends.
 */
@Component
public class FraudDecisionListener {

    private static final Logger log = LoggerFactory.getLogger(FraudDecisionListener.class);

    /** Scopes de-duplication. Two services must be able to process the same event independently. */
    private static final String CONSUMER = "orchestrator.fraud-decision-applied";

    private final EventConsumerSupport consumerSupport;
    private final SagaOrchestrator orchestrator;

    public FraudDecisionListener(EventConsumerSupport consumerSupport,
                                 SagaOrchestrator orchestrator) {
        this.consumerSupport = consumerSupport;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(topics = KafkaTopics.FRAUD_DECISION_APPLIED, groupId = "payment-orchestrator")
    public void onFraudDecisionApplied(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER, record, AnalystDecisionPayload.class, envelope -> {
            AnalystDecisionPayload decision = envelope.payload();

            if (!decision.isRelease() && !decision.isReject()) {
                // An action this version does not understand. Logged and ignored rather than
                // dead-lettered: retrying will not teach it a new action, and a poison record on the
                // only topic that unparks payments would block every decision behind it.
                log.error("Ignoring fraud decision on payment {} with unrecognised action '{}'",
                        decision.paymentId(), decision.action());
                return;
            }

            log.info("Applying analyst {} on payment {} from {}",
                    decision.action(), decision.paymentId(), decision.decidedBy());

            orchestrator.applyAnalystDecision(
                    decision.paymentId(),
                    decision.isRelease(),
                    decision.decidedBy(),
                    decision.reason());
        });
    }
}
