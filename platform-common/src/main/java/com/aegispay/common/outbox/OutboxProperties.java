package com.aegispay.common.outbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "aegis.outbox")
public class OutboxProperties {

    /**
     * Whether this service records and drains outbox events. Only services that
     * emit events need it on (payment-service, wallet-service, orchestrator,
     * fraud-service, ledger-service).
     */
    private boolean enabled = false;

    /**
     * Poll interval. This is the added end-to-end latency between committing a
     * state change and its event reaching Kafka, so it is deliberately short.
     * 200ms is imperceptible next to a payment's overall latency budget while
     * keeping the query rate low.
     */
    @NotNull
    private Duration pollInterval = Duration.ofMillis(200);

    /**
     * Rows claimed per poll. Large enough to clear a burst, small enough that the
     * row locks are held briefly.
     */
    @Min(1)
    @Max(1000)
    private int batchSize = 100;

    /** Publication attempts before a row is marked FAILED for operator attention. */
    @Min(1)
    @Max(20)
    private int maxAttempts = 8;

    /** Base delay for exponential backoff between attempts. */
    @NotNull
    private Duration retryBackoff = Duration.ofMillis(500);

    /** How long PUBLISHED rows are kept before the retention sweep removes them. */
    @NotNull
    private Duration publishedRetention = Duration.ofHours(24);

    /** How often the retention sweep runs. */
    @NotNull
    private Duration cleanupInterval = Duration.ofMinutes(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public Duration getPublishedRetention() {
        return publishedRetention;
    }

    public void setPublishedRetention(Duration publishedRetention) {
        this.publishedRetention = publishedRetention;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }
}
