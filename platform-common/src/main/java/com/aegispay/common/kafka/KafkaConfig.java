package com.aegispay.common.kafka;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit Kafka wiring shared by every event-driven service.
 *
 * <p>Everything here is deliberately hand-built rather than delegated to
 * autoconfiguration, because the three properties that make a payment platform
 * safe are all easy to get wrong by omission:
 *
 * <ul>
 *   <li><strong>Producer:</strong> {@code acks=all} plus idempotence. Without
 *       both, an acknowledged {@code payment-completed} event can be silently
 *       lost on leader failover, or duplicated by a producer retry.</li>
 *   <li><strong>Consumer:</strong> manual offset commit after the handler returns
 *       ({@link ContainerProperties.AckMode#RECORD}) and {@code
 *       enable.auto.commit=false}. Auto-commit acknowledges records the handler
 *       has not processed yet, so a crash loses them.</li>
 *   <li><strong>Failure:</strong> bounded exponential backoff, then the dead
 *       letter topic. Infinite retry on a poison record blocks its partition and
 *       stalls every payment that happens to hash to it.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(KafkaSettings.class)
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@ConditionalOnProperty(prefix = "aegis.kafka", name = "bootstrap-servers")
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    // ---------------------------------------------------------------- producer

    @Bean
    @ConditionalOnMissingBean
    public ProducerFactory<String, String> producerFactory(KafkaSettings settings) {
        KafkaSettings.Producer producer = settings.getProducer();
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Durability: wait for all in-sync replicas.
        props.put(ProducerConfig.ACKS_CONFIG, producer.getAcks());
        props.put(ProducerConfig.RETRIES_CONFIG, producer.getRetries());

        // Exactly-once *publication* semantics for retries within a producer session.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, producer.isIdempotence());
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,
                producer.getMaxInFlightRequestsPerConnection());

        props.put(ProducerConfig.LINGER_MS_CONFIG, producer.getLingerMs().toMillis());
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                (int) producer.getDeliveryTimeout().toMillis());
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true); // propagates the trace context into headers
        return template;
    }

    // ---------------------------------------------------------------- consumer

    @Bean
    @ConditionalOnMissingBean
    public ConsumerFactory<String, String> consumerFactory(KafkaSettings settings) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, settings.getConsumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, settings.getAutoOffsetReset());

        // Offsets are committed by the container AFTER the handler succeeds.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, settings.getMaxPollRecords());
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                (int) settings.getMaxPollInterval().toMillis());
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Named {@code kafkaListenerContainerFactory} so every {@code @KafkaListener}
     * picks it up without per-listener configuration.
     */
    @Bean("kafkaListenerContainerFactory")
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaSettings settings,
            DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(settings.getConcurrency());
        factory.setCommonErrorHandler(errorHandler);

        ContainerProperties containerProps = factory.getContainerProperties();
        // Commit per record: at most one record is reprocessed after a crash.
        containerProps.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProps.setObservationEnabled(true); // continues the producer's trace
        return factory;
    }

    /**
     * Bounded exponential backoff, then publish to {@code <topic>.DLT}.
     *
     * <p>Retryable vs not matters here. A transient failure (database blip,
     * downstream 503) deserves retries. A record that cannot be deserialised will
     * fail identically forever, so it is dead-lettered on the first attempt to keep
     * the partition moving.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 KafkaSettings settings,
                                                 MeterRegistry meterRegistry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> {
                    String deadLetterTopic = KafkaTopics.deadLetterFor(record.topic());
                    meterRegistry.counter(MetricNames.EVENTS_DEAD_LETTERED,
                            MetricNames.TAG_TOPIC, record.topic()).increment();
                    // Logged at ERROR: a dead-lettered payment event needs a human.
                    log.error("Dead-lettering record from {}-{}@{} to {} after exhausting retries: {}",
                            record.topic(), record.partition(), record.offset(),
                            deadLetterTopic, exception.getMessage(), exception);
                    // Partition -1 lets Kafka choose; the DLT may have a different
                    // partition count than the source topic.
                    return new org.apache.kafka.common.TopicPartition(deadLetterTopic, -1);
                });

        KafkaSettings.Retry retry = settings.getRetry();
        ExponentialBackOff backOff = new ExponentialBackOff(
                retry.getInitialInterval().toMillis(), retry.getMultiplier());
        backOff.setMaxInterval(retry.getMaxInterval().toMillis());
        // maxAttempts counts the first delivery, so retries = maxAttempts - 1.
        backOff.setMaxAttempts(Math.max(0, retry.getMaxAttempts() - 1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Permanent failures: retrying cannot change the outcome.
        handler.addNotRetryableExceptions(
                KafkaEventCodec.EventSerializationException.class,
                ConversionException.class,
                BadRequestException.class,
                IllegalArgumentException.class,
                NullPointerException.class,
                jakarta.validation.ConstraintViolationException.class);

        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }

    // ------------------------------------------------------------- publication

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                         KafkaEventCodec codec,
                                         MeterRegistry meterRegistry) {
        return new EventPublisher(kafkaTemplate, codec, meterRegistry);
    }

    // ------------------------------------------------------------ provisioning

    @Bean
    @ConditionalOnMissingBean
    public KafkaAdmin kafkaAdmin(KafkaSettings settings) {
        Map<String, Object> props = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, settings.getBootstrapServers());
        KafkaAdmin admin = new KafkaAdmin(props);
        // Do not stop the whole application if the broker is briefly unreachable
        // during a rolling restart; the listener containers retry on their own.
        admin.setFatalIfBrokerNotAvailable(false);
        admin.setAutoCreate(settings.isProvisionTopics());
        return admin;
    }

    /**
     * Declares every topic and its dead-letter twin with the partition count and
     * replication factor we chose, rather than inheriting broker defaults from
     * auto-creation.
     */
    @Bean
    @ConditionalOnProperty(prefix = "aegis.kafka", name = "provision-topics",
            havingValue = "true", matchIfMissing = true)
    public KafkaAdmin.NewTopics aegisTopics(KafkaSettings settings) {
        List<NewTopic> topics = KafkaTopics.ALL.stream()
                .flatMap(topic -> java.util.stream.Stream.of(
                        TopicBuilder.name(topic)
                                .partitions(settings.getTopicPartitions())
                                .replicas(settings.getTopicReplicationFactor())
                                // 30 days: long enough to replay a month of history
                                // into a new consumer or rebuild a projection.
                                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                                .config("min.insync.replicas",
                                        String.valueOf(Math.min(2, settings.getTopicReplicationFactor())))
                                .build(),
                        TopicBuilder.name(KafkaTopics.deadLetterFor(topic))
                                .partitions(1)
                                .replicas(settings.getTopicReplicationFactor())
                                // Dead letters are kept longer: they exist to be
                                // investigated and replayed by a human.
                                .config("retention.ms", String.valueOf(90L * 24 * 60 * 60 * 1000))
                                .build()))
                .toList();
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
