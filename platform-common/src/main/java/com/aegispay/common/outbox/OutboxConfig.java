package com.aegispay.common.outbox;

import com.aegispay.common.kafka.KafkaEventCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates the transactional outbox.
 *
 * <p>Opt-in via {@code aegis.outbox.enabled=true}. Only services that emit domain
 * events need it; a read-only service such as analytics would otherwise pay for a
 * scheduled poller and a table it never writes to.
 *
 * <p>Services enabling this must also make the entity and repository visible,
 * because they live in this shared module rather than the service's own package:
 *
 * <pre>{@code
 * @EntityScan({"com.aegispay.payment", "com.aegispay.common.outbox"})
 * @EnableJpaRepositories({"com.aegispay.payment", "com.aegispay.common.outbox"})
 * }</pre>
 *
 * and must apply the {@code outbox_events} migration
 * ({@code db/migration/V1__outbox.sql}, shipped identically in every such service).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "aegis.outbox", name = "enabled", havingValue = "true")
public class OutboxConfig {

    @Bean
    @ConditionalOnMissingBean
    public OutboxRecorder outboxRecorder(OutboxEventRepository repository, KafkaEventCodec codec) {
        return new OutboxRecorder(repository, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxPublisher outboxPublisher(OutboxEventRepository repository,
                                           KafkaTemplate<String, String> kafkaTemplate,
                                           OutboxProperties properties,
                                           MeterRegistry meterRegistry) {
        return new OutboxPublisher(repository, kafkaTemplate, properties, meterRegistry);
    }
}
