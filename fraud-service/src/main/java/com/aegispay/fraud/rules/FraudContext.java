package com.aegispay.fraud.rules;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;

import java.time.Duration;
import java.util.Optional;

/**
 * Everything known about one payment at scoring time.
 *
 * <p>Assembled once by the feature builder and then read by both the rule engine and the ml-service
 * request. One structure rather than two, deliberately: if the rules and the model saw different
 * inputs, a rule could fire on evidence the model never had, and nobody reading the assessment could
 * work out why the two disagreed.
 *
 * <p><strong>Every window figure is prior to this payment and excludes it.</strong> So
 * {@code attempts24h} of zero means "this is the first attempt today", and {@code amountSum24h} may
 * legitimately be smaller than {@code amount}.
 *
 * <p>Optional rather than nullable for the history figures, because "this account has no average
 * amount yet" is a genuinely different state from "its average is zero", and a rule that treated
 * them alike would fire on every brand-new account.
 */
public record FraudContext(

        String paymentId,
        String transactionId,
        String initiatorUserId,
        String destinationWalletId,

        Money amount,
        PaymentType paymentType,

        /** Context captured at the edge. Never re-derived: the model must score what the user did. */
        RiskSignals signals,

        /**
         * When the customer made the payment.
         *
         * <p>Every window below is measured back from this instant, and the time-of-day features are
         * derived from it. Deliberately <em>not</em> the assessment time: the saga can call this
         * service seconds after the payment on a good day and minutes later after a recovery, and a
         * payment made at 03:00 must not be scored as a 03:07 payment — still less as a daytime one
         * because a redelivery arrived hours later.
         */
        java.time.Instant occurredAt,

        // ---- account standing ----

        /** Days since this account was first seen, counted from its first attempt. */
        Optional<Long> accountAgeDays,
        long priorPaymentCount,
        /** Confirmed fraud on this account: the platform's stand-in for chargebacks. */
        long confirmedFraudCount,

        // ---- how this account normally behaves ----

        /** Empty for an account with no completed payments. Must not become zero. */
        Optional<Money> averageAmount,
        Optional<Money> largestAmount,

        // ---- velocity, prior to this payment ----

        long attempts1h,
        long attempts24h,
        Money amountSum24h,
        long failures24h,
        Optional<Duration> sinceLastPayment,

        // ---- device and location ----

        boolean newDevice,
        long distinctDevices30d,
        boolean newCountry,
        long distinctCountries30d,
        /** Empty when either this payment or the last known one had no coordinates. */
        Optional<Double> distanceFromLastKm,

        // ---- counterparty ----

        boolean newPayee
) {

    public FraudContext {
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("a fraud context must name its payment");
        }
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException(
                    "cannot assess payment " + paymentId + " with a non-positive amount " + amount);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * This amount as a multiple of the account's own mean.
     *
     * <p>The single most useful comparison available: it turns "is this a big payment" into "is this
     * big <em>for this customer</em>", which is what separates fraud from a wealthy customer. Empty
     * when there is no history to compare against.
     */
    public Optional<Double> amountToAverageRatio() {
        return averageAmount
                .filter(Money::isPositive)
                .map(average -> (double) amount.amountMinor() / average.amountMinor());
    }

    /** This amount as a multiple of the largest the account has ever sent. */
    public Optional<Double> amountToLargestRatio() {
        return largestAmount
                .filter(Money::isPositive)
                .map(largest -> (double) amount.amountMinor() / largest.amountMinor());
    }

    /** Whether this payment exceeds anything the account has sent before. */
    public boolean exceedsHistoricMaximum() {
        return largestAmount
                .map(largest -> amount.amountMinor() > largest.amountMinor())
                .orElse(false);
    }

    /**
     * Implied ground speed between the last known location and this one, in km/h.
     *
     * <p>Empty unless both a distance and an elapsed time are available. The elapsed time is floored
     * at one second: a zero would divide to infinity, and two payments in the same second from
     * different continents is a data problem rather than a supersonic customer.
     */
    public Optional<Double> impliedSpeedKmh() {
        return distanceFromLastKm.flatMap(distance -> sinceLastPayment
                .filter(elapsed -> !elapsed.isNegative())
                .map(elapsed -> {
                    double hours = Math.max(1.0, elapsed.toSeconds()) / 3600.0;
                    return distance / hours;
                }));
    }

    /** Whether the payment came from a country the account has never transacted from. */
    public boolean fromUnfamiliarCountry() {
        return newCountry && signals != null && signals.countryCode() != null;
    }

    /** Whether this account has enough history for a "departure from normal" rule to mean anything. */
    public boolean hasEstablishedHistory(long minimumPayments) {
        return priorPaymentCount >= minimumPayments && averageAmount.isPresent();
    }

    /** Whether the account has been quiet for at least {@code days}. */
    public boolean dormantFor(long days) {
        return sinceLastPayment
                .map(elapsed -> elapsed.toDays() >= days)
                .orElse(false);
    }

    public String countryCode() {
        return signals == null ? null : signals.countryCode();
    }

    public String merchantCategory() {
        return signals == null ? null : signals.merchantCategory();
    }

    public boolean isWithdrawal() {
        return paymentType == PaymentType.WALLET_WITHDRAWAL;
    }
}
