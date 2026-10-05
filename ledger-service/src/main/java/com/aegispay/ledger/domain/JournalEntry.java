package com.aegispay.ledger.domain;

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
 * One posting: the ledger's unit of work.
 *
 * <p>A posting is applied in full or not at all, and its debits must equal its credits.
 * The individual {@link JournalLine}s are the legs; this row is the entry that owns them.
 *
 * <p><strong>Immutable.</strong> Every column is {@code updatable = false}, there is no
 * {@code updatedAt}, and — importantly — there is no {@code @Version}. An optimistic-lock
 * version column exists to make updates safe, and this row is never updated, so having one
 * would imply the opposite of the intended contract. Database triggers reject
 * {@code UPDATE} and {@code DELETE} outright, so the guarantee holds against a migration
 * or a manual fix as well as against this class.
 *
 * <p>That immutability is why there is no {@code reversedBy} column: back-filling one
 * would require an update. "What reversed this entry" is answered by querying for the
 * entry whose {@code reversesLedgerTransactionId} points here.
 *
 * <p><strong>Two timestamps, deliberately.</strong> {@code occurredAt} is business time —
 * when the money actually moved — and {@code recordedAt} is system time. A redelivered
 * event booked minutes later still occurred when it occurred, and a report that conflated
 * the two would silently misattribute value across period boundaries.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    /** What kind of movement a posting represents. Mirrors {@code ck_journal_entries_posting_type}. */
    public enum PostingType {
        /** Wallet to wallet inside the platform. */
        TRANSFER,
        /** Funds entered the platform from an external source. */
        DEPOSIT,
        /** Funds left the platform. */
        WITHDRAWAL,
        /** Fee earned by the platform or charged by a provider. */
        FEE,
        /** Money returned to a payer, in whole or in part. */
        REFUND,
        /** A full, line-for-line reversal of an earlier posting. */
        REVERSAL,
        /** An operator correction that is not a straight reversal. */
        ADJUSTMENT
    }

    /**
     * Monotonic journal sequence number, and the primary key: the journal is an ordered
     * book and its natural key is the page.
     *
     * <p>Monotonic but <strong>not gapless</strong> — a rolled-back transaction still
     * consumes a sequence value. A gap is therefore normal and must never be read as
     * evidence that a row was deleted. Making it gapless would mean serialising every
     * posting behind a single lock, capping throughput at one posting at a time; the
     * immutability triggers provide tamper-evidence without that cost.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "journal_sequence", nullable = false, updatable = false)
    private Long journalSequence;

    /**
     * The caller-supplied idempotency key, unique across the journal.
     *
     * <p>This single constraint is the whole idempotency mechanism for the ledger. A
     * redelivered Kafka record or a retried HTTP call carries the same key and cannot post
     * the movement twice. A natural key that is already unique per logical posting is
     * stronger than a side table of keys, because there is no way to bypass it.
     */
    @Column(name = "ledger_transaction_id", nullable = false, unique = true,
            updatable = false, length = 80)
    private String ledgerTransactionId;

    @Column(name = "payment_id", updatable = false, length = 48)
    private String paymentId;

    @Column(name = "transaction_id", updatable = false, length = 48)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_type", nullable = false, updatable = false, length = 24)
    private PostingType postingType;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /**
     * Total value moved: equal to total debits and to total credits.
     *
     * <p>Redundant with the lines, and verified against them by a deferred constraint
     * trigger at commit. Stored because reports read it constantly and summing the lines
     * every time would turn every report into a join.
     */
    @Column(name = "total_minor", nullable = false, updatable = false)
    private long totalMinor;

    @Column(name = "narrative", updatable = false, length = 512)
    private String narrative;

    @Column(name = "reverses_ledger_transaction_id", updatable = false, length = 80)
    private String reversesLedgerTransactionId;

    /** Which service asked for this posting. The first question asked about a surprise entry. */
    @Column(name = "source", nullable = false, updatable = false, length = 32)
    private String source;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();

    protected JournalEntry() {
        // JPA
    }

    /**
     * Builds a posting header.
     *
     * <p>The debit/credit balance is not checked here because it is a property of the
     * lines, not of this row. It is validated by the posting service before insert and
     * again by the database at commit.
     */
    public static JournalEntry of(String ledgerTransactionId,
                                  PostingType postingType,
                                  String currency,
                                  long totalMinor,
                                  String paymentId,
                                  String transactionId,
                                  String narrative,
                                  String reversesLedgerTransactionId,
                                  String source,
                                  String correlationId,
                                  Instant occurredAt) {

        if (ledgerTransactionId == null || ledgerTransactionId.isBlank()) {
            throw new IllegalArgumentException(
                    "ledgerTransactionId is required: it is the posting's idempotency key");
        }
        if (totalMinor <= 0L) {
            throw new IllegalArgumentException("a posting must move a positive amount, got " + totalMinor);
        }
        if (postingType == PostingType.REVERSAL
                && (reversesLedgerTransactionId == null || reversesLedgerTransactionId.isBlank())) {
            throw new IllegalArgumentException("a REVERSAL must name the posting it reverses");
        }
        if (ledgerTransactionId.equals(reversesLedgerTransactionId)) {
            throw new IllegalArgumentException("a posting cannot reverse itself");
        }

        JournalEntry entry = new JournalEntry();
        entry.ledgerTransactionId = ledgerTransactionId;
        entry.postingType = postingType;
        entry.currency = currency.toUpperCase();
        entry.totalMinor = totalMinor;
        entry.paymentId = paymentId;
        entry.transactionId = transactionId;
        entry.narrative = narrative;
        entry.reversesLedgerTransactionId = reversesLedgerTransactionId;
        entry.source = source;
        entry.correlationId = correlationId;
        entry.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        entry.recordedAt = Instant.now();
        return entry;
    }

    public Money total() {
        return Money.ofMinor(totalMinor, currency);
    }

    public boolean isReversal() {
        return postingType == PostingType.REVERSAL;
    }

    /** True when this entry corrects or partially undoes an earlier one. */
    public boolean reversesSomething() {
        return reversesLedgerTransactionId != null && !reversesLedgerTransactionId.isBlank();
    }

    public Long getJournalSequence() {
        return journalSequence;
    }

    public String getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public PostingType getPostingType() {
        return postingType;
    }

    public String getCurrency() {
        return currency;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public String getNarrative() {
        return narrative;
    }

    public String getReversesLedgerTransactionId() {
        return reversesLedgerTransactionId;
    }

    public String getSource() {
        return source;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    @Override
    public String toString() {
        return "JournalEntry{#" + journalSequence + ", " + ledgerTransactionId + ", "
                + postingType + ", " + total() + '}';
    }
}
