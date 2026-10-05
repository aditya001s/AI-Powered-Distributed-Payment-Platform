package com.aegispay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The platform's single public entry point.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li><strong>Routing</strong> — one stable public URL surface in front of ten
 *       services, so clients never depend on internal topology.</li>
 *   <li><strong>Authentication at the edge</strong> — bearer tokens are validated
 *       here, so an unauthenticated request is rejected before it consumes any
 *       downstream capacity. Downstream services still re-validate: the gateway is
 *       a first line of defence, not the only one, and a service must not become
 *       trivially exploitable if something reaches it by another path.</li>
 *   <li><strong>Rate limiting</strong> — a Redis token bucket, so the limit is
 *       shared across gateway replicas rather than per-instance.</li>
 *   <li><strong>Correlation IDs</strong> — minted here and propagated, which is
 *       what makes one request traceable across every hop.</li>
 *   <li><strong>Blocking internal paths</strong> — {@code /internal/**} is not
 *       routed at all, so service-to-service endpoints are unreachable from the
 *       internet regardless of what token a caller presents.</li>
 * </ul>
 *
 * <p>Note the deliberately narrow component scan: unlike the other services this
 * one does <em>not</em> scan {@code com.aegispay.common}. That module's shared
 * configuration is servlet-based ({@code OncePerRequestFilter},
 * {@code @RestControllerAdvice}), and pulling it into a reactive application would
 * either fail to apply or drag in the servlet stack and disable the gateway. The
 * common module is still used as a library for {@code ApiError} and {@code Roles}.
 */
@SpringBootApplication(scanBasePackages = "com.aegispay.gateway")
@ConfigurationPropertiesScan(basePackages = "com.aegispay.gateway")
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
