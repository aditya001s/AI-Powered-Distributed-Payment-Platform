package com.aegispay.fraud.service;

import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import com.aegispay.fraud.client.MlScoringClient;
import com.aegispay.fraud.config.FraudProperties;
import com.aegispay.fraud.domain.PaymentObservation;
import com.aegispay.fraud.repository.PaymentObservationRepository;
import com.aegispay.fraud.rules.FraudContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the feature pipeline.
 *
 * <p>Three properties here are contracts rather than implementation details, and each would break
 * silently:
 *
 * <ol>
 *   <li><strong>Every window excludes the payment being scored.</strong> The saga calls milliseconds
 *       after {@code payment-created} is published, so whether the current payment's own observation
 *       has been consumed is a race. Including it would make the same payment score differently
 *       depending on which consumer won.</li>
 *   <li><strong>Absent means null, never zero.</strong> ml-service is trained to treat absence as its
 *       own signal. A zero average would tell the model this account's usual payment is nothing,
 *       which is a different and false claim.</li>
 *   <li><strong>Time-of-day comes from when the customer paid.</strong> Deriving it from the
 *       assessment time would score a 03:00 payment as a daytime one after any redelivery.</li>
 * </ol>
 */
class FeatureBuilderTest {

    private static final String USER = "USR-PAYER";
    private static final String PAYMENT = "PAY-SCORED";
    private static final String PAYEE_WALLET = "WLT-PAYEE";
    private static final Instant OCCURRED = Instant.parse("2026-09-01T03:17:00Z");

    private PaymentObservationRepository observations;
    private FeatureBuilder builder;

