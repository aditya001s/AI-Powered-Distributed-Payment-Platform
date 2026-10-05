package com.aegispay.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI metadata for auth-service.
 *
 * <p>Documents the bearer scheme so the Swagger UI "Authorize" button works, which
 * makes the spec directly usable for exploration rather than only for reading.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenApi(@Value("${aegis.gateway-url:http://localhost:8080}") String gatewayUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("AegisPay Auth Service API")
                        .version("1.0.0")
                        .description("""
                                Identity provider for the AegisPay platform, and the only issuer
                                of access tokens.

                                **Token model**
                                - Access tokens are RS256 JWTs with a 15-minute lifetime.
                                - Refresh tokens are opaque, single-use and rotated. Replaying a
                                  consumed refresh token revokes the entire session family, on the
                                  assumption that a replay means the token was stolen.
                                - Services authenticate with the OAuth2 client-credentials grant
                                  and receive a token carrying `ROLE_SERVICE` plus `scope=internal`.

                                **Key distribution**
                                No other service holds signing material. Validators fetch the
                                public key from `/.well-known/jwks.json`, so compromising a
                                downstream service cannot mint tokens.

                                **Enumeration resistance**
                                Every login failure returns an identical 401 whatever the cause,
                                and the unknown-email path performs a dummy hash comparison so
                                response timing does not reveal whether an account exists.""")
                        .contact(new Contact().name("AegisPay Platform Team"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url(gatewayUrl).description("API gateway (use this)"),
                        new Server().url("http://localhost:8081").description("Direct, local development only")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/auth/login")));
    }
}
