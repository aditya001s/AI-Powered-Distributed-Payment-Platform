package com.aegispay.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Immutable double-entry accounting journal.
 *
 * <p>The platform's book of record. Every posting is balanced (total debits ==
 * total credits) and append-only: a mistake is corrected by a new,
 * opposite posting rather than by editing history.
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
@SpringBootApplication(scanBasePackages = {"com.aegispay.ledger", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.ledger", "com.aegispay.common"})
@EntityScan(basePackages = {"com.aegispay.ledger", "com.aegispay.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.aegispay.ledger", "com.aegispay.common.outbox"})
@EnableScheduling
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
