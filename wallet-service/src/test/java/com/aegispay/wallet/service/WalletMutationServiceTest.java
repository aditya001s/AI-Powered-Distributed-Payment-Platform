package com.aegispay.wallet.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.money.Money;
import com.aegispay.common.outbox.OutboxRecorder;
import com.aegispay.wallet.domain.Wallet;
import com.aegispay.wallet.domain.WalletHold;
import com.aegispay.wallet.domain.WalletTransaction;
import com.aegispay.wallet.repository.WalletHoldRepository;
import com.aegispay.wallet.repository.WalletRepository;
import com.aegispay.wallet.repository.WalletTransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the reserve / capture / release primitives the payment saga is built on.
 *
 * <p><strong>Idempotency is the property under test.</strong> A saga retries, and Kafka
 * delivers at least once, so each of these will be invoked more than once for the same
 * payment. If any of them applied its effect twice the customer would be charged twice or
 * refunded money they never lost, so each is asserted to be a no-op on repetition.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletMutationServiceTest {

    private static final String INR = "INR";
    private static final String SOURCE = "WAL-AAAA1111";
    private static final String DEST = "WAL-BBBB2222";
    private static final String PAYMENT_ID = "PAY-0001";

    @Mock
    private WalletRepository wallets;
    @Mock
    private WalletHoldRepository holds;
    @Mock
    private WalletTransactionRepository transactions;
    /**
     * Mocked rather than exercised. What matters about the outbox is that the event is
     * recorded in the <em>same transaction</em> as the balance change, and no unit test with
     * mocked repositories can observe a transaction boundary — the mock records the call
     * whether or not the database keeps it. That property is asserted structurally by
     * {@code WalletTransactionBoundaryTest} and behaviourally by the integration suite.
     */
    @Mock
    private OutboxRecorder outbox;

    private WalletMutationService service;

    @BeforeEach
    void setUp() {
        service = new WalletMutationService(wallets, holds, transactions, outbox,
                new SimpleMeterRegistry());
        when(transactions.save(any(WalletTransaction.class))).thenAnswer(i -> i.getArgument(0));
        when(wallets.save(any(Wallet.class))).thenAnswer(i -> i.getArgument(0));
        when(holds.save(any(WalletHold.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Money inr(String amount) {
        return Money.of(amount, INR);
    }

    private Wallet funded(String walletId, String userId, String balance) {
        Wallet wallet = Wallet.open(walletId, userId, INR);
        wallet.credit(inr(balance), "test setup");
        return wallet;
    }

    // -------------------------------------------------------------- reserve

    @Nested
    @DisplayName("reserve")
    class Reserve {

        @Test
        @DisplayName("holds funds and records the reservation")
        void reservesFunds() {
            Wallet wallet = funded(SOURCE, "USR-A", "2000.00");
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(wallet));

            var response = service.reserve(PAYMENT_ID, SOURCE, inr("500.00"), null);

            assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);
            assertThat(response.state()).isEqualTo(WalletHold.State.ACTIVE);
            assertThat(response.availableAfter().amountMinor()).isEqualTo(150_000L);

            // The balance is untouched; only the held portion moved.
            assertThat(wallet.getBalanceMinor()).isEqualTo(200_000L);
            assertThat(wallet.getHeldMinor()).isEqualTo(50_000L);
        }

        /**
         * The idempotency guarantee. A retried reservation must return the existing hold
         * rather than reserving the amount a second time — which would silently double the
         * customer's unspendable balance.
         */
        @Test
        @DisplayName("returns the existing reservation instead of reserving twice")
        void isIdempotent() {
            Wallet wallet = funded(SOURCE, "USR-A", "2000.00");
            wallet.hold(inr("500.00"));
            WalletHold existing = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(existing));
            when(wallets.findByWalletId(SOURCE)).thenReturn(Optional.of(wallet));

            var response = service.reserve(PAYMENT_ID, SOURCE, inr("500.00"), null);

            assertThat(response.holdId()).isEqualTo(existing.getHoldId());
            // No second hold, and no further change to the wallet.
            verify(holds, never()).save(any(WalletHold.class));
            verify(wallets, never()).save(any(Wallet.class));
            assertThat(wallet.getHeldMinor()).isEqualTo(50_000L);
        }

        /**
         * Same payment, different amount, is a caller bug. Returning the original hold would
         * let a payment settle for an amount nobody reserved.
         */
        @Test
        @DisplayName("refuses to re-reserve the same payment for a different amount")
        void refusesAmountChange() {
            WalletHold existing = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(existing));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.reserve(PAYMENT_ID, SOURCE, inr("900.00"), null))
                    .withMessageContaining("already reserved");
        }

        @Test
        @DisplayName("refuses a reservation that exceeds available funds")
        void refusesInsufficientFunds() {
            Wallet wallet = funded(SOURCE, "USR-A", "100.00");
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(wallet));

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> service.reserve(PAYMENT_ID, SOURCE, inr("100.01"), null));

            assertThat(wallet.getHeldMinor()).isZero();
            verify(holds, never()).save(any(WalletHold.class));
        }

        @Test
        @DisplayName("gives every reservation a bounded lifetime")
        void reservationsExpire() {
            Wallet wallet = funded(SOURCE, "USR-A", "2000.00");
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(wallet));

            service.reserve(PAYMENT_ID, SOURCE, inr("500.00"), null);

            ArgumentCaptor<WalletHold> captor = ArgumentCaptor.forClass(WalletHold.class);
            verify(holds).save(captor.capture());

            // A reservation with no expiry would turn one crashed saga into permanently
            // frozen customer money.
            assertThat(captor.getValue().getExpiresAt())
                    .isNotNull()
                    .isAfter(captor.getValue().getCreatedAt());
        }
    }

    // -------------------------------------------------------------- capture

    @Nested
    @DisplayName("capture")
    class Capture {

        @Test
        @DisplayName("debits the source and credits the destination in one operation")
        void transfersAtomically() {
            Wallet source = funded(SOURCE, "USR-A", "2000.00");
            source.hold(inr("500.00"));
            Wallet destination = funded(DEST, "USR-B", "100.00");
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(source));
            when(wallets.findByWalletIdForUpdate(DEST)).thenReturn(Optional.of(destination));

            var response = service.captureAndTransfer(PAYMENT_ID, SOURCE, DEST, "Dinner");

            assertThat(response.amount().amountMinor()).isEqualTo(50_000L);
            assertThat(response.debitTransactionId()).isNotBlank();
            assertThat(response.creditTransactionId()).isNotBlank();

            // Both sides moved, and the money was never in neither wallet.
            assertThat(source.getBalanceMinor()).isEqualTo(150_000L);
            assertThat(source.getHeldMinor()).isZero();
            assertThat(destination.getBalanceMinor()).isEqualTo(60_000L);
            assertThat(hold.getState()).isEqualTo(WalletHold.State.CAPTURED);
        }

        /**
         * Deadlock avoidance. Every caller must take wallet locks in the same order, or
         * simultaneous A&rarr;B and B&rarr;A transfers each hold what the other needs.
         */
        @Test
        @DisplayName("locks wallets in sorted ID order regardless of transfer direction")
        void locksInConsistentOrder() {
            Wallet source = funded(DEST, "USR-B", "2000.00");   // note: DEST sorts first
            source.hold(inr("500.00"));
            // Opened rather than funded: crediting zero is correctly rejected by the domain.
            Wallet destination = Wallet.open(SOURCE, "USR-A", INR);
            WalletHold hold = WalletHold.create(DEST, PAYMENT_ID, inr("500.00"), null);

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletIdForUpdate(DEST)).thenReturn(Optional.of(source));
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(destination));

            // Transferring DEST -> SOURCE, i.e. the reverse of the other test's direction.
            service.captureAndTransfer(PAYMENT_ID, DEST, SOURCE, "Reverse direction");

            // WAL-AAAA1111 sorts before WAL-BBBB2222, so it must be locked first even though
            // it is the destination here.
            var order = org.mockito.Mockito.inOrder(wallets);
            order.verify(wallets).findByWalletIdForUpdate(SOURCE);
            order.verify(wallets).findByWalletIdForUpdate(DEST);
        }

        /**
         * The double-charge case. A replayed capture must not transfer again.
         */
        @Test
        @DisplayName("returns the original outcome for an already-captured payment")
        void isIdempotent() {
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);
            hold.capture();

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(transactions.findByReferenceIdOrderByCreatedAtAsc(PAYMENT_ID))
                    .thenReturn(List.of());

            var response = service.captureAndTransfer(PAYMENT_ID, SOURCE, DEST, "Dinner");

            assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);
            // No wallet was even loaded for update, so no money moved a second time.
            verify(wallets, never()).findByWalletIdForUpdate(anyString());
            verify(wallets, never()).save(any(Wallet.class));
        }

        @Test
        @DisplayName("refuses to capture a released reservation")
        void refusesReleasedHold() {
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);
            hold.release();
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.captureAndTransfer(PAYMENT_ID, SOURCE, DEST, null))
                    .withMessageContaining("released");
        }

        @Test
        @DisplayName("refuses a capture with no reservation behind it")
        void refusesMissingHold() {
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(NotFoundException.class)
                    .isThrownBy(() -> service.captureAndTransfer(PAYMENT_ID, SOURCE, DEST, null));
        }

        @Test
        @DisplayName("refuses a wallet paying itself")
        void refusesSelfTransfer() {
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.captureAndTransfer(PAYMENT_ID, SOURCE, SOURCE, null))
                    .satisfies(ex -> assertThat(ex.errorCode())
                            .isEqualTo(ErrorCode.SELF_TRANSFER_NOT_ALLOWED));
        }

        @Test
        @DisplayName("refuses a capture whose reservation is on a different wallet")
        void refusesWalletMismatch() {
            WalletHold hold = WalletHold.create("WAL-OTHER", PAYMENT_ID, inr("500.00"), null);
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.captureAndTransfer(PAYMENT_ID, SOURCE, DEST, null))
                    .withMessageContaining("reserved funds on wallet");
        }
    }

    // -------------------------------------------------------------- release

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("returns the reserved funds to spendable without moving money")
        void releasesFunds() {
            Wallet wallet = funded(SOURCE, "USR-A", "2000.00");
            wallet.hold(inr("500.00"));
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(wallet));

            var response = service.release(PAYMENT_ID, "Payment failed");

            assertThat(response.state()).isEqualTo(WalletHold.State.RELEASED);
            assertThat(wallet.getBalanceMinor()).isEqualTo(200_000L);
            assertThat(wallet.getHeldMinor()).isZero();
        }

        /**
         * Compensation paths legitimately run more than once, so a repeated release must be a
         * no-op rather than an error — otherwise a retried compensation would fail and look
         * like an unresolved problem.
         */
        @Test
        @DisplayName("releasing twice is a no-op")
        void isIdempotent() {
            Wallet wallet = funded(SOURCE, "USR-A", "2000.00");
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);
            hold.release();

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletId(SOURCE)).thenReturn(Optional.of(wallet));

            var response = service.release(PAYMENT_ID, "Retry of a compensation");

            assertThat(response.state()).isEqualTo(WalletHold.State.RELEASED);
            verify(wallets, never()).findByWalletIdForUpdate(anyString());
            verify(wallets, never()).save(any(Wallet.class));
        }

        /**
         * Releasing captured funds would hand back money that was legitimately taken, so it
         * is refused outright rather than treated as another idempotent no-op.
         */
        @Test
        @DisplayName("refuses to release a captured reservation")
        void refusesCapturedHold() {
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);
            hold.capture();
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.release(PAYMENT_ID, "too late"))
                    .withMessageContaining("captured");
        }
    }

    @Nested
    @DisplayName("extend")
    class Extend {

        /**
         * <strong>Why extension exists at all.</strong> The default fifteen-minute lifetime is sized
         * for a payment that completes in seconds. A payment held for fraud review waits on a human
         * instead, and the expiry sweeper would release the funds while the saga still believed they
         * were held — the customer could spend the money, and the analyst releasing the payment would
         * capture against a balance that had moved underneath them.
         */
        @Test
        @DisplayName("pushes the expiry out without touching the balance")
        void extendsTheExpiry() {
            Wallet wallet = funded(SOURCE, "USR-A", "2000.00");
            wallet.hold(inr("500.00"));
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"),
                    java.time.Duration.ofMinutes(15));
            java.time.Instant original = hold.getExpiresAt();

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletId(SOURCE)).thenReturn(Optional.of(wallet));

            var response = service.extend(PAYMENT_ID, java.time.Duration.ofDays(2),
                    "Held for fraud review");

            assertThat(hold.getExpiresAt()).isAfter(original);
            assertThat(response.state()).isEqualTo(WalletHold.State.ACTIVE);
            assertThat(wallet.getHeldMinor())
                    .as("extension changes a lifetime, never a balance")
                    .isEqualTo(50_000L);
            verify(wallets, never()).save(any(Wallet.class));
        }

        /**
         * Extension is read-modify-write on the expiry. Without the lock a concurrent release could
         * interleave and leave a RELEASED hold carrying a future expiry — a row claiming funds are
         * reserved when the customer can already spend them.
         */
        @Test
        @DisplayName("takes the row lock rather than an unlocked read")
        void locksTheHold() {
            Wallet wallet = funded(SOURCE, "USR-A", "2000.00");
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletId(SOURCE)).thenReturn(Optional.of(wallet));

            service.extend(PAYMENT_ID, java.time.Duration.ofHours(6), "review");

            verify(holds).findByPaymentIdForUpdate(PAYMENT_ID);
            verify(holds, never()).findByPaymentId(anyString());
        }

        /**
         * A shortened expiry could sweep a reservation a saga is still relying on, and a caller asking
         * for one is almost certainly passing a default it did not mean to. Returning the existing
         * expiry unchanged is the safe reading of an ambiguous request.
         */
        @Test
        @DisplayName("never shortens an expiry that is already further out")
        void neverShortens() {
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"),
                    java.time.Duration.ofDays(2));
            java.time.Instant original = hold.getExpiresAt();

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletId(SOURCE))
                    .thenReturn(Optional.of(funded(SOURCE, "USR-A", "2000.00")));

            service.extend(PAYMENT_ID, java.time.Duration.ofMinutes(1), "oops");

            assertThat(hold.getExpiresAt()).isEqualTo(original);
        }

        /**
         * Extending a captured hold would suggest the funds are still reserved when they have already
         * moved; extending a released one would suggest they are unspendable when the customer can
         * spend them. Both are lies about the balance, so the caller is told now rather than at
         * capture time.
         */
        @Test
        @DisplayName("refuses a reservation that is no longer active")
        void refusesInactiveHold() {
            WalletHold captured = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);
            captured.capture();
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(captured));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.extend(PAYMENT_ID,
                            java.time.Duration.ofDays(1), "too late"))
                    .withMessageContaining("CAPTURED");
        }

        @Test
        @DisplayName("reports a missing reservation rather than silently succeeding")
        void refusesMissingHold() {
            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(NotFoundException.class)
                    .isThrownBy(() -> service.extend(PAYMENT_ID,
                            java.time.Duration.ofDays(1), "review"));
        }

        /**
         * Extension must not become a way to freeze a customer's money indefinitely, which is the
         * failure the expiry exists to prevent — it would simply arrive by extension instead of by
         * omission.
         */
        @Test
        @DisplayName("caps a single extension at the hard ceiling")
        void capsTheExtension() {
            WalletHold hold = WalletHold.create(SOURCE, PAYMENT_ID, inr("500.00"), null);

            when(holds.findByPaymentIdForUpdate(PAYMENT_ID)).thenReturn(Optional.of(hold));
            when(wallets.findByWalletId(SOURCE))
                    .thenReturn(Optional.of(funded(SOURCE, "USR-A", "2000.00")));

            service.extend(PAYMENT_ID, java.time.Duration.ofDays(3650), "forever, please");

            assertThat(hold.getExpiresAt())
                    .as("a reservation with no effective bound is the thing expiry prevents")
                    .isBefore(java.time.Instant.now()
                            .plus(WalletHold.MAX_EXTENSION)
                            .plus(java.time.Duration.ofMinutes(1)));
        }
    }

    // ------------------------------------------------------ funds movement

    @Nested
    @DisplayName("deposit and withdraw")
    class FundsMovement {

        @Test
        @DisplayName("records the resulting balance on the movement")
        void recordsBalanceAfter() {
            Wallet wallet = funded(SOURCE, "USR-A", "1000.00");
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(wallet));

            var response = service.deposit(SOURCE, "USR-A", inr("500.00"), "Top-up");

            assertThat(response.wallet().balance().amountMinor()).isEqualTo(150_000L);
            // Denormalised so a statement line is independently verifiable.
            assertThat(response.transaction().balanceAfter().amountMinor()).isEqualTo(150_000L);
        }

        @Test
        @DisplayName("a withdrawal cannot spend reserved funds")
        void withdrawalRespectsHolds() {
            Wallet wallet = funded(SOURCE, "USR-A", "1000.00");
            wallet.hold(inr("900.00"));
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(wallet));

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> service.withdraw(SOURCE, "USR-A", inr("200.00"), null));

            assertThat(wallet.getBalanceMinor()).isEqualTo(100_000L);
        }

        /**
         * Reported as not-found rather than forbidden: confirming that a wallet exists but
         * belongs to someone else is itself an information leak, which is also why wallet IDs
         * are random rather than sequential.
         */
        @Test
        @DisplayName("hides a wallet the caller does not own")
        void refusesForeignWallet() {
            Wallet wallet = funded(SOURCE, "USR-OWNER", "1000.00");
            when(wallets.findByWalletIdForUpdate(SOURCE)).thenReturn(Optional.of(wallet));

            assertThatExceptionOfType(NotFoundException.class)
                    .isThrownBy(() -> service.withdraw(SOURCE, "USR-ATTACKER", inr("100.00"), null))
                    .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));

            assertThat(wallet.getBalanceMinor()).isEqualTo(100_000L);
        }
    }
}
