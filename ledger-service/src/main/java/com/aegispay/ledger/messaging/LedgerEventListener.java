package com.aegispay.ledger.messaging;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRefundedPayload;
import com.aegispay.common.events.payload.WalletMovementPayload;
import com.aegispay.common.kafka.EventConsumerSupport;
import com.aegispay.ledger.service.LedgerService;
import com.aegispay.ledger.service.PostingBuilder;
import com.aegispay.ledger.service.PostingResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Books every money movement the platform reports.
 *
 * <p>This is the normal way postings reach the journal. Kafka rather than a synchronous call
 * because a payment must not fail just because the ledger is briefly unavailable: the outbox
 * on the producing side plus retries here mean the posting is delayed, not lost. Phase 5's
 * saga will additionally call the ledger synchronously when it needs to know the posting
 * committed <em>before</em> it captures funds.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Kafka delivery is at-least-once, so every handler here will eventually see a duplicate —
 * after a rebalance, a retry, or a crash between processing a record and committing its
 * offset. Two things make that safe:
 *
 * <ul>
 *   <li>{@link PostingBuilder} derives each posting's {@code ledgerTransactionId}
 *       deterministically from an identifier the event already carries, so a redelivery
 *       produces the <em>same</em> key.</li>
 *   <li>That key is unique in the journal, so the second attempt is recognised as a replay
 *       rather than booked again.</li>
 * </ul>
 *
 * <p>This service runs without Redis, so {@code ProcessedEventRegistry} degrades to a no-op
 * and suppresses nothing. That is fine precisely because the guarantee lives in the database
 * constraint rather than in the de-duplication cache — which is the arrangement the registry's
 * own contract recommends for handlers that must be exactly-once.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Handlers rethrow. The container's error handler owns retry with backoff and, once
 * attempts are exhausted, the dead-letter topic. Swallowing an exception here would commit the
 * offset for a movement that was never booked, and a silently unbooked payment is exactly the
 * hole a ledger exists to close.
 *
 * <p>An event that can never be booked — a payment carrying a fee the platform has not yet
 * decided how to allocate, a movement missing its wallet ID — fails with
 * {@link IllegalArgumentException}, which {@code KafkaConfig} classifies as non-retryable. It
 * goes straight to the dead-letter topic for a human instead of retrying four times against a
 * problem no retry can fix.
 */
@Component
public class LedgerEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventListener.class);

    /** Scopes de-duplication. Two services must be able to process the same event. */
    private static final String CONSUMER_PAYMENT_COMPLETED = "ledger.payment-completed";
    private static final String CONSUMER_PAYMENT_REFUNDED = "ledger.payment-refunded";
    private static final String CONSUMER_WALLET_MOVEMENT = "ledger.wallet-movement";
    private static final String CONSUMER_POSTING_REQUESTED = "ledger.posting-requested";

    private final EventConsumerSupport consumerSupport;
    private final PostingBuilder postingBuilder;
    private final LedgerService ledgerService;

    public LedgerEventListener(EventConsumerSupport consumerSupport,
                               PostingBuilder postingBuilder,
                               LedgerService ledgerService) {
        this.consumerSupport = consumerSupport;
        this.postingBuilder = postingBuilder;
        this.ledgerService = ledgerService;
    }

    /**
     * Books a completed payment as a wallet-to-wallet transfer.
     *
     * <p>Both legs are customer liabilities, so the platform's total obligation is unchanged —
     * correctly, because an internal transfer moves who is owed rather than how much.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_COMPLETED, groupId = "ledger-service")
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_PAYMENT_COMPLETED, record, PaymentCompletedPayload.class,
                envelope -> {
                    PaymentCompletedPayload payment = envelope.payload();
                    LedgerPostingPayload posting = postingBuilder.forPaymentCompleted(payment);
                    PostingResult result = ledgerService.post(posting, "payment-service");
                    logOutcome("payment", payment.paymentId(), result);
                });
    }

    /**
     * Books a refund as the reverse of the original transfer.
     *
     * <p>Typed {@code REFUND} rather than {@code REVERSAL} even when it is a full refund: a
     * refund is a real, separate movement of money that a customer can request repeatedly up
     * to the original amount, whereas a reversal annuls a posting line for line and is
     * permitted only once.
     */
    @KafkaListener(topics = KafkaTopics.PAYMENT_REFUNDED, groupId = "ledger-service")
    public void onPaymentRefunded(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_PAYMENT_REFUNDED, record, PaymentRefundedPayload.class,
                envelope -> {
                    PaymentRefundedPayload refund = envelope.payload();
                    LedgerPostingPayload posting = postingBuilder.forPaymentRefunded(refund);
                    PostingResult result = ledgerService.post(posting, "payment-service");
                    logOutcome("refund", refund.refundId(), result);
                });
    }

    /**
     * Books a movement across the platform boundary against the external-funding account.
     *
     * <p>Without these the ledger would only ever see internal transfers, wallet liability
     * balances would be missing every deposit ever made, and the trial balance could never sum
     * to zero — so the journal could not honestly be called a book of record.
     */
    @KafkaListener(topics = KafkaTopics.WALLET_MOVEMENT_RECORDED, groupId = "ledger-service")
    public void onWalletMovement(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_WALLET_MOVEMENT, record, WalletMovementPayload.class,
                envelope -> {
                    WalletMovementPayload movement = envelope.payload();
                    LedgerPostingPayload posting = postingBuilder.forWalletMovement(movement);
                    PostingResult result = ledgerService.post(posting, "wallet-service");
                    logOutcome("wallet movement", movement.walletTransactionId(), result);
                });
    }

    /**
     * Books a posting somebody else already assembled.
     *
     * <p>The asynchronous counterpart to {@code POST /internal/ledger/postings}, for a caller
     * that has decided the accounting itself — an operator adjustment, or a future service with
     * accounting rules of its own. Everything normal arrives on the three topics above, where
     * the ledger owns the mapping and the chart of accounts stays in one place.
     */
    @KafkaListener(topics = KafkaTopics.LEDGER_POSTING_REQUESTED, groupId = "ledger-service")
    public void onPostingRequested(ConsumerRecord<String, String> record) {
        consumerSupport.handle(CONSUMER_POSTING_REQUESTED, record, LedgerPostingPayload.class,
                envelope -> {
                    LedgerPostingPayload posting = envelope.payload();
                    PostingResult result = ledgerService.post(posting,
                            envelope.source() == null ? "unknown" : envelope.source());
                    logOutcome("requested posting", posting.ledgerTransactionId(), result);
                });
    }

    private void logOutcome(String what, String id, PostingResult result) {
        if (result.duplicate()) {
            log.debug("Ledger already had a posting for {} {}; skipped as a replay", what, id);
        } else {
            log.info("Booked {} {} as journal entry #{}",
                    what, id, result.entry().getJournalSequence());
        }
    }
}
