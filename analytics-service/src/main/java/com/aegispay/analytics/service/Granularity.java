package com.aegispay.analytics.service;

import java.time.Duration;
import java.util.Locale;

/**
 * The time-bucket sizes a chart may ask for.
 *
 * <h2>Why this is a closed enum and not a string</h2>
 *
 * <p>The bucket unit is interpolated into SQL — {@code date_trunc(:unit, ...)} and
 * {@code CAST('1 ' || :unit AS interval)}. PostgreSQL will not accept a bind parameter as the unit of
 * an interval literal, so the value becomes part of the statement text.
 *
 * <p>That makes an unvalidated unit an injection point that no parameter binding protects. A closed
 * enum whose names are the only accepted values means the string reaching SQL can only ever be one of
 * four literals chosen here, and Spring rejects an unknown value at request binding with a 400 before
 * any query is built.
 *
 * <p>Each value also carries a maximum span, which is the other half of the protection. An hourly
 * series over five years is 43,800 buckets: the query would succeed, return megabytes, and the chart
 * would be unreadable — so the range is refused with an explanation rather than served.
 */
public enum Granularity {

    /** Minute buckets. For watching an incident as it happens. */
    MINUTE("minute", Duration.ofHours(6)),

    /** Hour buckets. The default working view. */
    HOUR("hour", Duration.ofDays(14)),

    /** Day buckets. For a month or a quarter. */
    DAY("day", Duration.ofDays(400)),

    /** Week buckets. For a year of trend. */
    WEEK("week", Duration.ofDays(1200));

    private final String sqlUnit;
    private final Duration maximumSpan;

    Granularity(String sqlUnit, Duration maximumSpan) {
        this.sqlUnit = sqlUnit;
        this.maximumSpan = maximumSpan;
    }

    /**
     * The literal passed to {@code date_trunc}.
     *
     * <p>Lower case because that is what PostgreSQL expects, and a constant of this enum rather than a
     * transformation of the caller's input — the difference between the two is the whole safety
     * argument.
     */
    public String sqlUnit() {
        return sqlUnit;
    }

    public Duration maximumSpan() {
        return maximumSpan;
    }

    /** Whether a requested window is small enough to plot at this granularity. */
    public boolean permits(Duration span) {
        return span != null && !span.isNegative() && span.compareTo(maximumSpan) <= 0;
    }

    /**
     * The largest granularity that can cover a window, for a caller that did not choose one.
     *
     * <p>Picks the finest resolution the span allows, so a short window gets detail and a long one gets
     * something renderable — rather than defaulting to one unit and being useless at the other end of
     * the range.
     */
    public static Granularity fitting(Duration span) {
        for (Granularity candidate : values()) {
            if (candidate.permits(span)) {
                return candidate;
            }
        }
        return WEEK;
    }

    /** A readable form for an error message. */
    public String describe() {
        return name().toLowerCase(Locale.ROOT) + " buckets (up to "
                + maximumSpan.toDays() + " days)";
    }
}
