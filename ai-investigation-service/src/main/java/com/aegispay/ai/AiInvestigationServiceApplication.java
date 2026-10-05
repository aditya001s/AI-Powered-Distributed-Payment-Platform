package com.aegispay.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RAG-based fraud investigation narratives.
 *
 * <p>Retrieves relevant fraud-policy documents from a vector store and generates
 * a grounded, cited explanation of why a transaction was flagged, plus an
 * analyst recommendation.
 *  Authority boundary: this service can only RECOMMEND. It holds no
 * credentials for any wallet or ledger write API, so it structurally cannot
 * move money or block an account.
 *
 * <p>{@code scanBasePackages} includes {@code com.aegispay.common} so the shared
 * correlation-ID filter, global exception handling, event codec, resilience
 * registries and security chain are all picked up.
 *
 * <p>{@code @EntityScan} and {@code @EnableJpaRepositories} include the shared transactional
 * outbox entity and repository. {@code @EnableScheduling} drives its drainer.
 */
@SpringBootApplication(scanBasePackages = {"com.aegispay.ai", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.ai", "com.aegispay.common"})
@EntityScan(basePackages = {"com.aegispay.ai", "com.aegispay.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.aegispay.ai", "com.aegispay.common.outbox"})
@EnableScheduling
public class AiInvestigationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiInvestigationServiceApplication.class, args);
    }
}
