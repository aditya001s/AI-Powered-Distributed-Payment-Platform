package com.aegispay.analytics.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket sizes, and the reason they are an enum.
 *
 * <p>{@link Granularity#sqlUnit()} is interpolated into SQL — {@code date_trunc(:unit, ...)} and
 * {@code CAST('1 ' || :unit AS interval)} — because PostgreSQL will not accept a bind parameter as the unit
 * of an interval literal. That makes the returned string part of the statement text, so it is an injection
 * point that no parameter binding protects.
 *
 * <p>The protection is that the string can only ever be one of four constants declared in the enum. This
 * class asserts that property directly, rather than trusting that nobody will later add a value derived from
 * caller input.
 */
class GranularityTest {

    // ------------------------------------------------------------------------
    // SQL safety
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the SQL unit is safe to interpolate")
    class SqlSafety {

        /**
         * The whole safety argument in one assertion. Anything outside this set reaching {@code date_trunc}
         * would be a statement fragment chosen by a caller.
         */
        @ParameterizedTest
        @EnumSource(Granularity.class)
        @DisplayName("is one of exactly four known PostgreSQL units")
        void unitIsAKnownLiteral(Granularity granularity) {
            assertThat(granularity.sqlUnit())
                    .as("""
                            %s is interpolated into date_trunc because PostgreSQL cannot bind an interval \
                            unit; only a fixed literal is safe there""", granularity)
                    .isIn("minute", "hour", "day", "week");
        }

        /**
         * No quotes, no spaces, no punctuation of any kind. A defence-in-depth assertion: even if the set
         * above were widened carelessly, a value that cannot terminate a string literal cannot escape one.
         */
        @ParameterizedTest
        @EnumSource(Granularity.class)
        @DisplayName("contains only lower-case letters")
        void unitIsAlphabetic(Granularity granularity) {
            assertThat(granularity.sqlUnit())
                    .as("a unit containing a quote or a semicolon would end the literal it sits inside")
                    .matches("[a-z]+");
        }

        /** Lower case because that is what PostgreSQL expects, and no value duplicates another. */
        @Test
        @DisplayName("every unit is distinct")
        void unitsAreDistinct() {
            Set<String> units = List.of(Granularity.values()).stream()
                    .map(Granularity::sqlUnit)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(units).hasSize(Granularity.values().length);
        }
    }

    // ------------------------------------------------------------------------
    // spans
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("maximum spans")
    class Spans {

        /**
         * Coarser buckets must allow longer windows, or {@link Granularity#fitting} — which walks the values
         * in declaration order and takes the first that permits the span — would return the wrong answer.
         */
        @Test
        @DisplayName("spans grow monotonically with bucket size")
        void spansGrowWithBucketSize() {
            Granularity[] values = Granularity.values();
            for (int index = 1; index < values.length; index++) {
                assertThat(values[index].maximumSpan())
                        .as("%s must allow a longer window than %s, or fitting() picks wrongly",
                                values[index], values[index - 1])
                        .isGreaterThan(values[index - 1].maximumSpan());
            }
        }

        /**
         * The concrete limits, asserted as values rather than relationships. These are the numbers a caller
         * gets told to use in an error message, and a change to one of them changes the contract.
         */
        @Test
        @DisplayName("holds the documented limits")
        void documentedLimits() {
            assertThat(Granularity.MINUTE.maximumSpan()).isEqualTo(Duration.ofHours(6));
            assertThat(Granularity.HOUR.maximumSpan()).isEqualTo(Duration.ofDays(14));
            assertThat(Granularity.DAY.maximumSpan()).isEqualTo(Duration.ofDays(400));
            assertThat(Granularity.WEEK.maximumSpan()).isEqualTo(Duration.ofDays(1200));
        }

        /**
         * The bucket count each limit implies. This is the figure the limits exist to bound: an hourly series
         * over five years is 43,800 buckets, which would return megabytes and render as a smear.
         */
        @Test
        @DisplayName("no granularity permits more buckets than a chart can render")
        void bucketCountsStayRenderable() {
            assertThat(bucketCount(Granularity.MINUTE)).isEqualTo(360);
            assertThat(bucketCount(Granularity.HOUR)).isEqualTo(336);

            for (Granularity granularity : Granularity.values()) {
                assertThat(bucketCount(granularity))
                        .as("%s permits %d buckets", granularity, bucketCount(granularity))
                        .isLessThanOrEqualTo(500);
            }
        }

        private static long bucketCount(Granularity granularity) {
            long bucketSeconds = switch (granularity) {
                case MINUTE -> 60L;
                case HOUR -> 3_600L;
                case DAY -> 86_400L;
                case WEEK -> 604_800L;
            };
            return granularity.maximumSpan().toSeconds() / bucketSeconds;
        }

        @Test
        @DisplayName("permits a span at exactly its limit")
        void limitIsInclusive() {
            assertThat(Granularity.MINUTE.permits(Duration.ofHours(6)))
                    .as("an off-by-one here refuses the exact window the error message recommends")
                    .isTrue();
            assertThat(Granularity.MINUTE.permits(Duration.ofHours(6).plusSeconds(1))).isFalse();
        }

        /**
         * A null or negative span is nonsense rather than a small one. Returning true would let a reversed
         * window through this check and fail later, further from the cause.
         */
        @Test
        @DisplayName("refuses a null or negative span")
        void refusesNonsenseSpans() {
            assertThat(Granularity.HOUR.permits(null)).isFalse();
            assertThat(Granularity.HOUR.permits(Duration.ofHours(-1))).isFalse();
        }

        @Test
        @DisplayName("permits a zero span")
        void permitsZero() {
            assertThat(Granularity.MINUTE.permits(Duration.ZERO)).isTrue();
        }
    }

    // ------------------------------------------------------------------------
    // fitting
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("choosing a granularity for a caller who did not")
    class Fitting {

        /**
         * The finest resolution the span allows. A short window should get detail and a long one something
         * renderable — defaulting to one unit would be useless at the other end of the range.
         */
        @Test
        @DisplayName("picks the finest bucket the span allows")
        void picksFinest() {
            assertThat(Granularity.fitting(Duration.ofMinutes(30))).isEqualTo(Granularity.MINUTE);
            assertThat(Granularity.fitting(Duration.ofHours(6))).isEqualTo(Granularity.MINUTE);
            assertThat(Granularity.fitting(Duration.ofHours(7))).isEqualTo(Granularity.HOUR);
            assertThat(Granularity.fitting(Duration.ofDays(14))).isEqualTo(Granularity.HOUR);
            assertThat(Granularity.fitting(Duration.ofDays(15))).isEqualTo(Granularity.DAY);
            assertThat(Granularity.fitting(Duration.ofDays(400))).isEqualTo(Granularity.DAY);
        }

        /**
         * Beyond every limit it returns the coarsest rather than throwing. The caller has already had the
         * span itself validated by {@link AnalyticsWindow}, so this is a fallback that cannot be reached
         * through the API — and a null return would be worse than a coarse one.
         */
        @Test
        @DisplayName("falls back to weeks rather than returning nothing")
        void fallsBackToWeeks() {
            assertThat(Granularity.fitting(Duration.ofDays(5_000))).isEqualTo(Granularity.WEEK);
            assertThat(Granularity.fitting(null)).isEqualTo(Granularity.WEEK);
        }

        /** Whatever it picks must permit the span it picked it for. */
        @Test
        @DisplayName("always returns a granularity that permits the span it was given")
        void fittingIsConsistentWithPermits() {
            for (long hours : List.of(1L, 5L, 6L, 7L, 24L, 100L, 336L, 337L, 9_600L)) {
                Duration span = Duration.ofHours(hours);
                Granularity fitted = Granularity.fitting(span);

                assertThat(fitted.permits(span))
                        .as("fitting(%s) returned %s, which does not permit it", span, fitted)
                        .isTrue();
            }
        }
    }

    // ------------------------------------------------------------------------
    // messages
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("error message text")
    class Description {

        /**
         * An over-long request is refused with the granularity to use, never silently coarsened. So the
         * description has to be usable: it names the unit and the limit, because a message that says only
         * "too long" leaves the caller guessing.
         */
        @ParameterizedTest
        @EnumSource(Granularity.class)
        @DisplayName("names the unit and its limit")
        void describesUnitAndLimit(Granularity granularity) {
            assertThat(granularity.describe())
                    .as("this text is what a 400 tells the caller to do instead")
                    .contains(granularity.sqlUnit())
                    .contains(String.valueOf(granularity.maximumSpan().toDays()))
                    .contains("days");
        }
    }
}
