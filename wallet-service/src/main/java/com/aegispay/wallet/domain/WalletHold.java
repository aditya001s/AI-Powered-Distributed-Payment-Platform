package com.aegispay.wallet.domain;

import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A reservation of funds against a wallet, belonging to exactly one payment.
 *
 * <p><strong>Why a row per reservation rather than a counter.</strong> If the wallet
 * only carried a "reserved amount" number, capturing payment A would have to trust
 * that the number still represents A's reservation. Under concurrency it does not:
 * two payments reserving and completing in different orders corrupt the total, and
 * nothing in the data can detect it afterwards. A row per hold means capture and
 * release always act on a specific, identified amount.
 *
 * <p><strong>One hold per payment</strong> is enforced by a unique constraint on
 * {@code payment_id}. That makes {@code reserve} idempotent by construction: a
 * retried reservation cannot create a second hold, so a network retry can never
 * reserve the amount twice.
 *
 * <p><strong>Expiry is mandatory.</strong> A reservation with no expiry turns one
 * crashed orchestrator into permanently frozen customer money. The sweeper releases
 * anything still ACTIVE past its expiry, so the worst case is a delayed release
 * rather than a stuck balance.
 */
@Entity
@Table(name = "wallet_holds")
public class WalletHold {

    public enum State {
        /** Funds reserved and unspendable. */
        ACTIVE,
        /** Converted into a real debit. Terminal. */
        CAPTURED,
        /** Returned to spendable without moving money. Terminal. */
        RELEASED
    }

    /**
     * Default reservation lifetime.
     *
     * <p>Comfortably longer than the payment saga's own timeout budget, so a
     * reservation is never swept out from under a payment that is still legitimately
     * in progress, but short enough that a crash frees the money in minutes rather
     * than days.
     */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    /**
     * Hard ceiling on any single extension.
     *
     * <p>Extension exists so a fraud hold can outlive the saga's fifteen minutes while an analyst
     * reviews the payment. It must not become a way to freeze a customer's money indefinitely: a
     * reservation with no effective bound is the failure mode {@link #expiresAt} exists to prevent,
     * and it would arrive by extension rather than by omission.
     *
     * <p>Seven days is chosen to be longer than any realistic review — including a weekend and a
     * public holiday — while still guaranteeing that a forgotten alert releases the funds rather
     * than holding them forever. An extension can be reapplied, so the ceiling bounds how long a
     * hold survives <em>without anyone touching it</em>, which is the property that matters.
     */
    public static final Duration MAX_EXTENSION = Duration.ofDays(7);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "hold_id", nullable = false, unique = true, updatable = false, length = 48)
    private String holdId;

    @Column(name = "wallet_id", nullable = false, updatable = false, length = 40)
    private String walletId;

    /** Unique: one reservation per payment, which is what makes reserve idempotent. */
    @Column(name = "payment_id", nullable = false, unique = true, updatable = false, length = 48)
    private String paymentId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state = State.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected WalletHold() {
        // JPA
    }

    public static WalletHold create(String walletId, String paymentId, Money amount, Duration ttl) {
        WalletHold hold = new WalletHold();
        hold.id = UUID.randomUUID();
        hold.holdId = "HLD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
        hold.walletId = walletId;
        hold.paymentId = paymentId;
        hold.amountMinor = amount.amountMinor();
        hold.currency = amount.currency();
        hold.state = State.ACTIVE;
        hold.createdAt = Instant.now();
        hold.expiresAt = hold.createdAt.plus(ttl == null ? DEFAULT_TTL : ttl);
        return hold;
    }

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    public boolean isExpired() {
        return state == State.ACTIVE && Instant.now().isAfter(expiresAt);
    }

    /**
     * Marks the reservation captured.
     *
     * <p>Rejects any non-ACTIVE state. Combined with the wallet's own
     * {@code captureHold} guard, this makes double-capture — charging the customer
     * twice for one payment — impossible even if the saga replays.
     */
    public void capture() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException(
                    "Hold " + holdId + " is " + state + " and cannot be captured");
        }
        this.state = State.CAPTURED;
        this.capturedAt = Instant.now();
    }

    /**
     * Pushes the expiry out, so the reservation survives longer than the saga's own budget.
     *
     * <p><strong>Why this exists.</strong> The default lifetime is sized for a payment that runs to
     * completion in seconds. A fraud HOLD is different: the payment stops and waits for a human, and
     * fifteen minutes later the sweeper would release the reservation while the saga still believes
     * the funds are reserved. The customer could then spend the money, and the analyst releasing the
     * payment would capture a reservation that no longer covers it — the capture would fail, or
     * worse, succeed against a balance that has since moved.
     *
     * <p><strong>Never shortens.</strong> A shorter expiry could sweep a reservation a saga is still
     * relying on, and the caller asking for one is almost certainly passing a default it did not mean
     * to. Returning the existing expiry unchanged is the safe reading of an ambiguous request.
     *
     * <p><strong>Only while ACTIVE.</strong> Extending a captured hold would suggest the funds are
     * still reserved when they have already moved; extending a released one would suggest they are
     * unspendable when the customer can spend them. Both are lies about the balance.
     *
     * @param ttl how long from now the reservation should live, capped at {@link #MAX_EXTENSION}
     * @return the new expiry
     */
    public Instant extendBy(Duration ttl) {
        if (state != State.ACTIVE) {
            throw new IllegalStateException(
                    "Hold " + holdId + " is " + state + " and cannot be extended");
        }
        Duration granted = ttl == null || ttl.compareTo(MAX_EXTENSION) > 0 ? MAX_EXTENSION : ttl;
        Instant candidate = Instant.now().plus(granted);

        if (candidate.isAfter(expiresAt)) {
            this.expiresAt = candidate;
        }
        return this.expiresAt;
    }

    public void release() {
        if (state == State.CAPTURED) {
            // Releasing captured funds would credit money back that was legitimately
            // taken, so it is refused outright.
            throw new IllegalStateException(
                    "Hold " + holdId + " was already captured and cannot be released");
        }
        if (state == State.RELEASED) {
            // Idempotent: a repeated release is a no-op rather than an error, because
            // compensation paths legitimately run more than once.
            return;
        }
        this.state = State.RELEASED;
        this.releasedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getHoldId() {
        return holdId;
    }

    public String getWalletId() {
        return walletId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public State getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    @Override
    public String toString() {
        return "WalletHold{" + holdId + ", wallet=" + walletId + ", " + amount() + ", " + state + '}';
    }
}
