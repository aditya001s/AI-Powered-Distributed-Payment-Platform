package com.aegispay.payment.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.NotificationPayload;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRefundedPayload;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.money.Money;
import com.aegispay.common.outbox.OutboxRecorder;
import com.aegispay.payment.domain.Payment;
import com.aegispay.payment.domain.PaymentRefundRecord;
import com.aegispay.payment.repository.PaymentRefundRepository;
import com.aegispay.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Every transaction that writes payment state, plus the events that accompany it.
 *
 * <h2>Why state change and event publication share a transaction</h2>
 *
 * <p>Each method that changes state also records its event to the <strong>transactional
 * outbox</strong> in the same transaction. That is the only way to make "the payment is
 * COMPLETED" and "the world was told the payment completed" atomic without a distributed
 * transaction across PostgreSQL and Kafka.
 *
 * <p>Publishing directly to Kafka instead would fail in one of two ways, both bad:
 *
 * <pre>
 *   commit();  kafka.send(event);   // crash here -> state changed, nobody notified;
 *                                   // the ledger never posts and the payment is orphaned
 *   kafka.send(event);  commit();   // rollback -> the world told about a payment
 *                                   // that never happened
 * </pre>
 *
 * <p>With the outbox, both the row and the event land in one commit and a poller drains
 * the event afterwards. Delivery becomes at-least-once, which consumer-side de-duplication
 * on event ID turns into effectively-once.
 *
 * <h2>Which events this service still emits, and which it no longer does</h2>
 *
 * <p>Since Phase 5 the saga lives in payment-orchestrator, and it is the one that announces
 * {@code payment-approved}, {@code payment-completed}, {@code payment-failed} and
 * {@code payment-rejected}. The {@code apply*} methods here <em>consume</em> those and bring this
 * service's row into step; they emit nothing. Emitting them from both sides would put two
 * differently-shaped payloads for one payment on the same topic — the orchestrator's, which knows
 * the provider, the fee and the ledger posting, and this service's, which does not — and leave
 * every consumer guessing which is authoritative.
 *
 * <p>What this service does still own is {@code payment-created}, which is the sole trigger for
 * orchestration, {@code payment-refunded}, because refunds remain synchronous here, and every
 * customer notification. That last one is the deliberate division of labour: the orchestrator owns
 * the saga's facts, this service owns the customer-facing record and everything the customer is
 * told about it.
 *
 * <h2>Why this is a separate bean</h2>
 *
 * <p>{@link PaymentService} orchestrates and makes HTTP calls to wallet-service, so it must
 * <em>not</em> be transactional — a remote call inside a transaction holds a database
 * connection for the duration of a network round trip. The transactional work therefore
 * lives behind a real bean boundary, because a {@code this.method()} call would bypass the
 * proxy and silently run untransacted.
 *
 * <p>Every method here is short and touches only the database. No HTTP, no hashing.
 */
