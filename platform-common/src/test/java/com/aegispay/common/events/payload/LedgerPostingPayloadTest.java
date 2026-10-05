package com.aegispay.common.events.payload;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.events.payload.LedgerPostingPayload.PostingLine;
import com.aegispay.common.exception.DomainExceptions.LedgerImbalanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the double-entry invariant.
 *
 * <p>{@code total debits == total credits} is the single property that makes a
 * ledger trustworthy. It is validated in the payload constructor so an unbalanced
 * posting cannot even be constructed, let alone published or committed — these tests
 * pin that down.
 */
class LedgerPostingPayloadTest {

    private static final String LEDGER_TX = "LTX-0001";
    private static final String PAYMENT_ID = "PAY-0001";
    private static final String TRANSACTION_ID = "TXN12345";

    @Test
    @DisplayName("accepts a balanced two-line transfer")
    void acceptsBalancedTransfer() {
        LedgerPostingPayload posting = posting(List.of(
                PostingLine.debit(LedgerPostingPayload.walletAccount("WAL-A"),
                        AccountType.LIABILITY, 200_000L, "Transfer out"),
                PostingLine.credit(LedgerPostingPayload.walletAccount("WAL-B"),
                        AccountType.LIABILITY, 200_000L, "Transfer in")));

        assertThat(posting.totalMinor()).isEqualTo(200_000L);
        assertThat(posting.lines()).hasSize(2);
    }

    /**
     * The realistic case: a transfer plus a platform fee. Three lines, still
     * balanced, because the payer is debited the full amount including the fee.
     */
    @Test
    @DisplayName("accepts a balanced three-line transfer with a fee")
    void acceptsTransferWithFee() {
        LedgerPostingPayload posting = posting(List.of(
                // Payer gives up 2,005.00 in total
                PostingLine.debit(LedgerPostingPayload.walletAccount("WAL-A"),
                        AccountType.LIABILITY, 200_500L, "Transfer out including fee"),
                // Payee receives 2,000.00
                PostingLine.credit(LedgerPostingPayload.walletAccount("WAL-B"),
                        AccountType.LIABILITY, 200_000L, "Transfer in"),
                // Platform earns 5.00
                PostingLine.credit(LedgerPostingPayload.feeRevenueAccount("INR"),
                        AccountType.REVENUE, 500L, "Platform fee")));

        assertThat(posting.totalMinor()).isEqualTo(200_500L);
    }

    @Test
    @DisplayName("rejects an unbalanced posting and reports the difference")
    void rejectsUnbalancedPosting() {
        List<PostingLine> unbalanced = List.of(
                PostingLine.debit("wallet:WAL-A", AccountType.LIABILITY, 200_000L, "out"),
                // Credit is 1 paisa short: the classic off-by-one that must never commit.
                PostingLine.credit("wallet:WAL-B", AccountType.LIABILITY, 199_999L, "in"));

        assertThatExceptionOfType(LedgerImbalanceException.class)
                .isThrownBy(() -> posting(unbalanced))
                .satisfies(ex -> {
                    assertThat(ex.details()).containsEntry("totalDebitMinor", 200_000L);
                    assertThat(ex.details()).containsEntry("totalCreditMinor", 199_999L);
                    assertThat(ex.details()).containsEntry("differenceMinor", 1L);
                });
    }

