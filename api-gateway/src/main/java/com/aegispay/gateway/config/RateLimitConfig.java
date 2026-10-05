package com.aegispay.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate-limit key resolution.
 *
 * <p>The bucket itself is a Redis token bucket configured per route in
 * {@code application.yml}. Redis rather than in-memory because the limit has to be
 * shared: with three gateway replicas and a per-instance counter, a client's real
 * allowance would be three times the configured value, and would change every time
 * the deployment scaled.
 *
 * <p>Two resolvers, because the right key depends on whether the caller is known:
 *
 * <ul>
 *   <li><strong>Authenticated traffic</strong> is keyed by user ID. Keying it by IP
 *       would make everyone behind one corporate NAT or mobile carrier gateway share
 *       a single bucket, so one heavy user would throttle an entire office.</li>
 *   <li><strong>Login and registration</strong> are keyed by IP, because there is no
 *       authenticated identity yet. This is the bucket that matters for credential
 *       stuffing, and it is configured far tighter than the general one.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * Keys authenticated requests by user ID, falling back to IP for anonymous ones.
     *
     * <p>The user ID is read from the validated token's {@code sub} claim, never from
     * a header or query parameter. A client-supplied identity here would let anyone
     * bypass their limit by sending a different value each request.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    /**
     * Keys strictly by client IP. Used for the unauthenticated auth endpoints.
     */
    @Bean("ipKeyResolver")
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + clientIp(exchange));
    }

    /**
     * Resolves the client IP.
     *
     * <p>{@code X-Forwarded-For} is honoured because in a container or Kubernetes
     * deployment the socket address is the load balancer's, not the client's. Only
     * the first entry is taken, and this is safe <em>only</em> because the ingress
     * overwrites the header on the way in so a client-supplied value never survives.
     * Trusting this header on a directly-exposed service would let any caller forge
     * their IP and defeat the rate limit entirely.
     */
    private String clientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String candidate = forwardedFor.split(",")[0].trim();
            if (isPlausibleIp(candidate)) {
                return candidate;
            }
            log.debug("Ignoring malformed X-Forwarded-For value");
        }
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        // No resolvable address: fall back to a shared bucket rather than skipping
        // the limit. Failing closed is the right default for a throttle.
        return "unknown";
    }

    /**
     * Cheap shape validation. Not full IP parsing: the goal is to reject a value
     * that would corrupt a Redis key, not to prove the address is routable.
     */
    private boolean isPlausibleIp(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 45
                && value.matches("^[0-9a-fA-F:.]+$");
    }
}
