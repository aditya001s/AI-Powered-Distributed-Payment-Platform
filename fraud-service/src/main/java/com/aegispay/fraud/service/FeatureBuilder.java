package com.aegispay.fraud.service;

import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import com.aegispay.fraud.client.MlScoringClient;
import com.aegispay.fraud.config.FraudProperties;
import com.aegispay.fraud.domain.PaymentObservation;
import com.aegispay.fraud.repository.PaymentObservationRepository;
import com.aegispay.fraud.rules.FraudContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Turns a payment plus this service's own history into the inputs both the rules and the model read.
 *
 * <p><strong>One context, two consumers.</strong> The rule engine and the ml-service request are built
 * from the same {@link FraudContext}. If they saw different inputs, a rule could fire on evidence the
 * model never had, and nobody reading the assessment afterwards could explain why the two disagreed.
 *
 * <p><strong>Every window excludes the payment being scored.</strong> The saga calls this service
 * milliseconds after {@code payment-created} is published, so the observation for the current payment
 * may or may not have been consumed yet. Excluding it by id makes the score independent of that race —
 * and the feature contract ml-service was trained against defines these windows as prior to the
 * payment, so including it would put every request slightly outside the trained distribution with no
 * error anywhere.
 *
 * <p>Read-only and transactional: several aggregate queries in one consistent snapshot, so a payment
 * arriving mid-assessment cannot make the velocity count disagree with the amount total.
 */
@Service
public class FeatureBuilder {

    private static final Logger log = LoggerFactory.getLogger(FeatureBuilder.class);

    /** Window for the "distinct devices / countries" features. */
    private static final Duration IDENTITY_WINDOW = Duration.ofDays(30);

    /** Mean radius of the Earth, for the haversine distance. */
    private static final double EARTH_RADIUS_KM = 6_371.0088;

    private final PaymentObservationRepository observations;
    private final FraudProperties properties;

    public FeatureBuilder(PaymentObservationRepository observations, FraudProperties properties) {
        this.observations = observations;
        this.properties = properties;
    }

    /**
     * Assembles the scoring context.
     *
     * @param paymentId the payment being scored, excluded from every aggregate
     */
    @Transactional(readOnly = true)
    public FraudContext build(String paymentId,
                              String transactionId,
                              String initiatorUserId,
                              String destinationWalletId,
                              Money amount,
                              com.aegispay.common.domain.SharedEnums.PaymentType paymentType,
                              RiskSignals signals,
                              Instant occurredAt) {

        RiskSignals effectiveSignals = signals == null ? RiskSignals.unknown() : signals;
        Instant now = occurredAt == null ? Instant.now() : occurredAt;

        Duration velocityWindow = properties.getRules().getVelocityWindow();
        Instant since24h = now.minus(velocityWindow);
        Instant since1h = now.minus(Duration.ofHours(1));
        Instant since30d = now.minus(IDENTITY_WINDOW);

        long attempts1h = observations.countAttemptsSince(initiatorUserId, paymentId, since1h);
        long attempts24h = observations.countAttemptsSince(initiatorUserId, paymentId, since24h);
        long failures24h = observations.countFailuresSince(initiatorUserId, paymentId, since24h);
        long sum24h = observations.sumAmountSince(initiatorUserId, paymentId, since24h);

        History history = summariseHistory(initiatorUserId, paymentId, amount.currency());

        Optional<Duration> sinceLast = observations
                .findRecentAttemptTimes(initiatorUserId, paymentId, Limit.of(1))
                .stream()
                .findFirst()
                .map(previous -> Duration.between(previous, now))
                // A negative interval means the previous observation is timestamped after this
                // payment, which happens when two events are consumed out of order. Discarded
                // rather than passed on: a negative duration would produce a nonsensical implied
                // travel speed and could fire the impossible-travel rule on a clock skew.
                .filter(elapsed -> !elapsed.isNegative());

        Optional<Long> accountAgeDays = observations.firstSeenAt(initiatorUserId)
                .filter(firstSeen -> !firstSeen.isAfter(now))
                .map(firstSeen -> ChronoUnit.DAYS.between(firstSeen, now));

        boolean newDevice = isNewDevice(initiatorUserId, paymentId, effectiveSignals);
        boolean newCountry = isNewCountry(initiatorUserId, paymentId, effectiveSignals);
        boolean newPayee = observations.countPriorPaymentsToPayee(
                initiatorUserId, paymentId, destinationWalletId) == 0;

        return new FraudContext(
                paymentId,
                transactionId,
                initiatorUserId,
                destinationWalletId,
                amount,
                paymentType,
                effectiveSignals,
                now,
                accountAgeDays,
                history.completedCount(),
                observations.countConfirmedFraud(initiatorUserId, paymentId),
                history.averageAmount(),
                history.largestAmount(),
                attempts1h,
                attempts24h,
                Money.ofMinor(sum24h, amount.currency()),
                failures24h,
                sinceLast,
                newDevice,
                observations.countDistinctDevicesSince(initiatorUserId, paymentId, since30d),
                newCountry,
                observations.countDistinctCountriesSince(initiatorUserId, paymentId, since30d),
                distanceFromLast(initiatorUserId, paymentId, effectiveSignals),
                newPayee);
    }

