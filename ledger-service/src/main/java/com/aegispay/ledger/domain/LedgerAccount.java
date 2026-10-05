package com.aegispay.ledger.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
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
 * One account in the chart of accounts.
 *
 * <p><strong>Direction depends on type, and this is where most ledger bugs live.</strong>
 * A debit increases an asset but decreases a liability. Customer wallet balances are a
 * LIABILITY: the money belongs to the customer and the platform owes it to them, so a
 * credit increases a wallet. Modelling wallets as an asset inverts every posting
 * downstream, and the mistake stays invisible until someone tries to reconcile.
 *
 * <p><strong>Two representations of the same facts.</strong> {@code debitTotalMinor} and
 * {@code creditTotalMinor} are the primitive, direction-neutral totals; the trial balance
 * is stated in those terms so it does not depend on per-type sign rules at all.
 * {@code balanceMinor} is the derived natural-direction balance, which is what a human
 * wants to read. The database enforces the relationship between them with a CHECK
 * constraint, so a sign error in this class becomes a failed commit rather than a
 * plausible-looking wrong number.
 *
 * <p>Unlike the journal, an account row <em>is</em> mutable — it carries a running
 * balance — so it has a {@link Version} for optimistic locking on top of the
 * {@code SELECT ... FOR UPDATE} the posting path takes.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    public enum Status {
        ACTIVE,
        /** No further postings accepted. History remains readable. */
        CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true, updatable = false, length = 96)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 16)
    private AccountType accountType;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "debit_total_minor", nullable = false)
    private long debitTotalMinor;

    @Column(name = "credit_total_minor", nullable = false)
    private long creditTotalMinor;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "allows_negative", nullable = false, updatable = false)
    private boolean allowsNegative;

    @Column(name = "system_account", nullable = false, updatable = false)
    private boolean systemAccount;

    @Column(name = "description", length = 256)
    private String description;

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

    protected LedgerAccount() {
        // JPA
    }

    /**
     * Opens an account for a customer wallet.
     *
     * <p>{@code allowsNegative = false}: the platform cannot owe a customer a negative
     * amount, so an over-debit is always a bug and the database refuses it at commit.
     */
    public static LedgerAccount forWallet(String accountId, String currency) {
        LedgerAccount account = new LedgerAccount();
        account.accountId = accountId;
        account.accountType = AccountType.LIABILITY;
        account.currency = currency.toUpperCase();
        account.allowsNegative = false;
        account.systemAccount = false;
        account.description = "Customer wallet liability";
        account.status = Status.ACTIVE;
        account.createdAt = Instant.now();
        account.updatedAt = account.createdAt;
        return account;
    }

    // ------------------------------------------------------------------ queries

    /** The natural-direction balance: positive means a normal balance for this type. */
    public Money balance() {
        return Money.ofMinor(balanceMinor, currency);
    }

    public Money debitTotal() {
        return Money.ofMinor(debitTotalMinor, currency);
    }

    public Money creditTotal() {
        return Money.ofMinor(creditTotalMinor, currency);
    }

    /** True when a debit increases this account's natural balance. */
    public boolean debitIncreases() {
        return accountType.debitIncreases();
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /**
     * The effect a movement of {@code amountMinor} in {@code direction} would have on this
     * account's natural balance.
     *
     * <p>Mirrors the {@code ck_journal_lines_signed_effect} CHECK constraint, and the same
     * value is persisted on the line so the two can be compared.
     */
    public long signedEffectOf(PostingDirection direction, long amountMinor) {
        boolean increases = accountType.debitIncreases() == (direction == PostingDirection.DEBIT);
        return increases ? amountMinor : -amountMinor;
    }

    // ----------------------------------------------------------------- mutators

    /**
     * Applies one posting line to this account and returns the resulting balance.
     *
     * <p>Deliberately does <em>not</em> check whether the balance would go negative. That
     * rule differs per account — a clearing account legitimately swings both ways — and it
     * is enforced by {@code ck_ledger_accounts_negative_allowed} at commit, which covers
     * every write path rather than only the ones that remember to ask. Checking it here as
     * well would be a second, drifting copy of the same rule.
     *
     * @return this account's natural balance after the movement, in minor units
     */
    public long apply(PostingDirection direction, long amountMinor) {
        if (amountMinor <= 0L) {
            throw new IllegalArgumentException(
                    "posting amount must be positive; direction carries the sign (got "
                            + amountMinor + ")");
        }
        if (!isActive()) {
            // A closed account with further movements would make its final balance a lie.
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Ledger account " + accountId + " is " + status + " and accepts no postings");
        }

        // addExact rather than +: an overflow must surface as an exception, not as a
        // wrapped-around balance that the CHECK constraint would then reject far from
        // the code that caused it.
        if (direction == PostingDirection.DEBIT) {
            this.debitTotalMinor = Math.addExact(this.debitTotalMinor, amountMinor);
        } else {
            this.creditTotalMinor = Math.addExact(this.creditTotalMinor, amountMinor);
        }
        this.balanceMinor = recomputeBalance();
        this.updatedAt = Instant.now();
        return this.balanceMinor;
    }

    public void close() {
        if (balanceMinor != 0L) {
            // Closing an account that still holds value would orphan it: the trial
            // balance would still include it, but nothing could ever move it back to zero.
            throw new ConflictException(ErrorCode.INVALID_PAYMENT_STATE,
                    "Ledger account " + accountId + " cannot be closed while its balance is "
                            + balance());
        }
        this.status = Status.CLOSED;
        this.updatedAt = Instant.now();
    }

    /**
     * Derives the natural balance from the two totals.
     *
     * <p>The same expression exists as a CHECK constraint in the schema. The duplication is
     * intentional: it is what allows the database to catch a bug in this method instead of
     * storing its result.
     */
    private long recomputeBalance() {
        return accountType.debitIncreases()
                ? Math.subtractExact(debitTotalMinor, creditTotalMinor)
                : Math.subtractExact(creditTotalMinor, debitTotalMinor);
    }

    public Long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public long getDebitTotalMinor() {
        return debitTotalMinor;
    }

    public long getCreditTotalMinor() {
        return creditTotalMinor;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public boolean isAllowsNegative() {
        return allowsNegative;
    }

    public boolean isSystemAccount() {
        return systemAccount;
    }

    public String getDescription() {
        return description;
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
        return "LedgerAccount{" + accountId + ", " + accountType + ", " + balance() + ", " + status + '}';
    }
}
