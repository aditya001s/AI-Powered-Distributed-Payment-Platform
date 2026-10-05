package com.aegispay.ledger.domain;

import com.aegispay.common.domain.SharedEnums.AccountType;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link LedgerAccount}, and specifically for the direction rules.
 *
 * <p>These are the highest-consequence rules in the ledger. "Increase" means opposite things
 * depending on account type — a debit increases an asset but decreases a liability — and
 * getting it backwards for wallet accounts inverts every posting in the platform while every
 * individual number still looks plausible. So the sign behaviour is asserted per account type
 * rather than only for the happy path.
 */
class LedgerAccountTest {

    private static final String INR = "INR";
    private static final String WALLET = "wallet:WAL-AAAA1111";

    private static LedgerAccount wallet() {
        return LedgerAccount.forWallet(WALLET, INR);
    }

    /**
     * Builds a system account of an arbitrary type.
     *
     * <p>Reflection because there is deliberately no public factory: system accounts are
     * created by migration, so production code has no legitimate way to construct one and
     * offering a factory would invite exactly the guessing the design forbids.
     */
    private static LedgerAccount systemAccount(String accountId, AccountType type,
                                               boolean allowsNegative) {
        try {
            LedgerAccount account = LedgerAccount.forWallet(accountId, INR);
            set(account, "accountType", type);
            set(account, "allowsNegative", allowsNegative);
            set(account, "systemAccount", true);
            return account;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void set(Object target, String field, Object value)
            throws ReflectiveOperationException {
        Field declared = LedgerAccount.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    @Nested
    @DisplayName("direction rules")
    class DirectionRules {

        /**
         * The single most commonly inverted thing in a payment system. A customer wallet is a
         * LIABILITY — the money is theirs and the platform owes it — so a CREDIT increases it.
         */
        @Test
        @DisplayName("a credit increases a customer wallet, because a wallet is a liability")
        void creditIncreasesAWallet() {
            LedgerAccount account = wallet();

            long after = account.apply(PostingDirection.CREDIT, 200_000L);

            assertThat(after).isEqualTo(200_000L);
            assertThat(account.getAccountType()).isEqualTo(AccountType.LIABILITY);
            assertThat(account.debitIncreases())
                    .as("if this were true, every wallet posting in the platform would be inverted")
                    .isFalse();
            assertThat(account.getCreditTotalMinor()).isEqualTo(200_000L);
            assertThat(account.getDebitTotalMinor()).isZero();
        }

        @Test
        @DisplayName("a debit decreases a customer wallet")
        void debitDecreasesAWallet() {
            LedgerAccount account = wallet();
            account.apply(PostingDirection.CREDIT, 200_000L);

            long after = account.apply(PostingDirection.DEBIT, 50_000L);

            assertThat(after).isEqualTo(150_000L);
            assertThat(account.getDebitTotalMinor()).isEqualTo(50_000L);
            assertThat(account.getCreditTotalMinor()).isEqualTo(200_000L);
        }

        @Test
        @DisplayName("a debit increases an asset account")
        void debitIncreasesAnAsset() {
            LedgerAccount funding = systemAccount("asset:external-funding:INR",
                    AccountType.ASSET, true);

            long after = funding.apply(PostingDirection.DEBIT, 100_000L);

            assertThat(after)
                    .as("money arriving from outside is an asset gained, so a debit adds to it")
                    .isEqualTo(100_000L);
            assertThat(funding.debitIncreases()).isTrue();
        }

        @ParameterizedTest
        @DisplayName("debitIncreases matches the accounting convention for every type")
        @CsvSource({
                "ASSET,true",
                "EXPENSE,true",
                "CLEARING,true",
                "LIABILITY,false",
                "REVENUE,false"
        })
        void debitIncreasesPerType(AccountType type, boolean expected) {
            // Mirrored by ck_ledger_accounts_balance_derived in the schema. The duplication is
            // the point: the database catches a Java sign error rather than storing it.
            assertThat(systemAccount("test:" + type, type, true).debitIncreases())
                    .isEqualTo(expected);
        }

        @ParameterizedTest
        @DisplayName("signedEffectOf agrees with the direction and the account type")
        @EnumSource(AccountType.class)
        void signedEffectAgreesWithType(AccountType type) {
            LedgerAccount account = systemAccount("test:" + type, type, true);
            long amount = 1_000L;

            long debitEffect = account.signedEffectOf(PostingDirection.DEBIT, amount);
            long creditEffect = account.signedEffectOf(PostingDirection.CREDIT, amount);

            assertThat(debitEffect).isEqualTo(type.debitIncreases() ? amount : -amount);
            assertThat(creditEffect).isEqualTo(-debitEffect);
            assertThat(debitEffect + creditEffect)
                    .as("a debit and a credit of the same amount on one account must net to zero")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("balance derivation")
    class BalanceDerivation {

        /**
         * The balance is fully derived from the two totals. The schema asserts the same
         * relationship with a CHECK constraint, so this test and that constraint together mean
         * a drifted cached balance is both caught in unit tests and unpersistable.
         */
        @Test
        @DisplayName("the balance is always credits minus debits for a liability")
        void balanceIsDerivedForALiability() {
            LedgerAccount account = wallet();

            account.apply(PostingDirection.CREDIT, 500_00L);
            account.apply(PostingDirection.DEBIT, 120_00L);
            account.apply(PostingDirection.CREDIT, 20_00L);

            assertThat(account.getCreditTotalMinor()).isEqualTo(520_00L);
            assertThat(account.getDebitTotalMinor()).isEqualTo(120_00L);
            assertThat(account.getBalanceMinor())
                    .isEqualTo(account.getCreditTotalMinor() - account.getDebitTotalMinor());
        }

        @Test
        @DisplayName("the balance is always debits minus credits for an asset")
        void balanceIsDerivedForAnAsset() {
            LedgerAccount account = systemAccount("asset:external-funding:INR",
                    AccountType.ASSET, true);

            account.apply(PostingDirection.DEBIT, 500_00L);
            account.apply(PostingDirection.CREDIT, 120_00L);

            assertThat(account.getBalanceMinor())
                    .isEqualTo(account.getDebitTotalMinor() - account.getCreditTotalMinor())
                    .isEqualTo(380_00L);
        }

        @Test
        @DisplayName("totals only ever grow")
        void totalsOnlyGrow() {
            LedgerAccount account = wallet();
            account.apply(PostingDirection.CREDIT, 100_00L);
            account.apply(PostingDirection.DEBIT, 100_00L);

            assertThat(account.getBalanceMinor()).isZero();
            assertThat(account.getCreditTotalMinor())
                    .as("a movement that nets to zero must still be visible in the totals")
                    .isEqualTo(100_00L);
            assertThat(account.getDebitTotalMinor()).isEqualTo(100_00L);
        }

        /**
         * The entity deliberately does <em>not</em> reject a negative result. Whether an
         * account may go negative differs per account, and the rule is enforced by
         * {@code ck_ledger_accounts_negative_allowed} at commit so it covers every write path.
         * Duplicating it here would be a second copy that could drift.
         */
        @Test
        @DisplayName("a negative result is left to the database, not clamped or refused here")
        void negativeIsLeftToTheDatabase() {
            LedgerAccount account = wallet();

            long after = account.apply(PostingDirection.DEBIT, 100_00L);

            assertThat(after)
                    .as("the entity records what it was told; the CHECK constraint refuses it")
                    .isEqualTo(-100_00L);
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("refuses a non-positive amount, because direction carries the sign")
        void refusesNonPositiveAmount() {
            LedgerAccount account = wallet();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> account.apply(PostingDirection.CREDIT, 0L))
                    .withMessageContaining("must be positive");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> account.apply(PostingDirection.CREDIT, -1L));
        }

        /**
         * A closed account with further movements would make its final balance a lie, and the
         * refusal is a {@link ConflictException} so it surfaces as a 409 rather than an opaque
         * 500.
         */
        @Test
        @DisplayName("a closed account accepts no further postings")
        void closedAccountRejectsPostings() {
            LedgerAccount account = wallet();
            account.close();

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> account.apply(PostingDirection.CREDIT, 100L))
                    .withMessageContaining("CLOSED");
        }

        @Test
        @DisplayName("refuses to close an account that still holds value")
        void refusesToCloseNonEmptyAccount() {
            LedgerAccount account = wallet();
            account.apply(PostingDirection.CREDIT, 100_00L);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(account::close)
                    .withMessageContaining("cannot be closed");

            // Closing it once the value is gone is fine.
            account.apply(PostingDirection.DEBIT, 100_00L);
            account.close();
            assertThat(account.getStatus()).isEqualTo(LedgerAccount.Status.CLOSED);
        }

        /**
         * Overflow must throw rather than wrap. A wrapped-around long would flip a huge
         * balance negative, and the CHECK constraint would then reject it far from the code
         * that caused it.
         */
        @Test
        @DisplayName("overflow throws instead of wrapping around")
        void overflowThrows() {
            LedgerAccount account = wallet();
            account.apply(PostingDirection.CREDIT, Long.MAX_VALUE);

            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> account.apply(PostingDirection.CREDIT, 1L));
        }

        @Test
        @DisplayName("a new wallet account starts at zero and may not go negative")
        void newWalletAccountDefaults() {
            LedgerAccount account = wallet();

            assertThat(account.getBalanceMinor()).isZero();
            assertThat(account.getDebitTotalMinor()).isZero();
            assertThat(account.getCreditTotalMinor()).isZero();
            assertThat(account.getAccountType()).isEqualTo(AccountType.LIABILITY);
            assertThat(account.isAllowsNegative())
                    .as("the platform cannot owe a customer a negative amount")
                    .isFalse();
            assertThat(account.isSystemAccount()).isFalse();
            assertThat(account.isActive()).isTrue();
            assertThat(account.getCurrency()).isEqualTo(INR);
        }

        @Test
        @DisplayName("the currency is normalised to upper case")
        void currencyIsNormalised() {
            assertThat(LedgerAccount.forWallet(WALLET, "inr").getCurrency()).isEqualTo("INR");
        }
    }
}
