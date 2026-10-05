package com.aegispay.ledger;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.security.Roles;
import com.aegispay.common.testsupport.TestJwtIssuer;
import com.aegispay.ledger.api.LedgerDtos.AccountResponse;
import com.aegispay.ledger.api.LedgerDtos.JournalEntryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Posting behaviour through the internal API: idempotency, reversal, concurrency and access.
 *
 * <p>Assertions read the tables directly wherever a bug could still produce a plausible HTTP
 * response. A ledger fails in exactly that way — a 201 for a posting that double-applied, a
 * balance that looks right while the journal behind it does not add up — so the response body is
 * never the only thing checked.
 */
class LedgerPostingIT extends AbstractLedgerIntegrationTest {

    private static String serviceToken() {
        return TestJwtIssuer.service("payment-service");
    }

    private static String adminToken() {
        return TestJwtIssuer.user("USR-ADMIN", Roles.ADMIN);
    }

    // ------------------------------------------------------------- fixtures

    /** A two-line deposit: debit external funding, credit the wallet. */
    private String depositBody(String key, String walletId, long amountMinor) {
        return """
                {
                  "ledgerTransactionId": "%s",
                  "postingType": "DEPOSIT",
                  "currency": "INR",
                  "narrative": "deposit fixture",
                  "lines": [
                    {"accountId": "%s", "accountType": "ASSET",
                     "direction": "DEBIT",  "amountMinor": %d, "memo": "funds in"},
                    {"accountId": "wallet:%s", "accountType": "LIABILITY",
                     "direction": "CREDIT", "amountMinor": %d, "memo": "deposit"}
                  ]
                }
                """.formatted(key, FUNDING_INR, amountMinor, walletId, amountMinor);
    }

    private String transferBody(String key, String fromWallet, String toWallet, long amountMinor) {
        return """
                {
                  "ledgerTransactionId": "%s",
                  "paymentId": "PAY-FIXTURE",
                  "postingType": "TRANSFER",
                  "currency": "INR",
                  "narrative": "transfer fixture",
                  "lines": [
                    {"accountId": "wallet:%s", "accountType": "LIABILITY",
                     "direction": "DEBIT",  "amountMinor": %d, "memo": "out"},
                    {"accountId": "wallet:%s", "accountType": "LIABILITY",
                     "direction": "CREDIT", "amountMinor": %d, "memo": "in"}
                  ]
                }
                """.formatted(key, fromWallet, amountMinor, toWallet, amountMinor);
    }

    /** Funds a wallet through a real deposit posting, so its liability can afford a debit. */
    private String fundedWallet(long amountMinor) {
        String walletId = newWalletId();
        Response<JournalEntryResponse> response = post("/internal/ledger/postings",
                depositBody("LTX-DEP-" + walletId, walletId, amountMinor),
                serviceToken(), JournalEntryResponse.class);
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        return walletId;
    }

    // -------------------------------------------------------------- posting

    @Nested
    @DisplayName("posting a double entry")
    class Posting {

