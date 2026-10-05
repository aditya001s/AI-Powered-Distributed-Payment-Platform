package com.aegispay.analytics.service;

import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Window resolution: defaults, bounds, and bucket alignment.
 *
 * <p>Six endpoints take a window, and each has the same four ways to be given a bad one. Resolving that in
 * six places would produce six subtly different answers to "what does no parameter mean" — and a dashboard
 * whose tiles and charts silently covered different periods would show numbers that disagree with each
 * other for no visible reason.
 *
 * <p>The alignment assertions matter more than they look. An unaligned window produces a partial first
 * bucket, which renders as a sudden dip at the start of every chart, and somebody eventually investigates it
 * as a traffic anomaly.
 */
class AnalyticsWindowTest {

    private static final Instant NOON = Instant.parse("2026-03-01T12:00:00Z");

    // ------------------------------------------------------------------------
    // defaults
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("a missing lower bound means one day back from the upper")
        void defaultsToOneDay() {
            AnalyticsWindow window = AnalyticsWindow.resolve(null, NOON, Granularity.HOUR);

            assertThat(window.from()).isEqualTo(Instant.parse("2026-02-28T12:00:00Z"));
            // The upper bound is advanced a whole bucket past the truncated value, so the bucket the
            // request landed in is included rather than cut off.
            assertThat(window.to()).isEqualTo(Instant.parse("2026-03-01T13:00:00Z"));
        }

        @Test
        @DisplayName("a missing upper bound means now")
        void defaultsToNow() {
            Instant before = Instant.now();
            AnalyticsWindow window = AnalyticsWindow.resolve(null, null, Granularity.HOUR);

            assertThat(window.to()).isAfterOrEqualTo(before.truncatedTo(java.time.temporal.ChronoUnit.HOURS));
            assertThat(window.span()).isGreaterThanOrEqualTo(Duration.ofDays(1));
        }

        /**
         * With no granularity the finest that fits is chosen, so a short window gets detail. A fixed default
         * would make the two-hour view as coarse as the two-week one.
         */
        @Test
        @DisplayName("chooses the finest granularity that fits when none is given")
        void choosesGranularity() {
            AnalyticsWindow twoHours = AnalyticsWindow.resolve(
                    NOON.minus(Duration.ofHours(2)), NOON, null);
            assertThat(twoHours.granularity()).isEqualTo(Granularity.MINUTE);

            AnalyticsWindow tenDays = AnalyticsWindow.resolve(
                    NOON.minus(Duration.ofDays(10)), NOON, null);
            assertThat(tenDays.granularity()).isEqualTo(Granularity.HOUR);

            AnalyticsWindow oneYear = AnalyticsWindow.resolve(
                    NOON.minus(Duration.ofDays(300)), NOON, null);
            assertThat(oneYear.granularity()).isEqualTo(Granularity.DAY);
        }

