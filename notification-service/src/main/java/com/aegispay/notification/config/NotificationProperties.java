package com.aegispay.notification.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Delivery, retry and transport settings.
 *
 * <p>What is deliberately <em>not</em> configurable: whether a HIGH-priority notification honours a
 * channel opt-out. That is a compiled rule, because a flag able to switch it off would be one
 * environment variable between a customer and never being told their account was accessed from a new
 * device — and the change would look like a routine tuning tweak in a diff.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.notification")
public class NotificationProperties {

    private final Retry retry = new Retry();
    private final Transports transports = new Transports();
    private final Recipients recipients = new Recipients();

    /**
     * How a failed delivery is retried.
     *
     * <p>Separate from {@code aegis.kafka.retry.*}, which governs a failed <em>event</em>. The two are
     * different problems: a Kafka retry blocks a partition and re-runs the whole handler, so it is kept
     * short and narrow, while a delivery retry is a background drain that must be patient enough to
     * outlast a provider outage.
     */
    public static class Retry {

        /**
         * How often the worker looks for due deliveries.
         *
         * <p>Seconds, not milliseconds. Nothing here is on a customer's critical path — the payment has
         * already completed — so polling aggressively would spend database round trips to make an email
         * arrive two seconds sooner.
         */
        @NotNull
        private Duration pollInterval = Duration.ofSeconds(5);

        /**
         * Delay before the first sweep after startup.
         *
         * <p>Non-zero so a pod that is still opening connection pools is not immediately handed a
         * batch of transport calls, and so a rolling restart does not have every instance sweep in the
         * same instant.
         */
        @NotNull
        private Duration initialDelay = Duration.ofSeconds(15);

        /**
         * How many deliveries one sweep claims.
         *
         * <p>Bounded because the batch is claimed inside a transaction: an unbounded sweep would hold a
         * database connection open across thousands of transport calls, which is the same mistake as
         * putting an HTTP call inside a transaction, just further from where it is usually noticed.
         */
        @Min(1)
        @Max(500)
        private int batchSize = 50;

        /**
         * Total attempts before a delivery is dead-lettered, including the first.
         *
         * <p>Eight, with the backoff below, spans roughly half an hour — long enough to ride out a
         * provider restart or a brief network partition, short enough that a genuinely broken
         * destination reaches the dead-letter queue while somebody is still on shift.
         */
        @Min(1)
        @Max(20)
        private int maxAttempts = 8;

        @NotNull
        private Duration initialBackoff = Duration.ofSeconds(30);

        @DecimalMin("1.0")
        @DecimalMax("10.0")
        private double backoffMultiplier = 2.0;

        /**
         * Ceiling on the gap between attempts.
         *
         * <p>Without it the eighth attempt would be over an hour after the seventh, and a notification
         * about a payment is worth less the later it arrives.
         */
        @NotNull
        private Duration maxBackoff = Duration.ofMinutes(10);

        /**
         * Random proportion added to each backoff.
         *
         * <p>Present because every instance sweeps on the same interval and a provider outage fails
         * every delivery at once. Without jitter the whole backlog would come due in the same second
         * and re-overload the provider that had just recovered.
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double jitter = 0.25;

        /**
         * Extra attempts granted when an operator replays a dead delivery.
         *
         * <p>Granted rather than resetting the count to zero. A delivery that already failed eight
         * times has earned a shorter budget, and a full reset would let a permanently broken
         * destination be replayed forever, each replay looking like a fresh start.
         */
        @Min(1)
        @Max(10)
        private int replayAttemptGrant = 3;

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
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

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        public int getReplayAttemptGrant() {
            return replayAttemptGrant;
        }

        public void setReplayAttemptGrant(int replayAttemptGrant) {
            this.replayAttemptGrant = replayAttemptGrant;
        }
    }

    /**
     * The simulated email and SMS transports.
     *
     * <p>This platform contacts no real SMTP relay and no real SMS gateway, exactly as it contacts no
     * real bank. The settings here shape the simulation so the retry path can actually be exercised —
     * a transport that never fails leaves the entire dead-letter mechanism untested until the first
     * real outage.
     */
    public static class Transports {

