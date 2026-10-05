package com.aegispay.gateway.config;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.security.JwtAuthenticationConverter;
import com.aegispay.common.security.Roles;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Edge authentication and authorisation.
 *
 * <p>Reactive equivalents throughout: {@code SecurityWebFilterChain} rather than
 * {@code SecurityFilterChain}, {@code ServerHttpSecurity} rather than
 * {@code HttpSecurity}. The shared servlet chain in platform-common cannot be
 * reused here, which is why the rules are restated — the trade-off of running one
 * reactive service alongside nine servlet ones.
 *
 * <p>Two rules carry most of the security weight:
 *
 * <ol>
 *   <li>{@code /internal/**} is <strong>denied outright</strong>, not merely
 *       role-restricted. Those endpoints are for service-to-service calls; nothing
 *       from the internet should reach them even with a valid token. Blocking at
 *       the edge means a downstream misconfiguration cannot expose them.</li>
 *   <li>Everything not explicitly permitted requires authentication. A new route
 *       added later is protected by default rather than open by default.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    private final ObjectMapper objectMapper;

    public GatewaySecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Stateless bearer-token API: no cookie is attached automatically,
                // so there is no CSRF vector to defend against.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                .authorizeExchange(exchange -> exchange
                        // Browser preflight carries no credentials by design.
                        .pathMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()

                        // Public: the endpoints that mint credentials. Rate limited
                        // far more aggressively than normal traffic (see the route
                        // definitions) because they are the credential-stuffing target.
                        .pathMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh")
                        .permitAll()

                        // Public: JWKS. Must be unauthenticated, since requiring a
                        // token to fetch the key that validates tokens is circular.
                        // Contains public key material only.
                        .pathMatchers("/.well-known/jwks.json").permitAll()

                        // The client-credentials token endpoint is DENIED at the edge.
                        //
                        // It is a machine-only grant: services reach auth-service
                        // directly on the internal network
                        // (aegis.service-auth.token-uri -> http://auth-service:8081),
                        // never through this gateway. Exposing it publicly would add
                        // nothing and would hand the internet an online guessing
                        // target for client secrets.
                        //
                        // This rule is required because the auth route below matches
                        // /api/auth/**, which would otherwise forward it.
                        .pathMatchers("/api/auth/token").denyAll()

                        // Gateway's own probes and scrape endpoint. Network-restricted
                        // rather than token-protected, because Kubernetes and
                        // Prometheus present no credentials.
                        .pathMatchers("/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()

                        // Aggregated API documentation.
                        .pathMatchers("/v3/api-docs/**", "/docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/webjars/**").permitAll()

                        // Service-to-service surface: never reachable from outside.
                        .pathMatchers("/internal/**").denyAll()

                        // Staff-only at the edge and again in each owning service.
                        .pathMatchers("/api/admin/**", "/api/wallets/admin/**", "/api/fraud/**",
                                "/api/orchestration/**", "/api/investigations/**")
                        .hasAnyAuthority(Roles.ROLE_ADMIN, Roles.ROLE_FRAUD_ANALYST)

                        // Deny by default.
                        .anyExchange().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                // Reuses the platform's claim-to-authority mapping so
                                // the gateway and every service agree on what a role is.
                                new ReactiveJwtAuthenticationConverterAdapter(
                                        new JwtAuthenticationConverter()))))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, denied) -> {
                            exchange.getResponse().getHeaders()
                                    .set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"aegispay\"");
                            return writeError(exchange, ErrorCode.UNAUTHENTICATED);
                        })
                        .accessDeniedHandler((exchange, denied) ->
                                writeError(exchange, ErrorCode.ACCESS_DENIED)))

                .build();
    }

    /**
     * Writes the platform's {@link ApiError} body for an edge rejection.
     *
     * <p>Matters more than it looks: without it a client gets an empty 401/403 from
     * the gateway but a structured JSON error from every service, so error handling
     * in the frontend would need two code paths.
     */
    private Mono<Void> writeError(ServerWebExchange exchange, ErrorCode code) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(code.statusValue()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CorrelationContext.HEADER);

        ApiError body = ApiError.builder(code)
                .path(exchange.getRequest().getPath().value())
                .correlationId(correlationId)
                .build();

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception ex) {
            // Fall back to the bare status code; never leak the serialisation error.
            return response.setComplete();
        }
    }

    /**
     * CORS for the React dashboard, applied once at the edge.
     *
     * <p>Centralising it here means downstream services do not each need their own
     * CORS policy for browser traffic, and there is one place to review which
     * origins can drive authenticated requests.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // An explicit allow-list. A wildcard combined with credentials is rejected
        // by browsers and would be unsafe regardless.
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://*.aegispay.local"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                CorrelationContext.HEADER,
                "Idempotency-Key"));
        // Exposed so the browser can read them: the correlation ID for support, and
        // the rate-limit headers so a client can back off intelligently.
        config.setExposedHeaders(List.of(
                CorrelationContext.HEADER,
                "X-RateLimit-Remaining",
                "X-RateLimit-Burst-Capacity",
                "X-RateLimit-Replenish-Rate",
                "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
