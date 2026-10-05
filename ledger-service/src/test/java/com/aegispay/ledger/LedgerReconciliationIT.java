package com.aegispay.ledger;

import com.aegispay.common.security.Roles;
import com.aegispay.common.testsupport.TestJwtIssuer;
import com.aegispay.ledger.api.LedgerDtos.AccountReconciliationResponse;
import com.aegispay.ledger.api.LedgerDtos.JournalEntryResponse;
import com.aegispay.ledger.api.LedgerDtos.LedgerReconciliationResponse;
import com.aegispay.ledger.api.LedgerDtos.TrialBalanceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the reconciliation checks can actually fail.
 *
 * <p>This is the point of the class. A reconciliation report that only ever confirms what the
 * write path already believes is worse than having none, because it manufactures confidence: it
 * would pass on a broken ledger just as happily as on a sound one. So every check here is run
 * against state that has been deliberately corrupted behind the application's back, and the
 * report is required to notice.
 *
 * <p>Corrupting the ledger takes real effort, which is itself a result worth having. The CHECK
 * constraints refuse an inconsistent balance outright, and the triggers refuse any edit, so the
 * only ways in are to insert journal lines without updating the cached balances, or to disable a
 * trigger first. Both are done here explicitly.
 */
class LedgerReconciliationIT extends AbstractLedgerIntegrationTest {

    private static String adminToken() {
        return TestJwtIssuer.user("USR-ADMIN", Roles.ADMIN);
    }

    private static String serviceToken() {
        return TestJwtIssuer.service("payment-service");
    }

    /**
     * Restores the triggers after any test that disabled one.
     *
     * <p>Unconditional rather than only in the tests that disable them: a leaked disabled trigger
     * would silently weaken every later test in the class, and the failure would appear somewhere
     * unrelated.
     */
    @AfterEach
    void restoreTriggers() {
        jdbc.execute("ALTER TABLE journal_lines ENABLE TRIGGER trg_journal_lines_balanced");
        jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_balanced");
    }

    /** Books a deposit through the real path, so the accounts and journal agree to begin with. */
    private String fundedWallet(long amountMinor) {
        String walletId = newWalletId();
        Response<JournalEntryResponse> response = post("/internal/ledger/postings", """
                {
                  "ledgerTransactionId": "LTX-REC-DEP-%s",
                  "postingType": "DEPOSIT",
                  "currency": "INR",
                  "lines": [
                    {"accountId": "%s", "accountType": "ASSET",
                     "direction": "DEBIT",  "amountMinor": %d, "memo": "in"},
                    {"accountId": "wallet:%s", "accountType": "LIABILITY",
                     "direction": "CREDIT", "amountMinor": %d, "memo": "deposit"}
                  ]
                }
                """.formatted(walletId, FUNDING_INR, amountMinor, walletId, amountMinor),
                serviceToken(), JournalEntryResponse.class);

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        return walletId;
    }

