package com.aegispay.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link Money}.
 *
 * <p>These target the properties that make the type <em>safe</em> rather than the
 * accessors: exactness, refusal to silently round, refusal to mix currencies, and
 * overflow detection. Those are the behaviours a money bug would violate.
 */
class MoneyTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("converts a decimal amount into minor units")
        void convertsDecimalToMinorUnits() {
            assertThat(Money.of(new BigDecimal("2000.00"), "INR").amountMinor()).isEqualTo(200_000L);
            assertThat(Money.of(new BigDecimal("0.01"), "INR").amountMinor()).isEqualTo(1L);
            assertThat(Money.of(new BigDecimal("1"), "INR").amountMinor()).isEqualTo(100L);
        }

        @Test
        @DisplayName("round-trips a decimal without loss")
        void roundTripsExactly() {
            Money amount = Money.of("1234.56", "INR");
            assertThat(amount.toDecimal()).isEqualByComparingTo("1234.56");
            assertThat(amount.toDecimal().toPlainString()).isEqualTo("1234.56");
        }

        /**
         * The behaviour that protects the books. Accepting 10.005 and rounding it
         * would mean the amount stored differs from the amount the caller asked for,
         * and nobody would find out until a reconciliation broke.
         */
        @Test
        @DisplayName("rejects more precision than the currency has, instead of rounding")
        void rejectsExcessPrecision() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of(new BigDecimal("10.005"), "INR"))
                    .withMessageContaining("more precision than INR supports");
        }

        @Test
        @DisplayName("normalises the currency code and rejects malformed ones")
        void validatesCurrency() {
            assertThat(Money.ofMinor(100L, "inr").currency()).isEqualTo("INR");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.ofMinor(100L, "RUPEES"))
                    .withMessageContaining("3-letter ISO-4217");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of(BigDecimal.ONE, "ZZZ"))
                    .withMessageContaining("unknown ISO-4217 currency");
        }

        @Test
        @DisplayName("honours currencies with no minor unit")
        void handlesZeroDecimalCurrency() {
            // JPY has 0 fraction digits, so one minor unit is one yen. Assuming
            // "two decimal places" everywhere is a classic internationalisation bug.
            Money yen = Money.of(new BigDecimal("500"), "JPY");
            assertThat(yen.amountMinor()).isEqualTo(500L);
            assertThat(yen.toDecimal()).isEqualByComparingTo("500");
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        /**
         * The reason this type exists. In binary floating point
         * 0.1 + 0.2 == 0.30000000000000004; summing a million such values drifts far
         * enough to fail an audit.
         */
        @Test
        @DisplayName("adds exactly where floating point would drift")
        void addsExactlyUnlikeDoubles() {
            Money total = Money.zero("INR");
            for (int i = 0; i < 1_000_000; i++) {
                total = total.plus(Money.of("0.01", "INR"));
            }
            assertThat(total.toDecimal()).isEqualByComparingTo("10000.00");
            assertThat(total.amountMinor()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("subtracts and negates")
        void subtractsAndNegates() {
            Money balance = Money.of("2000.00", "INR");
            assertThat(balance.minus(Money.of("500.50", "INR")).toDecimal())
                    .isEqualByComparingTo("1499.50");
            assertThat(balance.negated().amountMinor()).isEqualTo(-200_000L);
            assertThat(balance.negated().abs()).isEqualTo(balance);
        }

        /**
         * Implicit FX conversion is a silent-corruption bug: ₹2,000 + $2,000 has no
         * meaningful answer without a rate, so it must be an error, not a guess.
         */
        @Test
        @DisplayName("refuses to combine different currencies")
        void refusesCrossCurrencyArithmetic() {
            Money rupees = Money.of("100.00", "INR");
            Money dollars = Money.of("100.00", "USD");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rupees.plus(dollars))
                    .withMessageContaining("FX conversion must be explicit");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> rupees.compareTo(dollars));
        }

        @Test
        @DisplayName("detects overflow instead of wrapping around")
        void detectsOverflow() {
            Money huge = Money.ofMinor(Long.MAX_VALUE, "INR");
            // Math.addExact throws rather than silently producing a negative balance.
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> huge.plus(Money.ofMinor(1L, "INR")));
        }

        @ParameterizedTest
        @DisplayName("applies a basis-point fee, rounding half up to the minor unit")
        @CsvSource({
                // amount,        bps,  expected fee
                "'2000.00',       25,   '5.00'",
                "'2000.00',       35,   '7.00'",
                "'100.00',        18,   '0.18'",
                "'1.00',          1,    '0.00'",
                "'1.50',          100,  '0.02'",
                "'0.00',          25,   '0.00'"
        })
        void appliesBasisPoints(String amount, int basisPoints, String expectedFee) {
            assertThat(Money.of(amount, "INR").applyBasisPoints(basisPoints).toDecimal())
                    .isEqualByComparingTo(expectedFee);
        }

        @Test
        @DisplayName("rejects a negative fee rate")
        void rejectsNegativeBasisPoints() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Money.of("100.00", "INR").applyBasisPoints(-1));
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("orders and classifies amounts")
        void ordersAmounts() {
            Money small = Money.of("10.00", "INR");
            Money large = Money.of("20.00", "INR");

            assertThat(large.isGreaterThan(small)).isTrue();
            assertThat(small.isLessThan(large)).isTrue();
            assertThat(small.isGreaterThanOrEqualTo(small)).isTrue();
            assertThat(Money.zero("INR").isZero()).isTrue();
            assertThat(small.isPositive()).isTrue();
            assertThat(small.negated().isNegative()).isTrue();
        }

        /**
         * The balance check in wallet-service is exactly this comparison, so the
         * boundary case (spending the entire balance) must be allowed.
         */
        @Test
        @DisplayName("permits spending the exact available balance")
        void allowsExactBalanceSpend() {
            Money available = Money.of("2000.00", "INR");
            Money requested = Money.of("2000.00", "INR");
            assertThat(available.isGreaterThanOrEqualTo(requested)).isTrue();
            assertThat(available.minus(requested).isZero()).isTrue();
        }

        @Test
        @DisplayName("is a value type: equal amounts are equal")
        void isValueType() {
            assertThat(Money.of("50.00", "INR")).isEqualTo(Money.ofMinor(5000L, "INR"));
            assertThat(Money.of("50.00", "INR")).hasSameHashCodeAs(Money.ofMinor(5000L, "INR"));
            assertThat(Money.of("50.00", "INR")).isNotEqualTo(Money.of("50.00", "USD"));
        }

        @Test
        @DisplayName("renders amount and currency in toString")
        void rendersToString() {
            assertThat(Money.of("2000.00", "INR")).hasToString("2000.00 INR");
        }
    }
}
