package com.aegispay.ai.messaging;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.InvestigationPayloads.InvestigationRequestedPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import com.aegispay.ai.service.InvestigationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Investigates every payment a risk verdict refers.
 *
 * <p>The single entry point into automatic investigation. Kafka rather than a synchronous call from
 * fraud-service, and the reason is the reverse of the saga's: an investigation is <em>not</em> on the
 * payment's critical path. It happens after fraud-service has already decided, so a twenty-second language
 * model call delays an analyst's reading rather than a customer's payment — and making fraud-service wait
 * for it would put a model provider's latency in front of every held payment.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Kafka delivers at least once, so this will see duplicates after any rebalance, retry, or crash between
 * processing a record and committing its offset. A partial unique index on {@code payment_id} for automatic
 * investigations is the guarantee, and the service checks for an existing report before generating.
 *
 * <p>The cost of getting that wrong is unusual here: not corruption, but a second model call and two
 * analysts reading differently-worded narratives about the same payment with no way to tell which one the
 * other had seen.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Rethrows, so the container retries with backoff and eventually dead-letters. Swallowing would commit
 * the offset for a payment that was never investigated, and an analyst would find an empty queue rather
 * than a degraded report.
 *
 * <p>Note that an ordinary generation failure does <em>not</em> reach here: the service falls back to the
 * offline composer and produces a labelled report, because an advisory report an analyst can still read
 * beats no report at all. Only an inability to investigate at all propagates.
 */
@Component
public class InvestigationRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRequestedListener.class);

    /** Scopes de-duplication. Two services must be able to process the same event independently. */
    private static final String CONSUMER = "ai.investigation-requested";

    private final EventConsumerSupport consumerSupport;
    private final InvestigationService investigations;

    public InvestigationRequestedListener(EventConsumerSupport consumerSupport,
                                          InvestigationService investigations) {
        this.consumerSupport = consumerSupport;
        this.investigations = investigations;
    }

    @KafkaListener(topics = KafkaTopics.INVESTIGATION_REQUESTED,
            groupId = "ai-investigation-service")
    public void onInvestigationRequested(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER, record, InvestigationRequestedPayload.class, envelope -> {
            InvestigationRequestedPayload request = envelope.payload();

            log.info("Investigating payment {} referred at {} risk",
                    request.paymentId(), request.riskLevel());

            investigations.investigate(request);
        });
    }
}