    /**
     * Builds the ml-service request from the context.
     *
     * <p>Kept here rather than in the client so that the mapping between the context and the wire
     * contract sits next to the code that produced the context. Absent values are passed as
     * {@code null}, never defaulted: ml-service treats absence as its own signal, and a zero would
     * assert something false.
     */
    public MlScoringClient.ScoreRequest toScoreRequest(FraudContext context) {
        RiskSignals signals = context.signals();

        // Hour and day come from when the CUSTOMER paid, not from when this runs. The saga calls
        // seconds later on a good day and minutes later after a recovery sweep, so deriving them here
        // would score a 03:00 payment as a 03:07 one — or, after a redelivery, as a daytime payment
        // altogether. Time-of-day is a real fraud feature, so that drift would be silent and wrong.
        //
        // UTC, consistently, because the model was trained on UTC. Local time would arguably be the
        // better feature — 3am is suspicious where 3am UTC is lunchtime somewhere — but that needs a
        // timezone this platform does not capture, and inventing one would be worse than a consistent
        // offset the model has learned around.
        var utc = context.occurredAt().atZone(ZoneOffset.UTC);

        return new MlScoringClient.ScoreRequest(
                context.paymentId(),
                context.transactionId(),
                context.amount().amountMinor(),
                context.amount().currency(),
                context.paymentType() == null ? null : context.paymentType().name(),
                utc.getHour(),
                utc.getDayOfWeek().getValue(),
                context.accountAgeDays().orElse(null),
                context.priorPaymentCount(),
                context.confirmedFraudCount(),
                context.averageAmount().map(Money::amountMinor).orElse(null),
                context.largestAmount().map(Money::amountMinor).orElse(null),
                context.attempts1h(),
                context.attempts24h(),
                context.amountSum24h().amountMinor(),
                context.failures24h(),
                context.sinceLastPayment().map(Duration::toSeconds).orElse(null),
                // Only claim a device is new when there was a device at all: "no device" and
                // "unrecognised device" are different facts, and the model has a trained path
                // for the first.
                signals.hasDevice() ? context.newDevice() : null,
                context.distinctDevices30d(),
                signals.countryCode() == null ? null : context.newCountry(),
                context.distinctCountries30d(),
                signals.countryCode(),
                context.distanceFromLastKm().orElse(null),
                signals.hasGeoLocation(),
                context.newPayee(),
                signals.merchantCategory());
    }

    // --------------------------------------------------------------- internals

    private record History(long completedCount, Optional<Money> averageAmount,
                           Optional<Money> largestAmount) {
    }

    /**
     * The account's own norms, from completed payments only.
     *
     * <p>Completed rather than attempted, deliberately. If a card-testing burst raised the account's
     * baseline, the next fraudulent payment would look proportionate against it — the attacker would
     * be setting their own threshold.
     */
    private History summariseHistory(String userId, String paymentId, String currency) {
        List<Object[]> rows = observations.summariseCompletedHistory(userId, paymentId);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return new History(0L, Optional.empty(), Optional.empty());
        }
        Object[] row = rows.getFirst();
        long count = row[0] == null ? 0L : ((Number) row[0]).longValue();
        if (count == 0L) {
            // No completed history. Left empty rather than zeroed: "no average" and "an average of
            // zero" are different, and a rule comparing against zero would divide by it.
            return new History(0L, Optional.empty(), Optional.empty());
        }
        Optional<Money> average = row[1] == null
                ? Optional.empty()
                : Optional.of(Money.ofMinor(Math.round(((Number) row[1]).doubleValue()), currency));
        Optional<Money> largest = row[2] == null
                ? Optional.empty()
                : Optional.of(Money.ofMinor(((Number) row[2]).longValue(), currency));

        return new History(count, average, largest);
    }

    /**
     * Whether this account has used this device before.
     *
     * <p>Returns {@code false} when there is no device at all. "No device supplied" is not "an
     * unfamiliar device", and treating it as one would flag every API-channel payment on the platform.
     */
    private boolean isNewDevice(String userId, String paymentId, RiskSignals signals) {
        if (!signals.hasDevice()) {
            return false;
        }
        return observations.countPriorUsesOfDevice(
                userId, paymentId, signals.deviceFingerprint(), signals.deviceId()) == 0;
    }

    private boolean isNewCountry(String userId, String paymentId, RiskSignals signals) {
        if (signals.countryCode() == null || signals.countryCode().isBlank()) {
            return false;
        }
        return observations.countPriorPaymentsFromCountry(
                userId, paymentId, signals.countryCode()) == 0;
    }

    /**
     * Great-circle distance from the most recent prior payment that had coordinates.
     *
     * <p>"Most recent <em>with</em> a location" rather than "most recent": if the last three payments
     * came through a channel that supplies none, the distance from the last <em>known</em> location is
     * still the meaningful comparison. Reporting nothing would silently disable the geo features for
     * every customer who uses a mix of channels.
     */
    private Optional<Double> distanceFromLast(String userId, String paymentId, RiskSignals signals) {
        if (!signals.hasGeoLocation()) {
            return Optional.empty();
        }
        return observations.findRecentWithLocation(userId, paymentId, Limit.of(1)).stream()
                .findFirst()
                .map(previous -> haversineKm(
                        signals.latitude(), signals.longitude(),
                        previous.getLatitude(), previous.getLongitude()));
    }

    /**
     * Great-circle distance in kilometres.
     *
     * <p>Haversine rather than Euclidean on latitude and longitude, because a degree of longitude is
     * 111km at the equator and 43km in northern Europe. A flat-earth approximation would under-report
     * east-west travel by more than half at high latitudes, and the impossible-travel rule divides by
     * this number.
     */
    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
