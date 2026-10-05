package com.aegispay.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wallet balances and fund reservations.
 *
 * <p>Owns customer wallet balances in integer minor units, and the reserve / capture /
 * release hold lifecycle the payment saga depends on. Balance correctness comes from
 * row-level locking inside a database transaction plus CHECK constraints that make an
 * over-spend unpersistable; the Redis distributed lock only reduces contention.
 *
 * <p>{@code scanBasePackages} includes {@code com.aegispay.common} so the shared
 * correlation-ID filter, global exception handling, event codec, resilience registries and
 * security chain are all picked up.
 *
 * <p>{@code @EntityScan} and {@code @EnableJpaRepositories} name two shared packages
 * explicitly, because Spring Boot's default entity scan only covers this application's own
 * package and both live in the shared module:
 *
 * <ul>
 *   <li>{@code common.outbox} — the transactional outbox;</li>
 *   <li>{@code common.idempotency} — the authoritative idempotency record, which is what
 *       stops a retried deposit from crediting twice.</li>
 * </ul>
 *
 * <p>Omitting either lets the service start and then fail at runtime with "Not a managed
 * type", so they are listed rather than discovered.
 *
 * <p>{@code @EnableScheduling} drives the outbox drainer and the expired-hold sweeper.
 */
@SpringBootApplication(scanBasePackages = {"com.aegispay.wallet", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.wallet", "com.aegispay.common"})
@EntityScan(basePackages = {
        "com.aegispay.wallet",
        "com.aegispay.common.outbox",
        "com.aegispay.common.idempotency"
})
@EnableJpaRepositories(basePackages = {
        "com.aegispay.wallet",
        "com.aegispay.common.outbox",
        "com.aegispay.common.idempotency"
})
@EnableScheduling
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
