package com.aegispay.common.security;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.correlation.CorrelationContext;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The standard resource-server security chain shared by every downstream service.
 *
 * <p>Eight of the ten services need exactly the same rules: validate a bearer token
 * against the auth service's JWKS, restrict {@code /internal/**} to machine callers,
 * restrict {@code /api/admin/**} to staff, allow probes and API docs, and require
 * authentication for everything else. Copying that into eight files guarantees the
 * eight copies drift, and a drifted security rule is a vulnerability. So it lives
 * here once.
 *
 * <p><strong>Opt-in via {@code aegis.security.standard-chain.enabled=true}.</strong>
 * This class sits in a package that services component-scan, so it needs a
 * condition rather than being unconditionally active: auth-service and api-gateway
 * define their own chains (one issues tokens and must expose public login
 * endpoints, the other is reactive WebFlux) and must not inherit this one.
 *
 * <p>The chain is <strong>deny-by-default</strong>: {@code anyRequest().authenticated()}
 * is the final rule, so a newly added endpoint is protected until someone
 * deliberately opens it. A permit-by-default chain with an explicit deny list fails
 * open every time a developer forgets a line.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ConditionalOnProperty(prefix = "aegis.security.standard-chain", name = "enabled",
        havingValue = "true")
public class StandardSecurityConfig {

    private final ObjectMapper objectMapper;

    public StandardSecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain standardFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless bearer-token API with no cookie-based session. CSRF
                // exploits the browser's automatic cookie attachment; an
                // Authorization header is never sent automatically, so there is
                // nothing to forge. Must be re-enabled if cookie auth is ever added.
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Kubernetes probes and the Prometheus scraper present no
                        // credentials. These are protected at the network layer
                        // instead: the management endpoints are not published by the
                        // Ingress, and a NetworkPolicy admits only the monitoring
                        // namespace.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()

                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()

                        // Machine-to-machine only. Requires both the role and the
                        // scope, so a user token can never reach an internal endpoint
                        // even if it somehow carried the role.
                        .requestMatchers("/internal/**")
                        .access((authentication, context) -> {
                            var auth2 = authentication.get();
                            boolean allowed = auth2 != null && auth2.isAuthenticated()
                                    && auth2.getAuthorities().stream().anyMatch(a ->
                                            Roles.ROLE_SERVICE.equals(a.getAuthority()))
                                    && auth2.getAuthorities().stream().anyMatch(a ->
                                            ("SCOPE_" + Roles.SCOPE_INTERNAL).equals(a.getAuthority()));
                            return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                        })

                        .requestMatchers("/api/admin/**")
                        .hasAnyAuthority(Roles.ROLE_ADMIN, Roles.ROLE_FRAUD_ANALYST)

                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthenticationConverter())))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler((request, response, denied) ->
                                writeError(response, ErrorCode.ACCESS_DENIED, request.getRequestURI())))

                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)));

        return http.build();
    }

    private void writeUnauthorized(HttpServletRequest request,
                                   HttpServletResponse response,
                                   org.springframework.security.core.AuthenticationException ex) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"aegispay\"");
        writeError(response, ErrorCode.UNAUTHENTICATED, request.getRequestURI());
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String path) {
        try {
            response.setStatus(code.statusValue());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError body = ApiError.builder(code)
                    .path(path)
                    .correlationId(CorrelationContext.get())
                    .build();
            objectMapper.writeValue(response.getOutputStream(), body);
        } catch (Exception ignored) {
            // Response already committed or client gone; the status code still conveys it.
        }
    }

    /**
     * CORS for the React dashboard.
     *
     * <p>An explicit origin allow-list rather than a wildcard. Browsers reject
     * {@code *} combined with credentials anyway, and a permissive pattern here
     * would let any site drive authenticated requests on a user's behalf.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
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
        config.setExposedHeaders(List.of(CorrelationContext.HEADER));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
