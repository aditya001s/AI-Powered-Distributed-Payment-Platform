package com.aegispay.common.security;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Credentials this service uses to authenticate itself to other services.
 *
 * <p>Read from the environment ({@code INTERNAL_CLIENT_ID} /
 * {@code INTERNAL_CLIENT_SECRET}), never from a checked-in file. In Kubernetes
 * these come from a Secret; in Docker Compose from {@code .env}, which is
 * git-ignored.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.service-auth")
public class ServiceAuthProperties {

    /** Whether this service makes authenticated outbound calls. */
    private boolean enabled = true;

    /** Absolute URL of the token endpoint, e.g. {@code http://auth-service:8081/api/auth/token}. */
    private String tokenUri = "http://auth-service:8081/api/auth/token";

    private String clientId = "aegis-internal";

    /** Injected from the environment. A blank value fails validation when service auth is enabled. */
    private String clientSecret = "";

    /**
     * Refresh this long before actual expiry. Prevents a token that was valid when
     * the request left from being expired by the time it is validated, which is a
     * real failure mode under clock skew and network delay.
     */
    private Duration refreshSkew = Duration.ofSeconds(60);

    /** Timeout for the token request itself. */
    private Duration requestTimeout = Duration.ofSeconds(5);

    @AssertTrue(message = "token-uri must not be blank when service auth is enabled")
    public boolean isTokenUriValid() {
        return !enabled || hasText(tokenUri);
    }

    @AssertTrue(message = "client-id must not be blank when service auth is enabled")
    public boolean isClientIdValid() {
        return !enabled || hasText(clientId);
    }

    @AssertTrue(message = "client-secret must not be blank when service auth is enabled")
    public boolean isClientSecretValid() {
        return !enabled || hasText(clientSecret);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTokenUri() {
        return tokenUri;
    }

    public void setTokenUri(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public Duration getRefreshSkew() {
        return refreshSkew;
    }

    public void setRefreshSkew(Duration refreshSkew) {
        this.refreshSkew = refreshSkew;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