@Service
public class PaymentMutationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentMutationService.class);

    private static final String SOURCE = "payment-service";
    private static final String AGGREGATE = "Payment";

    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final OutboxRecorder outbox;

    public PaymentMutationService(PaymentRepository payments,
                                  PaymentRefundRepository refunds,
                                  OutboxRecorder outbox) {
        this.payments = payments;
        this.refunds = refunds;
        this.outbox = outbox;
    }

    /**
     * Persists a new payment and records {@code payment-created}.
     *
     * <p><strong>This event is now the only thing that starts a payment moving.</strong>
     * payment-orchestrator consumes it and drives the saga; nothing else does. That is why it is
     * recorded to the outbox inside this transaction rather than published after commit — an event
     * lost on a crash would leave a payment the customer was told had been accepted sitting at
     * {@code CREATED} forever, with no reservation, no provider call and nobody looking for it.
     */
    @Transactional
    public Payment createPayment(Payment payment) {
        Payment saved = payments.save(payment);

        PaymentCreatedPayload payload = new PaymentCreatedPayload(
                saved.getPaymentId(),
                saved.getTransactionId(),
                saved.getIdempotencyKey(),
                saved.getInitiatorUserId(),
                saved.getSourceWalletId(),
                saved.getDestinationWalletId(),
                saved.getDestinationUserId(),
                saved.amount(),
                saved.getPaymentType(),
                saved.getDescription(),
                saved.riskSignals(),
                saved.getCreatedAt());

        // MANDATORY propagation on the recorder means this call would throw if it were
        // ever made outside a transaction — the atomicity guarantee is enforced, not
        // merely documented.
        outbox.record(KafkaTopics.PAYMENT_CREATED, AGGREGATE, saved.getPaymentId(),
                EventEnvelope.of(KafkaTopics.PAYMENT_CREATED, AGGREGATE,
                        saved.getPaymentId(), SOURCE, payload));

        log.info("Created payment {} ({}) for {} from wallet {} to {}",
                saved.getPaymentId(), saved.getTransactionId(), saved.amount(),
                saved.getSourceWalletId(), saved.getDestinationWalletId());
        return saved;
    }

    // ------------------------------------------------- saga outcome handlers

    /**
     * Advances the payment to {@code AUTHORIZED} on {@code payment-approved}.
     *
     * <p>Records the risk verdict the saga reached. Kept as an explicit stored value rather than
     * inferred from the status, so a disputed payment can be reviewed against the verdict that was
     * actually applied to it.
     *
     * <p>No event is emitted. The orchestrator has already announced this; re-emitting would put two
     * differently-shaped payloads for one payment on the same topic and leave every consumer
     * guessing which is authoritative.
     */
    @Transactional
    public Payment applyApproved(String paymentId, RiskLevel riskLevel, Double probability,
                                 FraudDecision decision) {
        Payment payment = lock(paymentId);

        payment.recordRiskAssessment(riskLevel, probability, decision);
        if (!payment.advanceTo(PaymentStatus.AUTHORIZED)) {
            // Already at or past AUTHORIZED, or off the forward path entirely. Both are ordinary
            // for a redelivered event, and for payment-approved arriving after payment-completed —
            // Kafka orders records within one topic's partition, never across two topics.
            log.debug("Payment {} is {}; payment-approved needs no state change",
                    paymentId, payment.getStatus());
            return payments.save(payment);
        }
        log.info("Payment {} authorised by the saga (risk {})", paymentId, riskLevel);
        return payments.save(payment);
    }

    /**
     * Completes the payment on {@code payment-completed} and notifies both parties.
     *
     * <p>Walks the payment forward through every state it has not yet been through, because this
     * event may be the first the service sees: {@code payment-approved} travels on a different
     * topic and can arrive afterwards or not at all. Each hop is still a guarded transition.
     *
     * <p><strong>The notifications are emitted here and nowhere else.</strong> The split is
     * deliberate: payment-orchestrator owns the saga facts, and this service owns the
     * customer-facing record and everything the customer is told about it. Each notification
     * carries a de-duplication key derived from the payment ID, so a redelivered event cannot send
     * a second "payment successful" message.
     */
    @Transactional
    public Payment applyCompleted(String paymentId, String providerId, String providerReference,
                                  Money fee, long endToEndLatencyMs) {
        Payment payment = lock(paymentId);

        payment.recordProvider(providerId, providerReference, fee);
        if (!payment.advanceTo(PaymentStatus.COMPLETED)) {
            log.debug("Payment {} is already {}; ignoring the redelivered payment-completed",
                    paymentId, payment.getStatus());
            return payments.save(payment);
        }
        Payment saved = payments.save(payment);

        recordNotification(saved, saved.getInitiatorUserId(), "payment.sent", "sent");
        if (saved.getDestinationUserId() != null) {
            recordNotification(saved, saved.getDestinationUserId(), "payment.received", "received");
        }

        log.info("Payment {} completed via {} in {}ms (fee {})",
                saved.getPaymentId(), providerId, endToEndLatencyMs, saved.fee());
        return saved;
    }

    /**
     * Marks the payment failed on {@code payment-failed}.
     *
     * @param compensationComplete whether the saga confirmed every side effect was undone.
     *                             {@code false} means money may still be reserved at a wallet or
     *                             authorised at a provider and a human must look. Logged at
     *                             {@code ERROR} here as well as being alerted on from the
     *                             orchestrator, because this is the service someone investigating a
     *                             customer complaint will look at first.
     */
    @Transactional
    public Payment applyFailed(String paymentId, String failureCode, String failureReason,
                               String failedStep, boolean compensationComplete) {
        Payment payment = lock(paymentId);

        payment.recordFailure(failureCode, failureReason);
        // FAILED is reachable from every non-terminal state, so a payment can always be
        // abandoned cleanly no matter where it got to.
        if (!payment.tryTransitionTo(PaymentStatus.FAILED)) {
            log.warn("Payment {} is already {} and cannot be marked FAILED; leaving it alone",
                    paymentId, payment.getStatus());
            return payment;
        }
        Payment saved = payments.save(payment);

        recordNotification(saved, saved.getInitiatorUserId(), "payment.failed", "failed");

        if (!compensationComplete) {
            log.error("""
                            Payment {} FAILED and the saga could NOT confirm every side effect was \
                            undone. Funds may remain reserved, or a charge may still be held at a \
                            provider. Step={} code={}""",
                    paymentId, failedStep, failureCode);
        } else {
            log.info("Payment {} failed at step {} ({}); the saga compensated cleanly",
                    paymentId, failedStep, failureCode);
        }
        return saved;
    }

    /**
     * Marks the payment rejected on {@code payment-rejected}.
     *
     * <p>A rejection is a decision, not a fault: every provider declined, or fraud controls refused
     * it. Nothing was charged, so the customer can be told plainly rather than asked to retry into
     * the same answer — which is the whole reason this is a separate status from {@code FAILED}.
     *
     * <p>{@code REJECTED} is unreachable once a provider has authorised the charge. If the event
     * arrives against an authorised payment, something has gone genuinely wrong upstream, so it is
     * recorded as a failure and logged loudly rather than silently dropped — dropping it would leave
     * the payment in flight forever with no trace of why.
     */
    @Transactional
    public Payment applyRejected(String paymentId, String reasonCode, String reason,
                                 boolean fundsReleased) {
        Payment payment = lock(paymentId);

        payment.recordFailure(reasonCode, reason);

        if (payment.tryTransitionTo(PaymentStatus.REJECTED)) {
            Payment saved = payments.save(payment);
            recordNotification(saved, saved.getInitiatorUserId(), "payment.rejected", "rejected");
            log.info("Payment {} rejected ({}); funds released: {}",
                    paymentId, reasonCode, fundsReleased);
            return saved;
        }

        if (payment.getStatus().isTerminal()) {
            log.debug("Payment {} is already {}; ignoring the redelivered payment-rejected",
                    paymentId, payment.getStatus());
            return payment;
        }

        log.error("""
                        Payment {} was rejected by the saga but is {}, from which REJECTED is not \
                        reachable. Recording it as FAILED so the payment does not stay in flight. \
                        Reason={} ({})""",
                paymentId, payment.getStatus(), reasonCode, reason);
        return applyFailedAlreadyLocked(payment, reasonCode, reason, "AUTHORIZE", fundsReleased);
    }

    /**
     * The tail of {@link #applyFailed}, for a payment already loaded and locked.
     *
     * <p>Split out rather than calling {@code applyFailed} from {@link #applyRejected}: that would
     * be a self-invocation, which bypasses Spring's proxy — harmless for the transaction here since
     * one is already open, but it would also take the row lock a second time within the same
     * transaction and read the entity twice from the persistence context for no reason.
     */
    private Payment applyFailedAlreadyLocked(Payment payment, String failureCode,
                                             String failureReason, String failedStep,
                                             boolean compensationComplete) {
        if (!payment.tryTransitionTo(PaymentStatus.FAILED)) {
            return payment;
        }
        Payment saved = payments.save(payment);
        recordNotification(saved, saved.getInitiatorUserId(), "payment.failed", "failed");

        if (!compensationComplete) {
            log.error("Payment {} FAILED at {} ({}) with compensation unconfirmed",
                    saved.getPaymentId(), failedStep, failureCode);
        }
        return saved;
    }

    /**
     * Applies a refund and records {@code payment-refunded}.
     *
     * <p>The over-refund check lives in the aggregate and is backed by a CHECK constraint,
     * so refunding more than was paid is rejected in the domain and unpersistable in the
     * database. Refunds are frequently driven by support tooling rather than this code
     * path, which is why the constraint matters as much as the check.
     *
     * <p><strong>The event is not optional.</strong> A refund moves real money, so anything
     * that must stay consistent with the payment record has to hear about it — above all
     * ledger-service, which books the reverse posting. Until Phase 4 this method changed the
     * payment and the wallet balances while emitting nothing, which meant the book of record
     * would have shown the original transfer and never its reversal. Recorded on the outbox
     * inside this transaction, so the state change and the announcement are atomic.
     */
    @Transactional
    public Payment applyRefund(String paymentId, String refundId, Money refundAmount,
                               String reason, String initiatedBy) {
        Payment payment = lock(paymentId);
        var existingRefund = refunds.findById(refundId);
        if (existingRefund.isPresent()) {
            if (!existingRefund.get().matches(paymentId, refundAmount)) {
                throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Refund operation was already applied with different payment data");
            }
            log.warn("Recovered already-applied refund {} for payment {}", refundId, paymentId);
            return payment;
        }

        PaymentStatus resulting = payment.applyRefund(refundAmount);
        refunds.save(PaymentRefundRecord.record(refundId, paymentId, refundAmount));
        Payment saved = payments.save(payment);

        boolean fullyRefunded = resulting == PaymentStatus.REFUNDED;

        PaymentRefundedPayload payload = new PaymentRefundedPayload(
                saved.getPaymentId(),
                saved.getTransactionId(),
                refundId,
                saved.getInitiatorUserId(),
                // Both wallets travel on the event. The ledger books the reverse of the
                // original transfer and cannot look them up from the earlier
                // payment-completed posting, because Kafka gives no ordering guarantee
                // across two different topics.
                saved.getSourceWalletId(),
                saved.getDestinationWalletId(),
                refundAmount,
                saved.refunded(),
                saved.amount(),
                fullyRefunded,
                reason,
                initiatedBy,
                // Assigned by ledger-service when it books the posting.
                null,
                Instant.now());

        outbox.record(KafkaTopics.PAYMENT_REFUNDED, AGGREGATE, saved.getPaymentId(),
                EventEnvelope.of(KafkaTopics.PAYMENT_REFUNDED, AGGREGATE,
                        saved.getPaymentId(), SOURCE, payload));

        // The payer is the one who gets their money back, so they are the one told.
        recordNotification(saved, saved.getInitiatorUserId(),
                fullyRefunded ? "payment.refunded" : "payment.partially-refunded",
                "refunded:" + refundId);

        log.info("Refunded {} on payment {} (total refunded {}); status now {}",
                refundAmount, paymentId, saved.refunded(), resulting);
        return saved;
    }

    /** Reads a payment without locking, for query paths. */
    @Transactional(readOnly = true)
    public Payment require(String paymentId) {
        return payments.findByPaymentId(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Payment " + paymentId + " was not found"));
    }

    // ------------------------------------------------------------- internals

    /**
     * Loads a payment with its row locked.
     *
     * <p>State transitions are read-modify-write on the status column. Without the lock, a
     * saga step and a duplicate event delivery could both read {@code AUTHORIZED}, both
     * find the move to {@code CAPTURED} legal, and both apply it. The state machine only
     * rejects transitions that are illegal from the state it was shown, so it cannot catch
     * that on its own.
     */
    private Payment lock(String paymentId) {
        return payments.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Payment " + paymentId + " was not found"));
    }

    /**
     * Records a customer notification on the outbox.
     *
     * <p>The de-duplication key is derived from the payment ID and the event kind, so a
     * redelivered event produces the same key and notification-service suppresses the
     * duplicate. Without it, at-least-once delivery would occasionally send a customer two
     * "payment successful" messages for one payment.
     */
    private void recordNotification(Payment payment, String userId, String templateKey,
                                    String kind) {
        NotificationPayload payload = NotificationPayload.transactional(
                userId,
                templateKey,
                Map.of("transactionId", payment.getTransactionId(),
                        "amount", payment.amount().toDecimal().toPlainString(),
                        "currency", payment.getCurrency(),
                        "description", payment.getDescription() == null ? "" : payment.getDescription()),
                payment.getPaymentId() + ":" + kind + ":" + userId,
                payment.getPaymentId());

        outbox.record(KafkaTopics.NOTIFICATION_REQUIRED, AGGREGATE, payment.getPaymentId(),
                EventEnvelope.of(KafkaTopics.NOTIFICATION_REQUIRED, AGGREGATE,
                        payment.getPaymentId(), SOURCE, payload));
    }
}
