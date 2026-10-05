package com.aegispay.auth.config;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.security.JwtAuthenticationConverter;
import com.aegispay.common.security.Roles;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Security rules for auth-service.
 *
 * <p>The filter chain is deny-by-default: {@code anyRequest().authenticated()} is
 * the last rule, so a newly added endpoint is protected until someone
 * deliberately opens it. The reverse ordering (permit-by-default with an explicit
 * deny list) fails open every time a developer forgets a line.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity   // enables @PreAuthorize on service methods
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // ---- CSRF ----
                // Disabled because this is a stateless bearer-token API with no
                // cookie-based session. CSRF exploits the browser's automatic
                // cookie attachment; an Authorization header is never sent
                // automatically, so there is nothing to forge. Re-enable the moment
                // any cookie-based auth is introduced.
                .csrf(csrf -> csrf.disable())

                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // No server-side session: every request carries its own proof.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // --- Public: the endpoints that mint credentials ---
                        // Rate-limited at the gateway with a much stricter bucket
                        // than normal traffic (credential stuffing defence).
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/token")
                        .permitAll()

                        // --- Public: JWKS ---
                        // Must be reachable unauthenticated: every other service
                        // fetches it to validate tokens, and requiring a token to
                        // fetch the key needed to validate tokens is circular.
                        // Contains only public key material.
                        .requestMatchers("/.well-known/jwks.json", "/oauth2/jwks").permitAll()

                        // --- Probes and scraping ---
                        // Kubernetes probes and Prometheus present no credentials.
                        // These are protected at the network layer instead: the
                        // management port is not exposed by the Ingress and the
                        // NetworkPolicy only admits the monitoring namespace.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()

                        // --- API documentation ---
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // --- Service-to-service only ---
                        .requestMatchers("/internal/**")
                        .hasAuthority(Roles.ROLE_SERVICE)

                        // --- Administration ---
                        .requestMatchers("/api/admin/**")
                        .hasAnyAuthority(Roles.ROLE_ADMIN, Roles.ROLE_FRAUD_ANALYST)

                        // --- Everything else needs a valid token ---
                        .anyRequest().authenticated())

                // Validate bearer tokens with the locally-held public key.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthenticationConverter())))

                // Return our JSON error shape instead of an empty 401/403 body.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(this::writeUnauthorized)
                        .accessDeniedHandler((request, response, denied) ->
                                writeError(response, ErrorCode.ACCESS_DENIED, request.getRequestURI())))

                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        // HSTS is terminated at the ingress in production; harmless here.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)));

        return http.build();
    }

    private void writeUnauthorized(jakarta.servlet.http.HttpServletRequest request,
                                   HttpServletResponse response,
                                   org.springframework.security.core.AuthenticationException ex) {
        // WWW-Authenticate tells a compliant client how to authenticate, without
        // revealing why this particular token was rejected.
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
            // The response is already committed or the client disconnected; the
            // status code alone still conveys the outcome.
        }
    }

    /**
     * Password hashing.
     *
     * <p>{@link DelegatingPasswordEncoder} stores the algorithm as a prefix
     * ({@code {bcrypt}$2a$12$...}). That is what makes an algorithm migration
     * possible later without invalidating existing passwords: old hashes keep
     * verifying with their original algorithm while new ones use the current
     * default. A bare {@code BCryptPasswordEncoder} would leave no room to ever
     * change algorithm.
     *
     * <p>BCrypt (not SHA-256) because it is deliberately slow and salted per
     * password, which is what defeats offline brute force and rainbow tables.
     */
    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties properties) {
        int strength = properties.getPassword().getBcryptStrength();

        @SuppressWarnings("deprecation")
        Map<String, PasswordEncoder> encoders = Map.of(
                "bcrypt", new BCryptPasswordEncoder(strength),
                // Kept so hashes written by an older deployment still verify.
                "noop", org.springframework.security.crypto.password.NoOpPasswordEncoder.getInstance());

        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("bcrypt", encoders);
        // Any stored hash with no {id} prefix is treated as bcrypt, not as plaintext.
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder(strength));
        return delegating;
    }

    /**
     * CORS for the React dashboard.
     *
     * <p>Origins are an explicit allow-list. A wildcard origin combined with
     * credentials is rejected by browsers anyway, and would be unsafe here.
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
