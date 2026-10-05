package com.aegispay.common.security;

import com.aegispay.common.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds the {@link RestClient} used for all service-to-service HTTP calls.
 *
 * <p>Three cross-cutting concerns are applied by interceptor so no call site can
 * omit them:
 *
 * <ol>
 *   <li><strong>Authorization</strong>: a fresh client-credentials bearer token.</li>
 *   <li><strong>Correlation</strong>: the caller's correlation ID, so the callee's
 *       logs join the same trace.</li>
 *   <li><strong>Timeouts</strong>: bounded connect and read timeouts. An unbounded
 *       read timeout is how one slow dependency exhausts a caller's thread pool and
 *       turns a single degraded service into a platform-wide outage.</li>
 * </ol>
 *
 * <p>Exposed as {@code internalRestClient}. Callers inject it by name; the
 * unqualified {@code RestClient.Builder} remains available for outbound calls to
 * third parties that must <em>not</em> receive our internal token.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class InternalClientConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalClientConfig.class);

    /** Deliberately short: an internal call that takes 10s has already failed the user. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "aegis.service-auth", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ServiceTokenProvider serviceTokenProvider(RestClient.Builder builder,
                                                     ServiceAuthProperties properties) {
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            throw new IllegalStateException(
                    "aegis.service-auth.client-secret is not set. Provide INTERNAL_CLIENT_SECRET "
                            + "via the environment; service-to-service calls cannot be authenticated without it.");
        }
        return new ServiceTokenProvider(builder, properties);
    }

    /**
     * The client used for every internal call.
     *
     * <p>{@link ObjectProvider} rather than a direct {@code ServiceTokenProvider}
     * parameter, because {@link #serviceTokenProvider} is conditional on
     * {@code aegis.service-auth.enabled}. Injecting it directly makes this bean
     * <em>unconstructable</em> whenever service auth is switched off, and the failure is an
     * opaque "no qualifying bean of type ServiceTokenProvider" during context startup —
     * from a service that may never make an internal call at all. auth-service is exactly
     * that case: it <em>issues</em> tokens and never obtains one, so it legitimately runs
     * with service auth disabled.
     *
     * <p>With no token provider the client still exists and still propagates the
     * correlation ID, but sends no {@code Authorization} header. That degrades
     * <strong>closed</strong>, not open: {@code /internal/**} requires
     * {@code ROLE_SERVICE} plus {@code SCOPE_internal}, so an unauthenticated internal
     * call is refused by the callee and surfaces as
     * {@code UpstreamUnavailableException}. A misconfiguration therefore produces a
     * clear runtime rejection instead of a silently-unauthenticated request, and the
     * warning below names the property to change.
     */
    @Bean("internalRestClient")
    @ConditionalOnMissingBean(name = "internalRestClient")
    public RestClient internalRestClient(RestClient.Builder builder,
                                         ObjectProvider<ServiceTokenProvider> tokenProviders) {
        ServiceTokenProvider tokenProvider = tokenProviders.getIfAvailable();
        if (tokenProvider == null) {
            log.warn("""
                    aegis.service-auth.enabled is false, so internal HTTP calls will carry no \
                    bearer token. Any call to an /internal/** endpoint will be rejected with \
                    401/403. This is expected for a service that issues tokens rather than \
                    consuming them; set aegis.service-auth.enabled=true if this service does \
                    make internal calls.""");
        }

        return builder
                .requestFactory(timeoutAwareRequestFactory())
                .requestInterceptor((request, body, execution) -> {
                    if (tokenProvider != null) {
                        request.getHeaders().setBearerAuth(tokenProvider.getAccessToken());
                    }

                    String correlationId = CorrelationContext.get();
                    if (correlationId != null) {
                        request.getHeaders().add(CorrelationContext.HEADER, correlationId);
                    }
                    request.getHeaders().add(HttpHeaders.ACCEPT, "application/json");

                    var response = execution.execute(request, body);

                    // A 401 means our cached token is stale (auth-service restarted
                    // with a new signing key, or the token was revoked). Drop it so
                    // the next attempt mints a fresh one.
                    if (response.getStatusCode().value() == 401 && tokenProvider != null) {
                        log.warn("Internal call to {} returned 401; invalidating cached service token",
                                request.getURI());
                        tokenProvider.invalidate();
                    }
                    return response;
                })
                .build();
    }

    /**
     * A {@code RestClient} with no auth interceptor, for calls that must not carry
     * our internal credentials: the ML scoring service and external LLM APIs.
     * Sending an internal bearer token to a third party would leak it.
     */
    @Bean("externalRestClient")
    @ConditionalOnMissingBean(name = "externalRestClient")
    public RestClient externalRestClient(RestClient.Builder builder) {
        return builder
                .requestFactory(timeoutAwareRequestFactory())
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = CorrelationContext.get();
                    if (correlationId != null) {
                        request.getHeaders().add(CorrelationContext.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * A request factory with explicit, bounded timeouts.
     *
     * <p>{@code detect()} picks the best available client implementation on the
     * classpath (Apache HttpClient, Jetty, or the JDK client) rather than pinning
     * one, so a service can swap its HTTP stack without touching this code.
     */
    private ClientHttpRequestFactory timeoutAwareRequestFactory() {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT);
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }
}