        /**
         * The no-parameter endpoint window. Fixed at hourly rather than derived, so the endpoint's shape does
         * not change with the clock.
         */
        @Test
        @DisplayName("lastDay is a day of hourly buckets")
        void lastDay() {
            AnalyticsWindow window = AnalyticsWindow.lastDay();

            assertThat(window.granularity()).isEqualTo(Granularity.HOUR);
            assertThat(window.span()).isBetween(Duration.ofDays(1), Duration.ofDays(1).plusHours(1));
        }
    }

    // ------------------------------------------------------------------------
    // rejection
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("bad windows are refused")
    class Rejection {

        /**
         * Reversed bounds would produce an empty result set, which reads on the dashboard as "no activity"
         * rather than "your request was wrong". A 400 says which.
         */
        @Test
        @DisplayName("refuses a reversed window and echoes both bounds")
        void refusesReversed() {
            assertThatThrownBy(() -> AnalyticsWindow.resolve(NOON, NOON.minusSeconds(3_600),
                    Granularity.HOUR))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("'from' must be before 'to'")
                    // Echoing the values received is what turns this into a fixable report rather than a
                    // complaint.
                    .hasMessageContaining("2026-03-01T12:00:00Z");
        }

        @Test
        @DisplayName("refuses a zero-length window")
        void refusesZeroLength() {
            assertThatThrownBy(() -> AnalyticsWindow.resolve(NOON, NOON, Granularity.HOUR))
                    .isInstanceOf(BadRequestException.class);
        }

        /**
         * The row-scan ceiling, separate from the bucket-count one. A five-year window at weekly granularity
         * is only 260 buckets but still reads every payment the platform has ever taken, and one dashboard
         * request must not be able to do that.
         */
        @Test
        @DisplayName("refuses a span beyond 400 days whatever the granularity")
        void refusesOverlongSpan() {
            assertThatThrownBy(() -> AnalyticsWindow.resolve(
                    NOON.minus(Duration.ofDays(500)), NOON, Granularity.WEEK))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("too long")
                    .hasMessageContaining("400");
        }

        /**
         * <strong>Refused, not coarsened.</strong> Quietly returning day buckets for a request that asked for
         * minutes would make the chart's own axis labels wrong — the caller would render a series it believes
         * is minute-by-minute and label it accordingly.
         */
        @Test
        @DisplayName("refuses a granularity too fine for the span, naming the one to use")
        void refusesTooFineGranularity() {
            assertThatThrownBy(() -> AnalyticsWindow.resolve(
                    NOON.minus(Duration.ofDays(30)), NOON, Granularity.MINUTE))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("minute")
                    // The remedy, not just the complaint.
                    .hasMessageContaining("day buckets");
        }

        /** A window at exactly the ceiling is allowed; the limit is inclusive. */
        @Test
        @DisplayName("allows a span at exactly the maximum")
        void allowsExactMaximum() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    NOON.minus(Duration.ofDays(400)), NOON, Granularity.DAY);

            assertThat(window.granularity()).isEqualTo(Granularity.DAY);
        }
    }

    // ------------------------------------------------------------------------
    // alignment
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("bucket alignment")
    class Alignment {

        /**
         * Both bounds are rounded to the bucket unit, so the first and last buckets are whole. Without it a
         * request at 10:37 produces a 23-minute first bucket that renders as a dip.
         */
        @Test
        @DisplayName("truncates both bounds to the bucket unit")
        void truncatesToUnit() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    Instant.parse("2026-03-01T10:37:41.512Z"),
                    Instant.parse("2026-03-01T14:22:09.004Z"),
                    Granularity.HOUR);

            assertThat(window.from()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
            assertThat(window.to()).isEqualTo(Instant.parse("2026-03-01T15:00:00Z"));
        }

        /**
         * The upper bound gains a whole bucket. Asking for "today" at 14:20 must include the 14:00 hour, and
         * truncating alone would end the window at 14:00 and drop the hour in progress — the one an operator
         * is most likely to be watching.
         */
        @Test
        @DisplayName("includes the bucket the request landed in")
        void includesCurrentBucket() {
            Instant at1420 = Instant.parse("2026-03-01T14:20:00Z");
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    Instant.parse("2026-03-01T00:00:00Z"), at1420, Granularity.HOUR);

            assertThat(window.to())
                    .as("the hour in progress is the one somebody is watching")
                    .isAfter(at1420);
            assertThat(window.to()).isEqualTo(Instant.parse("2026-03-01T15:00:00Z"));
        }

        @Test
        @DisplayName("aligns minute windows to the minute")
        void alignsMinutes() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    Instant.parse("2026-03-01T10:37:41.512Z"),
                    Instant.parse("2026-03-01T10:41:02.900Z"),
                    Granularity.MINUTE);

            assertThat(window.from()).isEqualTo(Instant.parse("2026-03-01T10:37:00Z"));
            assertThat(window.to()).isEqualTo(Instant.parse("2026-03-01T10:42:00Z"));
        }

        @Test
        @DisplayName("aligns day windows to midnight UTC")
        void alignsDays() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    Instant.parse("2026-03-01T10:37:41Z"),
                    Instant.parse("2026-03-05T02:15:00Z"),
                    Granularity.DAY);

            assertThat(window.from()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
            assertThat(window.to()).isEqualTo(Instant.parse("2026-03-06T00:00:00Z"));
        }

        /**
         * Weeks start on Monday, matching PostgreSQL's {@code date_trunc('week', ...)}. If the API said one
         * thing and the SQL did another, the first and last buckets would be partial and the totals would not
         * match the tiles above them.
         *
         * <p>2026-03-01 is a Sunday, so its ISO week began on Monday 2026-02-23 — the case that catches a
         * naive "subtract dayOfWeek" implementation.
         */
        @Test
        @DisplayName("aligns week windows to Monday, as PostgreSQL does")
        void alignsWeeksToMonday() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    Instant.parse("2026-03-01T10:37:41Z"),
                    Instant.parse("2026-03-20T02:15:00Z"),
                    Granularity.WEEK);

            assertThat(window.from())
                    .as("2026-03-01 is a Sunday; its ISO week starts Monday 2026-02-23")
                    .isEqualTo(Instant.parse("2026-02-23T00:00:00Z"));
            // 2026-03-20 is a Friday, in the week starting Monday 2026-03-16, plus one whole bucket.
            assertThat(window.to()).isEqualTo(Instant.parse("2026-03-23T00:00:00Z"));
        }

        /**
         * A week bucket is added as seven days rather than {@code plus(1, WEEKS)}, because {@code Instant}
         * rejects every unit above {@code DAYS} — a week is not a fixed length in a calendar. Here every
         * bucket is a fixed span of elapsed time, so the conversion is exact and this asserts it happens.
         */
        @Test
        @DisplayName("a week bucket is exactly seven days, since Instant cannot add weeks")
        void weekBucketIsSevenDays() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    Instant.parse("2026-02-23T00:00:00Z"),
                    Instant.parse("2026-02-24T00:00:00Z"),
                    Granularity.WEEK);

            assertThat(window.to())
                    .as("Instant.plus(1, WEEKS) throws UnsupportedTemporalTypeException")
                    .isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
            assertThat(window.span()).isEqualTo(Duration.ofDays(7));
        }

        /**
         * Alignment must never make a window empty or reversed, whatever it is handed. A sub-bucket request
         * expands to one whole bucket rather than collapsing to nothing.
         */
        @Test
        @DisplayName("a sub-bucket request expands to one whole bucket")
        void subBucketRequestStaysNonEmpty() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    Instant.parse("2026-03-01T10:00:01Z"),
                    Instant.parse("2026-03-01T10:00:02Z"),
                    Granularity.HOUR);

            assertThat(window.from()).isBefore(window.to());
            assertThat(window.span()).isEqualTo(Duration.ofHours(1));
        }

        /**
         * Alignment is applied after validation, so a request at exactly the limit resolves to a span one
         * bucket longer than the limit itself.
         *
         * <p>Asserted rather than treated as a defect, because the alternative is worse. Validating the
         * aligned span instead would refuse the very window the error message recommends — a caller told to
         * use six hours of minute buckets would be refused for asking for six hours of minute buckets. One
         * extra bucket is 361 points instead of 360.
         */
        @Test
        @DisplayName("alignment may add one bucket beyond the granularity's limit, and no more")
        void alignmentAddsAtMostOneBucket() {
            AnalyticsWindow window = AnalyticsWindow.resolve(
                    NOON.minus(Duration.ofHours(6)), NOON, Granularity.MINUTE);

            assertThat(window.granularity()).isEqualTo(Granularity.MINUTE);
            assertThat(window.span())
                    .as("a request at the exact limit must still be served")
                    .isEqualTo(Duration.ofHours(6).plusMinutes(1));
            assertThat(window.span())
                    .as("but never more than one bucket past it")
                    .isLessThanOrEqualTo(Granularity.MINUTE.maximumSpan().plusMinutes(1));
        }
    }
}
