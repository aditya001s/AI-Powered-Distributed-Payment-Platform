package com.aegispay.common.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Baseline OpenAPI document for every service.
 *
 * <p>Derives the title from {@code spring.application.name} and the description
 * from {@code aegis.openapi.description}, so a service gets a complete, correctly
 * labelled spec with no per-service Java class. Declaring the bearer scheme here
 * means the Swagger UI "Authorize" button works everywhere, which is what makes the
 * spec usable for exploration rather than only for reading.
 *
 * <p>{@link ConditionalOnMissingBean} lets a service that needs richer metadata
 * (auth-service documents its whole token model) define its own {@code OpenAPI}
 * bean and have this one step aside.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenAPI.class)
public class CommonOpenApiConfig {

    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI aegisOpenApi(
            @Value("${spring.application.name:aegis-service}") String applicationName,
            @Value("${aegis.openapi.description:AegisPay platform service.}") String description,
            @Value("${aegis.gateway-url:http://localhost:8080}") String gatewayUrl,
            @Value("${server.port:8080}") int serverPort) {

        return new OpenAPI()
                .info(new Info()
                        .title(toTitle(applicationName) + " API")
                        .version("1.0.0")
                        .description(description + """


                                ---
                                **Authentication.** All endpoints except health probes and this
                                documentation require a bearer access token issued by
                                `POST /api/auth/login` on the auth service.

                                **Internal endpoints.** Paths under `/internal/**` are for
                                service-to-service calls and require a client-credentials token
                                carrying `ROLE_SERVICE` and `scope=internal`. They are not
                                reachable through the public gateway.

                                **Errors.** Every failure returns the same `ApiError` body with a
                                stable machine-readable `code` and a `correlationId` that can be
                                quoted to support to locate the request in the logs.""")
                        .contact(new Contact().name("AegisPay Platform Team"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url(gatewayUrl).description("API gateway (recommended)"),
                        new Server().url("http://localhost:" + serverPort)
                                .description("Direct access, local development only")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/auth/login")));
    }

    /** {@code wallet-service} -> {@code Wallet Service}. */
    private String toTitle(String applicationName) {
        String[] parts = applicationName.split("[-_]");
        StringBuilder title = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!title.isEmpty()) {
                title.append(' ');
            }
            title.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return title.isEmpty() ? "AegisPay" : "AegisPay " + title;
    }
}
