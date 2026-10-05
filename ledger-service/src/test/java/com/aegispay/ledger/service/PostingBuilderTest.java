package com.aegispay.ledger.service;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.events.payload.LedgerPostingPayload.PostingLine;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRefundedPayload;
import com.aegispay.common.events.payload.WalletMovementPayload;
import com.aegispay.common.money.Money;
import com.aegispay.ledger.domain.JournalEntry.PostingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the mapping from business events to double-entry postings.
 *
 * <p>This is where the platform's accounting decisions live, so the assertions are about
 * accounting rather than about plumbing: which account is debited, which is credited, whether
 * the platform's total obligation changed, and whether the entry balances.
 *
 * <p>Every test also checks that debits equal credits. That is already guaranteed by
 * {@link LedgerPostingPayload}'s constructor — so these assertions exist to catch the case
 * where the builder produces a payload that balances for the <em>wrong</em> reason, such as
 * two debits that happen to cancel.
 */
class PostingBuilderTest {

    private static final String INR = "INR";
    private static final String SOURCE_WALLET = "WAL-AAAA1111";
    private static final String DEST_WALLET = "WAL-BBBB2222";
    private static final String SOURCE_ACCOUNT = "wallet:" + SOURCE_WALLET;
    private static final String DEST_ACCOUNT = "wallet:" + DEST_WALLET;
    private static final String FUNDING_ACCOUNT = "asset:external-funding:INR";

