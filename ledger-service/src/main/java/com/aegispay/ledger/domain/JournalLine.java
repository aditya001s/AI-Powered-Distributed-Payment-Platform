package com.aegispay.ledger.domain;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One debit or credit leg of a posting.
 *
 * <p><strong>{@code amountMinor} is always positive.</strong> The direction carries the
 * sign. Allowing a negative amount would give two ways to express one movement and make
 * every {@code SUM()} over this table ambiguous — is a negative debit a credit?
 *
 * <p>Immutable, like its parent entry, and enforced by a database trigger rather than by
 * convention.
 *
 * <p>{@code accountType} is copied from the account rather than joined. A journal line
 * must be readable on its own terms years later: if the account were re-classified in the
 * meantime, joining would reinterpret a historical posting under today's chart of
 * accounts, which is exactly the kind of retroactive change a ledger exists to prevent.
 */
@Entity
@Table(name = "journal_lines")
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "journal_sequence", nullable = false, updatable = false)
    private Long journalSequence;

    /**
     * Position within the posting, starting at 1.
     *
     * <p>Also the order in which lines were applied, which is what makes
     * {@link #balanceAfterMinor} well defined when one posting touches the same account
     * more than once.
     */
    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Column(name = "account_id", nullable = false, updatable = false, length = 96)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 16)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 8)
    private PostingDirection direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /**
     * Effect on the account's natural-direction balance.
     *
     * <p>Stored rather than recomputed on read so that a report can sum it directly, and
     * so a CHECK constraint can verify it against the direction and account type — which
     * turns a sign error in the application into a failed insert.
     */
    @Column(name = "signed_effect_minor", nullable = false, updatable = false)
    private long signedEffectMinor;

    /**
     * The account's natural balance immediately after this line.
     *
     * <p>Denormalised on purpose. Reconstructing a historical balance by summing every
     * prior line is linear in the account's history and silently wrong if any row were
     * ever inserted out of order. Storing it makes each line independently verifiable and
     * turns an account statement into a single index scan.
     */
    @Column(name = "balance_after_minor", nullable = false, updatable = false)
    private long balanceAfterMinor;

    @Column(name = "memo", updatable = false, length = 256)
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected JournalLine() {
        // JPA
    }

    /**
     * Records a leg against an account, applying it to that account's running balance.
     *
     * <p>Takes the {@link LedgerAccount} rather than raw values so the account type, the
     * signed effect and the resulting balance all come from one source. Passing them
     * separately is how a caller ends up recording a line whose {@code accountType}
     * disagrees with the account it points at.
     */
    public static JournalLine record(JournalEntry entry,
                                     int lineNumber,
                                     LedgerAccount account,
                                     PostingDirection direction,
                                     long amountMinor,
                                     String memo) {

        if (amountMinor <= 0L) {
            throw new IllegalArgumentException(
                    "line amount must be positive; direction carries the sign (got "
                            + amountMinor + ")");
        }
        if (!account.getCurrency().equals(entry.getCurrency())) {
            // A posting is single-currency. Mixing them would make the debit==credit
            // check compare paise with cents and "balance" at a meaningless number.
            throw new IllegalArgumentException(
                    "account " + account.getAccountId() + " is " + account.getCurrency()
                            + " but the posting is " + entry.getCurrency());
        }

        long balanceAfter = account.apply(direction, amountMinor);

        JournalLine line = new JournalLine();
        line.journalSequence = entry.getJournalSequence();
        line.lineNumber = lineNumber;
        line.accountId = account.getAccountId();
        line.accountType = account.getAccountType();
        line.direction = direction;
        line.amountMinor = amountMinor;
        line.currency = account.getCurrency();
        line.signedEffectMinor = account.signedEffectOf(direction, amountMinor);
        line.balanceAfterMinor = balanceAfter;
        line.memo = memo;
        line.createdAt = Instant.now();
        return line;
    }

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public Money balanceAfter() {
        return Money.ofMinor(balanceAfterMinor, currency);
    }

    public boolean isDebit() {
        return direction == PostingDirection.DEBIT;
    }

    /** Debit-positive value, the convention the trial balance is stated in. */
    public long debitPositiveMinor() {
        return isDebit() ? amountMinor : -amountMinor;
    }

    public Long getId() {
        return id;
    }

    public Long getJournalSequence() {
        return journalSequence;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getAccountId() {
        return accountId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public long getSignedEffectMinor() {
        return signedEffectMinor;
    }

    public long getBalanceAfterMinor() {
        return balanceAfterMinor;
    }

    public String getMemo() {
        return memo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "JournalLine{#" + journalSequence + "." + lineNumber + ", " + direction + " "
                + amount() + " " + accountId + '}';
    }
}
