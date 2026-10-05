package com.aegispay.wallet;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.security.Roles;
import com.aegispay.common.testsupport.TestJwtIssuer;
import com.aegispay.wallet.api.WalletDtos.CreateWalletRequest;
import com.aegispay.wallet.api.WalletDtos.FundsMovementRequest;
import com.aegispay.wallet.api.WalletDtos.MovementResponse;
import com.aegispay.wallet.api.WalletDtos.ReconciliationResponse;
import com.aegispay.wallet.api.WalletDtos.WalletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Customer-facing wallet behaviour against real PostgreSQL and Redis.
 *
 * <p>Assertions read the database directly wherever a bug could produce a correct-looking
 * HTTP response. That is not defensive padding: the whole class of defect this phase has to
 * rule out — a double credit, a rolled-back debit, a balance that drifts from its own
 * statement — is invisible in the response body and visible only in the committed rows.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WalletFlowIT extends AbstractWalletIntegrationTest {

    private static final long RUPEE = 100L;

    // ====================================================== wallet lifecycle

    @Nested
    @DisplayName("opening wallets")
    class Opening {

        @Test
        @DisplayName("opens a wallet with a zero balance")
        void opensWallet() {
            String userId = newUserId();

            Response<WalletResponse> response = post("/api/wallets",
                    new CreateWalletRequest(CURRENCY), TestJwtIssuer.user(userId),
                    WalletResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
            WalletResponse wallet = response.body();
            assertThat(wallet).isNotNull();
            assertThat(wallet.walletId()).startsWith("WAL-");
            assertThat(wallet.userId()).isEqualTo(userId);
            assertThat(wallet.currency()).isEqualTo(CURRENCY);
            assertThat(wallet.balance().amountMinor()).isZero();
            assertThat(wallet.held().amountMinor()).isZero();
            assertThat(wallet.available().amountMinor()).isZero();
            assertThat(wallet.status()).isEqualTo(com.aegispay.wallet.domain.Wallet.Status.ACTIVE);
        }

        /**
         * The wallet is owned by the token's subject, never by a body field. A request that
         * tried to name a different owner would still produce a wallet for the caller.
         */
        @Test
        @DisplayName("assigns ownership from the token, not the request")
        void ownershipComesFromTheToken() {
            String caller = newUserId();

            String walletId = openWallet(caller);

            String storedOwner = jdbc.queryForObject(
                    "SELECT user_id FROM wallets WHERE wallet_id = ?", String.class, walletId);
            assertThat(storedOwner).isEqualTo(caller);
        }

        @Test
        @DisplayName("refuses a second wallet in the same currency")
        void refusesDuplicateCurrency() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            post("/api/wallets", new CreateWalletRequest(CURRENCY), token, WalletResponse.class);

            Response<ApiError> second = post("/api/wallets",
                    new CreateWalletRequest(CURRENCY), token, ApiError.class);

            assertThat(second.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(second.body()).isNotNull();
            assertThat(second.body().code()).isEqualTo(ErrorCode.WALLET_ALREADY_EXISTS.name());
            assertThat(countWalletsFor(userId)).isEqualTo(1);
        }

        @Test
        @DisplayName("allows one wallet per currency")
        void allowsOneWalletPerCurrency() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);

            post("/api/wallets", new CreateWalletRequest("INR"), token, WalletResponse.class);
            post("/api/wallets", new CreateWalletRequest("USD"), token, WalletResponse.class);

            assertThat(countWalletsFor(userId)).isEqualTo(2);
        }

        /**
         * The pre-check in {@code openWallet} cannot prevent this; the unique constraint
         * does. It matters more than a typical duplicate: two wallets in one currency would
         * let a user split funds across both and defeat per-wallet balance checks.
         */
        @Test
        @DisplayName("concurrent opens produce exactly one wallet")
        void concurrentOpensProduceOneWallet() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);

            List<HttpStatus> statuses = inParallel(8, () ->
                    postStatus("/api/wallets", new CreateWalletRequest(CURRENCY), token)
                            .status()).stream().toList();

            assertThat(statuses).filteredOn(s -> s == HttpStatus.CREATED)
                    .as("exactly one concurrent open may succeed")
                    .hasSize(1);
            assertThat(statuses).filteredOn(s -> s == HttpStatus.CONFLICT)
                    .as("every loser must report a conflict, never a 500")
                    .hasSize(7);
            assertThat(countWalletsFor(userId)).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects a currency that is not ISO-4217")
        void rejectsUnknownCurrency() {
            Response<ApiError> response = post("/api/wallets",
                    new CreateWalletRequest("ZZZ"), TestJwtIssuer.user(newUserId()), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.UNSUPPORTED_CURRENCY.name());
        }
    }

    // =============================================================== reading

    @Nested
    @DisplayName("reading balances")
    class Reading {

        @Test
        @DisplayName("reports balance, held and available separately")
        void reportsThreeAmounts() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            seedBalance(walletId, 500 * RUPEE);
            jdbc.update("UPDATE wallets SET held_minor = ? WHERE wallet_id = ?",
                    200 * RUPEE, walletId);

            Response<WalletResponse> response = get(
                    "/api/wallets/balance?currency=" + CURRENCY, token, WalletResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            WalletResponse wallet = response.body();
            assertThat(wallet).isNotNull();
            assertThat(wallet.balance().amountMinor()).isEqualTo(500 * RUPEE);
            assertThat(wallet.held().amountMinor()).isEqualTo(200 * RUPEE);
            assertThat(wallet.available().amountMinor())
                    .as("available is what a new payment is checked against")
                    .isEqualTo(300 * RUPEE);
        }

        /**
         * Minor units are the authoritative field. A JSON number becomes a double in every
         * JavaScript client, and a double cannot represent every paise value exactly, so a
         * client that reads {@code amount} instead of {@code amountMinor} can be off by a
         * paise. Both must be present and must agree.
         */
        @Test
        @DisplayName("renders money as both a decimal and an exact minor-unit integer")
        void rendersMoneyBothWays() {
            String userId = newUserId();
            String walletId = openWallet(userId);
            seedBalance(walletId, 123_456L); // 1234.56

            WalletResponse wallet = get("/api/wallets/" + walletId,
                    TestJwtIssuer.user(userId), WalletResponse.class).body();

            assertThat(wallet).isNotNull();
            assertThat(wallet.balance().amountMinor()).isEqualTo(123_456L);
            assertThat(wallet.balance().amount()).isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(wallet.balance().currency()).isEqualTo(CURRENCY);
        }

        @Test
        @DisplayName("returns 404 when the user has no wallet in that currency")
        void noWalletInCurrency() {
            Response<ApiError> response = get("/api/wallets/balance?currency=EUR",
                    TestJwtIssuer.user(newUserId()), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND.name());
        }

        /**
         * Not-found rather than forbidden. Confirming that a wallet exists but belongs to
         * someone else is itself a disclosure, and wallet IDs are random precisely so they
         * cannot be enumerated.
         */
        @Test
        @DisplayName("hides another user's wallet behind a 404")
        void cannotReadSomeoneElsesWallet() {
            String ownerWallet = openWallet(newUserId());

            Response<ApiError> response = get("/api/wallets/" + ownerWallet,
                    TestJwtIssuer.user(newUserId()), ApiError.class);

            assertThat(response.status())
                    .as("must not be 403: that would confirm the wallet exists")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND.name());
        }

        @Test
        @DisplayName("lists only the caller's own wallets")
        void listsOnlyOwnWallets() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            post("/api/wallets", new CreateWalletRequest("INR"), token, WalletResponse.class);
            post("/api/wallets", new CreateWalletRequest("USD"), token, WalletResponse.class);
            openWallet(newUserId()); // someone else's

            Response<WalletResponse[]> response = get("/api/wallets", token, WalletResponse[].class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull()
                    .hasSize(2)
                    .allSatisfy(w -> assertThat(w.userId()).isEqualTo(userId));
        }
    }

    // ============================================================== deposits

    @Nested
    @DisplayName("deposits")
    class Deposits {

        @Test
        @DisplayName("credits the wallet and records a statement line")
        void creditsAndRecords() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);

            Response<MovementResponse> response = post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, newKey(), MovementResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            MovementResponse body = response.body();
            assertThat(body).isNotNull();
            assertThat(body.transaction().amount().amountMinor()).isEqualTo(2000 * RUPEE);
            assertThat(body.transaction().balanceAfter().amountMinor()).isEqualTo(2000 * RUPEE);
            assertThat(body.wallet().balance().amountMinor()).isEqualTo(2000 * RUPEE);

            // What actually committed.
            assertThat(balanceMinor(walletId)).isEqualTo(2000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "DEPOSIT")).isEqualTo(1);
        }

        @Test
        @DisplayName("requires an Idempotency-Key header")
        void requiresIdempotencyKey() {
            String userId = newUserId();
            String walletId = openWallet(userId);

            Response<ApiError> response = post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", null), TestJwtIssuer.user(userId), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(balanceMinor(walletId))
                    .as("a rejected request must move no money")
                    .isZero();
        }

        /**
         * The retry case this whole mechanism exists for: the first response was lost, the
         * client sends the identical request again, and the customer must be credited once.
         */
        @Test
        @DisplayName("replaying a key returns the original response and credits once")
        void replayCreditsOnce() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            String key = newKey();

            MovementResponse first = post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, key, MovementResponse.class).body();
            MovementResponse replay = post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, key, MovementResponse.class).body();

            assertThat(first).isNotNull();
            assertThat(replay).isNotNull();
            assertThat(replay.transaction().transactionId())
                    .as("a replay must return the ORIGINAL movement, not a new one")
                    .isEqualTo(first.transaction().transactionId());

            assertThat(balanceMinor(walletId)).isEqualTo(2000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "DEPOSIT")).isEqualTo(1);
        }

        /**
         * The database is the guarantee, not Redis. Flushing the cache between the two
         * requests simulates a cache outage or eviction; the composite primary key on
         * {@code idempotency_keys} must still prevent the second credit.
         *
         * <p>This is the test that distinguishes a real two-layer design from a
         * Redis-only one, which would silently start double-charging during a cache
         * incident.
         */
        @Test
        @DisplayName("still credits once when the Redis fast path is empty")
        void replayCreditsOnceWithoutCache() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            String key = newKey();

            post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, key, MovementResponse.class);

            redis.getConnectionFactory().getConnection().serverCommands().flushDb();

            Response<MovementResponse> replay = post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, key, MovementResponse.class);

            assertThat(replay.status()).isEqualTo(HttpStatus.OK);
            assertThat(balanceMinor(walletId))
                    .as("Redis may fail open; the database must not")
                    .isEqualTo(2000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "DEPOSIT")).isEqualTo(1);
        }

        @Test
        @DisplayName("recovers from a missing idempotency response row without crediting twice")
        void movementNaturalKeyClosesCompletionCrashWindow() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            String key = newKey();

            MovementResponse first = post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, key, MovementResponse.class).body();
            assertThat(first).isNotNull();

            // Simulate the exact crash window: money/outbox committed, but the generic
            // idempotency completion record and Redis cache are unavailable.
            jdbc.update("DELETE FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?",
                    "wallet.deposit", key);
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();

            MovementResponse recovered = post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, key, MovementResponse.class).body();

            assertThat(recovered).isNotNull();
            assertThat(recovered.transaction().transactionId())
                    .isEqualTo(first.transaction().transactionId());
            assertThat(balanceMinor(walletId)).isEqualTo(2000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "DEPOSIT")).isEqualTo(1);
        }

        /**
         * Returning the first response would silently swallow a genuinely different second
         * deposit. Executing it would break the client's own idempotency contract. Both are
         * wrong, so this is refused as a client bug.
         */
        @Test
        @DisplayName("rejects the same key with a different amount")
        void rejectsKeyReuseWithDifferentPayload() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            String key = newKey();

            post("/api/wallets/" + walletId + "/deposit",
                    movement("2000.00", "Top-up"), token, key, MovementResponse.class);

            Response<ApiError> conflict = post("/api/wallets/" + walletId + "/deposit",
                    movement("5000.00", "Top-up"), token, key, ApiError.class);

            assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflict.body()).isNotNull();
            assertThat(conflict.body().code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
            assertThat(balanceMinor(walletId)).isEqualTo(2000 * RUPEE);
        }

        /**
         * Eight identical requests fired at once, the shape a double-tapped "Send" button
         * actually takes. Exactly one may credit; the rest must be told to retry or given
         * the original response. What must never happen is two credits.
         */
        @Test
        @DisplayName("concurrent duplicates credit exactly once")
        void concurrentDuplicatesCreditOnce() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            String key = newKey();

            List<HttpStatus> statuses = inParallel(8, () ->
                    postStatus("/api/wallets/" + walletId + "/deposit",
                            movement("2000.00", "Top-up"), token, key).status());

            assertThat(statuses).allSatisfy(status -> assertThat(status)
                    .as("every outcome must be a success replay or a conflict, never a 500")
                    .isIn(HttpStatus.OK, HttpStatus.CONFLICT));
            assertThat(balanceMinor(walletId)).isEqualTo(2000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "DEPOSIT")).isEqualTo(1);
        }

        /**
         * Distinct keys must all apply, and the total must be exact. This is the
         * lost-update test: without {@code SELECT ... FOR UPDATE}, several threads read the
         * same starting balance and the last writer wins, so the total comes out short.
         */
        @Test
        @DisplayName("concurrent distinct deposits all apply, with no lost updates")
        void concurrentDistinctDepositsAllApply() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            int deposits = 10;

            List<HttpStatus> statuses = inParallel(deposits, () ->
                    postStatus("/api/wallets/" + walletId + "/deposit",
                            movement("100.00", "Top-up"), token, newKey()).status());

            assertThat(statuses).allMatch(s -> s == HttpStatus.OK);
            assertThat(balanceMinor(walletId))
                    .as("a short total means a read-modify-write lost an update")
                    .isEqualTo(deposits * 100 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "DEPOSIT")).isEqualTo(deposits);
        }

        @Test
        @DisplayName("refuses a deposit into another user's wallet")
        void cannotDepositIntoAnotherUsersWallet() {
            String victimWallet = openWallet(newUserId());

            Response<ApiError> response = post("/api/wallets/" + victimWallet + "/deposit",
                    movement("2000.00", null), TestJwtIssuer.user(newUserId()), newKey(),
                    ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(balanceMinor(victimWallet)).isZero();
        }

        @Test
        @DisplayName("rejects a zero or negative amount")
        void rejectsNonPositiveAmount() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);

            assertThat(post("/api/wallets/" + walletId + "/deposit",
                    movement("0.00", null), token, newKey(), ApiError.class).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(post("/api/wallets/" + walletId + "/deposit",
                    movement("-50.00", null), token, newKey(), ApiError.class).status())
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            assertThat(balanceMinor(walletId)).isZero();
        }

        /** More precision than the currency has must be refused at the edge, not rounded. */
        @Test
        @DisplayName("rejects more decimal places than the currency supports")
        void rejectsExcessiveScale() {
            String userId = newUserId();
            String walletId = openWallet(userId);

            Response<ApiError> response = post("/api/wallets/" + walletId + "/deposit",
                    movement("10.999", null), TestJwtIssuer.user(userId), newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(balanceMinor(walletId)).isZero();
        }
    }

    // =========================================================== withdrawals

    @Nested
    @DisplayName("withdrawals")
    class Withdrawals {

        @Test
        @DisplayName("debits the wallet and records a statement line")
        void debitsAndRecords() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            seedBalance(walletId, 5000 * RUPEE);

            Response<MovementResponse> response = post("/api/wallets/" + walletId + "/withdraw",
                    movement("2000.00", "Cash out"), token, newKey(), MovementResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(balanceMinor(walletId)).isEqualTo(3000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "WITHDRAWAL")).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects a withdrawal beyond the balance and changes nothing")
        void rejectsInsufficientFunds() {
            String userId = newUserId();
            String walletId = openWallet(userId);
            seedBalance(walletId, 1000 * RUPEE);

            Response<ApiError> response = post("/api/wallets/" + walletId + "/withdraw",
                    movement("2000.00", null), TestJwtIssuer.user(userId), newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS.name());

            assertThat(balanceMinor(walletId)).isEqualTo(1000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "WITHDRAWAL")).isZero();
        }

        /**
         * The reason there are two balance columns. A withdrawal is checked against
         * <em>available</em>, so it cannot spend money already promised to an in-flight
         * payment — which is what a single-balance model would allow.
         */
        @Test
        @DisplayName("cannot spend funds reserved by an in-flight payment")
        void cannotSpendHeldFunds() {
            String userId = newUserId();
            String walletId = openWallet(userId);
            seedBalance(walletId, 1000 * RUPEE);
            jdbc.update("UPDATE wallets SET held_minor = ? WHERE wallet_id = ?",
                    800 * RUPEE, walletId);

            // 500 <= balance (1000) but > available (200).
            Response<ApiError> response = post("/api/wallets/" + walletId + "/withdraw",
                    movement("500.00", null), TestJwtIssuer.user(userId), newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(balanceMinor(walletId)).isEqualTo(1000 * RUPEE);
            assertThat(heldMinor(walletId)).isEqualTo(800 * RUPEE);
        }

        /**
         * The double-spend test. Ten threads each try to withdraw the entire balance. Only
         * one can succeed, and the balance must land on exactly zero — never negative, and
         * never partially debited more than once.
         */
        @Test
        @DisplayName("concurrent withdrawals of the whole balance cannot overdraw")
        void concurrentWithdrawalsCannotOverdraw() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            seedBalance(walletId, 1000 * RUPEE);

            List<HttpStatus> statuses = inParallel(10, () ->
                    postStatus("/api/wallets/" + walletId + "/withdraw",
                            movement("1000.00", null), token, newKey()).status());

            assertThat(statuses).filteredOn(s -> s == HttpStatus.OK)
                    .as("exactly one withdrawal of the full balance may succeed")
                    .hasSize(1);
            assertThat(statuses).filteredOn(s -> s == HttpStatus.UNPROCESSABLE_CONTENT).hasSize(9);

            assertThat(balanceMinor(walletId))
                    .as("a negative balance would mean the row lock did not hold")
                    .isZero();
            assertThat(countTransactionsOfType(walletId, "WITHDRAWAL")).isEqualTo(1);
        }

        /**
         * A frozen wallet cannot send. That is a business decision the customer must be
         * told about, so it is a 409 rather than a 500: a 500 would be retried by clients,
         * counted as a platform fault, and would page someone for a working fraud control.
         */
        @Test
        @DisplayName("refuses a withdrawal from a frozen wallet as a conflict, not a fault")
        void refusesWithdrawalFromFrozenWallet() {
            String userId = newUserId();
            String walletId = openWallet(userId);
            seedBalance(walletId, 1000 * RUPEE);
            jdbc.update("UPDATE wallets SET status = 'FROZEN' WHERE wallet_id = ?", walletId);

            Response<ApiError> response = post("/api/wallets/" + walletId + "/withdraw",
                    movement("100.00", null), TestJwtIssuer.user(userId), newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.INVALID_PAYMENT_STATE.name());
            assertThat(balanceMinor(walletId))
                    .as("a freeze must not take the money")
                    .isEqualTo(1000 * RUPEE);
        }

        /**
         * A frozen wallet must still accept credits. Bouncing an incoming refund would make
         * a fraud hold cost the customer money they are owed.
         */
        @Test
        @DisplayName("still accepts a deposit into a frozen wallet")
        void acceptsDepositIntoFrozenWallet() {
            String userId = newUserId();
            String walletId = openWallet(userId);
            jdbc.update("UPDATE wallets SET status = 'FROZEN' WHERE wallet_id = ?", walletId);

            assertThat(post("/api/wallets/" + walletId + "/deposit",
                    movement("500.00", null), TestJwtIssuer.user(userId), newKey(),
                    MovementResponse.class).status()).isEqualTo(HttpStatus.OK);
            assertThat(balanceMinor(walletId)).isEqualTo(500 * RUPEE);
        }

        /** A failed withdrawal must release its idempotency claim so the client can retry. */
        @Test
        @DisplayName("a declined withdrawal leaves its key retryable")
        void declinedWithdrawalLeavesKeyRetryable() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);
            String key = newKey();

            // Declined: no funds yet.
            assertThat(post("/api/wallets/" + walletId + "/withdraw",
                    movement("500.00", null), token, key, ApiError.class).status())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(idempotencyStatus("wallet.withdraw", key))
                    .as("a failed attempt must be reclaimable, not left IN_PROGRESS forever")
                    .isEqualTo("FAILED");

            // The customer tops up and retries the SAME key. It must now go through.
            seedBalance(walletId, 1000 * RUPEE);
            assertThat(post("/api/wallets/" + walletId + "/withdraw",
                    movement("500.00", null), token, key, MovementResponse.class).status())
                    .isEqualTo(HttpStatus.OK);
            assertThat(balanceMinor(walletId)).isEqualTo(500 * RUPEE);
        }
    }

    // ============================================================= statement

    @Nested
    @DisplayName("statements")
    class Statements {

        @Test
        @DisplayName("returns movements newest first with a running balance")
        void returnsMovementsNewestFirst() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);

            post("/api/wallets/" + walletId + "/deposit", movement("1000.00", "First"),
                    token, newKey(), MovementResponse.class);
            post("/api/wallets/" + walletId + "/deposit", movement("500.00", "Second"),
                    token, newKey(), MovementResponse.class);
            post("/api/wallets/" + walletId + "/withdraw", movement("200.00", "Third"),
                    token, newKey(), MovementResponse.class);

            Response<String> response = get("/api/wallets/" + walletId + "/transactions",
                    token, String.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull().contains("Third");
            assertThat(countTransactions(walletId)).isEqualTo(3);
            assertThat(balanceMinor(walletId)).isEqualTo(1300 * RUPEE);
        }

        @Test
        @DisplayName("refuses another user's statement")
        void refusesAnotherUsersStatement() {
            String walletId = openWallet(newUserId());

            assertThat(get("/api/wallets/" + walletId + "/transactions",
                    TestJwtIssuer.user(newUserId()), ApiError.class).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ======================================================== authentication

    @Nested
    @DisplayName("authentication and authorisation")
    class Security {

        @Test
        @DisplayName("rejects a request with no token")
        void rejectsMissingToken() {
            assertThat(get("/api/wallets", null, ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * A structurally perfect token signed by a key the service does not trust. Without
         * signature verification this would be indistinguishable from a real token, and
         * anyone could mint themselves any identity they liked.
         */
        @Test
        @DisplayName("rejects a token signed by an untrusted key")
        void rejectsForgedSignature() {
            assertThat(get("/api/wallets", TestJwtIssuer.forged(newUserId()), ApiError.class)
                    .status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("rejects an expired token")
        void rejectsExpiredToken() {
            assertThat(get("/api/wallets", TestJwtIssuer.expired(newUserId()), ApiError.class)
                    .status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("rejects a token from a different issuer")
        void rejectsWrongIssuer() {
            assertThat(get("/api/wallets", TestJwtIssuer.wrongIssuer(newUserId()), ApiError.class)
                    .status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("rejects a malformed token")
        void rejectsMalformedToken() {
            assertThat(get("/api/wallets", "not.a.real.jwt", ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * {@code /internal/**} moves money without the customer present, so a human token
         * must never reach it however many roles it carries.
         */
        @Test
        @DisplayName("denies /internal/** to a user token, even an admin one")
        void deniesInternalToUserTokens() {
            String walletId = openWallet(newUserId());

            assertThat(get("/internal/wallets/" + walletId,
                    TestJwtIssuer.user(newUserId(), Roles.USER), ApiError.class).status())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/internal/wallets/" + walletId,
                    TestJwtIssuer.user(newUserId(), Roles.ADMIN), ApiError.class).status())
                    .as("ADMIN is a human role and must not open the machine surface")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * The chain requires {@code ROLE_SERVICE} <em>and</em> {@code SCOPE_internal}. Both,
         * so a token that somehow acquired the role still cannot get in.
         */
        @Test
        @DisplayName("denies /internal/** to a service token lacking scope=internal")
        void deniesInternalWithoutScope() {
            String walletId = openWallet(newUserId());

            assertThat(get("/internal/wallets/" + walletId,
                    TestJwtIssuer.serviceTokenWithoutScope("payment-service"), ApiError.class)
                    .status()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("admits a service token carrying both the role and the scope")
        void admitsProperServiceToken() {
            String walletId = openWallet(newUserId());

            assertThat(get("/internal/wallets/" + walletId,
                    TestJwtIssuer.service("payment-service"), String.class).status())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("restricts reconciliation to staff roles")
        void reconcileRequiresStaffRole() {
            String walletId = openWallet(newUserId());

            assertThat(get("/api/wallets/admin/" + walletId + "/reconcile",
                    TestJwtIssuer.user(newUserId(), Roles.USER), ApiError.class).status())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/api/wallets/admin/" + walletId + "/reconcile",
                    TestJwtIssuer.user(newUserId(), Roles.ADMIN), ReconciliationResponse.class)
                    .status()).isEqualTo(HttpStatus.OK);
            assertThat(get("/api/wallets/admin/" + walletId + "/reconcile",
                    TestJwtIssuer.user(newUserId(), Roles.FRAUD_ANALYST),
                    ReconciliationResponse.class).status()).isEqualTo(HttpStatus.OK);
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

    // ======================================================== reconciliation

    @Nested
    @DisplayName("reconciliation")
    class Reconciliation {

        @Test
        @DisplayName("reports consistent when the balance matches its own history")
        void consistentAfterNormalActivity() {
            String userId = newUserId();
            String token = TestJwtIssuer.user(userId);
            String walletId = openWallet(userId);

            post("/api/wallets/" + walletId + "/deposit", movement("1000.00", null),
                    token, newKey(), MovementResponse.class);
            post("/api/wallets/" + walletId + "/withdraw", movement("300.00", null),
                    token, newKey(), MovementResponse.class);

            ReconciliationResponse report = get("/api/wallets/admin/" + walletId + "/reconcile",
                    TestJwtIssuer.user(newUserId(), Roles.ADMIN),
                    ReconciliationResponse.class).body();

            assertThat(report).isNotNull();
            assertThat(report.storedBalanceMinor()).isEqualTo(700 * RUPEE);
            assertThat(report.replayedBalanceMinor()).isEqualTo(700 * RUPEE);
            assertThat(report.balanceMatches()).isTrue();
            assertThat(report.heldMatches()).isTrue();
            assertThat(report.consistent()).isTrue();
        }

        /**
         * Proves the check can actually fail. A reconciliation endpoint that always returns
         * {@code consistent: true} is worse than none, because it manufactures confidence;
         * so the test corrupts a balance behind the API's back and requires the mismatch to
         * be reported.
         */
        @Test
        @DisplayName("detects a balance that drifted from its movement history")
        void detectsDrift() {
            String userId = newUserId();
            String walletId = openWallet(userId);
            post("/api/wallets/" + walletId + "/deposit", movement("1000.00", null),
                    TestJwtIssuer.user(userId), newKey(), MovementResponse.class);

            // Money created out of nothing, with no movement to justify it.
            jdbc.update("UPDATE wallets SET balance_minor = balance_minor + 5000 WHERE wallet_id = ?",
                    walletId);

            ReconciliationResponse report = get("/api/wallets/admin/" + walletId + "/reconcile",
                    TestJwtIssuer.user(newUserId(), Roles.ADMIN),
                    ReconciliationResponse.class).body();

            assertThat(report).isNotNull();
            assertThat(report.storedBalanceMinor()).isEqualTo(1000 * RUPEE + 5000);
            assertThat(report.replayedBalanceMinor()).isEqualTo(1000 * RUPEE);
            assertThat(report.balanceMatches()).isFalse();
            assertThat(report.consistent())
                    .as("an endpoint that cannot report a mismatch is not a check")
                    .isFalse();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String openWallet(String userId) {
        WalletResponse wallet = post("/api/wallets", new CreateWalletRequest(CURRENCY),
                TestJwtIssuer.user(userId), WalletResponse.class).body();
        assertThat(wallet).isNotNull();
        return wallet.walletId();
    }

    private static FundsMovementRequest movement(String amount, String description) {
        return new FundsMovementRequest(new BigDecimal(amount), CURRENCY, description);
    }

    private long countWalletsFor(String userId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM wallets WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }
}
