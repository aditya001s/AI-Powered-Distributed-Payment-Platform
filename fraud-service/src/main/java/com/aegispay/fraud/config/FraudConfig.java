package com.aegispay.fraud.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * fraud-service wiring.
 *
 * <p>Thin on purpose. The interesting configuration is in {@link FraudProperties}, and the resilience
 * registries the ML client uses come from platform-common so that every service's breakers are built
 * the same way.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FraudProperties.class)
public class FraudConfig {
}