        @Test
        @DisplayName("appends the entry, its legs, and applies both account balances")
        void appendsAndApplies() {
            String walletId = newWalletId();

            Response<JournalEntryResponse> response = post("/internal/ledger/postings",
                    depositBody("LTX-DEP-1", walletId, 1000 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
            JournalEntryResponse entry = response.body();
            assertThat(entry).isNotNull();
            assertThat(entry.journalSequence()).isPositive();
            assertThat(entry.duplicate()).isFalse();
            assertThat(entry.total().amountMinor()).isEqualTo(1000 * RUPEE);
            assertThat(entry.lines()).hasSize(2);
            assertThat(entry.source())
                    .as("the caller's identity comes from the token, not the request body")
                    .isEqualTo("payment-service");

            // An asset arrived, and we now owe the customer.
            assertThat(accountBalance(FUNDING_INR)).isEqualTo(1000 * RUPEE);
            assertThat(accountBalance(walletAccount(walletId))).isEqualTo(1000 * RUPEE);
            assertThat(accountDebitTotal(FUNDING_INR)).isEqualTo(1000 * RUPEE);
            assertThat(accountCreditTotal(walletAccount(walletId))).isEqualTo(1000 * RUPEE);

            // The identity that makes the books trustworthy.
            assertThat(trialBalanceDifference(INR))
                    .as("total debits must equal total credits")
                    .isZero();
        }

        @Test
        @DisplayName("records the balance each leg produced, so a statement needs no replay")
        void recordsRunningBalances() {
            String walletId = fundedWallet(1000 * RUPEE);
            String payee = newWalletId();

            JournalEntryResponse transfer = post("/internal/ledger/postings",
                    transferBody("LTX-TRF-1", walletId, payee, 300 * RUPEE),
                    serviceToken(), JournalEntryResponse.class).body();

            assertThat(transfer).isNotNull();
            assertThat(transfer.lines())
                    .filteredOn(line -> line.accountId().equals(walletAccount(walletId)))
                    .singleElement()
                    .satisfies(line -> assertThat(line.balanceAfter().amountMinor())
                            .as("1000 deposited, 300 sent")
                            .isEqualTo(700 * RUPEE));
        }

        /**
         * Customer wallet accounts are unbounded in number and appear as people join, so they
         * are created on first use rather than declared in a migration.
         */
        @Test
        @DisplayName("creates a customer wallet account on first use, as a liability")
        void provisionsWalletAccounts() {
            String walletId = newWalletId();
            assertThat(accountExists(walletAccount(walletId))).isFalse();

            post("/internal/ledger/postings", depositBody("LTX-DEP-2", walletId, 500 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);

            assertThat(accountExists(walletAccount(walletId))).isTrue();
            assertThat(accountType(walletAccount(walletId)))
                    .as("a wallet is what the platform owes a customer")
                    .isEqualTo("LIABILITY");
        }

        /**
         * Inventing a system account would mean guessing its type, and a wrong type silently
         * inverts every posting against it. Failing is the only safe response.
         */
        @Test
        @DisplayName("refuses to invent a missing system account")
        void refusesToInventSystemAccounts() {
            String body = """
                    {
                      "ledgerTransactionId": "LTX-BAD-ACCOUNT",
                      "postingType": "ADJUSTMENT",
                      "currency": "INR",
                      "lines": [
                        {"accountId": "revenue:invented:INR", "accountType": "REVENUE",
                         "direction": "CREDIT", "amountMinor": 100, "memo": "x"},
                        {"accountId": "%s", "accountType": "ASSET",
                         "direction": "DEBIT", "amountMinor": 100, "memo": "y"}
                      ]
                    }
                    """.formatted(FUNDING_INR);

            Response<ApiError> response = post("/internal/ledger/postings", body,
                    serviceToken(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(countEntriesWithKey("LTX-BAD-ACCOUNT")).isZero();
        }

        /**
         * The caller chose each line's direction from the type it believed the account to be, so
         * a mismatch means the posting means something other than what was intended. Silently
         * correcting it would book the opposite.
         */
        @Test
        @DisplayName("refuses a line whose declared account type is wrong")
        void refusesMisdeclaredAccountType() {
            String walletId = fundedWallet(500 * RUPEE);

            String body = """
                    {
                      "ledgerTransactionId": "LTX-BAD-TYPE",
                      "postingType": "ADJUSTMENT",
                      "currency": "INR",
                      "lines": [
                        {"accountId": "wallet:%s", "accountType": "ASSET",
                         "direction": "DEBIT", "amountMinor": 100, "memo": "x"},
                        {"accountId": "%s", "accountType": "ASSET",
                         "direction": "CREDIT", "amountMinor": 100, "memo": "y"}
                      ]
                    }
                    """.formatted(walletId, FUNDING_INR);

            Response<ApiError> response = post("/internal/ledger/postings", body,
                    serviceToken(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(countEntriesWithKey("LTX-BAD-TYPE")).isZero();
        }

        @Test
        @DisplayName("rejects an unbalanced posting at the API boundary")
        void rejectsUnbalancedPosting() {
            String walletId = newWalletId();

            String body = """
                    {
                      "ledgerTransactionId": "LTX-BAD-BALANCE",
                      "postingType": "DEPOSIT",
                      "currency": "INR",
                      "lines": [
                        {"accountId": "%s", "accountType": "ASSET",
                         "direction": "DEBIT", "amountMinor": 1000, "memo": "x"},
                        {"accountId": "wallet:%s", "accountType": "LIABILITY",
                         "direction": "CREDIT", "amountMinor": 400, "memo": "y"}
                      ]
                    }
                    """.formatted(FUNDING_INR, walletId);

            Response<ApiError> response = post("/internal/ledger/postings", body,
                    serviceToken(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.LEDGER_IMBALANCE.name());
            assertThat(countEntriesWithKey("LTX-BAD-BALANCE")).isZero();
        }

        @Test
        @DisplayName("rejects a posting with fewer than two legs")
        void rejectsSingleSidedPosting() {
            String body = """
                    {
                      "ledgerTransactionId": "LTX-BAD-ONE-LEG",
                      "postingType": "DEPOSIT",
                      "currency": "INR",
                      "lines": [
                        {"accountId": "%s", "accountType": "ASSET",
                         "direction": "DEBIT", "amountMinor": 1000, "memo": "x"}
                      ]
                    }
                    """.formatted(FUNDING_INR);

            assertThat(post("/internal/ledger/postings", body, serviceToken(), ApiError.class)
                    .status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        /**
         * The platform cannot owe a customer a negative amount. The error names the account and
         * what it holds, which is the difference between a usable incident report and a grep
         * through constraint names.
         */
        @Test
        @DisplayName("refuses to drive a customer wallet negative, and says which one")
        void refusesToOverdrawAWallet() {
            String walletId = fundedWallet(100 * RUPEE);
            String payee = newWalletId();

            Response<ApiError> response = post("/internal/ledger/postings",
                    transferBody("LTX-OVERDRAW", walletId, payee, 500 * RUPEE),
                    serviceToken(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().message())
                    .as("the message must identify the account and its balance")
                    .contains(walletAccount(walletId));

            assertThat(accountBalance(walletAccount(walletId))).isEqualTo(100 * RUPEE);
            assertThat(countEntriesWithKey("LTX-OVERDRAW")).isZero();
        }

        @Test
        @DisplayName("refuses a posting whose currency does not match its accounts")
        void refusesCurrencyMismatch() {
            String walletId = fundedWallet(500 * RUPEE);

            String body = """
                    {
                      "ledgerTransactionId": "LTX-BAD-CURRENCY",
                      "postingType": "ADJUSTMENT",
                      "currency": "USD",
                      "lines": [
                        {"accountId": "wallet:%s", "accountType": "LIABILITY",
                         "direction": "DEBIT", "amountMinor": 100, "memo": "x"},
                        {"accountId": "asset:external-funding:USD", "accountType": "ASSET",
                         "direction": "CREDIT", "amountMinor": 100, "memo": "y"}
                      ]
                    }
                    """.formatted(walletId);

            assertThat(post("/internal/ledger/postings", body, serviceToken(), ApiError.class)
                    .status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ----------------------------------------------------------- idempotency

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        /**
         * The retry case the whole mechanism exists for: the response was lost and the caller
         * sends the identical request again. The movement must be booked once.
         */
        @Test
        @DisplayName("replaying a key returns the original posting and books it once")
        void replayBooksOnce() {
            String walletId = newWalletId();
            String body = depositBody("LTX-IDEM-1", walletId, 1000 * RUPEE);

            Response<JournalEntryResponse> first = post("/internal/ledger/postings", body,
                    serviceToken(), JournalEntryResponse.class);
            Response<JournalEntryResponse> replay = post("/internal/ledger/postings", body,
                    serviceToken(), JournalEntryResponse.class);

            assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.status())
                    .as("200 rather than 201 tells a saga it was not the one that booked it")
                    .isEqualTo(HttpStatus.OK);

            assertThat(first.body()).isNotNull();
            assertThat(replay.body()).isNotNull();
            assertThat(replay.body().journalSequence()).isEqualTo(first.body().journalSequence());
            assertThat(replay.body().duplicate()).isTrue();

            assertThat(countEntries()).isEqualTo(1);
            assertThat(accountBalance(walletAccount(walletId)))
                    .as("a replay must not apply the movement twice")
                    .isEqualTo(1000 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        /**
         * Eight identical requests at once. The lookup cannot win this race on its own; the
         * unique constraint is what makes a second posting impossible, and the orchestrator's
         * recovery is what turns the loser into a clean replay rather than a 500.
         */
        @Test
        @DisplayName("concurrent identical postings book exactly once")
        void concurrentIdenticalPostingsBookOnce() {
            String walletId = newWalletId();
            String body = depositBody("LTX-IDEM-RACE", walletId, 1000 * RUPEE);

            List<HttpStatus> statuses = inParallel(8, () ->
                    postStatus("/internal/ledger/postings", body, serviceToken()));

            assertThat(statuses)
                    .as("every outcome must be a success or a replay, never a 500")
                    .allSatisfy(status -> assertThat(status)
                            .isIn(HttpStatus.CREATED, HttpStatus.OK));
            assertThat(statuses).filteredOn(status -> status == HttpStatus.CREATED)
                    .as("exactly one caller may book the posting")
                    .hasSize(1);

            assertThat(countEntries()).isEqualTo(1);
            assertThat(accountBalance(walletAccount(walletId))).isEqualTo(1000 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        /**
         * Distinct keys must all apply and the totals must be exact. This is the lost-update
         * test: without {@code SELECT ... FOR UPDATE} several threads read the same account
         * totals and the last writer wins, leaving the cached balance short.
         */
        @Test
        @DisplayName("concurrent distinct postings all apply with no lost updates")
        void concurrentDistinctPostingsAllApply() {
            String walletId = newWalletId();
            java.util.concurrent.atomic.AtomicInteger counter =
                    new java.util.concurrent.atomic.AtomicInteger();
            int postings = 10;

            List<HttpStatus> statuses = inParallel(postings, () -> {
                String key = "LTX-SEQ-" + counter.incrementAndGet();
                return postStatus("/internal/ledger/postings",
                        depositBody(key, walletId, 100 * RUPEE), serviceToken());
            });

            assertThat(statuses).allMatch(status -> status == HttpStatus.CREATED);
            assertThat(countEntries()).isEqualTo(postings);
            assertThat(accountBalance(walletAccount(walletId)))
                    .as("a short total means a read-modify-write lost an update")
                    .isEqualTo(postings * 100 * RUPEE);
            assertThat(accountBalance(FUNDING_INR)).isEqualTo(postings * 100 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        /**
         * <strong>The deadlock test.</strong> Two postings touch the same pair of accounts in
         * opposite orders. Locking in payload order would make each hold the row the other
         * needs, and PostgreSQL would kill one as a deadlock victim. Sorting account IDs before
         * locking means every caller acquires them in the same sequence, so no cycle can form.
         */
        @Test
        @DisplayName("crossing postings both succeed, so sorted-order locking prevents deadlock")
        void crossingPostingsDoNotDeadlock() {
            String walletA = fundedWallet(1000 * RUPEE);
            String walletB = fundedWallet(1000 * RUPEE);

            List<HttpStatus> statuses = inParallel(
                    () -> postStatus("/internal/ledger/postings",
                            transferBody("LTX-CROSS-AB", walletA, walletB, 300 * RUPEE),
                            serviceToken()),
                    () -> postStatus("/internal/ledger/postings",
                            transferBody("LTX-CROSS-BA", walletB, walletA, 500 * RUPEE),
                            serviceToken()));

            assertThat(statuses)
                    .as("a deadlock would surface as a 409 CONCURRENT_MODIFICATION or a 500")
                    .containsExactly(HttpStatus.CREATED, HttpStatus.CREATED);

            // A: 1000 - 300 + 500 = 1200. B: 1000 - 500 + 300 = 800.
            assertThat(accountBalance(walletAccount(walletA))).isEqualTo(1200 * RUPEE);
            assertThat(accountBalance(walletAccount(walletB))).isEqualTo(800 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }
    }

    // -------------------------------------------------------------- reversal

    @Nested
    @DisplayName("reversal")
    class Reversal {

        /**
         * The only way to undo a posting. Both entries stay visible and together net to zero on
         * every account they touch — deleting the original would destroy the evidence that it
         * ever happened, which is exactly what an audit needs.
         */
        @Test
        @DisplayName("appends the exact opposite and leaves both entries visible")
        void appendsTheOpposite() {
            String walletId = fundedWallet(1000 * RUPEE);
            String payee = newWalletId();
            post("/internal/ledger/postings",
                    transferBody("LTX-REVERSE-ME", walletId, payee, 300 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);

            assertThat(accountBalance(walletAccount(payee))).isEqualTo(300 * RUPEE);

            Response<JournalEntryResponse> reversal = post(
                    "/internal/ledger/postings/LTX-REVERSE-ME/reverse",
                    "{\"reason\": \"posted against the wrong account\"}",
                    serviceToken(), JournalEntryResponse.class);

            assertThat(reversal.status()).isEqualTo(HttpStatus.CREATED);
            assertThat(reversal.body()).isNotNull();
            assertThat(reversal.body().postingType())
                    .isEqualTo(com.aegispay.ledger.domain.JournalEntry.PostingType.REVERSAL);
            assertThat(reversal.body().reversesLedgerTransactionId()).isEqualTo("LTX-REVERSE-ME");

            // Net effect on both wallets is zero, and both entries remain.
            assertThat(accountBalance(walletAccount(payee))).isZero();
            assertThat(accountBalance(walletAccount(walletId))).isEqualTo(1000 * RUPEE);
            assertThat(countEntriesWithKey("LTX-REVERSE-ME"))
                    .as("the original must survive its own reversal")
                    .isEqualTo(1);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        @Test
        @DisplayName("refuses to reverse the same posting twice")
        void refusesDoubleReversal() {
            String walletId = fundedWallet(1000 * RUPEE);
            String payee = newWalletId();
            post("/internal/ledger/postings",
                    transferBody("LTX-ONE-REVERSAL", walletId, payee, 300 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);

            post("/internal/ledger/postings/LTX-ONE-REVERSAL/reverse",
                    "{\"reason\": \"first\"}", serviceToken(), JournalEntryResponse.class);

            Response<ApiError> second = post("/internal/ledger/postings/LTX-ONE-REVERSAL/reverse",
                    "{\"reason\": \"second\"}", serviceToken(), ApiError.class);

            assertThat(second.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(accountBalance(walletAccount(walletId)))
                    .as("a double reversal would re-apply the original movement")
                    .isEqualTo(1000 * RUPEE);
        }

        @Test
        @DisplayName("refuses to reverse a reversal")
        void refusesToReverseAReversal() {
            String walletId = fundedWallet(1000 * RUPEE);
            String payee = newWalletId();
            post("/internal/ledger/postings",
                    transferBody("LTX-REV-CHAIN", walletId, payee, 300 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);
            JournalEntryResponse reversal = post("/internal/ledger/postings/LTX-REV-CHAIN/reverse",
                    "{\"reason\": \"first\"}", serviceToken(), JournalEntryResponse.class).body();

            assertThat(reversal).isNotNull();
            Response<ApiError> response = post(
                    "/internal/ledger/postings/" + reversal.ledgerTransactionId() + "/reverse",
                    "{\"reason\": \"undo the undo\"}", serviceToken(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("returns 404 for an unknown posting")
        void unknownPosting() {
            assertThat(post("/internal/ledger/postings/LTX-NOPE/reverse",
                    "{\"reason\": \"x\"}", serviceToken(), ApiError.class).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("concurrent reversals produce at most one")
        void concurrentReversalsProduceOne() {
            String walletId = fundedWallet(1000 * RUPEE);
            String payee = newWalletId();
            post("/internal/ledger/postings",
                    transferBody("LTX-REV-RACE", walletId, payee, 300 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);

            List<HttpStatus> statuses = inParallel(6, () ->
                    postStatus("/internal/ledger/postings/LTX-REV-RACE/reverse",
                            "{\"reason\": \"race\"}", serviceToken()));

            assertThat(statuses).filteredOn(status -> status == HttpStatus.CREATED)
                    .as("the partial unique index permits exactly one reversal")
                    .hasSize(1);
            assertThat(accountBalance(walletAccount(walletId))).isEqualTo(1000 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }
    }

    // ---------------------------------------------------------------- reads

    @Nested
    @DisplayName("reading the journal")
    class Reading {

        @Test
        @DisplayName("exposes an account balance with its direction convention")
        void exposesAccountBalance() {
            String walletId = fundedWallet(1000 * RUPEE);

            Response<AccountResponse> response = get(
                    "/api/ledger/accounts/" + walletAccount(walletId), adminToken(),
                    AccountResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            AccountResponse account = response.body();
            assertThat(account).isNotNull();
            assertThat(account.balance().amountMinor()).isEqualTo(1000 * RUPEE);
            assertThat(account.debitIncreases())
                    .as("a reader must be told which way a debit moves this account, or a "
                            + "liability balance is meaningless")
                    .isFalse();
            assertThat(account.allowsNegative()).isFalse();
            assertThat(account.systemAccount()).isFalse();
        }

        @Test
        @DisplayName("renders money as both a decimal and an exact minor-unit integer")
        void rendersMoneyBothWays() {
            String walletId = newWalletId();
            post("/internal/ledger/postings", depositBody("LTX-MONEY", walletId, 123_456L),
                    serviceToken(), JournalEntryResponse.class);

            AccountResponse account = get("/api/ledger/accounts/" + walletAccount(walletId),
                    adminToken(), AccountResponse.class).body();

            assertThat(account).isNotNull();
            assertThat(account.balance().amountMinor()).isEqualTo(123_456L);
            assertThat(account.balance().amount())
                    .isEqualByComparingTo(new java.math.BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("returns every posting produced by one payment")
        void returnsPostingsForAPayment() {
            String walletId = fundedWallet(1000 * RUPEE);
            String payee = newWalletId();
            post("/internal/ledger/postings",
                    transferBody("LTX-PAY-LOOKUP", walletId, payee, 200 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);

            Response<JournalEntryResponse[]> response = get(
                    "/internal/ledger/payments/PAY-FIXTURE/postings", serviceToken(),
                    JournalEntryResponse[].class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull().hasSize(1);
        }

        @Test
        @DisplayName("returns 404 for an unknown account rather than an empty statement")
        void unknownAccountIsNotFound() {
            assertThat(get("/api/ledger/accounts/wallet:WAL-NOPE/entries", adminToken(),
                    ApiError.class).status())
                    .as("an empty page would look like a real account with no activity")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("records the ledger-posted event on the outbox and drains it")
        void recordsAndDrainsTheEvent() {
            String walletId = newWalletId();
            post("/internal/ledger/postings", depositBody("LTX-EVENT", walletId, 500 * RUPEE),
                    serviceToken(), JournalEntryResponse.class);

            assertThat(countOutboxEvents("ledger-posted"))
                    .as("the posting and its announcement must be one commit")
                    .isEqualTo(1);

            awaitUntil("the outbox drainer to publish the ledger-posted event",
                    java.time.Duration.ofSeconds(30),
                    () -> countPublishedOutboxEvents() == 1);
        }

        /**
         * A replay must not emit a second event. The original was already delivered
         * at-least-once by the outbox, so emitting one per retry would multiply events without
         * adding information.
         */
        @Test
        @DisplayName("a replayed posting emits no second event")
        void replayEmitsNoSecondEvent() {
            String walletId = newWalletId();
            String body = depositBody("LTX-EVENT-ONCE", walletId, 500 * RUPEE);

            post("/internal/ledger/postings", body, serviceToken(), JournalEntryResponse.class);
            post("/internal/ledger/postings", body, serviceToken(), JournalEntryResponse.class);

            assertThat(countOutboxEvents("ledger-posted")).isEqualTo(1);
        }
    }

    // --------------------------------------------------------- authorisation

    @Nested
    @DisplayName("authentication and authorisation")
    class Security {

        @Test
        @DisplayName("rejects an unauthenticated posting")
        void rejectsMissingToken() {
            assertThat(post("/internal/ledger/postings",
                    depositBody("LTX-NOAUTH", newWalletId(), 100L), null, ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(countEntries()).isZero();
        }

        @Test
        @DisplayName("rejects a forged token")
        void rejectsForgedToken() {
            assertThat(post("/internal/ledger/postings",
                    depositBody("LTX-FORGED", newWalletId(), 100L),
                    TestJwtIssuer.forged("USR-ATTACKER"), ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(countEntries()).isZero();
        }

        /**
         * Posting changes the platform's book of record, so no human token may do it however
         * many roles it carries.
         */
        @Test
        @DisplayName("denies posting to a human token, even an admin one")
        void deniesPostingToHumans() {
            assertThat(post("/internal/ledger/postings",
                    depositBody("LTX-HUMAN", newWalletId(), 100L), adminToken(), ApiError.class)
                    .status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(countEntries()).isZero();
        }

        @Test
        @DisplayName("denies a service token missing scope=internal")
        void deniesServiceTokenWithoutScope() {
            assertThat(post("/internal/ledger/postings",
                    depositBody("LTX-NOSCOPE", newWalletId(), 100L),
                    TestJwtIssuer.serviceTokenWithoutScope("payment-service"), ApiError.class)
                    .status()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * The journal is every customer's movements. {@code /api/ledger/**} does not match the
         * shared chain's {@code /api/admin/**} rule, so the controller's {@code @PreAuthorize}
         * is the only thing standing between a customer token and everyone else's history.
         */
        @Test
        @DisplayName("denies the journal to a plain customer token")
        void deniesJournalToCustomers() {
            assertThat(get("/api/ledger/journal", TestJwtIssuer.user("USR-CUSTOMER", Roles.USER),
                    ApiError.class).status())
                    .as("without method security this would expose every customer's movements")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("permits the journal to staff roles")
        void permitsJournalToStaff() {
            assertThat(get("/api/ledger/journal", adminToken(), String.class).status())
                    .isEqualTo(HttpStatus.OK);
            assertThat(get("/api/ledger/journal",
                    TestJwtIssuer.user("USR-ANALYST", Roles.FRAUD_ANALYST), String.class).status())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("leaves health and API docs open")
        void probesAndDocsArePublic() {
            assertThat(get("/actuator/health", null, String.class).status())
                    .isEqualTo(HttpStatus.OK);
            assertThat(get("/v3/api-docs", null, String.class).status())
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
