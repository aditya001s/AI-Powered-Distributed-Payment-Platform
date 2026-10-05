package com.aegispay.wallet;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.security.Roles;
import com.aegispay.common.testsupport.TestJwtIssuer;
import com.aegispay.wallet.api.WalletDtos.CaptureRequest;
import com.aegispay.wallet.api.WalletDtos.CaptureResponse;
import com.aegispay.wallet.api.WalletDtos.CreateWalletRequest;
import com.aegispay.wallet.api.WalletDtos.HoldResponse;
import com.aegispay.wallet.api.WalletDtos.InternalWalletView;
import com.aegispay.wallet.api.WalletDtos.ReconciliationResponse;
import com.aegispay.wallet.api.WalletDtos.ReleaseRequest;
import com.aegispay.wallet.api.WalletDtos.ReserveRequest;
import com.aegispay.wallet.api.WalletDtos.WalletResponse;
import com.aegispay.wallet.domain.WalletHold;
import com.aegispay.wallet.service.WalletHoldSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reserve / capture / release primitives the payment saga is built on.
 *
 * <p>These are the operations that actually move customer money, and every one of them can
 * be retried by a saga. So the properties under test here are not "does it work once" but:
 *
 * <ul>
 *   <li><strong>reserve</strong> is idempotent per payment, and cannot over-reserve;</li>
 *   <li><strong>capture</strong> is atomic across two wallets, idempotent, and
 *       deadlock-free under crossing transfers;</li>
 *   <li><strong>release</strong> is idempotent and refuses to undo a real debit;</li>
 *   <li>a reservation whose saga died is eventually freed rather than stranding funds.</li>
 * </ul>
 *
 * <p>Balances are read straight from the tables. A capture that returned 200 while leaving
 * the money in neither wallet would pass any response-only assertion.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WalletReservationIT extends AbstractWalletIntegrationTest {

    private static final long RUPEE = 100L;

    @Autowired
    private WalletHoldSweeper sweeper;

    // ============================================================== reserving

    @Nested
    @DisplayName("reserving funds")
    class Reserving {

        /**
         * A reservation makes money unspendable without moving it. The balance must not
         * change: the customer still owns the funds, and a statement that showed a debit
         * here would be a lie about a payment that has not happened yet.
         */
        @Test
        @DisplayName("raises held and leaves the balance untouched")
        void reserveHoldsWithoutMovingMoney() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();

            Response<HoldResponse> response = reserve(paymentId, walletId, "400.00", null);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            HoldResponse hold = response.body();
            assertThat(hold).isNotNull();
            assertThat(hold.holdId()).startsWith("HLD-");
            assertThat(hold.state()).isEqualTo(WalletHold.State.ACTIVE);
            assertThat(hold.amount().amountMinor()).isEqualTo(400 * RUPEE);
            assertThat(hold.availableAfter().amountMinor()).isEqualTo(600 * RUPEE);

            assertThat(balanceMinor(walletId))
                    .as("reserving must not debit; the customer still owns the money")
                    .isEqualTo(1000 * RUPEE);
            assertThat(heldMinor(walletId)).isEqualTo(400 * RUPEE);
            assertThat(countTransactions(walletId))
                    .as("a reservation is not a movement and must not appear on the statement")
                    .isEqualTo(1); // only the seeding credit
        }

        /**
         * Idempotent by construction: one hold per payment, enforced by a unique
         * constraint. This is what makes a retried reserve safe without the caller having
         * to pass a separate idempotency key.
         */
        @Test
        @DisplayName("a repeated reserve for the same payment returns the existing hold")
        void reserveIsIdempotent() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();

            HoldResponse first = reserve(paymentId, walletId, "400.00", null).body();
            Response<HoldResponse> second = reserve(paymentId, walletId, "400.00", null);

            assertThat(second.status()).isEqualTo(HttpStatus.OK);
            assertThat(first).isNotNull();
            assertThat(second.body()).isNotNull();
            assertThat(second.body().holdId()).isEqualTo(first.holdId());

            assertThat(heldMinor(walletId))
                    .as("a retry must not reserve the amount twice")
                    .isEqualTo(400 * RUPEE);
            assertThat(countHolds(paymentId)).isEqualTo(1);
        }

        /**
         * Same payment, different amount, is a caller bug. Silently returning the original
         * hold would let a payment settle for an amount nobody reserved.
         */
        @Test
        @DisplayName("refuses to re-reserve a different amount for one payment")
        void refusesDifferentAmountForSamePayment() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();
            reserve(paymentId, walletId, "400.00", null);

            Response<ApiError> conflict = reserveExpectingError(paymentId, walletId, "900.00");

            assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflict.body()).isNotNull();
            assertThat(conflict.body().code()).isEqualTo(ErrorCode.INVALID_PAYMENT_STATE.name());
            assertThat(heldMinor(walletId)).isEqualTo(400 * RUPEE);
        }

        @Test
        @DisplayName("refuses to reserve more than is available")
        void refusesInsufficientFunds() {
            String walletId = fundedWallet(1000 * RUPEE);

            Response<ApiError> response = reserveExpectingError(newPaymentId(), walletId, "1500.00");

            assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS.name());
            assertThat(heldMinor(walletId)).isZero();
        }

        /** Successive reservations consume available funds cumulatively. */
        @Test
        @DisplayName("a second reservation is checked against what the first left")
        void reservationsAccumulate() {
            String walletId = fundedWallet(1000 * RUPEE);

            assertThat(reserve(newPaymentId(), walletId, "600.00", null).status())
                    .isEqualTo(HttpStatus.OK);
            assertThat(reserve(newPaymentId(), walletId, "300.00", null).status())
                    .isEqualTo(HttpStatus.OK);
            // Only 100 left.
            assertThat(reserveExpectingError(newPaymentId(), walletId, "200.00").status())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            assertThat(heldMinor(walletId)).isEqualTo(900 * RUPEE);
            assertThat(availableMinor(walletId)).isEqualTo(100 * RUPEE);
        }

        /**
         * Concurrent retries of one payment. The explicit lookup in {@code reserve} cannot
         * win this race on its own; the unique constraint on {@code payment_id} is what
         * makes a second hold impossible.
         */
        @Test
        @DisplayName("concurrent reserves for one payment create exactly one hold")
        void concurrentReservesForOnePaymentCreateOneHold() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();

            List<HttpStatus> statuses = inParallel(8, () ->
                    reserveStatus(paymentId, walletId, "400.00").status());

            assertThat(statuses).allSatisfy(status -> assertThat(status)
                    .as("a concurrent retry must not produce a 500")
                    .isIn(HttpStatus.OK, HttpStatus.CONFLICT));
            assertThat(countHolds(paymentId)).isEqualTo(1);
            assertThat(heldMinor(walletId)).isEqualTo(400 * RUPEE);
        }

        /**
         * The double-spend test for reservations. Ten distinct payments each try to reserve
         * the whole balance. However many succeed, {@code held} must never exceed
         * {@code balance} — an invariant also enforced by a CHECK constraint, so a violation
         * would be unpersistable rather than merely wrong.
         */
        @Test
        @DisplayName("concurrent reserves cannot collectively over-reserve")
        void concurrentReservesCannotOverReserve() {
            String walletId = fundedWallet(1000 * RUPEE);

            List<HttpStatus> statuses = inParallel(10, () ->
                    reserveStatus(newPaymentId(), walletId, "1000.00").status());

            assertThat(statuses).filteredOn(s -> s == HttpStatus.OK)
                    .as("only one reservation of the full balance can succeed")
                    .hasSize(1);
            assertThat(heldMinor(walletId)).isEqualTo(1000 * RUPEE);
            assertThat(heldMinor(walletId))
                    .as("held must never exceed balance")
                    .isLessThanOrEqualTo(balanceMinor(walletId));
        }

        /**
         * A frozen wallet is a routine state, not a fault. It must be reported as a 409 the
         * caller can act on, never as a 500 — a 500 would page an engineer for a working
         * fraud control and tell the customer nothing.
         */
        @Test
        @DisplayName("refuses to reserve on a frozen wallet, as a conflict rather than a fault")
        void refusesFrozenWallet() {
            String walletId = fundedWallet(1000 * RUPEE);
            jdbc.update("UPDATE wallets SET status = 'FROZEN' WHERE wallet_id = ?", walletId);

            Response<ApiError> response = reserveExpectingError(newPaymentId(), walletId, "100.00");

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.INVALID_PAYMENT_STATE.name());
            assertThat(heldMinor(walletId)).isZero();
        }

        @Test
        @DisplayName("returns 404 for an unknown wallet")
        void unknownWallet() {
            assertThat(reserveExpectingError(newPaymentId(), "WAL-DOES-NOT-EXIST", "100.00")
                    .status()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =============================================================== capture

    @Nested
    @DisplayName("capturing a reservation")
    class Capturing {

        /**
         * The core money movement. Both sides in one transaction, so there is no instant at
         * which the funds exist in neither wallet.
         */
        @Test
        @DisplayName("debits the source and credits the destination atomically")
        void capturesAndTransfers() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);

            Response<CaptureResponse> response = capture(paymentId, source, destination);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            CaptureResponse capture = response.body();
            assertThat(capture).isNotNull();
            assertThat(capture.amount().amountMinor()).isEqualTo(400 * RUPEE);
            assertThat(capture.debitTransactionId()).isNotBlank();
            assertThat(capture.creditTransactionId()).isNotBlank();

            assertThat(balanceMinor(source)).isEqualTo(600 * RUPEE);
            assertThat(heldMinor(source))
                    .as("capturing must discharge the hold, not leave it outstanding")
                    .isZero();
            assertThat(balanceMinor(destination)).isEqualTo(400 * RUPEE);
            assertThat(holdState(paymentId)).isEqualTo("CAPTURED");

            // Money is conserved: what left one wallet arrived in the other.
            assertThat(balanceMinor(source) + balanceMinor(destination)).isEqualTo(1000 * RUPEE);
            assertThat(countTransactionsOfType(source, "TRANSFER_OUT")).isEqualTo(1);
            assertThat(countTransactionsOfType(destination, "TRANSFER_IN")).isEqualTo(1);
        }

        /** A saga replay must return the original outcome, not transfer a second time. */
        @Test
        @DisplayName("a repeated capture returns the original movements")
        void captureIsIdempotent() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);

            CaptureResponse first = capture(paymentId, source, destination).body();
            Response<CaptureResponse> replay = capture(paymentId, source, destination);

            assertThat(replay.status()).isEqualTo(HttpStatus.OK);
            assertThat(first).isNotNull();
            assertThat(replay.body()).isNotNull();
            assertThat(replay.body().debitTransactionId()).isEqualTo(first.debitTransactionId());
            assertThat(replay.body().creditTransactionId()).isEqualTo(first.creditTransactionId());

            assertThat(balanceMinor(source)).isEqualTo(600 * RUPEE);
            assertThat(balanceMinor(destination)).isEqualTo(400 * RUPEE);
            assertThat(countTransactionsOfType(source, "TRANSFER_OUT"))
                    .as("a replay must not record a second debit")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("concurrent captures of one payment transfer exactly once")
        void concurrentCapturesTransferOnce() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);

            List<HttpStatus> statuses = inParallel(6, () ->
                    captureStatus(paymentId, source, destination).status());

            assertThat(statuses).allSatisfy(status ->
                    assertThat(status).isIn(HttpStatus.OK, HttpStatus.CONFLICT));
            assertThat(balanceMinor(source)).isEqualTo(600 * RUPEE);
            assertThat(balanceMinor(destination)).isEqualTo(400 * RUPEE);
            assertThat(countTransactionsOfType(source, "TRANSFER_OUT")).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 404 when no reservation exists")
        void noReservation() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);

            assertThat(captureExpectingError(newPaymentId(), source, destination).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(balanceMinor(source)).isEqualTo(1000 * RUPEE);
        }

        @Test
        @DisplayName("refuses to capture a released reservation")
        void refusesReleasedReservation() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);
            release(paymentId);

            Response<ApiError> response = captureExpectingError(paymentId, source, destination);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(balanceMinor(source))
                    .as("a released reservation must not be chargeable")
                    .isEqualTo(1000 * RUPEE);
            assertThat(balanceMinor(destination)).isZero();
        }

        /**
         * The reservation names the wallet it was taken on. Capturing against a different
         * source would debit a wallet that never agreed to the payment.
         */
        @Test
        @DisplayName("refuses a capture whose source does not match the reservation")
        void refusesMismatchedSource() {
            String reserved = fundedWallet(1000 * RUPEE);
            String other = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, reserved, "400.00", null);

            Response<ApiError> response = captureExpectingError(paymentId, other, destination);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(balanceMinor(other)).isEqualTo(1000 * RUPEE);
            assertThat(balanceMinor(reserved)).isEqualTo(1000 * RUPEE);
        }

        @Test
        @DisplayName("refuses a wallet paying itself")
        void refusesSelfTransfer() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();
            reserve(paymentId, walletId, "400.00", null);

            Response<ApiError> response = captureExpectingError(paymentId, walletId, walletId);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code())
                    .isEqualTo(ErrorCode.SELF_TRANSFER_NOT_ALLOWED.name());
        }

        /**
         * A frozen wallet cannot send but must still receive: refusing an incoming credit
         * would bounce a legitimate refund and make a fraud hold cost the customer money.
         */
        @Test
        @DisplayName("credits a frozen destination, because a freeze must not bounce refunds")
        void creditsFrozenDestination() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);
            jdbc.update("UPDATE wallets SET status = 'FROZEN' WHERE wallet_id = ?", destination);

            assertThat(capture(paymentId, source, destination).status()).isEqualTo(HttpStatus.OK);
            assertThat(balanceMinor(destination)).isEqualTo(400 * RUPEE);
        }

        @Test
        @DisplayName("refuses to credit a closed destination")
        void refusesClosedDestination() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);
            jdbc.update("UPDATE wallets SET status = 'CLOSED' WHERE wallet_id = ?", destination);

            Response<ApiError> response = captureExpectingError(paymentId, source, destination);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(balanceMinor(source)).isEqualTo(1000 * RUPEE);
            assertThat(heldMinor(source))
                    .as("the reservation must survive a refused capture so it can be released")
                    .isEqualTo(400 * RUPEE);
        }

        /**
         * <strong>The deadlock test.</strong> A&rarr;B and B&rarr;A captured simultaneously.
         * Locking source-then-destination makes each transaction hold the row the other
         * needs, so PostgreSQL kills one as a deadlock victim and the payment fails with a
         * 500. Sorting the wallet IDs before locking means every caller acquires locks in
         * the same order, so no cycle can form and both transfers complete.
         */
        @Test
        @DisplayName("crossing transfers both succeed, so sorted-order locking prevents deadlock")
        void crossingTransfersDoNotDeadlock() {
            String walletA = fundedWallet(1000 * RUPEE);
            String walletB = fundedWallet(1000 * RUPEE);
            String aToB = newPaymentId();
            String bToA = newPaymentId();
            reserve(aToB, walletA, "300.00", null);
            reserve(bToA, walletB, "500.00", null);

            // Two threads, opposite directions, at the same time.
            List<HttpStatus> statuses = inParallelDistinct(
                    () -> captureStatus(aToB, walletA, walletB).status(),
                    () -> captureStatus(bToA, walletB, walletA).status());

            assertThat(statuses)
                    .as("a deadlock would surface as a 409 CONCURRENT_MODIFICATION or a 500")
                    .containsExactly(HttpStatus.OK, HttpStatus.OK);

            // A: 1000 - 300 + 500 = 1200. B: 1000 - 500 + 300 = 800.
            assertThat(balanceMinor(walletA)).isEqualTo(1200 * RUPEE);
            assertThat(balanceMinor(walletB)).isEqualTo(800 * RUPEE);
            assertThat(heldMinor(walletA)).isZero();
            assertThat(heldMinor(walletB)).isZero();
        }
    }

    // =============================================================== release

    @Nested
    @DisplayName("releasing a reservation")
    class Releasing {

        @Test
        @DisplayName("returns the funds to spendable without moving money")
        void releaseFreesFunds() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();
            reserve(paymentId, walletId, "400.00", null);

            Response<HoldResponse> response = release(paymentId);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().state()).isEqualTo(WalletHold.State.RELEASED);

            assertThat(balanceMinor(walletId)).isEqualTo(1000 * RUPEE);
            assertThat(heldMinor(walletId)).isZero();
            assertThat(availableMinor(walletId)).isEqualTo(1000 * RUPEE);
            assertThat(countTransactionsOfType(walletId, "TRANSFER_OUT"))
                    .as("a release moves no money and records no movement")
                    .isZero();
        }

        /**
         * Compensation paths legitimately run more than once, so a second release must be a
         * no-op rather than an error. The payment service relies on this: it releases even
         * when the reserve call itself failed, because a failed reserve may be a lost
         * response with the hold created.
         */
        @Test
        @DisplayName("releasing twice is a no-op")
        void releaseIsIdempotent() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();
            reserve(paymentId, walletId, "400.00", null);

            assertThat(release(paymentId).status()).isEqualTo(HttpStatus.OK);
            assertThat(release(paymentId).status())
                    .as("compensation runs more than once by design")
                    .isEqualTo(HttpStatus.OK);

            assertThat(heldMinor(walletId)).isZero();
            assertThat(balanceMinor(walletId)).isEqualTo(1000 * RUPEE);
            assertThat(holdState(paymentId)).isEqualTo("RELEASED");
        }

        /** Releasing captured funds would hand back money that was legitimately taken. */
        @Test
        @DisplayName("refuses to release a captured reservation")
        void refusesToReleaseCaptured() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);
            capture(paymentId, source, destination);

            Response<ApiError> response = releaseExpectingError(paymentId);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(balanceMinor(source))
                    .as("a release must never reverse a completed capture")
                    .isEqualTo(600 * RUPEE);
            assertThat(balanceMinor(destination)).isEqualTo(400 * RUPEE);
        }

        @Test
        @DisplayName("returns 404 for an unknown payment")
        void unknownPayment() {
            assertThat(releaseExpectingError(newPaymentId()).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("frees the reservation so the funds can be spent again")
        void releasedFundsAreSpendableAgain() {
            String walletId = fundedWallet(1000 * RUPEE);
            String first = newPaymentId();
            reserve(first, walletId, "1000.00", null);
            assertThat(reserveExpectingError(newPaymentId(), walletId, "500.00").status())
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

            release(first);

            assertThat(reserve(newPaymentId(), walletId, "500.00", null).status())
                    .isEqualTo(HttpStatus.OK);
            assertThat(heldMinor(walletId)).isEqualTo(500 * RUPEE);
        }
    }

    // ============================================================ hold expiry

    @Nested
    @DisplayName("expired reservations")
    class Expiry {

        /**
         * The safety net for a crashed saga. Without it, one crash permanently reduces a
         * customer's usable balance and the only remedy is manual database surgery.
         */
        @Test
        @DisplayName("the sweeper releases a reservation that outlived its expiry")
        void sweeperReleasesExpiredHold() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();
            // Shortest TTL the schema permits (expires_at > created_at).
            reserve(paymentId, walletId, "400.00", 1);
            assertThat(heldMinor(walletId)).isEqualTo(400 * RUPEE);

            awaitUntil("the reservation to pass its expiry", Duration.ofSeconds(5),
                    () -> jdbc.queryForObject(
                            "SELECT expires_at < now() FROM wallet_holds WHERE payment_id = ?",
                            Boolean.class, paymentId));

            sweeper.releaseExpiredHolds();

            assertThat(heldMinor(walletId))
                    .as("expired reservations must not strand a customer's funds")
                    .isZero();
            assertThat(balanceMinor(walletId))
                    .as("expiry releases; it never takes the money")
                    .isEqualTo(1000 * RUPEE);
            assertThat(holdState(paymentId)).isEqualTo("RELEASED");
        }

        @Test
        @DisplayName("leaves reservations that are still within their window alone")
        void sweeperLeavesLiveHolds() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();
            reserve(paymentId, walletId, "400.00", 900);

            sweeper.releaseExpiredHolds();

            assertThat(heldMinor(walletId))
                    .as("sweeping a live reservation would break a payment in progress")
                    .isEqualTo(400 * RUPEE);
            assertThat(holdState(paymentId)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("running the sweeper repeatedly is harmless")
        void sweeperIsIdempotent() {
            String walletId = fundedWallet(1000 * RUPEE);
            String paymentId = newPaymentId();
            reserve(paymentId, walletId, "400.00", 1);

            awaitUntil("the reservation to pass its expiry", Duration.ofSeconds(5),
                    () -> jdbc.queryForObject(
                            "SELECT expires_at < now() FROM wallet_holds WHERE payment_id = ?",
                            Boolean.class, paymentId));

            sweeper.releaseExpiredHolds();
            sweeper.releaseExpiredHolds();
            sweeper.releaseExpiredHolds();

            assertThat(heldMinor(walletId)).isZero();
            assertThat(balanceMinor(walletId))
                    .as("a repeated sweep must not credit the wallet again")
                    .isEqualTo(1000 * RUPEE);
        }
    }

    // ================================================== internal wallet view

    @Nested
    @DisplayName("the internal wallet view")
    class InternalView {

        @Test
        @DisplayName("exposes balances and spendability, and nothing else")
        void exposesMinimalState() {
            String userId = newUserId();
            String walletId = openWalletFor(userId);
            seedBalance(walletId, 1000 * RUPEE);
            reserve(newPaymentId(), walletId, "300.00", null);

            Response<InternalWalletView> response = get("/internal/wallets/" + walletId,
                    TestJwtIssuer.service("payment-service"), InternalWalletView.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            InternalWalletView view = response.body();
            assertThat(view).isNotNull();
            assertThat(view.walletId()).isEqualTo(walletId);
            assertThat(view.userId()).isEqualTo(userId);
            assertThat(view.balanceMinor()).isEqualTo(1000 * RUPEE);
            assertThat(view.heldMinor()).isEqualTo(300 * RUPEE);
            assertThat(view.availableMinor()).isEqualTo(700 * RUPEE);
            assertThat(view.canSpend()).isTrue();
        }

        @Test
        @DisplayName("resolves a user's wallet by currency")
        void resolvesByUser() {
            String userId = newUserId();
            String walletId = openWalletFor(userId);

            Response<InternalWalletView> response = get(
                    "/internal/wallets/by-user/" + userId + "?currency=" + CURRENCY,
                    TestJwtIssuer.service("payment-service"), InternalWalletView.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().walletId()).isEqualTo(walletId);
        }

        @Test
        @DisplayName("reports a frozen wallet as unable to spend")
        void frozenWalletCannotSpend() {
            String walletId = fundedWallet(1000 * RUPEE);
            jdbc.update("UPDATE wallets SET status = 'FROZEN' WHERE wallet_id = ?", walletId);

            InternalWalletView view = get("/internal/wallets/" + walletId,
                    TestJwtIssuer.service("payment-service"), InternalWalletView.class).body();

            assertThat(view).isNotNull();
            assertThat(view.canSpend())
                    .as("a caller must be able to see that a wallet cannot send")
                    .isFalse();
            assertThat(view.balanceMinor())
                    .as("a freeze does not take the money")
                    .isEqualTo(1000 * RUPEE);
        }

        @Test
        @DisplayName("returns 404 when the user has no wallet in that currency")
        void unknownUserWallet() {
            assertThat(get("/internal/wallets/by-user/" + newUserId() + "?currency=INR",
                    TestJwtIssuer.service("payment-service"), ApiError.class).status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ================================================ reconciliation of holds

    @Nested
    @DisplayName("reconciling holds")
    class HoldReconciliation {

        @Test
        @DisplayName("held equals the sum of active reservations")
        void heldMatchesActiveHolds() {
            String walletId = fundedWallet(1000 * RUPEE);
            reserve(newPaymentId(), walletId, "300.00", null);
            String released = newPaymentId();
            reserve(released, walletId, "200.00", null);
            release(released);

            ReconciliationResponse report = get("/api/wallets/admin/" + walletId + "/reconcile",
                    TestJwtIssuer.user(newUserId(), Roles.ADMIN),
                    ReconciliationResponse.class).body();

            assertThat(report).isNotNull();
            assertThat(report.storedHeldMinor()).isEqualTo(300 * RUPEE);
            assertThat(report.summedHoldsMinor())
                    .as("a released hold must no longer count toward held")
                    .isEqualTo(300 * RUPEE);
            assertThat(report.heldMatches()).isTrue();
            assertThat(report.consistent()).isTrue();
        }

        @Test
        @DisplayName("stays consistent through a full reserve-and-capture cycle")
        void consistentAfterCapture() {
            String source = fundedWallet(1000 * RUPEE);
            String destination = fundedWallet(0L);
            String paymentId = newPaymentId();
            reserve(paymentId, source, "400.00", null);
            capture(paymentId, source, destination);

            String adminToken = TestJwtIssuer.user(newUserId(), Roles.ADMIN);
            ReconciliationResponse sourceReport = get(
                    "/api/wallets/admin/" + source + "/reconcile", adminToken,
                    ReconciliationResponse.class).body();
            ReconciliationResponse destinationReport = get(
                    "/api/wallets/admin/" + destination + "/reconcile", adminToken,
                    ReconciliationResponse.class).body();

            assertThat(sourceReport).isNotNull();
            assertThat(sourceReport.consistent()).isTrue();
            assertThat(sourceReport.storedHeldMinor()).isZero();
            assertThat(destinationReport).isNotNull();
            assertThat(destinationReport.consistent()).isTrue();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final String SERVICE_CLIENT = "payment-service";

    private String openWalletFor(String userId) {
        WalletResponse wallet = post("/api/wallets", new CreateWalletRequest(CURRENCY),
                TestJwtIssuer.user(userId), WalletResponse.class).body();
        assertThat(wallet).isNotNull();
        return wallet.walletId();
    }

    /** Opens a wallet and seeds it, so a test that needs funds is not also testing deposits. */
    private String fundedWallet(long amountMinor) {
        String walletId = openWalletFor(newUserId());
        if (amountMinor > 0) {
            seedBalance(walletId, amountMinor);
        }
        return walletId;
    }

    private Response<HoldResponse> reserve(String paymentId, String walletId, String amount,
                                           Integer ttlSeconds) {
        return post("/internal/wallets/reserve",
                new ReserveRequest(paymentId, walletId, new BigDecimal(amount), CURRENCY, ttlSeconds),
                TestJwtIssuer.service(SERVICE_CLIENT), HoldResponse.class);
    }

    /** Same call, decoded as an error so a failure status can be asserted without a cast. */
    private Response<ApiError> reserveExpectingError(String paymentId, String walletId,
                                                     String amount) {
        return post("/internal/wallets/reserve",
                new ReserveRequest(paymentId, walletId, new BigDecimal(amount), CURRENCY, null),
                TestJwtIssuer.service(SERVICE_CLIENT), ApiError.class);
    }

    /** Mixed concurrent outcomes are intentionally decoded only for their status. */
    private Response<String> reserveStatus(String paymentId, String walletId, String amount) {
        return postStatus("/internal/wallets/reserve",
                new ReserveRequest(paymentId, walletId, new BigDecimal(amount), CURRENCY, null),
                TestJwtIssuer.service(SERVICE_CLIENT));
    }

    private Response<CaptureResponse> capture(String paymentId, String source, String destination) {
        return post("/internal/wallets/capture",
                new CaptureRequest(paymentId, source, destination, "Integration test transfer"),
                TestJwtIssuer.service(SERVICE_CLIENT), CaptureResponse.class);
    }

    private Response<ApiError> captureExpectingError(String paymentId, String source,
                                                     String destination) {
        return post("/internal/wallets/capture",
                new CaptureRequest(paymentId, source, destination, "Integration test transfer"),
                TestJwtIssuer.service(SERVICE_CLIENT), ApiError.class);
    }

    /** Mixed concurrent outcomes are intentionally decoded only for their status. */
    private Response<String> captureStatus(String paymentId, String source, String destination) {
        return postStatus("/internal/wallets/capture",
                new CaptureRequest(paymentId, source, destination, "Integration test transfer"),
                TestJwtIssuer.service(SERVICE_CLIENT));
    }

    private Response<HoldResponse> release(String paymentId) {
        return post("/internal/wallets/release",
                new ReleaseRequest(paymentId, "Integration test release"),
                TestJwtIssuer.service(SERVICE_CLIENT), HoldResponse.class);
    }

    private Response<ApiError> releaseExpectingError(String paymentId) {
        return post("/internal/wallets/release",
                new ReleaseRequest(paymentId, "Integration test release"),
                TestJwtIssuer.service(SERVICE_CLIENT), ApiError.class);
    }

    /**
     * Runs two <em>different</em> tasks at the same instant and returns their results in
     * order. Needed for the crossing-transfer deadlock test, where the two directions are
     * not interchangeable.
     */
    private static List<HttpStatus> inParallelDistinct(java.util.concurrent.Callable<HttpStatus> first,
                                                       java.util.concurrent.Callable<HttpStatus> second) {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = pool.invokeAll(List.of(first, second));
            return List.of(futures.get(0).get(), futures.get(1).get());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Crossing-transfer test was interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("A crossing transfer failed unexpectedly", ex);
        }
    }
}
