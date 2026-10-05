package com.aegispay.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Identity provider for AegisPay.
 *
 * <p>Owns user records, credentials, roles and refresh-token lifecycle, and is the
 * single issuer of access tokens for both humans and services.
 *
 * <p>{@code scanBasePackages} includes {@code com.aegispay.common} so the shared
 * correlation filter, error handling and security helpers are picked up.
 * Deliberately does <em>not</em> enable the outbox: identity changes are published
 * as audit events over Kafka only when they are security-relevant, and those are
 * derived notifications rather than state this service must guarantee delivery of.
 */
@SpringBootApplication(scanBasePackages = {"com.aegispay.auth", "com.aegispay.common"})
@ConfigurationPropertiesScan(basePackages = {"com.aegispay.auth", "com.aegispay.common"})
@EnableScheduling
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
