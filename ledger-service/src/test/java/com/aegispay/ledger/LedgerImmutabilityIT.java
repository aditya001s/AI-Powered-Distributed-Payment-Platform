package com.aegispay.ledger;

import com.aegispay.common.testsupport.TestJwtIssuer;
import com.aegispay.ledger.api.LedgerDtos.JournalEntryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves the database itself refuses to let the journal be rewritten or unbalanced.
 *
 * <p>Every test here attacks the schema directly with SQL, bypassing the application entirely.
 * That is the whole point: the guarantees under test are worth having precisely because they do
 * not depend on the application behaving. A test that went through the service layer would only
 * show that the service does not attempt these things — not that they are impossible.
 *
 * <p>These are the assertions that let the journal honestly be called a book of record. If any
 * of them failed, "append-only" and "always balanced" would be conventions rather than
 * guarantees, and an auditor would be right not to trust the output.
 */
class LedgerImmutabilityIT extends AbstractLedgerIntegrationTest {

    private String serviceToken() {
        return TestJwtIssuer.service("payment-service");
    }

    /**
     * Funds a wallet the way the platform does: a deposit posting against external funding.
     * Needed because a customer wallet liability may not go negative, so nothing can be
     * transferred out of a wallet that never received anything.
     */
    private String fundedWallet(long amountMinor) {
        String walletId = newWalletId();
        String key = "LTX-DEP-" + walletId;

        Response<JournalEntryResponse> response = post("/internal/ledger/postings", """
                {
                  "ledgerTransactionId": "%s",
                  "postingType": "DEPOSIT",
                  "currency": "INR",
                  "narrative": "funding fixture",
                  "lines": [
                    {"accountId": "%s", "accountType": "ASSET",
                     "direction": "DEBIT",  "amountMinor": %d, "memo": "funds in"},
                    {"accountId": "wallet:%s", "accountType": "LIABILITY",
                     "direction": "CREDIT", "amountMinor": %d, "memo": "deposit"}
                  ]
                }
                """.formatted(key, FUNDING_INR, amountMinor, walletId, amountMinor),
                serviceToken(), JournalEntryResponse.class);

        assertThat(response.body()).isNotNull();
        return walletId;
    }

    // ------------------------------------------------------------ append-only

    @Nested
    @DisplayName("the journal is append-only")
    class AppendOnly {

        @Test
        @DisplayName("an UPDATE on a posted entry is refused by the database")
        void cannotUpdateAnEntry() {
            String walletId = fundedWallet(1000 * RUPEE);
            long sequence = journalSequenceOf("LTX-DEP-" + walletId);

            assertThatExceptionOfType(DataAccessException.class)
                    .as("""
                            Rewriting a narrative is the mildest possible tamper, and it must \
                            still fail. If this succeeded, nothing about the journal's history \
                            could be trusted.""")
                    .isThrownBy(() -> jdbc.update(
                            "UPDATE journal_entries SET narrative = 'tampered' "
                                    + "WHERE journal_sequence = ?", sequence))
                    .withMessageContaining("append-only");

            assertThat(jdbc.queryForObject(
                    "SELECT narrative FROM journal_entries WHERE journal_sequence = ?",
                    String.class, sequence))
                    .isEqualTo("funding fixture");
        }

        /**
         * The most valuable single assertion in the suite. Editing an amount is how books are
         * cooked, and it must be impossible even for someone holding a database connection.
         */
        @Test
        @DisplayName("an UPDATE on a posted amount is refused by the database")
        void cannotUpdateALineAmount() {
            String walletId = fundedWallet(1000 * RUPEE);
            long sequence = journalSequenceOf("LTX-DEP-" + walletId);

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> jdbc.update(
                            "UPDATE journal_lines SET amount_minor = 1 "
                                    + "WHERE journal_sequence = ?", sequence))
                    .withMessageContaining("append-only");

            assertThat(lineTotalByDirection(INR, "DEBIT"))
                    .as("the original amount must survive the attempt")
                    .isEqualTo(1000 * RUPEE);
        }

        @Test
        @DisplayName("a DELETE on a posted entry is refused by the database")
        void cannotDeleteAnEntry() {
            String walletId = fundedWallet(1000 * RUPEE);
            long sequence = journalSequenceOf("LTX-DEP-" + walletId);

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> jdbc.update(
                            "DELETE FROM journal_entries WHERE journal_sequence = ?", sequence));