    private TrialBalanceResponse trialBalance() {
        Response<TrialBalanceResponse> response = get(
                "/api/ledger/admin/trial-balance?currency=INR", adminToken(),
                TrialBalanceResponse.class);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.body()).isNotNull();
        return response.body();
    }

    private LedgerReconciliationResponse reconcileAll() {
        Response<LedgerReconciliationResponse> response = get("/api/ledger/admin/reconcile",
                adminToken(), LedgerReconciliationResponse.class);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.body()).isNotNull();
        return response.body();
    }

    private AccountReconciliationResponse reconcileAccount(String accountId) {
        Response<AccountReconciliationResponse> response = get(
                "/api/ledger/admin/accounts/" + accountId + "/reconcile", adminToken(),
                AccountReconciliationResponse.class);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.body()).isNotNull();
        return response.body();
    }

    // ------------------------------------------------------------ sound ledger

    @Nested
    @DisplayName("a sound ledger")
    class SoundLedger {

        @Test
        @DisplayName("balances, agrees with its journal, and reports no findings")
        void reportsNothingWrong() {
            String walletId = fundedWallet(1000 * RUPEE);

            TrialBalanceResponse balance = trialBalance();
            assertThat(balance.balanced()).isTrue();
            assertThat(balance.differenceMinor()).isZero();
            assertThat(balance.accountsAgreeWithJournal()).isTrue();
            assertThat(balance.totalDebits().amountMinor()).isEqualTo(1000 * RUPEE);
            assertThat(balance.totalCredits().amountMinor()).isEqualTo(1000 * RUPEE);
            assertThat(balance.customerLiability().amountMinor()).isEqualTo(1000 * RUPEE);

            LedgerReconciliationResponse report = reconcileAll();
            assertThat(report.consistent()).isTrue();
            assertThat(report.findings()).isEmpty();
            assertThat(report.unbalancedPostings()).isEmpty();
            assertThat(report.singleSidedPostings()).isEmpty();
            assertThat(report.driftedAccounts()).isEmpty();
            assertThat(report.accountsChecked()).isPositive();

            AccountReconciliationResponse account = reconcileAccount(walletAccount(walletId));
            assertThat(account.consistent()).isTrue();
            assertThat(account.storedBalanceMinor()).isEqualTo(account.replayedBalanceMinor());
            assertThat(account.inconsistentRunningBalanceAt()).isEmpty();
            assertThat(account.lineCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("reports an empty ledger as consistent")
        void emptyLedgerIsConsistent() {
            LedgerReconciliationResponse report = reconcileAll();

            assertThat(report.consistent())
                    .as("the seeded system accounts alone must reconcile")
                    .isTrue();
            assertThat(trialBalance().balanced()).isTrue();
        }
    }

    // ------------------------------------------------------- detecting drift

    @Nested
    @DisplayName("drift between the cached balances and the journal")
    class Drift {

        /**
         * Journal lines inserted without updating the accounts they touch. Every constraint is
         * satisfied — the posting balances, the signed effects are right — but the cached totals
         * no longer describe the journal. This is what a lost update looks like from the outside.
         */
        @Test
        @DisplayName("is detected when journal lines exist that the balances never saw")
        void detectsLinesMissingFromBalances() {
            String walletId = fundedWallet(1000 * RUPEE);
            long before = accountBalance(walletAccount(walletId));

            insertRawPosting("LTX-DRIFT-1", "ADJUSTMENT", 300 * RUPEE, List.of(
                    rawLine(1, FUNDING_INR, "ASSET", "DEBIT", 300 * RUPEE),
                    rawLine(2, walletAccount(walletId), "LIABILITY", "CREDIT", 300 * RUPEE)));

            assertThat(accountBalance(walletAccount(walletId)))
                    .as("the cached balance is untouched, which is precisely the problem")
                    .isEqualTo(before);

            TrialBalanceResponse balance = trialBalance();
            assertThat(balance.balanced())
                    .as("the cached totals still balance against each other")
                    .isTrue();
            assertThat(balance.accountsAgreeWithJournal())
                    .as("but they no longer agree with the journal, and that must be reported")
                    .isFalse();
            assertThat(balance.replayedDebits().amountMinor())
                    .isEqualTo(1300 * RUPEE)
                    .isNotEqualTo(balance.totalDebits().amountMinor());

            LedgerReconciliationResponse report = reconcileAll();
            assertThat(report.consistent()).isFalse();
            assertThat(report.findings())
                    .as("the findings must name the problem in terms an operator can act on")
                    .isNotEmpty()
                    .anySatisfy(finding -> assertThat(finding)
                            .containsIgnoringCase("disagree with the journal"));
        }

        @Test
        @DisplayName("is detected per account, with both figures reported")
        void detectsPerAccountDrift() {
            String walletId = fundedWallet(1000 * RUPEE);

            insertRawPosting("LTX-DRIFT-2", "ADJUSTMENT", 250 * RUPEE, List.of(
                    rawLine(1, FUNDING_INR, "ASSET", "DEBIT", 250 * RUPEE),
                    rawLine(2, walletAccount(walletId), "LIABILITY", "CREDIT", 250 * RUPEE)));

            AccountReconciliationResponse account = reconcileAccount(walletAccount(walletId));

            assertThat(account.consistent()).isFalse();
            assertThat(account.balanceMatches()).isFalse();
            assertThat(account.storedBalanceMinor()).isEqualTo(1000 * RUPEE);
            assertThat(account.replayedBalanceMinor())
                    .as("the replay must show what the journal actually says")
                    .isEqualTo(1250 * RUPEE);
            assertThat(account.totalsMatch()).isFalse();

            LedgerReconciliationResponse report = reconcileAll();
            assertThat(report.driftedAccounts())
                    .extracting(AccountReconciliationResponse::accountId)
                    .contains(walletAccount(walletId));
        }

        /**
         * A break in the running-balance chain. Each line is individually valid and the account
         * total is right, but the sequence of balances does not add up — which is the signature
         * of a lost update that was later papered over.
         */
        @Test
        @DisplayName("is detected when the running-balance chain does not add up")
        void detectsBrokenRunningBalance() {
            String walletId = fundedWallet(1000 * RUPEE);

            // balance_after deliberately wrong: the chain claims 9999 after this line.
            insertRawPosting("LTX-DRIFT-3", "ADJUSTMENT", 100 * RUPEE, List.of(
                    rawLine(1, FUNDING_INR, "ASSET", "DEBIT", 100 * RUPEE, 0L),
                    rawLine(2, walletAccount(walletId), "LIABILITY", "CREDIT", 100 * RUPEE, 9999L)));

            AccountReconciliationResponse account = reconcileAccount(walletAccount(walletId));

            assertThat(account.inconsistentRunningBalanceAt())
                    .as("a statement whose balances do not chain is not auditable")
                    .isNotEmpty();
            assertThat(account.consistent()).isFalse();
        }
    }

    // ------------------------------------------- verifying the guarantee itself

    @Nested
    @DisplayName("checks that verify the database guarantees rather than assume them")
    class VerifiesGuarantees {

        /**
         * The unbalanced-posting check exists for the case where the constraint trigger has been
         * dropped or disabled. Disabling it here is the only way to reach that state, and it is
         * exactly the state the check is for — so this test proves the check is real rather than
         * a comment.
         */
        @Test
        @DisplayName("an unbalanced posting is reported when the trigger has been disabled")
        void detectsUnbalancedPostingWithTriggerDisabled() {
            String walletId = fundedWallet(1000 * RUPEE);

            jdbc.execute("ALTER TABLE journal_lines DISABLE TRIGGER trg_journal_lines_balanced");
            jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_balanced");

            // Debit 500, credit 200. Impossible while the trigger is enabled.
            insertRawPosting("LTX-BROKEN-1", "ADJUSTMENT", 500L, List.of(
                    rawLine(1, walletAccount(walletId), "LIABILITY", "DEBIT", 500L),
                    rawLine(2, FUNDING_INR, "ASSET", "CREDIT", 200L)));

            LedgerReconciliationResponse report = reconcileAll();

            assertThat(report.consistent()).isFalse();
            assertThat(report.unbalancedPostings())
                    .as("the report must find the posting the trigger would have refused")
                    .isNotEmpty();
            assertThat(report.findings())
                    .as("and it must say that the trigger itself is missing, not merely list "
                            + "sequence numbers, because that is the real finding")
                    .anySatisfy(finding -> assertThat(finding)
                            .containsIgnoringCase("trg_journal_lines_balanced"));
        }

        @Test
        @DisplayName("a single-sided posting is reported when the trigger has been disabled")
        void detectsSingleSidedPostingWithTriggerDisabled() {
            String walletId = fundedWallet(1000 * RUPEE);

            jdbc.execute("ALTER TABLE journal_lines DISABLE TRIGGER trg_journal_lines_balanced");
            jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_balanced");

            insertRawPosting("LTX-BROKEN-2", "ADJUSTMENT", 100L, List.of(
                    rawLine(1, walletAccount(walletId), "LIABILITY", "CREDIT", 100L)));

            LedgerReconciliationResponse report = reconcileAll();

            assertThat(report.singleSidedPostings()).isNotEmpty();
            assertThat(report.findings())
                    .anySatisfy(finding -> assertThat(finding)
                            .containsIgnoringCase("fewer than two legs"));
        }

        /**
         * Re-enabling must actually restore the guarantee. Without this, a leaked disabled trigger
         * would silently weaken every later test and the failure would surface somewhere
         * unrelated.
         */
        @Test
        @DisplayName("re-enabling the trigger restores the guarantee")
        void reEnablingRestoresTheGuarantee() {
            String walletId = fundedWallet(1000 * RUPEE);

            jdbc.execute("ALTER TABLE journal_lines DISABLE TRIGGER trg_journal_lines_balanced");
            jdbc.execute("ALTER TABLE journal_entries DISABLE TRIGGER trg_journal_entries_balanced");
            insertRawPosting("LTX-BROKEN-3", "ADJUSTMENT", 500L, List.of(
                    rawLine(1, walletAccount(walletId), "LIABILITY", "DEBIT", 500L),
                    rawLine(2, FUNDING_INR, "ASSET", "CREDIT", 200L)));

            jdbc.execute("ALTER TABLE journal_lines ENABLE TRIGGER trg_journal_lines_balanced");
            jdbc.execute("ALTER TABLE journal_entries ENABLE TRIGGER trg_journal_entries_balanced");

            org.assertj.core.api.Assertions
                    .assertThatExceptionOfType(org.springframework.dao.DataAccessException.class)
                    .isThrownBy(() -> insertRawPosting("LTX-BROKEN-4", "ADJUSTMENT", 500L, List.of(
                            rawLine(1, walletAccount(walletId), "LIABILITY", "DEBIT", 500L),
                            rawLine(2, FUNDING_INR, "ASSET", "CREDIT", 200L))));
        }
    }

    // ------------------------------------------------------------ scoping

    @Nested
    @DisplayName("wallet liability snapshot")
    class WalletLiabilities {

        /**
         * The figure wallet-service's own total must equal. Comparable only because the ledger
         * books deposits and withdrawals as well as transfers; a ledger fed by payment events
         * alone would be short by every deposit ever made and the comparison would be guaranteed
         * to fail.
         */
        @Test
        @DisplayName("reports per-wallet balances keyed by wallet id, not account id")
        void reportsPerWalletBalances() {
            String walletA = fundedWallet(1000 * RUPEE);
            String walletB = fundedWallet(400 * RUPEE);

            Response<String> response = get("/internal/ledger/wallet-liabilities?currency=INR",
                    serviceToken(), String.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull()
                    .as("keyed by wallet id so a caller can match it against its own records "
                            + "without knowing the ledger's account naming")
                    .contains(walletA)
                    .contains(walletB)
                    .doesNotContain("asset:external-funding");
        }

        @Test
        @DisplayName("excludes system accounts from the customer total")
        void excludesSystemAccounts() {
            fundedWallet(1000 * RUPEE);

            TrialBalanceResponse balance = trialBalance();

            assertThat(balance.customerLiability().amountMinor())
                    .as("the external-funding asset must not be counted as money owed to customers")
                    .isEqualTo(1000 * RUPEE);
        }

        @Test
        @DisplayName("denies the snapshot to a human token")
        void deniesSnapshotToHumans() {
            assertThat(get("/internal/ledger/wallet-liabilities?currency=INR", adminToken(),
                    String.class).status())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