        /**
         * Whether the email transport accepts messages.
         *
         * <p>Turning it off suppresses the channel rather than failing it, so a deployment that has not
         * configured email does not accumulate a dead-letter queue full of notifications it was never
         * going to send.
         */
        private boolean emailEnabled = true;

        private boolean smsEnabled = true;

        /**
         * Simulated per-attempt failure rate, 0.0 to 1.0.
         *
         * <p><strong>Zero by default.</strong> A default that injected failures would make every
         * developer's local run produce dead letters and teach them to ignore the queue. It is raised
         * in tests, where a deliberate failure is the point.
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double simulatedFailureRate = 0.0;

        /** Simulated transport latency, so timing behaviour is visible in local runs. */
        @NotNull
        private Duration simulatedLatency = Duration.ZERO;

        /**
         * Envelope sender for simulated email.
         *
         * <p>Recorded on the message so a local run shows what a real deployment would send, and so the
         * value appears in one place rather than being spelled slightly differently in six templates.
         */
        @NotNull
        private String emailFromAddress = "no-reply@aegispay.local";

        @NotNull
        private String emailFromName = "AegisPay";

        /** Sender id for simulated SMS. Short because real networks limit alphanumeric sender ids. */
        @NotNull
        private String smsSenderId = "AEGISPAY";

        public boolean isEmailEnabled() {
            return emailEnabled;
        }

        public void setEmailEnabled(boolean emailEnabled) {
            this.emailEnabled = emailEnabled;
        }

        public boolean isSmsEnabled() {
            return smsEnabled;
        }

        public void setSmsEnabled(boolean smsEnabled) {
            this.smsEnabled = smsEnabled;
        }

        public double getSimulatedFailureRate() {
            return simulatedFailureRate;
        }

        public void setSimulatedFailureRate(double simulatedFailureRate) {
            this.simulatedFailureRate = simulatedFailureRate;
        }

        public Duration getSimulatedLatency() {
            return simulatedLatency;
        }

        public void setSimulatedLatency(Duration simulatedLatency) {
            this.simulatedLatency = simulatedLatency;
        }

        public String getEmailFromAddress() {
            return emailFromAddress;
        }

        public void setEmailFromAddress(String emailFromAddress) {
            this.emailFromAddress = emailFromAddress;
        }

        public String getEmailFromName() {
            return emailFromName;
        }

        public void setEmailFromName(String emailFromName) {
            this.emailFromName = emailFromName;
        }

        public String getSmsSenderId() {
            return smsSenderId;
        }

        public void setSmsSenderId(String smsSenderId) {
            this.smsSenderId = smsSenderId;
        }
    }

    /** How a recipient's delivery address is found. */
    public static class Recipients {

        /**
         * Budget for the auth-service lookup that resolves an email address.
         *
         * <p>Short. This sits between an event arriving and a notification being recorded, and a slow
         * identity service must not hold a Kafka consumer thread — the notification can be retried, so
         * failing fast and letting the retry worker try again later is strictly better than waiting.
         */
        @NotNull
        private Duration lookupTimeout = Duration.ofSeconds(2);

        /**
         * How long a resolved address is cached.
         *
         * <p>Caching at all is a trade: a customer who changes their email would keep receiving
         * notifications at the old address for up to this long. Ten minutes is short enough that the
         * window is measured in one support call rather than one billing cycle, and long enough that a
         * burst of notifications about one payment resolves the address once.
         */
        @NotNull
        private Duration cacheTtl = Duration.ofMinutes(10);

        @Min(0)
        @Max(100_000)
        private int cacheMaxEntries = 10_000;

        public Duration getLookupTimeout() {
            return lookupTimeout;
        }

        public void setLookupTimeout(Duration lookupTimeout) {
            this.lookupTimeout = lookupTimeout;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public int getCacheMaxEntries() {
            return cacheMaxEntries;
        }

        public void setCacheMaxEntries(int cacheMaxEntries) {
            this.cacheMaxEntries = cacheMaxEntries;
        }
    }

    public Retry getRetry() {
        return retry;
    }

    public Transports getTransports() {
        return transports;
    }

    public Recipients getRecipients() {
        return recipients;
    }
}
