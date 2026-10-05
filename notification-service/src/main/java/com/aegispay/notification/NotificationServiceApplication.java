package com.aegispay.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Multi-channel customer notifications.
 *
 * <p>Consumes {@code notification-required} and renders each request into email, SMS and in-app
 * messages. Delivery is de-duplicated on the producer's notification key, so Kafka's at-least-once
 * delivery cannot produce two "payment successful" messages for one payment.
 *
 * <p>{@code scanBasePackages} includes {@code com.aegispay.common} so the shared correlation-ID filter,
 * global exception handling, event codec, resilience registries and security chain are all picked up.
 *
 * <p>Default entity scanning is sufficient: this service publishes no domain events of its own, so it
 * has no outbox entities outside its own package.
 *
 * <p>{@code @EnableScheduling} drives {@code DeliveryRetryWorker}. Without it the service would consume
 * events, record notifications, attempt them once, and then never retry a single failure — and every
 * one of those failures would already be committed as {@code FAILED} with a {@code next_attempt_at}
 * nothing ever reads. The symptom would be a silently growing table rather than an error.
 */
@SpringBootApplication(scanBasePackages = {"com.aegispay.notification", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.notification", "com.aegispay.common"})
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