            assertThat(countEntries()).isEqualTo(1);
        }

        @Test
        @DisplayName("a DELETE on a posted line is refused by the database")
        void cannotDeleteALine() {
            String walletId = fundedWallet(1000 * RUPEE);
            long sequence = journalSequenceOf("LTX-DEP-" + walletId);

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> jdbc.update(
                            "DELETE FROM journal_lines WHERE journal_sequence = ?", sequence));

            assertThat(countLines(sequence)).isEqualTo(2);
        }

        /**
         * The error names the remedy. An operator who hits this needs to know what to do
         * instead, not merely that they were stopped.
         */
        @Test
        @DisplayName("the refusal explains that a correction must be a new reversing entry")
        void refusalExplainsTheRemedy() {
            String walletId = fundedWallet(500 * RUPEE);
            long sequence = journalSequenceOf("LTX-DEP-" + walletId);

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> jdbc.update(
                            "UPDATE journal_entries SET total_minor = 1 "
                                    + "WHERE journal_sequence = ?", sequence))
                    .withMessageContaining("reversing entry");
        }
    }

    // -------------------------------------------------------- always balanced

    @Nested
    @DisplayName("every posting must balance")
    class AlwaysBalanced {

        /**
         * Hand-written SQL that inserts a deliberately unbalanced posting. The deferred
         * constraint trigger fires at COMMIT, so this is refused even though every individual
         * statement is perfectly valid — which is exactly why the check has to be deferred
         * rather than per-statement.
         */
        @Test
        @DisplayName("an unbalanced posting cannot be committed, even by raw SQL")
        void cannotCommitAnUnbalancedPosting() {
            String walletId = fundedWallet(1000 * RUPEE);

            assertThatExceptionOfType(DataAccessException.class)
                    .as("""
                            The application checks this twice, but the guarantee is the deferred \
                            constraint trigger: it holds against a hand-written INSERT, which no \
                            amount of application validation can.""")
                    .isThrownBy(() -> insertRawPosting("LTX-BAD-UNBALANCED", "ADJUSTMENT", 1000 * RUPEE,
                            List.of(
                                    rawLine(1, walletAccount(walletId), "LIABILITY", "DEBIT",
                                            1000 * RUPEE),
                                    // Credit deliberately smaller than the debit.
                                    rawLine(2, FUNDING_INR, "ASSET", "CREDIT", 400 * RUPEE))))
                    .withMessageContaining("unbalanced");

            assertThat(countEntriesWithKey("LTX-BAD-UNBALANCED")).isZero();
        }

        @Test
        @DisplayName("a posting whose declared total disagrees with its lines is refused")
        void cannotCommitAMisdeclaredTotal() {
            String walletId = fundedWallet(1000 * RUPEE);

            assertThatExceptionOfType(DataAccessException.class)
                    .as("""
                            Reports read total_minor rather than summing the lines, so a wrong \
                            declared total would make every report wrong while the posting itself \
                            balanced.""")
                    .isThrownBy(() -> insertRawPosting("LTX-BAD-TOTAL", "ADJUSTMENT", 999 * RUPEE,
                            List.of(
                                    rawLine(1, walletAccount(walletId), "LIABILITY", "DEBIT",
                                            100 * RUPEE),
                                    rawLine(2, FUNDING_INR, "ASSET", "CREDIT", 100 * RUPEE))))
                    .withMessageContaining("declares total");

            assertThat(countEntriesWithKey("LTX-BAD-TOTAL")).isZero();
        }

        /**
         * An entry with one leg, or none, is not a double entry. The per-line trigger cannot
         * catch the zero-line case at all, which is why the constraint trigger is also attached
         * to {@code journal_entries}.
         */
        @Test
        @DisplayName("a posting with no lines cannot be committed")
        void cannotCommitAnEntryWithNoLines() {
            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> insertRawPosting("LTX-BAD-EMPTY", "ADJUSTMENT", 100L, List.of()))
                    .withMessageContaining("at least two");

            assertThat(countEntriesWithKey("LTX-BAD-EMPTY")).isZero();
        }

        @Test
        @DisplayName("a posting with a single leg cannot be committed")
        void cannotCommitASingleSidedEntry() {
            String walletId = fundedWallet(1000 * RUPEE);

            assertThatExceptionOfType(DataAccessException.class)
                    .as("a single-sided entry is money appearing from nowhere")
                    .isThrownBy(() -> insertRawPosting("LTX-BAD-SINGLE", "ADJUSTMENT", 100L,
                            List.of(rawLine(1, walletAccount(walletId), "LIABILITY", "CREDIT", 100L))))
                    .withMessageContaining("at least two");

            assertThat(countEntriesWithKey("LTX-BAD-SINGLE")).isZero();
        }

        /** A correctly balanced raw posting must still be accepted, or the trigger is too strict. */
        @Test
        @DisplayName("a correctly balanced raw posting is accepted")
        void acceptsABalancedRawPosting() {
            String walletId = fundedWallet(1000 * RUPEE);

            insertRawPosting("LTX-GOOD-RAW", "ADJUSTMENT", 250 * RUPEE, List.of(
                    rawLine(1, walletAccount(walletId), "LIABILITY", "DEBIT", 250 * RUPEE),
                    rawLine(2, FUNDING_INR, "ASSET", "CREDIT", 250 * RUPEE)));

            assertThat(countEntriesWithKey("LTX-GOOD-RAW"))
                    .as("the trigger must refuse only what is actually wrong")
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------- derived-value integrity

    @Nested
    @DisplayName("cached values cannot drift from their own inputs")
    class DerivedValues {

        /**
         * The account balance is derived from its two totals by a CHECK constraint. Writing an
         * inconsistent one is refused, so a sign error in Java becomes a failed commit rather
         * than a plausible-looking wrong number.
         */
        @Test
        @DisplayName("a balance that disagrees with its own totals cannot be stored")
        void cannotStoreAnInconsistentBalance() {
            String walletId = fundedWallet(1000 * RUPEE);

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> jdbc.update(
                            "UPDATE ledger_accounts SET balance_minor = 999 WHERE account_id = ?",
                            walletAccount(walletId)))
                    .withMessageContaining("ck_ledger_accounts_balance_derived");

            assertThat(accountBalance(walletAccount(walletId))).isEqualTo(1000 * RUPEE);
        }

        /**
         * The platform cannot owe a customer a negative amount, so an over-debited wallet is
         * always a bug and is refused at the storage layer rather than discovered at audit.
         */
        @Test
        @DisplayName("a customer wallet cannot be driven negative")
        void walletCannotGoNegative() {
            String walletId = fundedWallet(100 * RUPEE);

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> jdbc.update("""
                            UPDATE ledger_accounts
                               SET debit_total_minor = credit_total_minor + 1,
                                   balance_minor = -1
                             WHERE account_id = ?
                            """, walletAccount(walletId)))
                    .withMessageContaining("ck_ledger_accounts_negative_allowed");
        }

        /**
         * A clearing account legitimately swings both ways mid-saga, so the non-negative rule is
         * per-account rather than global. Asserting both halves shows the rule is targeted
         * rather than accidental.
         */
        @Test
        @DisplayName("a clearing account may go negative, because it legitimately swings")
        void clearingAccountMayGoNegative() {
            jdbc.update("""
                    UPDATE ledger_accounts
                       SET credit_total_minor = 500, balance_minor = debit_total_minor - 500
                     WHERE account_id = ?
                    """, CLEARING_INR);

            assertThat(accountBalance(CLEARING_INR)).isEqualTo(-500L);
        }

        @Test
        @DisplayName("a line whose signed effect contradicts its direction cannot be stored")
        void cannotStoreAContradictorySignedEffect() {
            String walletId = fundedWallet(1000 * RUPEE);

            assertThatExceptionOfType(DataAccessException.class)
                    .as("""
                            A liability credited increases it, so signed_effect must be positive. \
                            Storing the opposite would corrupt every replay that sums this column.""")
                    .isThrownBy(() -> insertRawLineWithSignedEffect(
                            journalSequenceOf("LTX-DEP-" + walletId),
                            walletAccount(walletId), "LIABILITY", "CREDIT", 100L, -100L))
                    .withMessageContaining("ck_journal_lines_signed_effect");
        }

        @Test
        @DisplayName("a non-positive line amount cannot be stored")
        void cannotStoreANonPositiveAmount() {
            String walletId = fundedWallet(1000 * RUPEE);

            assertThatExceptionOfType(DataAccessException.class)
                    .as("direction carries the sign; a negative amount would make every SUM "
                            + "over this table ambiguous")
                    .isThrownBy(() -> insertRawPosting("LTX-BAD-NEGATIVE", "ADJUSTMENT", 100L, List.of(
                            rawLine(1, walletAccount(walletId), "LIABILITY", "DEBIT", -100L),
                            rawLine(2, FUNDING_INR, "ASSET", "CREDIT", -100L))));
        }
    }

    // ------------------------------------------------------------- raw SQL

    /** Appends a line with a deliberately wrong signed effect, to exercise that CHECK. */
    private void insertRawLineWithSignedEffect(long journalSequence, String accountId,
                                               String accountType, String direction,
                                               long amountMinor, long signedEffectMinor) {
        jdbc.update("""
                INSERT INTO journal_lines
                    (journal_sequence, line_number, account_id, account_type, direction,
                     amount_minor, currency, signed_effect_minor, balance_after_minor, memo)
                VALUES (?, 99, ?, ?, ?, ?, 'INR', ?, 0, 'raw sql fixture')
                """, journalSequence, accountId, accountType, direction, amountMinor,
                signedEffectMinor);
    }
}
