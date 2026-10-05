package com.aegispay.common.events.payload;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.exception.DomainExceptions.LedgerImbalanceException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * A complete, balanced double-entry posting request.
 *
 * <p>The unit of work in a ledger is the <em>posting</em> (a journal entry), not
 * the individual line. A posting is either applied in full or not at all, and it
 * must balance. This payload therefore carries all of its lines together and
 * validates the invariant at construction, so an unbalanced posting can never
 * even be put on the wire.
 *
 * <p>A ₹2,000 wallet-to-wallet transfer with a ₹5 fee is one posting with three
 * lines:
 *
 * <pre>
 *   DEBIT   wallet:A (LIABILITY)   200500   we owe A ₹2,005 less
 *   CREDIT  wallet:B (LIABILITY)   200000   we owe B ₹2,000 more
 *   CREDIT  revenue:fees (REVENUE)    500   we earned ₹5
 *                                  ------
 *   debits 200500 == credits 200500
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerPostingPayload(

        /** Idempotency key for the posting. Replays are ignored by ledger-service. */
        String ledgerTransactionId,

        String paymentId,

        String transactionId,

        /** e.g. {@code TRANSFER}, {@code FEE}, {@code REFUND}, {@code REVERSAL}. */
        String postingType,

        /** ISO-4217 code. A single posting is always single-currency. */
        String currency,

        List<PostingLine> lines,

        /** Free-text description shown on statements. */
        String narrative,

        /**
         * For reversals: the {@code ledgerTransactionId} being reversed. A ledger
         * is append-only, so a mistake is corrected by a new, opposite posting
         * rather than by editing history.
         */
        String reversesLedgerTransactionId,

        Instant occurredAt
) {

    public LedgerPostingPayload {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("a posting must have at least two lines");
        }
        lines = List.copyOf(lines);
        long debits = 0L;
        long credits = 0L;
        for (PostingLine line : lines) {
            if (line.direction() == PostingDirection.DEBIT) {
                debits = Math.addExact(debits, line.amountMinor());
            } else {
                credits = Math.addExact(credits, line.amountMinor());
            }
        }
        // The invariant that makes a ledger trustworthy. Checked here, and again
        // inside the ledger's own transaction before commit.
        if (debits != credits) {
            throw new LedgerImbalanceException(debits, credits);
        }
        if (debits == 0L) {
            throw new IllegalArgumentException("a posting must move a non-zero amount");
        }
    }

    /** Total value moved by this posting (equal to both the debit and credit total). */
    public long totalMinor() {
        return lines.stream()
                .filter(l -> l.direction() == PostingDirection.DEBIT)
                .mapToLong(PostingLine::amountMinor)
                .sum();
    }

    /** One side of one account's movement within a posting. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostingLine(
            /** Canonical account ID, e.g. {@code wallet:WAL-123} or {@code revenue:fees}. */
            String accountId,
            AccountType accountType,
            PostingDirection direction,
            /** Always positive. Direction, not sign, carries the meaning. */
            long amountMinor,
            String memo
    ) {
        public PostingLine {
            if (amountMinor <= 0L) {
                throw new IllegalArgumentException(
                        "posting line amount must be positive; use direction to express sign (got "
                                + amountMinor + ")");
            }
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("posting line requires an accountId");
            }
        }

        public static PostingLine debit(String accountId, AccountType type, long amountMinor, String memo) {
            return new PostingLine(accountId, type, PostingDirection.DEBIT, amountMinor, memo);
        }

        public static PostingLine credit(String accountId, AccountType type, long amountMinor, String memo) {
            return new PostingLine(accountId, type, PostingDirection.CREDIT, amountMinor, memo);
        }

        /** The signed effect on this account's balance, respecting account type. */
        public long signedEffectMinor() {
            boolean increases = accountType.debitIncreases() == (direction == PostingDirection.DEBIT);
            return increases ? amountMinor : -amountMinor;
        }
    }

    /** Canonical account ID for a customer wallet. */
    public static String walletAccount(String walletId) {
        return "wallet:" + walletId;
    }

    /** Canonical account ID for platform fee revenue. */
    public static String feeRevenueAccount(String currency) {
        return "revenue:fees:" + currency.toUpperCase();
    }

    /**
     * Canonical account ID for funds that entered or left the platform through an
     * external funding source.
     *
     * <p>An ASSET account: a deposit debits it (money arrived) and credits the
     * customer's wallet liability. Without this account a deposit would have no
     * counterparty and the trial balance could never sum to zero — the ledger
     * would only ever see internal transfers and could not be a book of record.
     *
     * <p>Simulated in this project. No real bank or UPI rail is ever contacted.
     */
    public static String externalFundingAccount(String currency) {
        return "asset:external-funding:" + currency.toUpperCase();
    }

    /** Canonical account ID for fees charged to the platform by a provider. */
    public static String providerFeeExpenseAccount(String currency) {
        return "expense:provider-fees:" + currency.toUpperCase();
    }

    /**
     * Canonical account ID for the settlement balance the platform holds at a provider.
     *
     * <p>Currency-scoped, like every other system account. An account row carries exactly one
     * currency, so a single {@code asset:settlement:PROVIDER} account could only ever hold
     * one — and the first payment in a second currency would have nowhere to book its fee.
     */
    public static String providerSettlementAccount(String providerId, String currency) {
        return "asset:settlement:" + providerId.toUpperCase() + ":" + currency.toUpperCase();
    }

    /** Canonical account ID for the in-flight clearing (suspense) account. */
    public static String clearingAccount(String currency) {
        return "clearing:in-flight:" + currency.toUpperCase();
    }
}
