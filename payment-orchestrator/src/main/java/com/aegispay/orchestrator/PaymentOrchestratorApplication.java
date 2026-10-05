package com.aegispay.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Saga coordinator and payment provider router.
 *
 * <p>Drives the payment saga (reserve funds, assess risk, charge a provider,
 * post to the ledger, capture) and compensates on failure. Routes across
 * three simulated providers on health, latency and failure rate, with a
 * circuit breaker and automatic fallback.
 *
 * <p>{@code scanBasePackages} includes {@code com.aegispay.common} so the shared
 * correlation-ID filter, global exception handling, event codec, resilience
 * registries and security chain are all picked up.
 *
 * <p>{@code @EntityScan} and {@code @EnableJpaRepositories} name
 * {@code com.aegispay.common.outbox} explicitly because the transactional outbox
 * entity and repository live in the shared module, and Spring Boot's default entity
 * scan only covers this application's own package. Without them the service starts
 * and then fails at runtime with "Not a managed type".
 *
 * <p>{@code @EnableScheduling} drives the outbox drainer.
 */
@SpringBootApplication(scanBasePackages = {"com.aegispay.orchestrator", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.orchestrator", "com.aegispay.common"})
@EntityScan(basePackages = {"com.aegispay.orchestrator", "com.aegispay.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.aegispay.orchestrator", "com.aegispay.common.outbox"})
@EnableScheduling
public class PaymentOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentOrchestratorApplication.class, args);
    }
}
