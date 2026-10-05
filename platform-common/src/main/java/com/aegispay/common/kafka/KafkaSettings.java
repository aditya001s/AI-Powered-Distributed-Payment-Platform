package com.aegispay.common.kafka;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Kafka settings owned by the platform rather than inherited from Spring Boot's
 * {@code spring.kafka.*}.
 *
 * <p>Spring Boot 4 repackaged its autoconfiguration property classes into
 * per-module packages. Binding our own properties and constructing the
 * producer/consumer factories explicitly keeps this shared module independent of
 * that internal layout, and makes the durability settings below reviewable in one
 * place instead of scattered across nine YAML files.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.kafka")
public class KafkaSettings {

    /** Broker list, e.g. {@code kafka:9092}. */
    @NotBlank
    private String bootstrapServers = "localhost:9092";

    /** Consumer group. Must be unique per service so each gets its own copy of events. */
    @NotBlank
    private String consumerGroupId = "aegis-default";

    /** {@code earliest} so a newly deployed service replays history rather than skipping it. */
    @NotBlank
    private String autoOffsetReset = "earliest";

    /** Records per poll. Kept modest so one slow record cannot trigger a rebalance. */
    @Min(1)
    @Max(500)
    private int maxPollRecords = 50;

    /** Listener threads per container; effectively the per-service partition parallelism. */
    @Min(1)
    @Max(32)
    private int concurrency = 2;

    /** Upper bound on handler time before the broker considers the consumer dead. */
    @NotNull
    private Duration maxPollInterval = Duration.ofMinutes(3);

    /** Partitions for auto-provisioned topics. Caps consumer parallelism. */
    @Min(1)
    private int topicPartitions = 6;

    /** Replication factor. 1 for single-broker local dev; 3 in production. */
    @Min(1)
    private short topicReplicationFactor = 1;

    /** Create missing topics at startup instead of relying on broker auto-create. */
    private boolean provisionTopics = true;

    private final Retry retry = new Retry();
    private final Producer producer = new Producer();

    /** Consumer retry/backoff policy before a record is dead-lettered. */
    public static class Retry {
        /** Total delivery attempts, including the first. 4 => 3 retries. */
        @Min(1)
        @Max(10)
        private int maxAttempts = 4;

        @NotNull
        private Duration initialInterval = Duration.ofMillis(500);

        /** Backoff multiplier. 2.0 gives 500ms, 1s, 2s, ... */
        @Min(1)
        private double multiplier = 2.0;

        @NotNull
        private Duration maxInterval = Duration.ofSeconds(10);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }
    }

    /** Producer durability settings. */
    public static class Producer {
        /**
         * {@code all} means the leader waits for every in-sync replica before
         * acknowledging. Anything weaker can lose an acknowledged payment event if
         * the leader fails, which is not acceptable for financial events.
         */
        @NotBlank
        private String acks = "all";

        /** Broker-side retries for transient errors. */
        @Min(0)
        private int retries = 5;

        /**
         * Deduplicates producer retries so a retried send cannot append the record
         * twice. Combined with the outbox, this gives effectively-once publication.
         */
        private boolean idempotence = true;

        /** Small linger batches records without adding meaningful latency. */
        @NotNull
        private Duration lingerMs = Duration.ofMillis(5);

        /** Maximum time from send to success or failure, including retries. */
        @NotNull
        private Duration deliveryTimeout = Duration.ofSeconds(120);

        /**
         * Must be 1..5 when idempotence is on. Above 5 Kafka cannot preserve
         * ordering during retries.
         */
        @Min(1)
        @Max(5)
        private int maxInFlightRequestsPerConnection = 5;

        public String getAcks() {
            return acks;
        }

        public void setAcks(String acks) {
            this.acks = acks;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public boolean isIdempotence() {
            return idempotence;
        }

        public void setIdempotence(boolean idempotence) {
            this.idempotence = idempotence;
        }

        public Duration getLingerMs() {
            return lingerMs;
        }

        public void setLingerMs(Duration lingerMs) {
            this.lingerMs = lingerMs;
        }

        public Duration getDeliveryTimeout() {
            return deliveryTimeout;
        }

        public void setDeliveryTimeout(Duration deliveryTimeout) {
            this.deliveryTimeout = deliveryTimeout;
        }

        public int getMaxInFlightRequestsPerConnection() {
            return maxInFlightRequestsPerConnection;
        }

        public void setMaxInFlightRequestsPerConnection(int value) {
            this.maxInFlightRequestsPerConnection = value;
        }
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getConsumerGroupId() {
        return consumerGroupId;
    }

    public void setConsumerGroupId(String consumerGroupId) {
        this.consumerGroupId = consumerGroupId;
    }

    public String getAutoOffsetReset() {
        return autoOffsetReset;
    }

    public void setAutoOffsetReset(String autoOffsetReset) {
        this.autoOffsetReset = autoOffsetReset;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public Duration getMaxPollInterval() {
        return maxPollInterval;
    }

    public void setMaxPollInterval(Duration maxPollInterval) {
        this.maxPollInterval = maxPollInterval;
    }

    public int getTopicPartitions() {
        return topicPartitions;
    }

    public void setTopicPartitions(int topicPartitions) {
        this.topicPartitions = topicPartitions;
    }

    public short getTopicReplicationFactor() {
        return topicReplicationFactor;
    }

    public void setTopicReplicationFactor(short topicReplicationFactor) {
        this.topicReplicationFactor = topicReplicationFactor;
    }

    public boolean isProvisionTopics() {
        return provisionTopics;
    }

    public void setProvisionTopics(boolean provisionTopics) {
        this.provisionTopics = provisionTopics;
    }

    public Retry getRetry() {
        return retry;
    }

    public Producer getProducer() {
        return producer;
    }
}
