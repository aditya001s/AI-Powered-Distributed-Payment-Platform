package com.aegispay.wallet.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests for the {@link Wallet} balance invariants.
 *
 * <p>These are the highest-consequence rules in the platform: a bug here creates or
 * destroys customer money. The tests target the invariants rather than the accessors, and
 * in particular the interaction between the balance and the reserved (held) portion, which
 * is where the subtle errors live.
 */
class WalletTest {

    private static final String INR = "INR";

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        wallet = Wallet.open("WAL-TEST0001", "USR-TEST0001", INR);
    }

    private Money inr(String amount) {
        return Money.of(amount, INR);
    }

    @Nested
    @DisplayName("a new wallet")
    class NewWallet {

        @Test
        @DisplayName("starts empty and active")
        void startsEmpty() {
            assertThat(wallet.getBalanceMinor()).isZero();
            assertThat(wallet.getHeldMinor()).isZero();
            assertThat(wallet.availableMinor()).isZero();
            assertThat(wallet.getStatus()).isEqualTo(Wallet.Status.ACTIVE);
        }

        @Test
        @DisplayName("normalises its currency code")
        void normalisesCurrency() {
            assertThat(Wallet.open("WAL-X", "USR-X", "inr").getCurrency()).isEqualTo("INR");
        }
    }

    @Nested
    @DisplayName("credit and debit")
    class CreditAndDebit {

        @Test
        @DisplayName("credit increases the balance and the available amount")
        void creditIncreasesBalance() {
            wallet.credit(inr("2000.00"), "deposit");

            assertThat(wallet.balance()).isEqualTo(inr("2000.00"));
            assertThat(wallet.available()).isEqualTo(inr("2000.00"));
        }

        @Test
        @DisplayName("debit decreases the balance")
        void debitDecreasesBalance() {
            wallet.credit(inr("2000.00"), "deposit");
            wallet.debit(inr("500.00"), "withdrawal");

            assertThat(wallet.balance()).isEqualTo(inr("1500.00"));
        }

        @Test
        @DisplayName("permits spending the entire available balance")
        void allowsExactBalanceSpend() {
            wallet.credit(inr("2000.00"), "deposit");
            wallet.debit(inr("2000.00"), "withdrawal");

            assertThat(wallet.balance().isZero()).isTrue();
        }

        /**
         * The single most important rejection in the service. A wallet must never go
         * negative: overdraft is not a product here, so a negative balance is always a bug.
         */
        @Test
        @DisplayName("refuses a debit that exceeds the balance")
        void refusesOverdraft() {
            wallet.credit(inr("100.00"), "deposit");

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> wallet.debit(inr("100.01"), "withdrawal"))
                    .satisfies(ex -> {
                        assertThat(ex.details()).containsEntry("available", "100.00");
                        assertThat(ex.details()).containsEntry("requested", "100.01");
                    });

            // State is untouched by the failed attempt.
            assertThat(wallet.balance()).isEqualTo(inr("100.00"));
        }

        @Test
        @DisplayName("rejects a non-positive amount")
        void rejectsNonPositiveAmounts() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> wallet.credit(Money.zero(INR), "deposit"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> wallet.debit(Money.ofMinor(-100L, INR), "withdrawal"));
        }

        /**
         * A wallet is denominated in one currency. Accepting a different one would corrupt
         * the balance silently, because the stored value is a bare integer with no currency
         * of its own.
         */
        @Test
        @DisplayName("rejects an amount in another currency")
        void rejectsForeignCurrency() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> wallet.credit(Money.of("10.00", "USD"), "deposit"))
                    .withMessageContaining("denominated in INR");
        }
    }

    @Nested
    @DisplayName("reservations")
    class Reservations {

        @BeforeEach
        void fund() {
            wallet.credit(inr("2000.00"), "deposit");
        }

        /**
         * The defining property of a reservation: the money becomes unspendable but is still
         * visibly the customer's. Debiting early instead would show a phantom
         * debit-and-refund pair for every abandoned payment.
         */
        @Test
        @DisplayName("a hold reduces available funds without changing the balance")
        void holdReducesAvailableOnly() {
            wallet.hold(inr("500.00"));

            assertThat(wallet.balance()).isEqualTo(inr("2000.00"));
            assertThat(wallet.held()).isEqualTo(inr("500.00"));
            assertThat(wallet.available()).isEqualTo(inr("1500.00"));
        }

        /**
         * The double-spend case. Two payments cannot each reserve more than what remains,
         * because the second is checked against <em>available</em> funds, not the balance.
         */
        @Test
        @DisplayName("refuses a second hold that exceeds the remaining available funds")
        void refusesOverReservation() {
            wallet.hold(inr("1500.00"));

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> wallet.hold(inr("600.00")));

            // The first reservation is intact; the failed one changed nothing.
            assertThat(wallet.held()).isEqualTo(inr("1500.00"));
            assertThat(wallet.available()).isEqualTo(inr("500.00"));
        }

        @Test
        @DisplayName("permits holds that exactly consume the available funds")
        void allowsHoldsUpToAvailable() {
            wallet.hold(inr("1500.00"));
            wallet.hold(inr("500.00"));

            assertThat(wallet.held()).isEqualTo(inr("2000.00"));
            assertThat(wallet.available().isZero()).isTrue();
            // held == balance is the boundary the CHECK constraint permits.
            assertThat(wallet.getHeldMinor()).isEqualTo(wallet.getBalanceMinor());
        }

        /**
         * A withdrawal must not be able to spend money already promised to an in-flight
         * payment. This is the whole reason holds are tracked separately from the balance.
         */
        @Test
        @DisplayName("a withdrawal cannot spend reserved funds")
        void withdrawalCannotSpendHeldFunds() {
            wallet.hold(inr("1800.00"));

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> wallet.debit(inr("300.00"), "withdrawal"));

            // 200.00 is available, so that much still works.
            wallet.debit(inr("200.00"), "withdrawal");
            assertThat(wallet.balance()).isEqualTo(inr("1800.00"));
            assertThat(wallet.available().isZero()).isTrue();
        }

        @Test
        @DisplayName("capturing a hold moves both the balance and the held total together")
        void captureMovesBothColumns() {
            wallet.hold(inr("500.00"));
            wallet.captureHold(inr("500.00"));

            assertThat(wallet.balance()).isEqualTo(inr("1500.00"));
            assertThat(wallet.held().isZero()).isTrue();
            assertThat(wallet.available()).isEqualTo(inr("1500.00"));
        }

        /**
         * Capture deliberately does not re-check available funds: the money was already
         * reserved. Re-checking would reject a legitimate capture whenever another payment
         * had reserved the remainder in the meantime.
         */
        @Test
        @DisplayName("capture succeeds even when nothing else is available")
        void captureIgnoresAvailableFunds() {
            wallet.hold(inr("2000.00"));
            assertThat(wallet.available().isZero()).isTrue();

            wallet.captureHold(inr("2000.00"));

            assertThat(wallet.balance().isZero()).isTrue();
            assertThat(wallet.held().isZero()).isTrue();
        }

        @Test
        @DisplayName("releasing a hold returns the funds without moving money")
        void releaseRestoresAvailable() {
            wallet.hold(inr("500.00"));
            wallet.releaseHold(inr("500.00"));

            assertThat(wallet.balance()).isEqualTo(inr("2000.00"));
            assertThat(wallet.held().isZero()).isTrue();
            assertThat(wallet.available()).isEqualTo(inr("2000.00"));
        }

        /**
         * Capturing or releasing more than was reserved would corrupt the held total and
         * break the {@code held <= balance} invariant. Both are refused rather than clamped:
         * a clamp would hide a saga bug, an exception reports it.
         */
        @Test
        @DisplayName("refuses to capture or release more than is reserved")
        void refusesToExceedTheReservation() {
            wallet.hold(inr("500.00"));

            assertThatIllegalStateException()
                    .isThrownBy(() -> wallet.captureHold(inr("500.01")))
                    .withMessageContaining("only 500.00 INR is reserved");

            assertThatIllegalStateException()
                    .isThrownBy(() -> wallet.releaseHold(inr("600.00")))
                    .withMessageContaining("only 500.00 INR is reserved");

            assertThat(wallet.held()).isEqualTo(inr("500.00"));
        }

        @Test
        @DisplayName("keeps held within balance across a mixed sequence")
        void maintainsHeldWithinBalanceInvariant() {
            wallet.hold(inr("800.00"));
            wallet.hold(inr("400.00"));
            wallet.captureHold(inr("800.00"));
            wallet.releaseHold(inr("400.00"));
            wallet.credit(inr("100.00"), "refund");
            wallet.hold(inr("1300.00"));

            // The invariant the database also enforces, checked after every step.
            assertThat(wallet.getHeldMinor()).isLessThanOrEqualTo(wallet.getBalanceMinor());
            assertThat(wallet.getBalanceMinor()).isNotNegative();
            assertThat(wallet.availableMinor()).isNotNegative();
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @BeforeEach
        void fund() {
            wallet.credit(inr("1000.00"), "deposit");
        }

        /**
         * A frozen wallet must still accept credits. Refusing them would bounce a legitimate
         * refund owed to the customer, so a fraud hold would cost them money.
         *
         * <p>The refusals are {@link ConflictException}, not {@code IllegalStateException},
         * and that distinction is the point of asserting the type here. FROZEN is a routine
         * operational state, so "cannot send" is a business outcome worth a 409 the caller
         * can act on. An {@code IllegalStateException} would reach the global handler's
         * catch-all and become an opaque 500: retried by clients, counted as a platform
         * fault, and paging an engineer for a fraud control that is working correctly.
         */
        @Test
        @DisplayName("a frozen wallet can still receive but cannot send")
        void frozenWalletReceivesButCannotSend() {
            wallet.freeze();

            wallet.credit(inr("100.00"), "refund");
            assertThat(wallet.balance()).isEqualTo(inr("1100.00"));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> wallet.debit(inr("100.00"), "withdrawal"))
                    .withMessageContaining("FROZEN")
                    .satisfies(ex -> assertThat(ex.errorCode())
                            .isEqualTo(ErrorCode.INVALID_PAYMENT_STATE));
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> wallet.hold(inr("100.00")));
            assertThat(wallet.canSpend(100_00L)).isFalse();
        }

        /** The status refusal must map to 409, not 500. */
        @Test
        @DisplayName("a status refusal is a client-visible conflict, not a server fault")
        void statusRefusalIsAConflict() {
            wallet.freeze();

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> wallet.debit(inr("100.00"), "withdrawal"))
                    .satisfies(ex -> assertThat(ex.errorCode().status().value())
                            .as("a frozen wallet must never surface as a 5xx")
                            .isEqualTo(409));
        }

        @Test
        @DisplayName("unfreezing restores the ability to send")
        void unfreezeRestoresSpending() {
            wallet.freeze();
            wallet.unfreeze();

            wallet.debit(inr("100.00"), "withdrawal");
            assertThat(wallet.balance()).isEqualTo(inr("900.00"));
        }

        /**
         * Capture is still permitted while frozen. By that point the funds were already
         * reserved and the payment is mid-flight; blocking it would strand money in a hold
         * that nothing can discharge.
         */
        @Test
        @DisplayName("a frozen wallet can still capture an existing reservation")
        void frozenWalletCanStillCapture() {
            wallet.hold(inr("200.00"));
            wallet.freeze();

            wallet.captureHold(inr("200.00"));

            assertThat(wallet.balance()).isEqualTo(inr("800.00"));
        }

        @Test
        @DisplayName("refuses to close a wallet that still holds money")
        void refusesToCloseNonEmptyWallet() {
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> wallet.close())
                    .withMessageContaining("cannot be closed while it holds");

            wallet.debit(inr("1000.00"), "withdrawal");
            wallet.close();
            assertThat(wallet.getStatus()).isEqualTo(Wallet.Status.CLOSED);
        }

        @Test
        @DisplayName("refuses to close a wallet with an outstanding reservation")
        void refusesToCloseWithActiveHold() {
            wallet.debit(inr("900.00"), "withdrawal");
            wallet.hold(inr("100.00"));

            assertThatExceptionOfType(ConflictException.class).isThrownBy(() -> wallet.close());
        }

        @Test
        @DisplayName("a closed wallet cannot receive")
        void closedWalletCannotReceive() {
            wallet.debit(inr("1000.00"), "withdrawal");
            wallet.close();

            assertThat(wallet.canReceive()).isFalse();
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> wallet.credit(inr("10.00"), "refund"));
        }
    }

    @Nested
    @DisplayName("arithmetic safety")
    class ArithmeticSafety {

        /**
         * Overflow must throw rather than wrap. A wrapped-around long would turn a huge
         * balance negative, which the CHECK constraint would then reject at commit — but far
         * from the code that caused it.
         */
        @Test
        @DisplayName("detects overflow instead of wrapping")
        void detectsOverflow() {
            Wallet huge = Wallet.open("WAL-HUGE", "USR-HUGE", INR);
            huge.credit(Money.ofMinor(Long.MAX_VALUE, INR), "deposit");

            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> huge.credit(Money.ofMinor(1L, INR), "deposit"));
        }

        @Test
        @DisplayName("stays exact across many small movements")
        void staysExactOverManyMovements() {
            for (int i = 0; i < 10_000; i++) {
                wallet.credit(Money.ofMinor(1L, INR), "deposit");
            }
            // 10,000 paise = 100.00 exactly. Floating-point accumulation would drift.
            assertThat(wallet.balance()).isEqualTo(inr("100.00"));
            assertThat(wallet.getBalanceMinor()).isEqualTo(10_000L);
        }
    }
}
