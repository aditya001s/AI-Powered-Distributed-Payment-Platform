package com.aegispay.ledger.service;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.events.payload.LedgerPostingPayload.PostingLine;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRefundedPayload;
import com.aegispay.common.events.payload.WalletMovementPayload;
import com.aegispay.common.money.Money;
import com.aegispay.ledger.domain.JournalEntry.PostingType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a business event into a balanced double-entry posting.
 *
 * <p><strong>All accounting knowledge in the platform lives here.</strong> Deciding which
 * accounts a deposit or a transfer touches is an accounting decision, not a payment
 * decision, so payment-service and wallet-service publish what happened in business terms
 * and this class decides how to book it. The alternative — each service constructing its
 * own postings — spreads the chart of accounts across nine codebases and makes a change to
 * it a nine-service deployment.
 *
 * <h2>The two ingest paths, and why the boundary matters</h2>
 *
 * <p>Value enters the ledger from exactly two kinds of event, and the split is drawn so
 * that no movement can be booked twice:
 *
 * <ul>
 *   <li><strong>Edge movements</strong> ({@code wallet-movement-recorded}) — a deposit into
 *       the platform or a withdrawal out of it. Only wallet-service knows these happened,
 *       and they need an external counterparty account.</li>
 *   <li><strong>Internal movements</strong> ({@code payment-completed},
 *       {@code payment-refunded}) — value moving between two wallets already inside the
 *       platform.</li>
 * </ul>
 *
 * <p>wallet-service deliberately does not emit movement events for a payment's transfer
 * legs, because those are already described by {@code payment-completed}. Double-counting
 * is the most likely way to corrupt a ledger fed by more than one stream, so the boundary
 * is stated explicitly rather than left to a filter somewhere downstream.
 *
 * <h2>Idempotency keys are derived, never random</h2>
 *
 * <p>Each posting's {@code ledgerTransactionId} is computed deterministically from an
 * identifier the source event already carries. A redelivered Kafka record therefore
 * produces the same key, hits the journal's unique constraint and is recognised as a
 * replay. A random key would make every redelivery a new posting, which is the one failure
 * mode a ledger cannot tolerate.
 */
@Component
public class PostingBuilder {

    /**
     * Prefixes keep the three derivations in separate namespaces, so a payment ID and a
     * wallet transaction ID that happened to collide could still not produce one key.
     */
    private static final String KEY_PAYMENT = "LTX-PAY-";
    private static final String KEY_REFUND = "LTX-RFD-";
    private static final String KEY_WALLET = "LTX-WLT-";

    /** Matches {@code journal_entries.ledger_transaction_id}. */
    private static final int MAX_KEY_LENGTH = 80;

    // ------------------------------------------------------- internal transfer

