package com.aegispay.analytics.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A validated time window, and the bucket size to chart it at.
 *
 * <h2>Why the window is a type rather than two parameters</h2>
 *
 * <p>Six endpoints take a window, and each one has the same four ways to be given a bad one: reversed
 * bounds, a span so long the query returns megabytes, a bucket size too fine for the span, and no bounds
 * at all. Resolving that in six places would mean six subtly different answers to "what does no
 * parameter mean" — and a dashboard whose tiles and charts silently covered different periods would
 * produce numbers that disagree with each other for no visible reason.
 *
 * @param from        inclusive lower bound
 * @param to          exclusive upper bound
 * @param granularity the bucket size, already checked against the span
 */
public record AnalyticsWindow(Instant from, Instant to, Granularity granularity) {

    /** What a caller gets for asking with no parameters. Long enough to see a trend, short enough to load. */
    private static final Duration DEFAULT_SPAN = Duration.ofDays(1);

    /**
     * Hard ceiling on any window, whatever the granularity.
     *
     * <p>Separate from {@link Granularity#maximumSpan()}, which bounds the number of <em>buckets</em>.
     * This bounds the number of <em>rows scanned</em>: a five-year window at weekly granularity is only
     * 260 buckets but still reads every payment the platform has ever taken, and one dashboard request
     * should not be able to do that.
     */
    private static final Duration MAXIMUM_SPAN = Duration.ofDays(400);

    /**
     * Resolves the window a request asked for.
     *
     * <p>Rounded to the granularity's own unit, so the first and last buckets are whole. Without it a
     * request at 10:37 would produce a 23-minute first bucket that renders as a sudden dip at the start of
     * every chart — and somebody would eventually investigate it as a traffic anomaly.
     *
     * @param from        may be null, meaning {@code to} minus the default span
     * @param to          may be null, meaning now
     * @param granularity may be null, meaning the finest that fits the span
     */
    public static AnalyticsWindow resolve(Instant from, Instant to, Granularity granularity) {
        Instant upper = to == null ? Instant.now() : to;
        Instant lower = from == null ? upper.minus(DEFAULT_SPAN) : from;

        if (!lower.isBefore(upper)) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "'from' must be before 'to'. Received from=" + lower + " to=" + upper + ".");
        }

        Duration span = Duration.between(lower, upper);
        if (span.compareTo(MAXIMUM_SPAN) > 0) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "A window of " + span.toDays() + " days is too long. The maximum is "
                            + MAXIMUM_SPAN.toDays() + " days, because one dashboard request should not "
                            + "read every payment the platform has ever taken.");
        }

        Granularity resolved = granularity == null ? Granularity.fitting(span) : granularity;

        if (!resolved.permits(span)) {
            // Refused rather than silently coarsened. Quietly returning day buckets for a request that
            // asked for minutes would make the chart's own axis labels wrong.
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "A " + span.toDays() + "-day window cannot be charted at "
                            + resolved.name().toLowerCase(java.util.Locale.ROOT)
                            + " granularity: that would be more buckets than a chart can render. Use "
                            + Granularity.fitting(span).describe() + " for this range.");
        }

        // The upper bound is advanced by one whole bucket past the truncated value, so the bucket the
        // request landed in is included rather than cut off. Asking "today" at 14:20 must include the
        // 14:00 hour, and truncating alone would end the window at 14:00 and drop it.
        return new AnalyticsWindow(
                truncate(lower, resolved),
                truncate(upper, resolved).plus(bucketLength(resolved)),
                resolved);
    }

    /**
     * One bucket, as a {@link Duration}.
     *
     * <p>A duration rather than {@code plus(1, unit)} because {@code Instant} rejects {@code WEEKS} —
     * it supports no unit above {@code DAYS}, on the grounds that a week is not a fixed length in a
     * calendar. Here every bucket is a fixed span of elapsed time, so the conversion is exact.
     */
    private static Duration bucketLength(Granularity granularity) {
        return switch (granularity) {
            case MINUTE -> Duration.ofMinutes(1);
            case HOUR -> Duration.ofHours(1);
            case DAY -> Duration.ofDays(1);
            case WEEK -> Duration.ofDays(7);
        };
    }

    /** The default window, for an endpoint that takes no parameters at all. */
    public static AnalyticsWindow lastDay() {
        return resolve(null, null, Granularity.HOUR);
    }

    public Duration span() {
        return Duration.between(from, to);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Rounds an instant down to the start of its bucket.
     *
     * <p>{@code truncatedTo} cannot do weeks or months, so a week is handled by truncating to a day and
     * then stepping back to Monday. Doing it here rather than in SQL keeps the returned bounds and the
     * bucket boundaries in agreement — if the API said one thing and {@code date_trunc} did another, the
     * first and last buckets would be partial and the totals would not match the tiles.
     */
    private static Instant truncate(Instant instant, Granularity granularity) {
        if (granularity == Granularity.WEEK) {
            java.time.ZonedDateTime utc = instant.atZone(java.time.ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.DAYS);
            // ISO weeks start on Monday, which is what PostgreSQL's date_trunc('week', ...) uses.
            return utc.minusDays(utc.getDayOfWeek().getValue() - 1L).toInstant();
        }
        return instant.truncatedTo(unitOf(granularity));
    }

    private static ChronoUnit unitOf(Granularity granularity) {
        return switch (granularity) {
            case MINUTE -> ChronoUnit.MINUTES;
            case HOUR -> ChronoUnit.HOURS;
            case DAY, WEEK -> ChronoUnit.DAYS;
        };
    }
}