    @Test
    @DisplayName("rejects a posting with fewer than two lines")
    void rejectsIncompletePosting() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> posting(List.of()))
                .withMessageContaining("at least two lines");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> posting(null))
                .withMessageContaining("at least two lines");
    }

    /**
     * A zero-value posting is impossible to construct: {@code PostingLine} rejects a
     * non-positive amount, so no combination of valid lines can sum to zero. The
     * payload's own {@code debits == 0} guard is therefore defence in depth against a
     * future change to line validation, and is intentionally not covered by a test
     * that would have to fabricate an invalid line to reach it.
     */
    @Test
    @DisplayName("cannot construct a posting that moves nothing")
    void cannotConstructZeroValuePosting() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingLine.debit("wallet:WAL-A", AccountType.LIABILITY, 0L, "zero"))
                .withMessageContaining("must be positive");
    }

    /**
     * Direction, not sign, carries the meaning. Allowing a negative debit would
     * create two ways to express the same movement and make the balance check
     * ambiguous.
     */
    @Test
    @DisplayName("rejects a non-positive line amount")
    void rejectsNonPositiveLineAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingLine.debit("wallet:WAL-A", AccountType.LIABILITY, -100L, "bad"))
                .withMessageContaining("must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingLine.credit("wallet:WAL-A", AccountType.LIABILITY, 0L, "bad"));
    }

    @Test
    @DisplayName("requires an account id on every line")
    void requiresAccountId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingLine.debit("  ", AccountType.LIABILITY, 100L, "bad"))
                .withMessageContaining("accountId");
    }

    /**
     * A debit increases an asset but decreases a liability. Customer wallet balances
     * are a liability of the platform (we owe the customer their money), and getting
     * this backwards is the single most common modelling error in payment systems.
     */
    @Test
    @DisplayName("applies the correct sign per account type")
    void appliesAccountTypeSemantics() {
        // Liability: a debit reduces what we owe the customer.
        assertThat(PostingLine.debit("wallet:WAL-A", AccountType.LIABILITY, 100L, "")
                .signedEffectMinor()).isEqualTo(-100L);
        // Liability: a credit increases what we owe.
        assertThat(PostingLine.credit("wallet:WAL-B", AccountType.LIABILITY, 100L, "")
                .signedEffectMinor()).isEqualTo(100L);
        // Asset: a debit increases the balance we hold.
        assertThat(PostingLine.debit("asset:settlement:PROV_A", AccountType.ASSET, 100L, "")
                .signedEffectMinor()).isEqualTo(100L);
        // Revenue: a credit increases earnings.
        assertThat(PostingLine.credit("revenue:fees:INR", AccountType.REVENUE, 100L, "")
                .signedEffectMinor()).isEqualTo(100L);
    }

    @Test
    @DisplayName("builds canonical account identifiers")
    void buildsCanonicalAccountIds() {
        assertThat(LedgerPostingPayload.walletAccount("WAL-123")).isEqualTo("wallet:WAL-123");
        assertThat(LedgerPostingPayload.feeRevenueAccount("inr")).isEqualTo("revenue:fees:INR");
        // Settlement balances are held per provider AND per currency: one account per provider
        // would net a rupee balance against a dollar one and report a number that is not money.
        assertThat(LedgerPostingPayload.providerSettlementAccount("SwiftPay", "inr"))
                .isEqualTo("asset:settlement:SWIFTPAY:INR");
        assertThat(LedgerPostingPayload.providerSettlementAccount("NEXUSPAY", "USD"))
                .isEqualTo("asset:settlement:NEXUSPAY:USD");
        assertThat(LedgerPostingPayload.clearingAccount("inr")).isEqualTo("clearing:in-flight:INR");
    }

    @Test
    @DisplayName("exposes an immutable line list")
    void linesAreImmutable() {
        LedgerPostingPayload posting = posting(List.of(
                PostingLine.debit("wallet:WAL-A", AccountType.LIABILITY, 100L, "out"),
                PostingLine.credit("wallet:WAL-B", AccountType.LIABILITY, 100L, "in")));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> posting.lines().add(
                        PostingLine.debit("wallet:WAL-C", AccountType.LIABILITY, 1L, "sneaky")));
    }

    // ------------------------------------------------------------------ helpers

    private LedgerPostingPayload posting(List<PostingLine> lines) {
        return new LedgerPostingPayload(
                LEDGER_TX, PAYMENT_ID, TRANSACTION_ID, "TRANSFER", "INR",
                lines, "Wallet transfer", null, Instant.now());
    }
}
