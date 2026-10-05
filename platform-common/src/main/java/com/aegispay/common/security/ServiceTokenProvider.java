package com.aegispay.common.security;

import com.aegispay.common.exception.DomainExceptions.UpstreamUnavailableException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtains and caches the access token this service presents to other services.
 *
 * <p>Uses the OAuth2 <strong>client-credentials</strong> grant: the caller is the
 * service itself, not a user, so there is no user to redirect and no refresh
 * token. The issued token carries {@code ROLE_SERVICE} and
 * {@code scope=internal}, which is what {@code /internal/**} endpoints require.
 *
 * <p><strong>Why not a shared static API key.</strong> A static key never expires,
 * is identical in every service, and cannot be attributed to a caller after the
 * fact. A short-lived signed token limits the blast radius of a leak to its TTL,
 * names the calling client in the {@code sub} claim so every internal call is
 * attributable in audit logs, and is validated with exactly the same JWKS
 * machinery as user tokens rather than a second bespoke code path.
 *
 * <p>Caching matters: minting a token per outbound call would put the auth service
 * on the critical path of every payment. The token is reused until shortly before
 * expiry.
 *
 * <p>Thread safety: a {@link ReentrantLock} serialises refresh so a burst of
 * concurrent requests triggers one token fetch, not fifty. Readers see the cached
 * token through a volatile field with no locking at all.
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    private final RestClient restClient;
    private final ServiceAuthProperties properties;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile CachedToken cached;

    public ServiceTokenProvider(RestClient.Builder restClientBuilder, ServiceAuthProperties properties) {
        this.properties = properties;
        // A plain builder: no interceptors, or fetching a token would recurse.
        this.restClient = restClientBuilder.build();
    }

    /** A valid bearer token, fetching or refreshing only when necessary. */
    public String getAccessToken() {
        CachedToken current = cached;
        if (current != null && current.isUsable(properties.getRefreshSkew().getSeconds())) {
            return current.token();
        }

        refreshLock.lock();
        try {
            // Re-check: another thread may have refreshed while we waited.
            current = cached;
            if (current != null && current.isUsable(properties.getRefreshSkew().getSeconds())) {
                return current.token();
            }
            CachedToken fresh = fetchToken();
            cached = fresh;
            return fresh.token();
        } finally {
            refreshLock.unlock();
        }
    }

    /** Drops the cached token, forcing a refetch. Called after a 401 from a peer. */
    public void invalidate() {
        cached = null;
    }

    private CachedToken fetchToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("scope", Roles.SCOPE_INTERNAL);

        try {
            TokenResponse response = restClient.post()
                    .uri(properties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new UpstreamUnavailableException("auth-service",
                        "token endpoint returned an empty access token");
            }

            log.debug("Obtained service access token for client {} (expires in {}s)",
                    properties.getClientId(), response.expiresIn());

            return new CachedToken(response.accessToken(),
                    Instant.now().plusSeconds(response.expiresIn()));

        } catch (UpstreamUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Never log the client secret or the token itself.
            throw new UpstreamUnavailableException("auth-service",
                    "could not obtain a service access token", ex);
        }
    }

    /** The subset of the OAuth2 token response we need. */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("scope") String scope) {
    }

    /** A token with its computed expiry. */
    private record CachedToken(String token, Instant expiresAt) {

        /** True while the token remains valid for at least {@code skewSeconds} more. */
        boolean isUsable(long skewSeconds) {
            return Instant.now().isBefore(expiresAt.minusSeconds(skewSeconds));
        }
    }
}
