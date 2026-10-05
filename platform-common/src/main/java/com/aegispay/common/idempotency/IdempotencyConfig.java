package com.aegispay.common.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Activates database-backed idempotency.
 *
 * <p>Opt-in via {@code aegis.idempotency.enabled=true}, because only the services with
 * externally-triggered, money-moving operations need it (payment-service and
 * wallet-service). A read-only service would otherwise pay for a table it never writes.
 *
 * <p>A service enabling this must also make the entity and repository visible, since
 * they live in this shared module:
 *
 * <pre>{@code
 * @EntityScan({"com.aegispay.payment", "com.aegispay.common.outbox",
 *              "com.aegispay.common.idempotency"})
 * @EnableJpaRepositories({...same...})
 * }</pre>
 *
 * and must ship the {@code idempotency_keys} migration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aegis.idempotency", name = "enabled", havingValue = "true")
public class IdempotencyConfig {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(IdempotencyRecordRepository repository) {
        return new IdempotencyStore(repository);
    }

    /**
     * {@code Optional<IdempotencyGuard>} rather than a hard dependency: the Redis fast
     * path is genuinely optional, and the service degrades to database-only when Redis
     * is absent instead of refusing to start.
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyService idempotencyService(IdempotencyStore store,
                                                 ObjectMapper objectMapper,
                                                 MeterRegistry meterRegistry,
                                                 Optional<IdempotencyGuard> guard) {
        return new IdempotencyService(store, objectMapper, meterRegistry, guard);
    }
}