    @BeforeEach
    void setUp() {
        observations = mock(PaymentObservationRepository.class);
        builder = new FeatureBuilder(observations, new FraudProperties());

        // A quiet account by default, so each test only arranges the facts it is about.
        when(observations.countAttemptsSince(anyString(), anyString(), any())).thenReturn(0L);
        when(observations.countFailuresSince(anyString(), anyString(), any())).thenReturn(0L);
        when(observations.sumAmountSince(anyString(), anyString(), any())).thenReturn(0L);
        when(observations.summariseCompletedHistory(anyString(), anyString()))
                .thenReturn(List.of());
        when(observations.firstSeenAt(anyString())).thenReturn(Optional.empty());
        when(observations.countConfirmedFraud(anyString(), anyString())).thenReturn(0L);
        when(observations.countPriorUsesOfDevice(anyString(), anyString(), any(), any()))
                .thenReturn(0L);
        when(observations.countDistinctDevicesSince(anyString(), anyString(), any())).thenReturn(0L);
        when(observations.countPriorPaymentsFromCountry(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(observations.countDistinctCountriesSince(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(observations.findRecentWithLocation(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(observations.findRecentAttemptTimes(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(observations.countPriorPaymentsToPayee(anyString(), anyString(), anyString()))
                .thenReturn(0L);
    }

    // ------------------------------------------------------------- exclusion

    @Nested
    @DisplayName("excluding the payment being scored")
    class Exclusion {

        /**
         * <strong>The property the whole pipeline rests on.</strong> If the scored payment were
         * included, its own observation arriving a few milliseconds earlier or later would change the
         * score — and the feature contract ml-service was trained against defines these windows as
         * prior to the payment, so including it would put every request slightly outside the trained
         * distribution with no error anywhere.
         */
        @Test
        @DisplayName("passes the payment id as the exclusion to every aggregate")
        void everyAggregateExcludesTheScoredPayment() {
            build();

            verify(observations, org.mockito.Mockito.atLeastOnce())
                    .countAttemptsSince(eq(USER), eq(PAYMENT), any());
            verify(observations).countFailuresSince(eq(USER), eq(PAYMENT), any());
            verify(observations).sumAmountSince(eq(USER), eq(PAYMENT), any());
            verify(observations).summariseCompletedHistory(USER, PAYMENT);
            verify(observations).countConfirmedFraud(USER, PAYMENT);
            verify(observations).countDistinctDevicesSince(eq(USER), eq(PAYMENT), any());
            verify(observations).countDistinctCountriesSince(eq(USER), eq(PAYMENT), any());
            verify(observations).findRecentAttemptTimes(eq(USER), eq(PAYMENT), any());
            verify(observations).countPriorPaymentsToPayee(USER, PAYMENT, PAYEE_WALLET);
        }

        /**
         * Account age counts from the first <em>attempt</em>, and deliberately does not exclude the
         * scored payment: an account whose only payment is this one is a day-zero account, and
         * excluding it would report no age at all rather than zero days.
         */
        @Test
        @DisplayName("counts account age from the first attempt, including this one")
        void accountAgeIncludesThisPayment() {
            verifyNoExclusionOnFirstSeen();
        }

        private void verifyNoExclusionOnFirstSeen() {
            build();
            verify(observations).firstSeenAt(USER);
        }

        /**
         * Windows are measured back from when the customer paid, not from when this runs. After a
         * recovery sweep those differ by minutes or more.
         */
        @Test
        @DisplayName("anchors the windows to when the customer paid")
        void windowsAnchorToOccurredAt() {
            build();

            ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
            verify(observations, org.mockito.Mockito.atLeastOnce())
                    .countAttemptsSince(eq(USER), eq(PAYMENT), since.capture());

            assertThat(since.getAllValues())
                    .as("every window must start before the payment and near it")
                    .allSatisfy(start -> assertThat(start).isBefore(OCCURRED));
            assertThat(since.getAllValues())
                    .anySatisfy(start -> assertThat(start)
                            .isEqualTo(OCCURRED.minus(Duration.ofHours(1))));
        }
    }

    // ------------------------------------------------------- absence semantics

    @Nested
    @DisplayName("absent values")
    class Absence {

        /**
         * A brand-new account has no average amount. Reporting zero would tell the model its usual
         * payment is nothing, and every rule dividing by it would produce a meaningless ratio.
         */
        @Test
        @DisplayName("leaves the account norms empty when there is no completed history")
        void noHistoryMeansEmpty() {
            FraudContext context = build();

            assertThat(context.averageAmount()).isEmpty();
            assertThat(context.largestAmount()).isEmpty();
            assertThat(context.amountToAverageRatio()).isEmpty();
            assertThat(context.priorPaymentCount()).isZero();
        }

        @Test
        @DisplayName("passes absent norms to ml-service as null, never zero")
        void nullsOnTheWire() {
            MlScoringClient.ScoreRequest request = builder.toScoreRequest(build());

            assertThat(request.avgAmountMinor())
                    .as("zero would assert that this account's usual payment is nothing")
                    .isNull();
            assertThat(request.maxAmountMinor()).isNull();
            assertThat(request.accountAgeDays()).isNull();
            assertThat(request.secondsSinceLastPayment()).isNull();
            assertThat(request.distanceFromLastKm()).isNull();
        }

        /**
         * "No device supplied" is not "an unfamiliar device". Treating them alike would flag every
         * API-channel payment on the platform.
         */
        @Test
        @DisplayName("does not claim a new device when no device was supplied")
        void noDeviceIsNotANewDevice() {
            FraudContext context = build(signalsWithout());
            assertThat(context.newDevice()).isFalse();

            MlScoringClient.ScoreRequest request = builder.toScoreRequest(context);
            assertThat(request.isNewDevice())
                    .as("null says 'no device'; false would say 'a device we recognise'")
                    .isNull();
        }

        @Test
        @DisplayName("reports a genuinely unrecognised device as new")
        void unrecognisedDeviceIsNew() {
            FraudContext context = build(signals("fp-unseen", "IN", null, null));

            assertThat(context.newDevice()).isTrue();
            assertThat(builder.toScoreRequest(context).isNewDevice()).isTrue();
        }

        /** Absent geo must not become a distance of zero, which would read as "same place". */
        @Test
        @DisplayName("reports no distance when there is no location on either end")
        void noGeoMeansNoDistance() {
            FraudContext context = build(signalsWithout());

            assertThat(context.distanceFromLastKm()).isEmpty();
            assertThat(builder.toScoreRequest(context).hasGeo()).isFalse();
        }

        /**
         * A negative interval means the previous observation is timestamped after this payment, which
         * happens when events are consumed out of order. Passing it on would produce a nonsensical
         * implied speed and could fire the impossible-travel rule on clock skew.
         */
        @Test
        @DisplayName("discards a negative interval rather than passing on clock skew")
        void discardsNegativeInterval() {
            when(observations.findRecentAttemptTimes(anyString(), anyString(), any()))
                    .thenReturn(List.of(OCCURRED.plus(Duration.ofMinutes(5))));

            FraudContext context = build();

            assertThat(context.sinceLastPayment())
                    .as("a payment cannot follow one that has not happened yet")
                    .isEmpty();
            assertThat(context.impliedSpeedKmh()).isEmpty();
        }
    }

    // ------------------------------------------------------------- haversine

    @Nested
    @DisplayName("the distance calculation")
    class Haversine {

        /**
         * Haversine rather than Euclidean on latitude and longitude, because a degree of longitude is
         * 111km at the equator and 43km in northern Europe. A flat approximation would under-report
         * east-west travel by more than half at high latitudes — and the impossible-travel rule
         * divides by this number, so the error would land directly on a decision.
         */
        @Test
        @DisplayName("measures a known great-circle distance")
        void knownDistance() {
            // Delhi (28.6139, 77.2090) to Mumbai (19.0760, 72.8777): about 1,150km.
            double km = FeatureBuilder.haversineKm(28.6139, 77.2090, 19.0760, 72.8777);
            assertThat(km).isCloseTo(1_150.0, org.assertj.core.data.Offset.offset(25.0));
        }

        @Test
        @DisplayName("measures a long east-west hop at high latitude")
        void highLatitudeEastWest() {
            // Oslo to Anchorage, roughly 6,500km. A flat-earth approximation gets this badly wrong.
            double km = FeatureBuilder.haversineKm(59.9139, 10.7522, 61.2181, -149.9003);
            assertThat(km).isCloseTo(6_600.0, org.assertj.core.data.Offset.offset(300.0));
        }

        @Test
        @DisplayName("is zero for the same point and symmetric between two")
        void zeroAndSymmetric() {
            assertThat(FeatureBuilder.haversineKm(12.9716, 77.5946, 12.9716, 77.5946)).isZero();
            assertThat(FeatureBuilder.haversineKm(12.9716, 77.5946, 28.6139, 77.2090))
                    .isCloseTo(FeatureBuilder.haversineKm(28.6139, 77.2090, 12.9716, 77.5946),
                            org.assertj.core.data.Offset.offset(0.001));
        }

        /** Antipodal points must not overflow the arcsine into NaN. */
        @Test
        @DisplayName("handles antipodal points without producing NaN")
        void antipodal() {
            double km = FeatureBuilder.haversineKm(0.0, 0.0, 0.0, 180.0);
            assertThat(Double.isFinite(km)).isTrue();
            assertThat(km).isCloseTo(20_015.0, org.assertj.core.data.Offset.offset(50.0));
        }

        @Test
        @DisplayName("uses the most recent prior payment that had a location")
        void usesLastKnownLocation() {
            // Built before the stubbing starts. Creating a mock inside a thenReturn(...) argument
            // leaves Mockito's own stubbing state half-open and fails as UnfinishedStubbing.
            PaymentObservation mumbai = observationAt(19.0760, 72.8777);
            when(observations.findRecentWithLocation(anyString(), anyString(), any()))
                    .thenReturn(List.of(mumbai));

            FraudContext context = build(signals(null, "IN", 28.6139, 77.2090));

            assertThat(context.distanceFromLastKm())
                    .isPresent()
                    .get(org.assertj.core.api.InstanceOfAssertFactories.DOUBLE)
                    .isCloseTo(1_150.0, org.assertj.core.data.Offset.offset(25.0));
        }
    }

    // ----------------------------------------------------------- time of day

    @Nested
    @DisplayName("time-of-day features")
    class TimeOfDay {

        /**
         * Derived from when the customer paid, not from when the assessment runs. The saga calls
         * seconds later on a good day and minutes later after a recovery, so using the current time
         * would score a 03:00 payment as a 03:07 one — or, after a redelivery, as a daytime payment
         * altogether. Time-of-day is a real fraud feature, so that drift would be silent and wrong.
         */
        @Test
        @DisplayName("come from the payment's own timestamp, not from now")
        void fromOccurredAt() {
            MlScoringClient.ScoreRequest request = builder.toScoreRequest(build());

            assertThat(request.hourOfDay())
                    .as("03:17Z is hour 3, whenever this test happens to run")
                    .isEqualTo(3);
            // 2026-09-01 is a Tuesday: ISO day 2.
            assertThat(request.dayOfWeek()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------- the wire contract

    @Nested
    @DisplayName("the ml-service request")
    class WireContract {

        /**
         * Window aggregates are prior to the payment and exclude it, so the 24-hour total may
         * legitimately be smaller than the amount being scored. Asserted because a reader is likely to
         * "fix" that as a bug.
         */
        @Test
        @DisplayName("carries prior-window aggregates that exclude this payment")
        void priorWindowsExcludeThisPayment() {
            when(observations.countAttemptsSince(anyString(), anyString(), any())).thenReturn(0L);

            MlScoringClient.ScoreRequest request = builder.toScoreRequest(build());

            assertThat(request.paymentCount1h()).isZero();
            assertThat(request.paymentCount24h()).isZero();
            assertThat(request.amountSum24hMinor())
                    .as("zero prior spend on a payment of 1,000.00 is correct, not a bug")
                    .isZero();
            assertThat(request.amountMinor()).isEqualTo(100_000L);
        }

        @Test
        @DisplayName("carries the amount in minor units and never as a decimal")
        void moneyStaysInteger() {
            MlScoringClient.ScoreRequest request = builder.toScoreRequest(build());

            assertThat(request.amountMinor()).isEqualTo(100_000L);
            assertThat(request.currency()).isEqualTo("INR");
        }

        @Test
        @DisplayName("names the payment so a score can be traced back to it")
        void identifiesThePayment() {
            MlScoringClient.ScoreRequest request = builder.toScoreRequest(build());

            assertThat(request.paymentId()).isEqualTo(PAYMENT);
            assertThat(request.paymentType()).isEqualTo(PaymentType.P2P_TRANSFER.name());
        }
    }

    // ---------------------------------------------------------------- helpers

    private FraudContext build() {
        return build(signals("fp-known", "IN", null, null));
    }

    private FraudContext build(RiskSignals signals) {
        return builder.build(PAYMENT, "TXN-1", USER, PAYEE_WALLET,
                Money.of("1000.00", "INR"), PaymentType.P2P_TRANSFER, signals, OCCURRED);
    }

    private static RiskSignals signals(String fingerprint, String country,
                                       Double latitude, Double longitude) {
        return new RiskSignals(null, fingerprint, "203.0.113.7", "curl/8.4.0",
                country, null, latitude, longitude, Channel.MOBILE_ANDROID, null, null);
    }

    /** No device, no country, no coordinates: an API-channel payment with no client context. */
    private static RiskSignals signalsWithout() {
        return RiskSignals.unknown();
    }

    private static PaymentObservation observationAt(double latitude, double longitude) {
        PaymentObservation observation = mock(PaymentObservation.class);
        when(observation.getLatitude()).thenReturn(latitude);
        when(observation.getLongitude()).thenReturn(longitude);
        return observation;
    }
}
