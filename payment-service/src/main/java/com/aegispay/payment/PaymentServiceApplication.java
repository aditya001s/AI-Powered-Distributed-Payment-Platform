package com.aegispay.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Customer-facing payment API and payment state machine.
 *
 * <p>Accepts payment requests, enforces idempotency, persists the payment aggregate and
 * emits {@code payment-created} through the transactional outbox. Owns the payment
 * lifecycle; owns neither balances (wallet-service) nor the accounting journal
 * (ledger-service).
 *
 * <p>{@code scanBasePackages} includes {@code com.aegispay.common} so the shared
 * correlation-ID filter, global exception handling, event codec, resilience registries and
 * security chain are all picked up.
 *
 * <p>{@code @EntityScan} and {@code @EnableJpaRepositories} name two shared packages
 * explicitly, because Boot's default entity scan only covers this application's own package:
 *
 * <ul>
 *   <li>{@code common.outbox} — makes the state change and its event atomic;</li>
 *   <li>{@code common.idempotency} — the composite primary key that makes a retried payment
 *       impossible to execute twice.</li>
 * </ul>
 *
 * <p>Omitting either lets the service start and then fail at runtime with "Not a managed
 * type", so they are listed rather than discovered.
 *
 * <p>{@code @EnableScheduling} drives the outbox drainer.
 */
@SpringBootApplication(scanBasePackages = {"com.aegispay.payment", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.payment", "com.aegispay.common"})
@EntityScan(basePackages = {
        "com.aegispay.payment",
        "com.aegispay.common.outbox",
        "com.aegispay.common.idempotency"
})
@EnableJpaRepositories(basePackages = {
        "com.aegispay.payment",
        "com.aegispay.common.outbox",
        "com.aegispay.common.idempotency"
})
@EnableScheduling
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
