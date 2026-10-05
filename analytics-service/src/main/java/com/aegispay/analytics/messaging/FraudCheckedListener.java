package com.aegispay.analytics.messaging;

import com.aegispay.analytics.service.PaymentProjectionService;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.FraudCheckedPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Adds the risk fields to the payment read model.
 *
 * <h2>Why the risk data is projected rather than fetched</h2>
 *
 * <p>fraud-service already exposes every assessment, and the dashboard calls it directly for the alert
 * queue and for a single payment's detail. What it cannot answer is a question spanning both domains:
 * "what proportion of payments over ten thousand were held last week, and how many of those settled
 * anyway". That needs risk and outcome in the same row.
 *
 * <p>Doing it by joining across two services at read time would mean the dashboard fetching a page of
 * payments and then issuing one assessment lookup per row — the classic N+1, across a network, on a page
 * that exists to show a hundred rows at once.
 *
 * <h2>The field that changes the meaning of the others</h2>
 *
 * <p>{@code degradedMode} says the verdict was reached without the model, on deterministic rules alone. A
 * {@code LOW} risk level with it set means "nothing was found", not "nothing is there".
 *
 * <p>A dashboard that charted risk levels without it would show a reassuringly clean week during a model
 * outage, which is the single most misleading thing this service could display. It is latched on in the
 * aggregate — once true for a payment, always true — so no later event can erase the fact that the
 * platform decided blind.
 */
@Component
public class FraudCheckedListener {

    private static final String CONSUMER = "analytics.fraud-checked";

    private final EventConsumerSupport consumerSupport;
    private final PaymentProjectionService projection;

    public FraudCheckedListener(EventConsumerSupport consumerSupport,
                                PaymentProjectionService projection) {
        this.consumerSupport = consumerSupport;
        this.projection = projection;
    }

    @KafkaListener(topics = KafkaTopics.FRAUD_CHECKED, groupId = "analytics-service")
    public void onFraudChecked(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER, record, FraudCheckedPayload.class, envelope -> {
            FraudCheckedPayload payload = envelope.payload();
            if (payload == null) {
                throw new IllegalArgumentException(
                        "a record on " + KafkaTopics.FRAUD_CHECKED + " decoded to an empty payload");
            }

            // RISK_ASSESSED as the seed status, because a verdict can be the first event this
            // projection sees for a payment -- fraud-checked and payment-created are different topics
            // with no ordering between them. Seeding CREATED here would then be overwritten by nothing,
            // and the payment would sit at CREATED with a risk score attached.
            projection.apply(KafkaTopics.FRAUD_CHECKED, payload.paymentId(),
                    PaymentStatus.RISK_ASSESSED, payload.evaluatedAt(),
                    fact -> fact.applyRisk(
                            payload.riskLevel(),
                            payload.decision(),
                            payload.fraudProbability(),
                            payload.modelVersion(),
                            payload.ruleHits(),
                            payload.scoringLatencyMs(),
                            payload.degradedMode(),
                            payload.evaluatedAt()));
        });
    }
}