    /**
     * Books a completed payment: value moves from the payer's wallet to the payee's.
     *
     * <pre>
     *   DEBIT   wallet:SOURCE  (LIABILITY)   we owe the payer less
     *   CREDIT  wallet:DEST    (LIABILITY)   we owe the payee more
     * </pre>
     *
     * <p>Both legs are liabilities, so the platform's total obligation is unchanged — which
     * is exactly right: an internal transfer moves who is owed, not how much.
     *
     * <h3>Provider fees: the platform bears them</h3>
     *
     * <p>When a provider charged a fee, two further legs are added:
     *
     * <pre>
     *   DEBIT   expense:provider-fees:CCY        fee   a cost we incurred
     *   CREDIT  asset:settlement:PROVIDER:CCY    fee   taken from our balance at them
     * </pre>
     *
     * <p>Note what the fee does <em>not</em> touch: either customer wallet. The payer is
     * debited exactly the amount they agreed to pay and the payee is credited exactly that
     * same amount. That is the whole reason for the choice. wallet-service moves precisely
     * {@code amount} during capture, so deducting the fee from the payee's credit or adding
     * it to the payer's debit would make the ledger claim a movement the wallet never made,
     * and the customer-liability reconciliation would fail on every single payment. Keeping
     * the fee entirely outside customer accounts is worth more than shifting a cost.
     *
     * <p>Phase 4 refused a fee outright rather than guessing at this. Phase 5 introduced
     * provider routing and made the decision, which is why the refusal has become a booking.
     *
     * @throws IllegalArgumentException when the event cannot be booked as it stands — a
     *         missing wallet, a self-transfer, or a fee with no provider to attribute it to.
     *         Chosen because {@code KafkaConfig} classifies it as non-retryable, so the
     *         record goes straight to the dead-letter topic for a human instead of retrying
     *         four times against a problem no retry can fix.
     */
    public LedgerPostingPayload forPaymentCompleted(PaymentCompletedPayload payment) {
        Money amount = require(payment.amount(), "amount", payment.paymentId());
        String currency = amount.currency();

        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "payment " + payment.paymentId() + " has a non-positive amount " + amount);
        }
        requireDifferent(payment.sourceWalletId(), payment.destinationWalletId(), payment.paymentId());

        String source = LedgerPostingPayload.walletAccount(
                require(payment.sourceWalletId(), "sourceWalletId", payment.paymentId()));
        String destination = LedgerPostingPayload.walletAccount(
                require(payment.destinationWalletId(), "destinationWalletId", payment.paymentId()));

        List<PostingLine> lines = new ArrayList<>(4);
        lines.add(PostingLine.debit(source, AccountType.LIABILITY, amount.amountMinor(),
                "Transfer out"));
        lines.add(PostingLine.credit(destination, AccountType.LIABILITY, amount.amountMinor(),
                "Transfer in"));

        Money fee = payment.fee();
        if (fee != null && fee.isPositive()) {
            if (!fee.currency().equals(currency)) {
                // A posting is single-currency. Booking a USD fee inside an INR posting would
                // make the balance check compare cents with paise and "balance" at nonsense.
                throw new IllegalArgumentException(
                        "payment " + payment.paymentId() + " is in " + currency
                                + " but its fee is in " + fee.currency());
            }
            // A fee has to be attributable. Without knowing which provider charged it there
            // is no settlement account to take it from, and booking it against a placeholder
            // would misstate what the platform owes each provider.
            String providerId = require(payment.providerId(), "providerId",
                    "the fee on payment " + payment.paymentId());

            lines.add(PostingLine.debit(
                    LedgerPostingPayload.providerFeeExpenseAccount(currency),
                    AccountType.EXPENSE, fee.amountMinor(),
                    "Fee charged by " + providerId));
            lines.add(PostingLine.credit(
                    LedgerPostingPayload.providerSettlementAccount(providerId, currency),
                    AccountType.ASSET, fee.amountMinor(),
                    "Deducted from the settlement balance held at " + providerId));
        }

        return new LedgerPostingPayload(
                paymentKey(payment.paymentId()),
                payment.paymentId(),
                payment.transactionId(),
                PostingType.TRANSFER.name(),
                currency,
                lines,
                "Transfer " + amount + " for payment " + payment.transactionId()
                        + (fee != null && fee.isPositive() ? " (fee " + fee + ")" : ""),
                null,
                payment.completedAt());
    }

    /**
     * Books a refund: value moves back from the payee to the payer.
     *
     * <pre>
     *   DEBIT   wallet:PAYEE  (LIABILITY)   we owe the payee less
     *   CREDIT  wallet:PAYER  (LIABILITY)   we owe the payer more
     * </pre>
     *
     * <p>Recorded as a new posting rather than by touching the original. The journal is
     * append-only, so a refund is a fresh entry that points at what it partially undoes via
     * {@code reversesLedgerTransactionId} — which keeps both the original movement and the
     * correction visible, as an audit requires.
     *
     * <p>Typed {@code REFUND} and not {@code REVERSAL} even when it happens to be a full
     * refund. {@code REVERSAL} means "this posting is annulled, line for line" and the
     * schema permits only one per original; a refund is a real, separate movement of money
     * that a customer can request repeatedly up to the original amount.
     */
    public LedgerPostingPayload forPaymentRefunded(PaymentRefundedPayload refund) {
        Money amount = require(refund.refundedAmount(), "refundedAmount", refund.paymentId());
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "refund " + refund.refundId() + " has a non-positive amount " + amount);
        }

        // The refund reverses the direction of the original transfer, so the original's
        // destination is debited and its source credited.
        String payee = LedgerPostingPayload.walletAccount(
                require(refund.destinationWalletId(), "destinationWalletId", refund.paymentId()));
        String payer = LedgerPostingPayload.walletAccount(
                require(refund.sourceWalletId(), "sourceWalletId", refund.paymentId()));
        requireDifferent(refund.sourceWalletId(), refund.destinationWalletId(), refund.paymentId());

        return new LedgerPostingPayload(
                refundKey(refund.refundId()),
                refund.paymentId(),
                refund.transactionId(),
                PostingType.REFUND.name(),
                amount.currency(),
                List.of(
                        PostingLine.debit(payee, AccountType.LIABILITY, amount.amountMinor(),
                                "Refund taken back"),
                        PostingLine.credit(payer, AccountType.LIABILITY, amount.amountMinor(),
                                "Refund received")),
                (refund.fullRefund() ? "Full refund of " : "Partial refund of ") + amount
                        + " on payment " + refund.transactionId(),
                // Points at the transfer this refund undoes part of, so the chain is walkable.
                paymentKey(refund.paymentId()),
                refund.refundedAt());
    }

    // ---------------------------------------------------------- edge movement

    /**
     * Books a movement across the platform boundary.
     *
     * <p>A deposit:
     * <pre>
     *   DEBIT   asset:external-funding  (ASSET)      an asset arrived
     *   CREDIT  wallet:X                (LIABILITY)  we now owe the customer
     * </pre>
     *
     * <p>A withdrawal is the exact reverse.
     *
     * <p><strong>Why the external-funding account has to exist.</strong> Without a
     * counterparty, a deposit would be a single-sided entry — money appearing from nowhere.
     * The trial balance could then never sum to zero, and the ledger would only ever
     * describe internal transfers, which makes it useless as a book of record: wallet
     * liability balances would reflect transfers alone and could never be reconciled
     * against what wallet-service actually holds.
     *
     * <p>The funding source is simulated. This platform never contacts a real bank or
     * payment rail, and the account name says so.
     */
    public LedgerPostingPayload forWalletMovement(WalletMovementPayload movement) {
        Money amount = require(movement.amount(), "amount", movement.walletTransactionId());
        String currency = amount.currency();

        String wallet = LedgerPostingPayload.walletAccount(
                require(movement.walletId(), "walletId", movement.walletTransactionId()));
        String funding = LedgerPostingPayload.externalFundingAccount(currency);

        boolean deposit = movement.isDeposit();
        PostingType type = deposit ? PostingType.DEPOSIT : PostingType.WITHDRAWAL;

        List<PostingLine> lines = deposit
                ? List.of(
                        PostingLine.debit(funding, AccountType.ASSET, amount.amountMinor(),
                                "Funds received from an external source"),
                        PostingLine.credit(wallet, AccountType.LIABILITY, amount.amountMinor(),
                                "Deposit"))
                : List.of(
                        PostingLine.debit(wallet, AccountType.LIABILITY, amount.amountMinor(),
                                "Withdrawal"),
                        PostingLine.credit(funding, AccountType.ASSET, amount.amountMinor(),
                                "Funds returned to an external source"));

        return new LedgerPostingPayload(
                walletKey(movement.walletTransactionId()),
                null,
                movement.walletTransactionId(),
                type.name(),
                currency,
                lines,
                (deposit ? "Deposit of " : "Withdrawal of ") + amount + " on wallet "
                        + movement.walletId(),
                null,
                movement.occurredAt());
    }

    // ----------------------------------------------------------------- keys

    /** Deterministic key for a payment's transfer posting. */
    public String paymentKey(String paymentId) {
        return bounded(KEY_PAYMENT + require(paymentId, "paymentId", paymentId));
    }

    /** Deterministic key for one refund. Scoped to the refund, not the payment, so repeat
     * partial refunds each get their own posting. */
    public String refundKey(String refundId) {
        return bounded(KEY_REFUND + require(refundId, "refundId", refundId));
    }

    /** Deterministic key for one wallet edge movement. */
    public String walletKey(String walletTransactionId) {
        return bounded(KEY_WALLET + require(walletTransactionId, "walletTransactionId",
                walletTransactionId));
    }

    // ------------------------------------------------------------- internals

    private static String bounded(String key) {
        if (key.length() > MAX_KEY_LENGTH) {
            // Truncating would risk two distinct movements colliding on one key and the
            // second being silently discarded as a replay. Failing loudly is the only safe
            // response.
            throw new IllegalArgumentException(
                    "derived ledger transaction id exceeds " + MAX_KEY_LENGTH
                            + " characters and cannot be safely truncated: " + key);
        }
        return key;
    }

    private static String require(String value, String field, String context) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "cannot book " + context + ": " + field + " is missing");
        }
        return value;
    }

    private static Money require(Money value, String field, String context) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "cannot book " + context + ": " + field + " is missing");
        }
        return value;
    }

    private static void requireDifferent(String source, String destination, String context) {
        if (source != null && source.equals(destination)) {
            // Would net to nothing while still producing two journal lines and a posting
            // that appears to move money.
            throw new IllegalArgumentException(
                    "cannot book " + context + ": source and destination wallets are identical ("
                            + source + ")");
        }
    }
}
