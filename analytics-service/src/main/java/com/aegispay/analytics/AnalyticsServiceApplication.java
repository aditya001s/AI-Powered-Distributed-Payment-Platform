package com.aegispay.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Admin analytics and platform read models.
 *
 * <p>Builds read-optimised projections from the event stream: transaction volume
 * and success rate, fraud rate, provider health, latency percentiles and
 * high-risk transaction lists for the admin dashboard.
 *
 * <p>{@code scanBasePackages} includes {@code com.aegispay.common} so the shared
 * correlation-ID filter, global exception handling, event codec, resilience
 * registries and security chain are all picked up.
 *
 * <p>Default entity scanning is sufficient here: this service does not use the
 * transactional outbox, so it has no entities outside its own package.
 */
@SpringBootApplication(scanBasePackages = {"com.aegispay.analytics", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.analytics", "com.aegispay.common"})
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
