package com.aegispay.ledger.api;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.money.Money;
import com.aegispay.ledger.domain.JournalEntry;
import com.aegispay.ledger.domain.JournalLine;
import com.aegispay.ledger.domain.LedgerAccount;
import com.aegispay.ledger.service.PostingResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies for ledger-service.
 *
 * <p><strong>Amounts on the ledger's own API are minor units, full stop.</strong> Every
 * other service accepts a decimal string because a human or a form field produced the value;
 * the ledger's writing surface is machine-only, and its callers already hold minor-unit
 * integers. Converting to a decimal and back would add two chances to lose precision in
 * exchange for nothing. Responses still render both forms, because a dashboard reads them.
 */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    // ------------------------------------------------------------------ input

    /**
     * A complete, balanced posting.
     *
     * <p>Validation here is structural only — required fields, positive amounts, at least two
     * lines. The debit==credit invariant is checked when {@link LedgerPostingPayload} is
     * constructed, again by the posting service, and finally by a deferred database
     * constraint; expressing it as a bean-validation annotation would be a fourth, weaker
     * copy.
     */
    @Schema(description = "Internal: a balanced double-entry posting")
    public record CreatePostingRequest(

            @NotBlank
            @Size(max = 80)
            @Schema(description = """
                    Idempotency key, unique per logical movement. Derive it from something the
                    source already owns (a payment ID, a wallet transaction ID) so a retry
                    produces the same key. A random value would make every redelivery a new
                    posting.""",
                    example = "LTX-PAY-PAY-3F9A2B7C1D4E5F60")
            String ledgerTransactionId,

            @Size(max = 48)
            String paymentId,

            @Size(max = 48)
            String transactionId,

            @NotNull
            @Schema(example = "TRANSFER",
                    description = "TRANSFER, DEPOSIT, WITHDRAWAL, FEE, REFUND, REVERSAL or ADJUSTMENT")
            JournalEntry.PostingType postingType,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO-4217 code")
            @Schema(example = "INR")
            String currency,

            @NotEmpty(message = "a double entry needs at least two lines")
            @Size(min = 2, max = 64)
            @Valid
            List<PostingLineRequest> lines,

            @Size(max = 512)
            @Schema(example = "Transfer 2000.00 INR for payment TXN7F3A9C2E4B61")
            String narrative,

            @Size(max = 80)
            @Schema(description = "The posting this one reverses or partially undoes")
            String reversesLedgerTransactionId,

            @Schema(description = "Business time: when the money actually moved. Defaults to now.")
            Instant occurredAt
    ) {

        /** Converts to the shared payload, whose constructor enforces debits == credits. */
        public LedgerPostingPayload toPayload() {
            return new LedgerPostingPayload(
                    ledgerTransactionId,
                    paymentId,
                    transactionId,
                    postingType.name(),
                    currency.toUpperCase(),
                    lines.stream().map(PostingLineRequest::toLine).toList(),
                    narrative,
                    reversesLedgerTransactionId,
                    occurredAt == null ? Instant.now() : occurredAt);
        }
    }

    @Schema(description = "One debit or credit leg")
    public record PostingLineRequest(

            @NotBlank
            @Size(max = 96)
            @Schema(description = "Canonical account ID", example = "wallet:WAL-3F9A2B7C1D4E5F60")
            String accountId,

            @NotNull
            @Schema(description = """
                    The account's classification, as the caller believes it to be. Checked
                    against the stored account: a mismatch is rejected, because the caller
                    chose this line's direction based on this belief.""",
                    example = "LIABILITY")
            AccountType accountType,

            @NotNull
            @Schema(example = "DEBIT")
            PostingDirection direction,

            @NotNull
            @Positive(message = "must be positive; direction carries the sign")
            @Schema(description = "Amount in minor units. Always positive.", example = "200000")
            Long amountMinor,

            @Size(max = 256)
            String memo
    ) {
        LedgerPostingPayload.PostingLine toLine() {
            return new LedgerPostingPayload.PostingLine(
                    accountId, accountType, direction, amountMinor, memo);
        }
    }

    @Schema(description = "Internal: reverse a posting by appending its opposite")
    public record ReversePostingRequest(
            @NotBlank
            @Size(max = 256)
            @Schema(example = "Posted against the wrong account")
            String reason
    ) {
    }

    // ----------------------------------------------------------------- output

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A monetary amount in both decimal and minor-unit form")
    public record MoneyView(
            @Schema(example = "2000.00") BigDecimal amount,
            @Schema(example = "200000", description = "Exact value in minor units. Prefer this.")
            long amountMinor,
            @Schema(example = "INR") String currency
    ) {
        public static MoneyView of(Money money) {
            return new MoneyView(money.toDecimal(), money.amountMinor(), money.currency());
        }

        static MoneyView ofMinor(long amountMinor, String currency) {
            return of(Money.ofMinor(amountMinor, currency));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One leg of a posting")
    public record JournalLineResponse(
            int lineNumber,
            String accountId,
            AccountType accountType,
            PostingDirection direction,
            MoneyView amount,
            @Schema(description = "Effect on the account's natural-direction balance")
            long signedEffectMinor,
            @Schema(description = "The account's balance immediately after this line")
            MoneyView balanceAfter,
            String memo
    ) {
        public static JournalLineResponse from(JournalLine line) {
            return new JournalLineResponse(
                    line.getLineNumber(),
                    line.getAccountId(),
                    line.getAccountType(),
                    line.getDirection(),
                    MoneyView.of(line.amount()),
                    line.getSignedEffectMinor(),
                    MoneyView.of(line.balanceAfter()),
                    line.getMemo());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A posting and its legs")
    public record JournalEntryResponse(

            @Schema(description = """
                    Monotonic journal sequence number. Monotonic but NOT gapless: a
                    rolled-back transaction consumes a value, so a gap is normal and is not
                    evidence of deletion.""")
            long journalSequence,

            String ledgerTransactionId,
            String paymentId,
            String transactionId,
            JournalEntry.PostingType postingType,
            MoneyView total,
            List<JournalLineResponse> lines,
            String narrative,

            @Schema(description = "The posting this one reverses or partially undoes")
            String reversesLedgerTransactionId,

            @Schema(description = "Which service or operator asked for this posting")
            String source,
            String correlationId,

            @Schema(description = "Business time: when the money moved")
            Instant occurredAt,
            @Schema(description = "System time: when the journal recorded it")
            Instant recordedAt,

            @Schema(description = """
                    True when this response describes a posting that already existed and the
                    caller was retrying. The movement happened exactly once either way.""")
            boolean duplicate
    ) {
        public static JournalEntryResponse from(PostingResult result) {
            JournalEntry entry = result.entry();
            return new JournalEntryResponse(
                    entry.getJournalSequence(),
                    entry.getLedgerTransactionId(),
                    entry.getPaymentId(),
                    entry.getTransactionId(),
                    entry.getPostingType(),
                    MoneyView.of(entry.total()),
                    result.lines().stream().map(JournalLineResponse::from).toList(),
                    entry.getNarrative(),
                    entry.getReversesLedgerTransactionId(),
                    entry.getSource(),
                    entry.getCorrelationId(),
                    entry.getOccurredAt(),
                    entry.getRecordedAt(),
                    result.duplicate());
        }
    }

    /** Compact form for journal listings, without the legs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Journal listing entry")
    public record JournalSummary(
            long journalSequence,
            String ledgerTransactionId,
            String paymentId,
            JournalEntry.PostingType postingType,
            MoneyView total,
            String narrative,
            String source,
            Instant occurredAt,
            Instant recordedAt
    ) {
        public static JournalSummary from(JournalEntry entry) {
            return new JournalSummary(
                    entry.getJournalSequence(),
                    entry.getLedgerTransactionId(),
                    entry.getPaymentId(),
                    entry.getPostingType(),
                    MoneyView.of(entry.total()),
                    entry.getNarrative(),
                    entry.getSource(),
                    entry.getOccurredAt(),
                    entry.getRecordedAt());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "An account and its balance")
    public record AccountResponse(
            String accountId,
            AccountType accountType,
            String currency,

            @Schema(description = """
                    Balance in the account's natural direction: positive means a normal
                    balance for this type. A LIABILITY wallet with a positive balance means
                    the platform owes the customer that amount.""")
            MoneyView balance,

            MoneyView debitTotal,
            MoneyView creditTotal,

            @Schema(description = "True when a debit increases this account's balance")
            boolean debitIncreases,

            @Schema(description = "False for customer wallets, which may never go negative")
            boolean allowsNegative,

            @Schema(description = "True for platform accounts declared by migration")
            boolean systemAccount,

            String description,
            LedgerAccount.Status status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static AccountResponse from(LedgerAccount account) {
            return new AccountResponse(
                    account.getAccountId(),
                    account.getAccountType(),
                    account.getCurrency(),
                    MoneyView.of(account.balance()),
                    MoneyView.of(account.debitTotal()),
                    MoneyView.of(account.creditTotal()),
                    account.debitIncreases(),
                    account.isAllowsNegative(),
                    account.isSystemAccount(),
                    account.getDescription(),
                    account.getStatus(),
                    account.getCreatedAt(),
                    account.getUpdatedAt());
        }
    }

    // --------------------------------------------------------- reconciliation

    /**
     * The trial balance: the fundamental accounting identity.
     *
     * <p>Total debits must equal total credits across every account in a currency. If they do
     * not, the books are broken and no report built on them can be trusted, so this is the
     * single most important number the service produces.
     *
     * <p>Stated in raw debit/credit terms rather than natural balances on purpose: that makes
     * the check independent of the per-account-type sign rules, which are themselves part of
     * what could be wrong.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Trial balance for one currency")
    public record TrialBalanceResponse(
            String currency,

            MoneyView totalDebits,
            MoneyView totalCredits,

            @Schema(description = "Debits minus credits. Must be exactly zero.")
            long differenceMinor,

            @Schema(description = "True only when debits and credits are exactly equal")
            boolean balanced,

            @Schema(description = "Same totals recomputed from the journal lines instead of "
                    + "the cached account totals")
            MoneyView replayedDebits,
            MoneyView replayedCredits,

            @Schema(description = "True when the cached account totals agree with the journal")
            boolean accountsAgreeWithJournal,

            @Schema(description = """
                    Total the platform owes customers: the sum of every customer wallet
                    liability. Must equal wallet-service's own sum of wallet balances.""")
            MoneyView customerLiability,

            long accountCount,
            long postingCount,
            List<AccountBalanceView> accounts,
            Instant checkedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One line of the trial balance")
    public record AccountBalanceView(
            String accountId,
            AccountType accountType,
            MoneyView debitTotal,
            MoneyView creditTotal,
            MoneyView balance
    ) {
        public static AccountBalanceView from(LedgerAccount account) {
            return new AccountBalanceView(
                    account.getAccountId(),
                    account.getAccountType(),
                    MoneyView.of(account.debitTotal()),
                    MoneyView.of(account.creditTotal()),
                    MoneyView.of(account.balance()));
        }
    }

    /**
     * Per-account reconciliation: does the cached balance match the journal that justifies it?
     *
     * <p>A mismatch means either a balance was mutated without a line to justify it, or a line
     * was written that never reached the balance. Both are silent failures that otherwise
     * surface only as a customer complaint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Consistency check for one account")
    public record AccountReconciliationResponse(
            String accountId,
            AccountType accountType,
            String currency,

            long storedBalanceMinor,
            @Schema(description = "Balance recomputed by replaying every journal line")
            long replayedBalanceMinor,

            long storedDebitTotalMinor,
            long replayedDebitTotalMinor,
            long storedCreditTotalMinor,
            long replayedCreditTotalMinor,

            boolean balanceMatches,
            boolean totalsMatch,

            @Schema(description = """
                    Journal sequences where the stored running balance disagrees with the
                    lines preceding it. Expected to be empty.""")
            List<Long> inconsistentRunningBalanceAt,

            @Schema(description = "True only when every check above agrees")
            boolean consistent,

            long lineCount
    ) {
    }

    /**
     * The full reconciliation report.
     *
     * <p>{@code findings} is the part an operator reads first: it is empty when the ledger is
     * sound, and each entry names something specific that is wrong.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Whole-ledger consistency report")
    public record LedgerReconciliationResponse(

            @Schema(description = "True only when every check across every currency passed")
            boolean consistent,

            List<TrialBalanceResponse> trialBalances,

            @Schema(description = """
                    Postings whose debits do not equal their credits. Must be empty: a
                    deferred constraint trigger makes an unbalanced posting impossible to
                    commit, so anything here means the trigger was dropped or bypassed.""")
            List<Long> unbalancedPostings,

            @Schema(description = "Postings with fewer than two legs. Must be empty.")
            List<Long> singleSidedPostings,

            @Schema(description = "Accounts whose cached balance disagrees with the journal")
            List<AccountReconciliationResponse> driftedAccounts,

            @Schema(description = "Human-readable description of every problem found")
            List<String> findings,

            long accountsChecked,
            Instant checkedAt
    ) {
    }

    /**
     * Balances the ledger believes each account holds, for a caller cross-checking against
     * its own records.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Internal: wallet liability balances as the ledger sees them")
    public record WalletLiabilitySnapshot(
            String currency,
            @Schema(description = "Total owed to customers, per the ledger")
            MoneyView totalCustomerLiability,
            @Schema(description = "Per-wallet balances, keyed by wallet ID")
            Map<String, Long> balancesMinor,
            Instant takenAt
    ) {
    }
}
