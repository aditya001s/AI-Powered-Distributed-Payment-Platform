package com.aegispay.analytics.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Read-model settings.
 *
 * <p>What is deliberately not configurable: whether aggregates are derived from per-payment rows rather
 * than stored counters. That is the property which makes at-least-once event delivery unable to inflate a
 * total, and a switch to "fast mode" counters would trade correctness for latency in a way no operator
 * would be able to detect afterwards.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.analytics")
public class AnalyticsProperties {

    /**
     * Lag beyond which a stream is reported as stale.
     *
     * <p>Not a hard failure — the API still answers, and still returns the numbers it has. It sets the
     * flag that tells a reader the figures are historical, because a dashboard that goes quietly stale is
     * worse than one that says so: an operator reading "0 held payments" needs to know whether the queue
     * is clear or the consumer stopped.
     *
     * <p>Two minutes is chosen against the platform's own cadence. The outbox drains every 200ms and
     * consumers commit per record, so healthy lag is sub-second; two minutes is far enough above that to
     * absorb a rolling restart without crying wolf, and short enough that a genuinely dead consumer is
     * flagged while somebody is still looking.
     */
    @NotNull
    private Duration staleAfter = Duration.ofMinutes(2);

    /**
     * The reporting currency for aggregate totals.
     *
     * <p>Aggregates sum {@code amount_minor} across rows, and minor units are only comparable within one
     * currency. This service does not convert — it has no rate source, and inventing one would produce
     * authoritative-looking totals derived from a made-up number.
     *
     * <p>So the label is configuration, and it is honest only while the platform is single-currency.
     * Multi-currency support means grouping by currency in every aggregate, not adding a conversion here.
     */
    @NotNull
    private String reportingCurrency = "INR";

    /**
     * Largest page the payment list will return.
     *
     * <p>Bounded because the list is platform-wide. A customer-scoped endpoint is naturally limited by how
     * many payments one person made; this one is limited only by what a caller asks for.
     */
    @Min(1)
    @Max(500)
    private int maximumPageSize = 100;

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    public String getReportingCurrency() {
        return reportingCurrency;
    }

    public void setReportingCurrency(String reportingCurrency) {
        this.reportingCurrency = reportingCurrency;
    }

    public int getMaximumPageSize() {
        return maximumPageSize;
    }

    public void setMaximumPageSize(int maximumPageSize) {
        this.maximumPageSize = maximumPageSize;
    }
}
