package com.aegispay.wallet.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.WalletMovementPayload;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.money.Money;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.common.outbox.OutboxRecorder;
import com.aegispay.wallet.api.WalletDtos.CaptureResponse;
import com.aegispay.wallet.api.WalletDtos.HoldResponse;
import com.aegispay.wallet.api.WalletDtos.MovementResponse;
import com.aegispay.wallet.api.WalletDtos.MoneyView;
import com.aegispay.wallet.api.WalletDtos.TransactionResponse;
import com.aegispay.wallet.api.WalletDtos.WalletResponse;
import com.aegispay.wallet.domain.Wallet;
import com.aegispay.wallet.domain.WalletHold;
import com.aegispay.wallet.domain.WalletTransaction;
import com.aegispay.wallet.repository.WalletHoldRepository;
import com.aegispay.wallet.repository.WalletRepository;
import com.aegispay.wallet.repository.WalletTransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every transaction that mutates a balance.
 *
 * <p><strong>Why this is a separate bean from {@link WalletService}.</strong> Spring
 * applies transaction advice through a proxy, so a {@code @Transactional} method invoked
 * as {@code this.method()} from within the same class is <em>silently untransacted</em>.
 * {@link WalletService} is non-transactional because it orchestrates idempotency around
 * these calls, so the transactional work has to live behind a real bean boundary for the
 * annotations to take effect at all. (The auth service learned this the hard way: a
 * private self-called method meant refresh-token reuse detection revoked nothing.)
 *
 * <h2>Locking</h2>
 *
 * <p>Every method loads its wallet rows with {@code SELECT ... FOR UPDATE}, so concurrent
 * operations on one wallet serialise and a read-modify-write cannot lose an update.
 *
 * <p>Multi-wallet operations lock in a <strong>globally consistent order</strong> — wallet
 * IDs sorted lexicographically. Without that rule, a transfer A&rarr;B and a simultaneous
 * transfer B&rarr;A each hold the lock the other needs and both block until the database
 * kills one as a deadlock victim. Sorting means every caller acquires locks in the same
 * sequence, so a cycle cannot form. This is the whole reason
 * {@link #captureAndTransfer} does its own sorting rather than locking source then
 * destination in the obvious order.
 *
 * <h2>Transaction hygiene</h2>
 *
 * <p>These methods contain no network calls and no password hashing — only database work.
 * Every one is a handful of statements, so wallet row locks are held for microseconds
 * rather than across a remote call.
 */
@Service
public class WalletMutationService {

    private static final Logger log = LoggerFactory.getLogger(WalletMutationService.class);

    private static final String SOURCE = "wallet-service";
    private static final String AGGREGATE = "Wallet";

    private final WalletRepository wallets;
    private final WalletHoldRepository holds;
    private final WalletTransactionRepository transactions;
    private final OutboxRecorder outbox;
    private final MeterRegistry meterRegistry;

    public WalletMutationService(WalletRepository wallets,
                                 WalletHoldRepository holds,
                                 WalletTransactionRepository transactions,
                                 OutboxRecorder outbox,
                                 MeterRegistry meterRegistry) {
        this.wallets = wallets;
        this.holds = holds;
        this.transactions = transactions;
        this.outbox = outbox;
        this.meterRegistry = meterRegistry;
    }

    // ------------------------------------------------------- funds movement

    @Transactional
    public MovementResponse deposit(String walletId, String ownerUserId,
                                    Money amount, String description) {
        return deposit(walletId, ownerUserId, amount, description, null, null);
    }

    @Transactional
    public MovementResponse deposit(String walletId, String ownerUserId,
                                    Money amount, String description,
                                    String operationKey, String operationFingerprint) {
        Wallet wallet = lockWallet(walletId);
        requireOwnership(wallet, ownerUserId);

        Optional<MovementResponse> replay = completedHttpMovement(
                wallet, operationKey, operationFingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        wallet.credit(amount, "deposit");
        WalletTransaction tx = record(wallet, PostingDirection.CREDIT, amount,
                WalletTransaction.Type.DEPOSIT, null, operationKey, operationFingerprint,
                description == null ? "Deposit" : description);

        recordEdgeMovement(wallet, tx, "DEPOSIT");

        log.info("Deposited {} into wallet {}; balance now {}", amount, walletId, wallet.balance());
        return new MovementResponse(TransactionResponse.from(tx), WalletResponse.from(wallet));
    }

    @Transactional
    public MovementResponse withdraw(String walletId, String ownerUserId,
                                     Money amount, String description) {
        return withdraw(walletId, ownerUserId, amount, description, null, null);
    }

    @Transactional
    public MovementResponse withdraw(String walletId, String ownerUserId,
                                     Money amount, String description,
                                     String operationKey, String operationFingerprint) {
        Wallet wallet = lockWallet(walletId);
        requireOwnership(wallet, ownerUserId);

        Optional<MovementResponse> replay = completedHttpMovement(
                wallet, operationKey, operationFingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            // Checks AVAILABLE funds, so a withdrawal cannot spend money already
            // reserved by an in-flight payment.
            wallet.debit(amount, "withdrawal");
        } catch (InsufficientFundsException ex) {
            meterRegistry.counter(MetricNames.WALLET_INSUFFICIENT_FUNDS,
                    "operation", "withdrawal").increment();
            throw ex;
        }

        WalletTransaction tx = record(wallet, PostingDirection.DEBIT, amount,
                WalletTransaction.Type.WITHDRAWAL, null, operationKey, operationFingerprint,
                description == null ? "Withdrawal" : description);

        recordEdgeMovement(wallet, tx, "WITHDRAWAL");

        log.info("Withdrew {} from wallet {}; balance now {}", amount, walletId, wallet.balance());
        return new MovementResponse(TransactionResponse.from(tx), WalletResponse.from(wallet));
    }

    // ----------------------------------------------------------- reservations

    /**
     * Reserves funds for a payment.
     *
     * <p><strong>Idempotent by construction.</strong> A hold row is unique per
     * {@code paymentId}, so a retried reservation returns the existing hold instead of
     * reserving the amount again. That is checked explicitly first for a clean response,
     * and enforced by the constraint if two retries race.
     *
     * <p>Reserving does not move money: it raises {@code held_minor} and leaves
     * {@code balance_minor} alone, so the customer still sees the funds as theirs while
     * being unable to spend them twice.
     */
    @Transactional
    public HoldResponse reserve(String paymentId, String walletId, Money amount, Duration ttl) {
        Optional<WalletHold> existing = holds.findByPaymentIdForUpdate(paymentId);
        if (existing.isPresent()) {
            WalletHold hold = existing.get();
            if (hold.getAmountMinor() != amount.amountMinor()) {
                // Same payment, different amount: a caller bug. Silently returning the
                // original hold would let a payment settle for an amount nobody
                // reserved.
                throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                        "Payment " + paymentId + " already reserved "
                                + hold.amount() + ", cannot re-reserve " + amount);
            }
            log.debug("Reservation for payment {} already exists; returning it", paymentId);
            return HoldResponse.of(hold, requireWallet(walletId));
        }

        Wallet wallet = lockWallet(walletId);

        try {
            wallet.hold(amount);
        } catch (InsufficientFundsException ex) {
            meterRegistry.counter(MetricNames.WALLET_INSUFFICIENT_FUNDS,
                    "operation", "reserve").increment();
            throw ex;
        }

        WalletHold hold = WalletHold.create(walletId, paymentId, amount, ttl);
        holds.save(hold);
        wallets.save(wallet);

        log.info("Reserved {} on wallet {} for payment {}; available now {}",
                amount, walletId, paymentId, wallet.available());
        return HoldResponse.of(hold, wallet);
    }

    /**
     * Captures a reservation and credits the destination — one atomic transfer.
     *
     * <p>Debit and credit are deliberately a <strong>single transaction</strong>. Splitting
     * them into two calls would leave a window in which the money existed in neither
     * wallet, and a crash inside that window would destroy customer funds with no record
     * of where they went. This is also why the ledger's debit==credit invariant
     * (Phase 4) is checked before a posting is accepted rather than after.
     *
     * <p>Locks both wallets in sorted ID order to make deadlock impossible; see the class
     * documentation.
     *
     * <p>Idempotent: a hold already {@code CAPTURED} short-circuits, so a saga replay
     * cannot transfer twice.
     */
    @Transactional
    public CaptureResponse captureAndTransfer(String paymentId, String sourceWalletId,
                                              String destinationWalletId, String description) {
        if (sourceWalletId.equals(destinationWalletId)) {
            throw new ConflictException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED,
                    "Source and destination wallets must differ");
        }

        WalletHold hold = holds.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No reservation exists for payment " + paymentId));

        if (hold.getState() == WalletHold.State.CAPTURED) {
            // Replay. Return the movements already recorded rather than transferring
            // again.
            log.debug("Payment {} was already captured; returning the original outcome", paymentId);
            return describeExistingCapture(paymentId, hold, sourceWalletId, destinationWalletId);
        }
        if (hold.getState() == WalletHold.State.RELEASED) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "The reservation for payment " + paymentId
                            + " was released and cannot be captured");
        }
        if (!hold.getWalletId().equals(sourceWalletId)) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Payment " + paymentId + " reserved funds on wallet " + hold.getWalletId()
                            + ", not " + sourceWalletId);
        }

        // Deadlock avoidance: acquire both locks in a globally consistent order.
        List<Wallet> locked = lockInOrder(sourceWalletId, destinationWalletId);
        Wallet source = locked.stream()
                .filter(w -> w.getWalletId().equals(sourceWalletId)).findFirst().orElseThrow();
        Wallet destination = locked.stream()
                .filter(w -> w.getWalletId().equals(destinationWalletId)).findFirst().orElseThrow();

        Money amount = hold.amount();
        if (!destination.canReceive()) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Destination wallet " + destinationWalletId + " cannot receive funds");
        }

        // Both sides in one transaction: money is never in flight between them.
        source.captureHold(amount);
        destination.credit(amount, "transfer in");
        hold.capture();

        WalletTransaction debit = record(source, PostingDirection.DEBIT, amount,
                WalletTransaction.Type.TRANSFER_OUT, paymentId,
                description == null ? "Transfer out" : description);
        WalletTransaction credit = record(destination, PostingDirection.CREDIT, amount,
                WalletTransaction.Type.TRANSFER_IN, paymentId,
                description == null ? "Transfer in" : description);

        wallets.save(source);
        wallets.save(destination);
        holds.save(hold);

        log.info("Captured {} for payment {}: {} -> {}",
                amount, paymentId, sourceWalletId, destinationWalletId);

        return new CaptureResponse(paymentId, hold.getHoldId(), MoneyView.of(amount),
                sourceWalletId, destinationWalletId,
                debit.getTransactionId(), credit.getTransactionId(), Instant.now());
    }

    /**
     * Releases a reservation without moving money.
     *
     * <p>The compensating action for a rejected or failed payment. Idempotent: releasing
     * an already-released hold is a no-op, because compensation paths legitimately run
     * more than once. Releasing a <em>captured</em> hold is refused outright — that would
     * hand back money that was legitimately taken.
     */
    @Transactional
    public HoldResponse release(String paymentId, String reason) {
        WalletHold hold = holds.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No reservation exists for payment " + paymentId));

        if (hold.getState() == WalletHold.State.RELEASED) {
            log.debug("Reservation for payment {} was already released", paymentId);
            return HoldResponse.of(hold, requireWallet(hold.getWalletId()));
        }
        if (hold.getState() == WalletHold.State.CAPTURED) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "The reservation for payment " + paymentId
                            + " was captured and cannot be released");
        }

        Wallet wallet = lockWallet(hold.getWalletId());
        wallet.releaseHold(hold.amount());
        hold.release();

        wallets.save(wallet);
        holds.save(hold);

        log.info("Released reservation of {} on wallet {} for payment {}: {}",
                hold.amount(), wallet.getWalletId(), paymentId, reason);
        return HoldResponse.of(hold, wallet);
    }

    /**
     * Extends a reservation's lifetime.
     *
     * <p><strong>Why a payment ever needs this.</strong> The default fifteen minutes is sized for a
     * saga that runs to completion. A payment held for fraud review does not: it stops and waits for
     * a human. Without an extension the sweeper would release the reservation while the saga still
     * believed the funds were held — the customer could spend the money, and the analyst releasing
     * the payment would capture against a balance that had moved underneath them.
     *
     * <p>No balance changes here, so no wallet lock is needed for correctness. The hold row is loaded
     * for update all the same: extension is read-modify-write on {@code expires_at}, and a concurrent
     * release must not interleave with it and leave a released hold carrying a future expiry.
     *
     * <p>Idempotent and monotonic. Extending twice is harmless, and a shorter request never shortens
     * an existing expiry — see {@link WalletHold#extendBy}.
     */
    @Transactional
    public HoldResponse extend(String paymentId, Duration ttl, String reason) {
        WalletHold hold = holds.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No reservation exists for payment " + paymentId));

        if (hold.getState() != WalletHold.State.ACTIVE) {
            // Reported as a conflict rather than silently accepted: the caller is about to wait on a
            // reservation that is not there, and it needs to know now rather than at capture time.
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "The reservation for payment " + paymentId + " is " + hold.getState()
                            + " and cannot be extended");
        }

        Instant expiresAt = hold.extendBy(ttl);
        holds.save(hold);

        log.info("Extended reservation {} on wallet {} (payment {}) until {}: {}",
                hold.getHoldId(), hold.getWalletId(), paymentId, expiresAt, reason);
        return HoldResponse.of(hold, requireWallet(hold.getWalletId()));
    }

    /**
     * Releases one expired reservation.
     *
     * <p>Each expiry is its own transaction so the sweeper cannot hold locks on many
     * wallets at once and stall live payments. Called per hold by the scheduled sweeper.
     */
    @Transactional
    public boolean releaseExpired(String paymentId) {
        Optional<WalletHold> maybeHold = holds.findByPaymentIdForUpdate(paymentId);
        if (maybeHold.isEmpty() || !maybeHold.get().isActive()) {
            return false;
        }
        WalletHold hold = maybeHold.get();
        Wallet wallet = lockWallet(hold.getWalletId());

        wallet.releaseHold(hold.amount());
        hold.release();
        wallets.save(wallet);
        holds.save(hold);

        log.warn("Released EXPIRED reservation {} of {} on wallet {} (payment {}). "
                        + "The payment saga did not complete within the reservation window.",
                hold.getHoldId(), hold.amount(), wallet.getWalletId(), paymentId);
        return true;
    }

    // ---------------------------------------------------------- administration

    @Transactional
    public WalletResponse freeze(String walletId) {
        Wallet wallet = lockWallet(walletId);
        wallet.freeze();
        wallets.save(wallet);
        return WalletResponse.from(wallet);
    }

    @Transactional
    public WalletResponse unfreeze(String walletId) {
        Wallet wallet = lockWallet(walletId);
        wallet.unfreeze();
        wallets.save(wallet);
        return WalletResponse.from(wallet);
    }

    // ------------------------------------------------------------- internals

    /**
     * Locks several wallets in a globally consistent order.
     *
     * <p>Sorting by wallet ID is what makes deadlock impossible: every caller in the
     * system acquires locks in the same sequence, so no two transactions can each hold
     * what the other needs.
     */
    private List<Wallet> lockInOrder(String... walletIds) {
        return java.util.Arrays.stream(walletIds)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .map(this::lockWallet)
                .toList();
    }

    private Wallet lockWallet(String walletId) {
        return wallets.findByWalletIdForUpdate(walletId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                        "Wallet " + walletId + " was not found"));
    }

    private Wallet requireWallet(String walletId) {
        return wallets.findByWalletId(walletId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                        "Wallet " + walletId + " was not found"));
    }

    /**
     * Reports as not-found rather than forbidden when a caller targets someone else's
     * wallet: confirming that a wallet exists but belongs to another user is itself an
     * information leak.
     */
    private void requireOwnership(Wallet wallet, String ownerUserId) {
        if (ownerUserId != null && !wallet.getUserId().equals(ownerUserId)) {
            log.warn("User {} attempted to move funds on wallet {} owned by {}",
                    ownerUserId, wallet.getWalletId(), wallet.getUserId());
            throw new NotFoundException(ErrorCode.WALLET_NOT_FOUND,
                    "Wallet " + wallet.getWalletId() + " was not found");
        }
    }

    private Optional<MovementResponse> completedHttpMovement(
            Wallet wallet, String operationKey, String operationFingerprint) {
        if (operationKey == null) {
            return Optional.empty();
        }
        return transactions.findByOperationKey(operationKey).map(existing -> {
            if (!java.util.Objects.equals(existing.getOperationFingerprint(), operationFingerprint)) {
                throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key was already used with a different request");
            }
            log.warn("Recovered completed wallet operation {} from its movement row after the "
                    + "idempotency response record was unavailable", operationKey);
            return new MovementResponse(TransactionResponse.from(existing), WalletResponse.from(wallet));
        });
    }

    private WalletTransaction record(Wallet wallet, PostingDirection direction, Money amount,
                                     WalletTransaction.Type type, String referenceId,
                                     String description) {
        return record(wallet, direction, amount, type, referenceId, null, null, description);
    }

    private WalletTransaction record(Wallet wallet, PostingDirection direction, Money amount,
                                     WalletTransaction.Type type, String referenceId,
                                     String operationKey, String operationFingerprint,
                                     String description) {
        WalletTransaction tx = WalletTransaction.record(
                wallet, direction, amount, type, referenceId, operationKey,
                operationFingerprint, description);
        return transactions.save(tx);
    }

    /**
     * Announces a movement that crossed the platform boundary, for ledger-service to book.
     *
     * <p>Recorded on the outbox inside the balance-changing transaction, so "the balance
     * changed" and "the world was told" are one commit. Publishing to Kafka directly would
     * either lose the event when the process died after commit, or announce a movement that
     * then rolled back — and the ledger would be permanently wrong either way, in opposite
     * directions.
     *
     * <p><strong>Only deposits and withdrawals.</strong> A payment's transfer legs are
     * deliberately not announced here: {@code payment-completed} already describes them
     * fully, and emitting them from both services would book every transfer twice. That is
     * the most likely way to corrupt a ledger fed from more than one stream, so the boundary
     * is drawn on ownership — wallet-service owns movements at the platform's edge, where
     * the counterparty is outside the system and only this service knows they happened;
     * payment-service owns movements between two wallets inside it.
     *
     * <p>The wallet transaction ID is the event's identity, and ledger-service derives its
     * posting's idempotency key from it. A redelivered event therefore produces the same key
     * and is recognised as a replay rather than booked again.
     */
    private void recordEdgeMovement(Wallet wallet, WalletTransaction tx, String movementType) {
        WalletMovementPayload payload = new WalletMovementPayload(
                tx.getTransactionId(),
                wallet.getWalletId(),
                wallet.getUserId(),
                tx.getDirection(),
                tx.amount(),
                tx.balanceAfter(),
                movementType,
                tx.getDescription(),
                tx.getCreatedAt());

        // MANDATORY propagation on the recorder means this would throw if it were ever
        // called outside a transaction, so the atomicity is enforced rather than documented.
        outbox.record(KafkaTopics.WALLET_MOVEMENT_RECORDED, AGGREGATE, wallet.getWalletId(),
                EventEnvelope.of(KafkaTopics.WALLET_MOVEMENT_RECORDED, AGGREGATE,
                        wallet.getWalletId(), SOURCE, payload));
    }

    /** Rebuilds the response for an already-captured payment, so a replay is truthful. */
    private CaptureResponse describeExistingCapture(String paymentId, WalletHold hold,
                                                    String sourceWalletId,
                                                    String destinationWalletId) {
        List<WalletTransaction> recorded = transactions.findByReferenceIdOrderByCreatedAtAsc(paymentId);
        String debitId = recorded.stream()
                .filter(t -> t.getDirection() == PostingDirection.DEBIT)
                .map(WalletTransaction::getTransactionId).findFirst().orElse(null);
        String creditId = recorded.stream()
                .filter(t -> t.getDirection() == PostingDirection.CREDIT)
                .map(WalletTransaction::getTransactionId).findFirst().orElse(null);

        return new CaptureResponse(paymentId, hold.getHoldId(), MoneyView.of(hold.amount()),
                sourceWalletId, destinationWalletId, debitId, creditId, hold.getCapturedAt());
    }
}
