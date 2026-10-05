package com.aegispay.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fraud risk scoring and alerting.
 *
 * <p>Combines a deterministic rule engine with the Python ML model to produce a
 * calibrated fraud probability, a risk bucket and an APPROVE / REVIEW / HOLD /
 * REJECT decision. If the model is unreachable the verdict falls
 * back to rules and is marked degraded.
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
@SpringBootApplication(scanBasePackages = {"com.aegispay.fraud", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.fraud", "com.aegispay.common"})
@EntityScan(basePackages = {"com.aegispay.fraud", "com.aegispay.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.aegispay.fraud", "com.aegispay.common.outbox"})
@EnableScheduling
public class FraudServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