    private PostingBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PostingBuilder();
    }

    // ------------------------------------------------------------- fixtures

    /** A payment with no provider attributed, as Phase 3 produced. */
    private static PaymentCompletedPayload completed(long amountMinor, Money fee) {
        return new PaymentCompletedPayload(
                "PAY-0001", "TXN0001", "USR-PAYER", "USR-PAYEE",
                SOURCE_WALLET, DEST_WALLET,
                Money.ofMinor(amountMinor, INR), fee,
                null, null, List.of(), null, 120L, Instant.now());
    }

    /** A payment routed through a named provider, as Phase 5 produces. */
    private static PaymentCompletedPayload completedVia(String providerId, long amountMinor,
                                                        Money fee) {
        return new PaymentCompletedPayload(
                "PAY-0001", "TXN0001", "USR-PAYER", "USR-PAYEE",
                SOURCE_WALLET, DEST_WALLET,
                Money.ofMinor(amountMinor, INR), fee,
                providerId, "provider-ref-1", List.of(providerId), null, 120L, Instant.now());
    }

    private static PaymentRefundedPayload refunded(long refundMinor, boolean full) {
        return new PaymentRefundedPayload(
                "PAY-0001", "TXN0001", "RFD-0001", "USR-PAYER",
                SOURCE_WALLET, DEST_WALLET,
                Money.ofMinor(refundMinor, INR),
                Money.ofMinor(refundMinor, INR),
                Money.ofMinor(200_000L, INR),
                full, "Customer request", "USR-PAYER", null, Instant.now());
    }

    private static WalletMovementPayload movement(PostingDirection direction, long amountMinor) {
        return new WalletMovementPayload(
                "TXN-WLT-0001", SOURCE_WALLET, "USR-PAYER", direction,
                Money.ofMinor(amountMinor, INR), Money.ofMinor(amountMinor, INR),
                direction == PostingDirection.CREDIT ? "DEPOSIT" : "WITHDRAWAL",
                "test", Instant.now());
    }

    private static void assertBalances(LedgerPostingPayload posting) {
        long debits = posting.lines().stream()
                .filter(line -> line.direction() == PostingDirection.DEBIT)
                .mapToLong(PostingLine::amountMinor).sum();
        long credits = posting.lines().stream()
                .filter(line -> line.direction() == PostingDirection.CREDIT)
                .mapToLong(PostingLine::amountMinor).sum();

        assertThat(debits).as("debits must equal credits").isEqualTo(credits);
        assertThat(posting.totalMinor()).isEqualTo(debits);
        assertThat(debits).as("a posting must move something").isPositive();
    }

    private static PostingLine lineFor(LedgerPostingPayload posting, String accountId) {
        return posting.lines().stream()
                .filter(line -> line.accountId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + accountId));
    }

    // ------------------------------------------------------------- transfers

    @Nested
    @DisplayName("a completed payment")
    class CompletedPayment {

        @Test
        @DisplayName("debits the payer's wallet and credits the payee's")
        void debitsPayerCreditsPayee() {
            LedgerPostingPayload posting = builder.forPaymentCompleted(completed(200_000L,
                    Money.zero(INR)));

            assertBalances(posting);
            assertThat(posting.postingType()).isEqualTo(PostingType.TRANSFER.name());
            assertThat(posting.currency()).isEqualTo(INR);
            assertThat(posting.lines()).hasSize(2);

            PostingLine payer = lineFor(posting, SOURCE_ACCOUNT);
            assertThat(payer.direction())
                    .as("a wallet is a liability, so debiting it means we owe the payer less")
                    .isEqualTo(PostingDirection.DEBIT);
            assertThat(payer.accountType()).isEqualTo(AccountType.LIABILITY);
            assertThat(payer.amountMinor()).isEqualTo(200_000L);

            PostingLine payee = lineFor(posting, DEST_ACCOUNT);
            assertThat(payee.direction()).isEqualTo(PostingDirection.CREDIT);
            assertThat(payee.accountType()).isEqualTo(AccountType.LIABILITY);
        }

        /**
         * An internal transfer moves who is owed, not how much. Both legs are liabilities, so
         * their signed effects cancel and the platform's total obligation is unchanged.
         */
        @Test
        @DisplayName("leaves the platform's total obligation unchanged")
        void totalObligationUnchanged() {
            LedgerPostingPayload posting = builder.forPaymentCompleted(completed(200_000L,
                    Money.zero(INR)));

            long netLiabilityChange = posting.lines().stream()
                    .filter(line -> line.accountType() == AccountType.LIABILITY)
                    .mapToLong(PostingLine::signedEffectMinor)
                    .sum();

            assertThat(netLiabilityChange)
                    .as("an internal transfer must not create or destroy an obligation")
                    .isZero();
        }

        @Test
        @DisplayName("derives its idempotency key from the payment id")
        void keyIsDerivedFromPaymentId() {
            LedgerPostingPayload first = builder.forPaymentCompleted(completed(200_000L,
                    Money.zero(INR)));
            LedgerPostingPayload second = builder.forPaymentCompleted(completed(200_000L,
                    Money.zero(INR)));

            assertThat(first.ledgerTransactionId())
                    .as("a redelivered event must produce the SAME key, or it would be booked twice")
                    .isEqualTo(second.ledgerTransactionId())
                    .isEqualTo("LTX-PAY-PAY-0001");
            assertThat(first.paymentId()).isEqualTo("PAY-0001");
        }

        @Test
        @DisplayName("treats a null fee as no fee")
        void nullFeeIsFine() {
            assertThat(builder.forPaymentCompleted(completed(200_000L, null)).lines()).hasSize(2);
        }

        /**
         * The fee decision, asserted where it matters most: on the customer accounts.
         *
         * <p>Phase 4 refused a fee outright rather than guessing who bore it. Phase 5 decided
         * the platform does, and this test pins the consequence — the payer's debit and the
         * payee's credit are both exactly {@code amount}, unchanged by the fee. wallet-service
         * moves precisely {@code amount} during capture, so any other choice would make the
         * ledger claim a movement the wallet never made and break reconciliation on every
         * payment.
         */
        @Test
        @DisplayName("books a provider fee without touching either customer wallet")
        void feeDoesNotTouchCustomerWallets() {
            LedgerPostingPayload posting = builder.forPaymentCompleted(
                    completedVia("SWIFTPAY", 200_000L, Money.ofMinor(500L, INR)));

            assertBalances(posting);
            assertThat(posting.lines()).hasSize(4);
            assertThat(posting.totalMinor())
                    .as("the posting moves the amount plus the fee")
                    .isEqualTo(200_500L);

            assertThat(lineFor(posting, SOURCE_ACCOUNT).amountMinor())
                    .as("the payer is debited exactly what they agreed to pay")
                    .isEqualTo(200_000L);
            assertThat(lineFor(posting, DEST_ACCOUNT).amountMinor())
                    .as("and the payee receives exactly that same amount")
                    .isEqualTo(200_000L);
        }

        @Test
        @DisplayName("books the fee as an expense settled against the provider's balance")
        void feeIsAnExpenseAgainstSettlement() {
            LedgerPostingPayload posting = builder.forPaymentCompleted(
                    completedVia("NEXUSPAY", 200_000L, Money.ofMinor(500L, INR)));

            PostingLine expense = lineFor(posting, "expense:provider-fees:INR");
            assertThat(expense.direction())
                    .as("a debit increases an expense")
                    .isEqualTo(PostingDirection.DEBIT);
            assertThat(expense.accountType()).isEqualTo(AccountType.EXPENSE);
            assertThat(expense.amountMinor()).isEqualTo(500L);

            PostingLine settlement = lineFor(posting, "asset:settlement:NEXUSPAY:INR");
            assertThat(settlement.direction())
                    .as("the provider took it out of what we hold with them, so the asset falls")
                    .isEqualTo(PostingDirection.CREDIT);
            assertThat(settlement.accountType()).isEqualTo(AccountType.ASSET);
            assertThat(settlement.amountMinor()).isEqualTo(500L);
        }

        /**
         * A fee still leaves the platform's obligation to customers unchanged. The cost lands
         * on an expense account, not on anybody's wallet.
         */
        @Test
        @DisplayName("a fee leaves the total owed to customers unchanged")
        void feeDoesNotChangeCustomerObligation() {
            LedgerPostingPayload posting = builder.forPaymentCompleted(
                    completedVia("PAYBRIDGE", 200_000L, Money.ofMinor(750L, INR)));

            long netLiabilityChange = posting.lines().stream()
                    .filter(line -> line.accountType() == AccountType.LIABILITY)
                    .mapToLong(PostingLine::signedEffectMinor)
                    .sum();

            assertThat(netLiabilityChange).isZero();
        }

        /**
         * A fee has to be attributable. Without a provider there is no settlement account to
         * take it from, and booking it against a placeholder would misstate what the platform
         * owes each provider.
         */
        @Test
        @DisplayName("refuses a fee that names no provider")
        void refusesUnattributableFee() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.forPaymentCompleted(
                            completed(200_000L, Money.ofMinor(500L, INR))))
                    .withMessageContaining("providerId is missing");
        }

        @Test
        @DisplayName("refuses a fee denominated in another currency")
        void refusesCrossCurrencyFee() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.forPaymentCompleted(
                            completedVia("SWIFTPAY", 200_000L, Money.ofMinor(500L, "USD"))))
                    .withMessageContaining("its fee is in USD");
        }

        @Test
        @DisplayName("treats a zero fee as no fee at all")
        void zeroFeeAddsNoLines() {
            assertThat(builder.forPaymentCompleted(
                    completedVia("SWIFTPAY", 200_000L, Money.zero(INR))).lines())
                    .as("a zero-value leg would clutter the journal with nothing")
                    .hasSize(2);
        }

        @Test
        @DisplayName("refuses a self-transfer, which would net to nothing")
        void refusesSelfTransfer() {
            PaymentCompletedPayload selfPay = new PaymentCompletedPayload(
                    "PAY-0002", "TXN0002", "USR-A", "USR-A",
                    SOURCE_WALLET, SOURCE_WALLET,
                    Money.ofMinor(100L, INR), Money.zero(INR),
                    null, null, List.of(), null, 1L, Instant.now());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.forPaymentCompleted(selfPay))
                    .withMessageContaining("identical");
        }

        @Test
        @DisplayName("refuses a payment missing a wallet id")
        void refusesMissingWallet() {
            PaymentCompletedPayload noSource = new PaymentCompletedPayload(
                    "PAY-0003", "TXN0003", "USR-A", "USR-B",
                    null, DEST_WALLET,
                    Money.ofMinor(100L, INR), Money.zero(INR),
                    null, null, List.of(), null, 1L, Instant.now());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.forPaymentCompleted(noSource))
                    .withMessageContaining("sourceWalletId is missing");
        }
    }

    // --------------------------------------------------------------- refunds

    @Nested
    @DisplayName("a refund")
    class Refund {

        @Test
        @DisplayName("reverses the direction of the original transfer")
        void reversesTheOriginalDirection() {
            LedgerPostingPayload posting = builder.forPaymentRefunded(refunded(50_000L, false));

            assertBalances(posting);
            assertThat(posting.postingType()).isEqualTo(PostingType.REFUND.name());

            assertThat(lineFor(posting, DEST_ACCOUNT).direction())
                    .as("the payee gives the money back, so their wallet is debited")
                    .isEqualTo(PostingDirection.DEBIT);
            assertThat(lineFor(posting, SOURCE_ACCOUNT).direction())
                    .as("the payer gets it back, so their wallet is credited")
                    .isEqualTo(PostingDirection.CREDIT);
        }

        /**
         * Typed REFUND rather than REVERSAL even when it is full. The schema permits only one
         * REVERSAL per posting, so typing a refund as one would block a legitimate second
         * refund on a payment that had only been partially refunded.
         */
        @Test
        @DisplayName("is typed REFUND even when it is a full refund")
        void fullRefundIsStillARefund() {
            LedgerPostingPayload posting = builder.forPaymentRefunded(refunded(200_000L, true));

            assertThat(posting.postingType())
                    .as("REVERSAL is limited to one per posting; a refund must not consume that slot")
                    .isEqualTo(PostingType.REFUND.name())
                    .isNotEqualTo(PostingType.REVERSAL.name());
            assertThat(posting.narrative()).contains("Full refund");
        }

        @Test
        @DisplayName("links back to the transfer it partially undoes")
        void linksToTheOriginalTransfer() {
            LedgerPostingPayload posting = builder.forPaymentRefunded(refunded(50_000L, false));

            assertThat(posting.reversesLedgerTransactionId())
                    .as("so the reversal chain is walkable from either end")
                    .isEqualTo("LTX-PAY-PAY-0001");
        }

        /**
         * Keyed on the refund, not the payment: a customer may refund the same payment several
         * times up to its original amount, and each must be its own posting.
         */
        @Test
        @DisplayName("is keyed on the refund id, so repeat partial refunds each get a posting")
        void keyedOnRefundId() {
            LedgerPostingPayload posting = builder.forPaymentRefunded(refunded(50_000L, false));

            assertThat(posting.ledgerTransactionId()).isEqualTo("LTX-RFD-RFD-0001");
            assertThat(posting.ledgerTransactionId())
                    .isNotEqualTo(builder.paymentKey("PAY-0001"));
        }
    }

    // -------------------------------------------------------- edge movements

    @Nested
    @DisplayName("a wallet edge movement")
    class EdgeMovement {

        /**
         * A deposit needs an external counterparty, or it would be a single-sided entry —
         * money appearing from nowhere — and the trial balance could never sum to zero.
         */
        @Test
        @DisplayName("a deposit debits external funding and credits the wallet")
        void depositDebitsFundingCreditsWallet() {
            LedgerPostingPayload posting = builder.forWalletMovement(
                    movement(PostingDirection.CREDIT, 100_000L));

            assertBalances(posting);
            assertThat(posting.postingType()).isEqualTo(PostingType.DEPOSIT.name());

            PostingLine funding = lineFor(posting, FUNDING_ACCOUNT);
            assertThat(funding.direction())
                    .as("an asset arrived, and a debit increases an asset")
                    .isEqualTo(PostingDirection.DEBIT);
            assertThat(funding.accountType()).isEqualTo(AccountType.ASSET);

            PostingLine wallet = lineFor(posting, SOURCE_ACCOUNT);
            assertThat(wallet.direction()).isEqualTo(PostingDirection.CREDIT);
            assertThat(wallet.accountType()).isEqualTo(AccountType.LIABILITY);
        }

        /**
         * A deposit genuinely does increase what the platform owes customers, which is the
         * opposite of an internal transfer and worth asserting explicitly.
         */
        @Test
        @DisplayName("a deposit increases both platform assets and platform obligations")
        void depositIncreasesBothSides() {
            LedgerPostingPayload posting = builder.forWalletMovement(
                    movement(PostingDirection.CREDIT, 100_000L));

            assertThat(lineFor(posting, FUNDING_ACCOUNT).signedEffectMinor()).isEqualTo(100_000L);
            assertThat(lineFor(posting, SOURCE_ACCOUNT).signedEffectMinor())
                    .as("we now owe the customer more")
                    .isEqualTo(100_000L);
        }

        @Test
        @DisplayName("a withdrawal is the exact reverse of a deposit")
        void withdrawalIsTheReverse() {
            LedgerPostingPayload posting = builder.forWalletMovement(
                    movement(PostingDirection.DEBIT, 100_000L));

            assertBalances(posting);
            assertThat(posting.postingType()).isEqualTo(PostingType.WITHDRAWAL.name());
            assertThat(lineFor(posting, SOURCE_ACCOUNT).direction())
                    .isEqualTo(PostingDirection.DEBIT);
            assertThat(lineFor(posting, FUNDING_ACCOUNT).direction())
                    .isEqualTo(PostingDirection.CREDIT);
            assertThat(lineFor(posting, SOURCE_ACCOUNT).signedEffectMinor())
                    .as("we owe the customer less")
                    .isEqualTo(-100_000L);
        }

        @Test
        @DisplayName("is keyed on the wallet transaction id")
        void keyedOnWalletTransactionId() {
            LedgerPostingPayload posting = builder.forWalletMovement(
                    movement(PostingDirection.CREDIT, 100_000L));

            assertThat(posting.ledgerTransactionId()).isEqualTo("LTX-WLT-TXN-WLT-0001");
            assertThat(posting.paymentId())
                    .as("an edge movement belongs to no payment")
                    .isNull();
        }

        @Test
        @DisplayName("picks the funding account matching the movement's currency")
        void fundingAccountFollowsCurrency() {
            WalletMovementPayload usd = new WalletMovementPayload(
                    "TXN-WLT-0002", SOURCE_WALLET, "USR-A", PostingDirection.CREDIT,
                    Money.ofMinor(1_000L, "USD"), Money.ofMinor(1_000L, "USD"),
                    "DEPOSIT", "test", Instant.now());

            LedgerPostingPayload posting = builder.forWalletMovement(usd);

            assertThat(posting.currency()).isEqualTo("USD");
            assertThat(lineFor(posting, "asset:external-funding:USD")).isNotNull();
        }
    }

    // ------------------------------------------------------------------ keys

    @Nested
    @DisplayName("idempotency keys")
    class Keys {

        @Test
        @DisplayName("are namespaced per source so identifiers cannot collide")
        void keysAreNamespaced() {
            String shared = "ABC123";

            assertThat(builder.paymentKey(shared))
                    .isNotEqualTo(builder.refundKey(shared))
                    .isNotEqualTo(builder.walletKey(shared));
        }

        /**
         * Truncating would let two distinct movements collide on one key, and the second would
         * be silently discarded as a replay — money lost with no error anywhere.
         */
        @Test
        @DisplayName("refuse to truncate rather than risk two movements sharing a key")
        void refusesToTruncate() {
            String tooLong = "X".repeat(80);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> builder.paymentKey(tooLong))
                    .withMessageContaining("cannot be safely truncated");
        }

        @Test
        @DisplayName("fit within the ledger_transaction_id column for realistic identifiers")
        void fitWithinTheColumn() {
            // Widths taken from the schema: payment_id is VARCHAR(48).
            assertThat(builder.paymentKey("P".repeat(48))).hasSizeLessThanOrEqualTo(80);
            assertThat(builder.refundKey("R".repeat(48))).hasSizeLessThanOrEqualTo(80);
            assertThat(builder.walletKey("W".repeat(48))).hasSizeLessThanOrEqualTo(80);
        }
    }
}
