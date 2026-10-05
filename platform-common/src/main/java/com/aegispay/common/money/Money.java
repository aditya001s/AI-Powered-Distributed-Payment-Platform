package com.aegispay.common.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact monetary amount.
 *
 * <p><strong>Why minor units.</strong> {@code double} and {@code float} cannot
 * represent 0.01 exactly, so accumulating them silently loses money. This type
 * stores the amount as a {@code long} count of the currency's <em>minor
 * unit</em> (paise for INR, cents for USD) and never performs binary
 * floating-point arithmetic. Every persisted column backing a {@code Money} is
 * a {@code BIGINT}, and every ledger invariant is checked in {@code long}
 * arithmetic where equality is exact.
 *
 * <p>Range: a {@code long} of paise covers roughly &plusmn;9.2 &times; 10^16,
 * which is about &plusmn;92 quadrillion INR. Overflow is still checked
 * explicitly via {@link Math#addExact} so a bug surfaces as an exception rather
 * than as a wrapped-around balance.
 *
 * <p>Instances are immutable and therefore safe to share across threads.
 */
public record Money(long amountMinor, String currency) implements Comparable<Money>, Serializable {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        currency = currency.trim().toUpperCase();
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code, got: " + currency);
        }
    }

    /** Creates an amount from its minor-unit representation (e.g. 200000 paise = 2000.00 INR). */
    @JsonCreator
    public static Money ofMinor(@JsonProperty("amountMinor") long amountMinor,
                                @JsonProperty("currency") String currency) {
        return new Money(amountMinor, currency);
    }

    /**
     * Creates an amount from a decimal major-unit value.
     *
     * <p>Rejects values with more precision than the currency supports instead
     * of silently rounding: {@code 10.005 INR} is a caller bug, not something
     * this type should guess at.
     */
    public static Money of(BigDecimal major, String currency) {
        Objects.requireNonNull(major, "amount must not be null");
        int fractionDigits = fractionDigits(currency);
        BigDecimal scaled;
        try {
            scaled = major.setScale(fractionDigits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "amount " + major.toPlainString() + " has more precision than " + currency
                            + " supports (" + fractionDigits + " fraction digits)");
        }
        return new Money(scaled.movePointRight(fractionDigits).longValueExact(), currency);
    }

    public static Money of(String major, String currency) {
        return of(new BigDecimal(major), currency);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    /** The amount as a decimal in major units, scaled to the currency's precision. */
    @JsonProperty("amount")
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(amountMinor, fractionDigits(currency));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public Money abs() {
        return amountMinor < 0 ? negated() : this;
    }

    /**
     * Applies a rate expressed in basis points (1 bp = 0.01%), rounding half-up
     * to the nearest minor unit. Used for provider fee calculation.
     */
    public Money applyBasisPoints(int basisPoints) {
        if (basisPoints < 0) {
            throw new IllegalArgumentException("basisPoints must not be negative: " + basisPoints);
        }
        BigDecimal fee = BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.HALF_UP);
        return new Money(fee.longValueExact(), currency);
    }

    @JsonIgnore
    public boolean isZero() {
        return amountMinor == 0L;
    }

    @JsonIgnore
    public boolean isPositive() {
        return amountMinor > 0L;
    }

    @JsonIgnore
    public boolean isNegative() {
        return amountMinor < 0L;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    /**
     * Rejects cross-currency arithmetic. Implicit FX conversion is a classic
     * source of silent financial corruption, so it is a hard error here;
     * conversion must be an explicit, rate-stamped operation.
     */
    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency + " with " + other.currency
                            + "; FX conversion must be explicit");
        }
    }

    private static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits();
            // -1 is returned for pseudo-currencies such as XXX; treat as minor-unit-less.
            return Math.max(digits, 0);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown ISO-4217 currency: " + currencyCode, ex);
        }
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency;
    }
}
