package com.aegispay.payment.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.idempotency.IdempotencyService;
import com.aegispay.common.idempotency.RequestFingerprint;
import com.aegispay.common.money.Money;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.payment.api.PaymentDtos.CreatePaymentRequest;
import com.aegispay.payment.api.PaymentDtos.MoneyView;
import com.aegispay.payment.api.PaymentDtos.PaymentResponse;
import com.aegispay.payment.api.PaymentDtos.PaymentSummary;
import com.aegispay.payment.api.PaymentDtos.RefundRequest;
import com.aegispay.payment.api.PaymentDtos.RefundResponse;
import com.aegispay.payment.api.PaymentDtos.RiskContext;
import com.aegispay.payment.client.WalletClient;
import com.aegispay.payment.domain.Payment;
import com.aegispay.payment.repository.PaymentRefundRepository;
import com.aegispay.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment acceptance, history and refunds.
 *
 * <h2>Initiation is asynchronous</h2>
 *
 * <pre>
 *   1. resolve and validate both wallets                (HTTP -> wallet-service)
 *   2. persist as CREATED + payment-created on the outbox   (one transaction)
 *   3. return 202 Accepted
 *
 *   ... payment-orchestrator consumes payment-created and drives the saga:
 *       reserve -> assess risk -> authorise at a provider -> post the ledger -> capture
 *
 *   ... this service consumes the saga's outcome events and advances its own row
 *       (see SagaOutcomeListener)
 * </pre>
 *
 * <p><strong>Why the request no longer waits for the money to move.</strong> Phase 3 ran the whole
 * sequence inline and returned {@code 201 COMPLETED}. From Phase 5 the sequence includes routing
 * across providers, retrying, and falling back — each provider call has a three-second deadline of
 * its own, and a payment may legitimately try three of them. Holding the customer's HTTP connection
 * open across that would mean a request that can take ten seconds, a client timeout that tells the
 * customer nothing about whether their money moved, and a service whose throughput is capped by the
 * slowest rail it talks to.
 *
 * <p>The honest consequence is that {@code POST /api/payments} answers "accepted", not "done", and
 * the API says so with {@code 202}. Callers poll {@code GET /api/payments/{id}} or wait for the
 * notification. Pretending otherwise — returning 201 with a status the saga has not reached — would
 * be worse than the extra round trip, because a client would render a payment as successful before
 * anything had been charged.
 *
 * <h2>What is still checked synchronously, and why</h2>
 *
 * <p>Wallet resolution, currency agreement, self-transfer and a balance pre-check all happen before
 * the payment is persisted, so an obviously impossible request is refused immediately with a clear
 * error instead of being accepted and then failed asynchronously. None of these is authoritative —
 * wallet-service re-checks the balance under a row lock when the saga reserves — but a fast, precise
 * rejection is worth far more to the caller than a 202 followed by a silent failure.
 *
 * <h2>Refunds stay synchronous</h2>
 *
 * <p>{@link #refund} still moves money inline. A refund has no provider leg — it is a wallet-to-wallet
 * reversal — so there is nothing to route, nothing to retry across rails, and no reason to make the
 * caller poll. It can still fail for a real reason (the payee has already spent the funds), and that
 * answer is more useful delivered immediately than as an event.
 *
 * <h2>Why this class is not transactional</h2>
 *
 * <p>It makes HTTP calls. A transaction spanning them would hold a database connection open across
 * the network — under load the pool empties and the whole service stalls waiting on a downstream it
 * cannot hurry. So this class orchestrates, and each state change is a short transaction inside
 * {@link PaymentMutationService}, behind a real bean boundary.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String SCOPE_CREATE = "payment.create";
    private static final String SCOPE_REFUND = "payment.refund";

    /**
     * Reservation lifetime requested from wallet-service for a refund.
     *
     * <p>Only the refund path reserves from here now; a payment's reservation is taken by the saga,
     * which sets its own TTL. Longer than a refund needs, so a slow capture is never swept out from
     * under one still legitimately in progress, and short enough that a crash frees the money in
     * minutes rather than days.
     */
    private static final Duration HOLD_TTL = Duration.ofMinutes(15);

    private final PaymentRepository payments;
    private final PaymentRefundRepository refunds;
    private final PaymentMutationService mutations;
    private final WalletClient walletClient;
    private final IdempotencyService idempotency;
    private final MeterRegistry meterRegistry;
    private final SecureRandom secureRandom = new SecureRandom();

    public PaymentService(PaymentRepository payments,
                          PaymentRefundRepository refunds,
                          PaymentMutationService mutations,
                          WalletClient walletClient,
                          IdempotencyService idempotency,
                          MeterRegistry meterRegistry) {
        this.payments = payments;
        this.refunds = refunds;
        this.mutations = mutations;
        this.walletClient = walletClient;
        this.idempotency = idempotency;
        this.meterRegistry = meterRegistry;
    }

    // ---------------------------------------------------------- initiation

    /**
     * Accepts a payment for orchestration, at most once per idempotency key.
     *
     * <p>The fingerprint covers payer, payee and amount, so replaying a key with a
     * different destination or amount is rejected rather than silently returning the first
     * payment's response — which would drop a real transfer without telling anyone.
     *
     * <p>Replaying the <em>same</em> request returns the stored response, which is the payment as it
     * was when first accepted — status {@code CREATED}. That is deliberate: the idempotency store
     * exists to make the write happen once, not to act as a status endpoint. A caller that wants the
     * current state asks {@code GET /api/payments/{id}}.
     */
    public PaymentResponse initiate(String initiatorUserId, String observedIp, String userAgent,
                                    CreatePaymentRequest request, String idempotencyKey) {
        Money amount = request.toMoney();

        String fingerprint = RequestFingerprint.of(
                initiatorUserId,
                request.destinationWalletId(),
                request.destinationUserId(),
                amount.amountMinor(),
                amount.currency(),
                request.paymentType());

        return idempotency.execute(SCOPE_CREATE, idempotencyKey, fingerprint,
                PaymentResponse.class,
                () -> executePayment(initiatorUserId, observedIp, userAgent, request,
                        amount, idempotencyKey));
    }

    /**
     * Validates the request, persists it, and hands it to the saga.
     *
     * <p>Nothing here moves money and nothing here needs compensating: either the payment is
     * persisted with {@code payment-created} on the outbox in the same transaction, or neither
     * happened. That single-commit property is what lets this method be as short as it is — the
     * elaborate compensation Phase 3 needed existed because it performed four network side effects
     * before returning, and it now lives in the saga where those side effects do.
     */
    private PaymentResponse executePayment(String initiatorUserId, String observedIp,
                                           String userAgent, CreatePaymentRequest request,
                                           Money amount, String idempotencyKey) {

        // ---- 1. Resolve both wallets and refuse anything obviously impossible ----
        //
        // Not authoritative: wallet-service re-checks the balance under a row lock when the saga
        // reserves, and that is the decision that counts. This is here so a caller gets a precise
        // error immediately rather than a 202 followed by a silent asynchronous failure.
        WalletClient.WalletView source =
                walletClient.getWalletByUser(initiatorUserId, amount.currency());
        WalletClient.WalletView destination = resolveDestination(request, amount.currency());

        validateTransfer(source, destination, amount);

        // ---- 2. Persist as CREATED, with payment-created on the outbox ----
        Payment payment = mutations.createPayment(Payment.create(
                generatePaymentId(),
                generateTransactionId(),
                idempotencyKey,
                initiatorUserId,
                source.walletId(),
                destination.walletId(),
                destination.userId(),
                amount,
                request.paymentType(),
                request.description(),
                buildRiskSignals(request.riskContext(), observedIp, userAgent)));

        meterRegistry.counter(MetricNames.PAYMENT_ACCEPTED,
                MetricNames.TAG_OUTCOME, "accepted",
                MetricNames.TAG_PAYMENT_TYPE, String.valueOf(payment.getPaymentType()))
                .increment();

        log.info("Accepted payment {} ({}) for orchestration", payment.getPaymentId(), amount);

        // ---- 3. Done. The orchestrator takes it from the payment-created event ----
        return PaymentResponse.from(payment);
    }

    // ------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String paymentId, String requestingUserId) {
        Payment payment = payments.findByPaymentId(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Payment " + paymentId + " was not found"));
        requireParticipant(payment, requestingUserId);
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentSummary> history(String userId, PaymentStatus status,
                                                Pageable pageable) {
        var page = status == null
                ? payments.findByInitiatorUserIdOrderByCreatedAtDesc(userId, pageable)
                : payments.findByInitiatorUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);

        // Direction is computed per payment relative to the viewer's own wallet, so a
        // transfer reads as OUTGOING for the payer and INCOMING for the payee.
        return PageResponse.of(page, payment ->
                PaymentSummary.from(payment, payment.getSourceWalletId()));
    }

    // ------------------------------------------------------------- refunds

    /**
     * Refunds a completed payment, in whole or in part.
     *
     * <p>Idempotent, because a retried refund that credited twice would be a direct loss.
     *
     * <p>The money movement is the reverse of the original: the payee's wallet is debited
     * and the payer's credited. Phase 3 performs that as a fresh reserve-and-capture in the
     * opposite direction, which means a refund can legitimately fail if the payee has
     * already spent the funds — a real constraint, surfaced as 422 rather than hidden.
     */
    public RefundResponse refund(String paymentId, String requestingUserId,
                                 RefundRequest request, String idempotencyKey) {
        Payment payment = payments.findByPaymentId(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                        "Payment " + paymentId + " was not found"));
        requireParticipant(payment, requestingUserId);

        String refundId = "RFD-" + RequestFingerprint.sha256(
                SCOPE_REFUND + ":" + idempotencyKey).substring(0, 20).toUpperCase();
        var applied = refunds.findById(refundId);
        if (applied.isPresent()) {
            Money appliedAmount = Money.ofMinor(
                    applied.get().getAmountMinor(), applied.get().getCurrency());
            if (request.amount() != null
                    && !Money.of(request.amount(), payment.getCurrency()).equals(appliedAmount)) {
                throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Refund idempotency key was already used with a different amount");
            }
            return new RefundResponse(
                    payment.getPaymentId(), refundId, MoneyView.of(appliedAmount),
                    MoneyView.of(payment.refunded()), MoneyView.of(payment.refundableRemaining()),
                    payment.getStatus(), applied.get().getCreatedAt());
        }

        if (!payment.getStatus().isRefundable()) {
            throw new ConflictException(ErrorCode.REFUND_NOT_ALLOWED,
                    "Payment " + paymentId + " is " + payment.getStatus()
                            + " and cannot be refunded");
        }

        Money refundAmount = request.amount() == null
                ? payment.refundableRemaining()
                : Money.of(request.amount(), payment.getCurrency());

        if (!refundAmount.isPositive()) {
            throw new BadRequestException(ErrorCode.INVALID_AMOUNT,
                    "There is nothing left to refund on payment " + paymentId);
        }
        if (refundAmount.isGreaterThan(payment.refundableRemaining())) {
            throw new BadRequestException(ErrorCode.INVALID_AMOUNT,
                    "Refunding " + refundAmount + " exceeds the remaining refundable amount of "
                            + payment.refundableRemaining());
        }

        String fingerprint = RequestFingerprint.of(
                paymentId, refundAmount.amountMinor(), refundAmount.currency(), "REFUND");

        return idempotency.execute(SCOPE_REFUND, idempotencyKey, fingerprint,
                RefundResponse.class,
                () -> executeRefund(payment, refundId, refundAmount, request.reason(), requestingUserId));
    }

    private RefundResponse executeRefund(Payment payment, String refundId,
                                         Money refundAmount, String reason,
                                         String initiatedBy) {

        // Reverse direction: take the money back from the original payee.
        walletClient.reserve(refundId, payment.getDestinationWalletId(), refundAmount, HOLD_TTL);
        try {
            walletClient.capture(refundId, payment.getDestinationWalletId(),
                    payment.getSourceWalletId(), "Refund of " + payment.getTransactionId());
        } catch (RuntimeException ex) {
            walletClient.releaseQuietly(refundId, "Refund capture failed");
            throw ex;
        }

        Payment updated = mutations.applyRefund(
                payment.getPaymentId(), refundId, refundAmount, reason, initiatedBy);

        return new RefundResponse(
                updated.getPaymentId(),
                refundId,
                MoneyView.of(refundAmount),
                MoneyView.of(updated.refunded()),
                MoneyView.of(updated.refundableRemaining()),
                updated.getStatus(),
                Instant.now());
    }

    // ------------------------------------------------------------ internals

    private WalletClient.WalletView resolveDestination(CreatePaymentRequest request,
                                                       String currency) {
        if (request.destinationWalletId() != null && !request.destinationWalletId().isBlank()) {
            return walletClient.getWallet(request.destinationWalletId());
        }
        if (request.destinationUserId() != null && !request.destinationUserId().isBlank()) {
            return walletClient.getWalletByUser(request.destinationUserId(), currency);
        }
        throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                "Either destinationWalletId or destinationUserId must be provided");
    }

    private void validateTransfer(WalletClient.WalletView source,
                                  WalletClient.WalletView destination, Money amount) {
        if (source.walletId().equals(destination.walletId())) {
            // Would net to zero while still producing ledger entries and notifications.
            throw new BadRequestException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED,
                    "A wallet cannot pay itself");
        }
        if (!source.currency().equals(destination.currency())) {
            // Cross-currency needs an explicit, rate-stamped conversion. Silently
            // transferring between mismatched currencies would corrupt both balances.
            throw new BadRequestException(ErrorCode.UNSUPPORTED_CURRENCY,
                    "Cross-currency transfers are not supported: source is "
                            + source.currency() + ", destination is " + destination.currency());
        }
        if (!source.canSpend()) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "The source wallet is " + source.status() + " and cannot send funds");
        }

        // Checked here for a fast, clear rejection. wallet-service re-checks under a row
        // lock during reserve, and that is the authoritative decision — this balance is
        // already stale by the time it arrives.
        if (source.availableMinor() < amount.amountMinor()) {
            throw new InsufficientFundsException(
                    source.walletId(), source.available(), amount);
        }
    }

    /**
     * Builds the immutable risk context.
     *
     * <p>The IP address comes from the gateway's observed value, never from the request
     * body: a client-supplied IP would let an attacker forge their apparent location and
     * defeat every geo-based fraud rule.
     */
    private RiskSignals buildRiskSignals(RiskContext context, String observedIp,
                                         String userAgent) {
        if (context == null) {
            return new RiskSignals(null, null, observedIp, userAgent,
                    null, null, null, null, Channel.UNKNOWN, null, null);
        }
        return new RiskSignals(
                context.deviceId(),
                context.deviceFingerprint(),
                observedIp,
                userAgent,
                context.countryCode() == null ? null : context.countryCode().toUpperCase(),
                context.city(),
                context.latitude(),
                context.longitude(),
                context.channel() == null ? Channel.UNKNOWN : context.channel(),
                context.merchantId(),
                context.merchantCategory());
    }

    /**
     * Both parties may read a payment; nobody else may.
     *
     * <p>Reported as not-found rather than forbidden, because confirming that a payment
     * exists between two other people is itself a disclosure.
     */
    private void requireParticipant(Payment payment, String requestingUserId) {
        boolean participant = requestingUserId.equals(payment.getInitiatorUserId())
                || requestingUserId.equals(payment.getDestinationUserId());
        if (!participant) {
            log.warn("User {} attempted to read payment {} they are not party to",
                    requestingUserId, payment.getPaymentId());
            throw new NotFoundException(ErrorCode.PAYMENT_NOT_FOUND,
                    "Payment " + payment.getPaymentId() + " was not found");
        }
    }

    /** Opaque, random payment ID. Non-sequential so payment volume cannot be inferred. */
    private String generatePaymentId() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder("PAY-");
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString().toUpperCase();
    }

    /** Human-facing reference, shorter than the payment ID so it can be read aloud. */
    private String generateTransactionId() {
        byte[] bytes = new byte[6];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder("TXN");
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString().toUpperCase();
    }
}
