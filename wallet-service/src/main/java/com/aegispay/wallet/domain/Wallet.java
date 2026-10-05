package com.aegispay.wallet.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A customer's balance in a single currency.
 *
 * <p><strong>Accounting orientation.</strong> A wallet balance is a
 * <em>liability</em> of the platform: the money belongs to the customer and the
 * platform owes it to them. That is why the ledger credits a wallet account to
 * increase it. Modelling wallets as an asset is the single most common accounting
 * error in payment systems and it inverts every posting downstream.
 *
 * <p><strong>Two amounts, not one.</strong> {@code balanceMinor} is everything the
 * customer owns; {@code heldMinor} is the part reserved by payments that are still
 * in flight. Spendable money is the difference. Reservations are tracked separately
 * rather than by debiting early, so a customer never sees a phantom
 * debit-and-refund pair for a payment that was abandoned.
 *
 * <p><strong>Concurrency.</strong> Correctness comes from the database: callers load
 * the row with {@code SELECT ... FOR UPDATE} so concurrent operations on one wallet
 * serialise. {@link Version} is a second line of defence for any path that reads
 * without a lock. The Redis distributed lock in the service layer is only an
 * optimisation to reduce lock contention, never the source of correctness — see
 * {@link com.aegispay.common.lock.DistributedLock}.
 *
 * <p>All mutators are guarded and throw rather than clamping. A silent clamp is how
 * a balance bug becomes invisible; an exception is how it becomes a bug report.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    public enum Status {
        /** Normal. Can send and receive. */
        ACTIVE,
        /**
         * Frozen by fraud controls or an administrator. Cannot send, but CAN still
         * receive: refusing incoming credits would bounce a legitimate refund owed
         * to the customer, and would let a fraud hold cost them money.
         */
        FROZEN,
        /** Closed permanently. No movement in either direction. */
        CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "wallet_id", nullable = false, unique = true, updatable = false, length = 40)
    private String walletId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 40)
    private String userId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "held_minor", nullable = false)
    private long heldMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Wallet() {
        // JPA
    }

    public static Wallet open(String walletId, String userId, String currency) {
        Wallet wallet = new Wallet();
        wallet.walletId = walletId;
        wallet.userId = userId;
        wallet.currency = currency.toUpperCase();
        wallet.balanceMinor = 0L;
        wallet.heldMinor = 0L;
        wallet.status = Status.ACTIVE;
        wallet.createdAt = Instant.now();
        wallet.updatedAt = wallet.createdAt;
        return wallet;
    }

    // ------------------------------------------------------------------ queries

    public Money balance() {
        return Money.ofMinor(balanceMinor, currency);
    }

    public Money held() {
        return Money.ofMinor(heldMinor, currency);
    }

    /** What the customer can actually spend right now. */
    public Money available() {
        return Money.ofMinor(availableMinor(), currency);
    }

    public long availableMinor() {
        return balanceMinor - heldMinor;
    }

    public boolean canSpend(long amountMinor) {
        return status == Status.ACTIVE && availableMinor() >= amountMinor;
    }

    public boolean canReceive() {
        // FROZEN wallets accept credits on purpose; see Status.FROZEN.
        return status != Status.CLOSED;
    }

    // ----------------------------------------------------------------- mutators

    /**
     * Credits the wallet.
     *
     * <p>Permitted while FROZEN so that refunds and reversals owed to the customer
     * are never bounced.
     */
    public void credit(Money amount, String reason) {
        requireSameCurrency(amount);
        requirePositive(amount);
        if (!canReceive()) {
            throw notUsable("receive funds (" + reason + ")");
        }
        // addExact rather than +: an overflow must surface as an exception, not as a
        // wrapped-around negative balance.
        this.balanceMinor = Math.addExact(this.balanceMinor, amount.amountMinor());
        touch();
    }

    /**
     * Debits the wallet directly, without a prior reservation.
     *
     * <p>Used for withdrawals. Checks against <em>available</em> funds, not total
     * balance, so a withdrawal cannot spend money already promised to an in-flight
     * payment.
     */
    public void debit(Money amount, String reason) {
        requireSameCurrency(amount);
        requirePositive(amount);
        requireActive(reason);

        if (availableMinor() < amount.amountMinor()) {
            throw new InsufficientFundsException(walletId, available(), amount);
        }
        this.balanceMinor = Math.subtractExact(this.balanceMinor, amount.amountMinor());
        touch();
    }

    /**
     * Reserves funds for an in-flight payment.
     *
     * <p>Increases {@code heldMinor} and leaves {@code balanceMinor} untouched, so
     * the money is unspendable but still visibly the customer's.
     */
    public void hold(Money amount) {
        requireSameCurrency(amount);
        requirePositive(amount);
        requireActive("reserve funds");

        if (availableMinor() < amount.amountMinor()) {
            throw new InsufficientFundsException(walletId, available(), amount);
        }
        this.heldMinor = Math.addExact(this.heldMinor, amount.amountMinor());
        touch();
    }

    /**
     * Converts a reservation into an actual debit.
     *
     * <p>Both columns move together: the balance goes down and the hold is
     * discharged. Doing these in separate steps would leave a window where the
     * customer's available balance was briefly overstated.
     *
     * <p>Deliberately does <em>not</em> re-check available funds. The money was
     * already reserved, and re-checking would reject a legitimate capture whenever
     * another payment happened to reserve the remainder in the meantime.
     */
    public void captureHold(Money amount) {
        requireSameCurrency(amount);
        requirePositive(amount);
        if (status == Status.CLOSED) {
            throw notUsable("capture a reservation");
        }
        if (heldMinor < amount.amountMinor()) {
            // Would corrupt the held total. Indicates a saga bug: capturing more
            // than was reserved.
            throw new IllegalStateException(
                    "Cannot capture " + amount + " from wallet " + walletId
                            + ": only " + held() + " is reserved");
        }
        this.heldMinor = Math.subtractExact(this.heldMinor, amount.amountMinor());
        this.balanceMinor = Math.subtractExact(this.balanceMinor, amount.amountMinor());
        touch();
    }

    /**
     * Releases a reservation without moving money, returning the funds to
     * spendable. Called when a payment is rejected, fails, or its hold expires.
     */
    public void releaseHold(Money amount) {
        requireSameCurrency(amount);
        requirePositive(amount);
        if (heldMinor < amount.amountMinor()) {
            throw new IllegalStateException(
                    "Cannot release " + amount + " on wallet " + walletId
                            + ": only " + held() + " is reserved");
        }
        this.heldMinor = Math.subtractExact(this.heldMinor, amount.amountMinor());
        touch();
    }

    public void freeze() {
        if (status == Status.CLOSED) {
            throw notUsable("be frozen");
        }
        this.status = Status.FROZEN;
        touch();
    }

    public void unfreeze() {
        if (status == Status.FROZEN) {
            this.status = Status.ACTIVE;
            touch();
        }
    }

    /**
     * Closes the wallet.
     *
     * <p>Refuses while any money remains, held or otherwise. Closing a wallet with a
     * balance would orphan customer funds with no route to recover them.
     */
    public void close() {
        if (balanceMinor != 0L || heldMinor != 0L) {
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Wallet " + walletId + " cannot be closed while it holds "
                            + balance() + " (" + held() + " reserved)");
        }
        this.status = Status.CLOSED;
        touch();
    }

    // ------------------------------------------------------------------ helpers

    private void requireActive(String operation) {
        if (status != Status.ACTIVE) {
            throw notUsable(operation);
        }
    }

    /**
     * A refusal caused by the wallet's <em>status</em>.
     *
     * <p>Deliberately a {@link ConflictException} and not an {@code IllegalStateException}.
     * FROZEN is a routine operational state — fraud controls put wallets there every day —
     * so "your wallet is frozen" is a business outcome the customer is entitled to be told
     * about, and it must not be retried. An {@code IllegalStateException} falls through to
     * the global handler's catch-all, which by design reveals nothing and returns 500: that
     * would page an on-call engineer for a working fraud control, and leave the customer
     * with an opaque error and a correlation ID.
     *
     * <p>The genuinely-impossible cases below keep throwing {@code IllegalStateException}
     * on purpose. Capturing more than was reserved, or releasing more than is held, means
     * the saga is broken rather than the request; a 500 and a stack trace is exactly the
     * right response to that.
     */
    private ConflictException notUsable(String operation) {
        return new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                "Wallet " + walletId + " is " + status + " and cannot " + operation);
    }

    private void requireSameCurrency(Money amount) {
        if (!currency.equals(amount.currency())) {
            throw new IllegalArgumentException(
                    "Wallet " + walletId + " is denominated in " + currency
                            + " but the amount is " + amount.currency());
        }
    }

    private void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be positive, got " + amount);
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getWalletId() {
        return walletId;
    }

    public String getUserId() {
        return userId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public long getHeldMinor() {
        return heldMinor;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "Wallet{" + walletId + ", " + balance() + ", held=" + held() + ", " + status + '}';
    }
}
