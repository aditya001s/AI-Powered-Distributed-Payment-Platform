package com.aegispay.gateway.config;

import com.aegispay.common.correlation.CorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Mints or adopts the correlation ID for every request entering the platform, and
 * forwards it downstream.
 *
 * <p>This is the origin point of the correlation chain. The ID set here is copied
 * into the request forwarded to the target service, picked up there by
 * {@code CorrelationIdFilter}, carried into Kafka headers by {@code EventPublisher},
 * and restored on the consumer side by {@code EventConsumerSupport}. That is what
 * makes a single payment reconstructable across nine services and an async event
 * boundary.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so even requests rejected by
 * security carry an ID the caller can quote in a support ticket.
 *
 * <p><strong>MDC is deliberately not used here.</strong> In a reactive application a
 * request hops between event-loop threads, and MDC is thread-local, so writing to it
 * would attach the ID to whichever thread happened to run that instant and then lose
 * it. Instead the ID is written to the exchange (request-scoped, follows the
 * request) and Micrometer's observation context handles trace propagation. Copying
 * the servlet approach here is a common and hard-to-debug mistake.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

    /** Attribute key for handlers that need the ID without re-reading headers. */
    public static final String ATTRIBUTE = "aegis.correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(CorrelationContext.HEADER);
        String correlationId = sanitise(incoming);

        // Mutate the forwarded request so downstream services see the same ID
        // whether or not the client supplied one.
        ServerWebExchange mutated = exchange.mutate()
                .request(builder -> builder.header(CorrelationContext.HEADER, correlationId))
                .build();

        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        // Echo it back so a client can log the ID it should quote to support.
        mutated.getResponse().getHeaders().set(CorrelationContext.HEADER, correlationId);

        if (log.isDebugEnabled()) {
            log.debug("{} {} [correlationId={}]",
                    mutated.getRequest().getMethod(),
                    mutated.getRequest().getPath().value(),
                    correlationId);
        }

        return chain.filter(mutated);
    }

    /**
     * Accepts a caller-supplied ID only if it is safe, otherwise mints a new one.
     *
     * <p>The header is untrusted input that ends up in log lines across ten
     * services. Stripping everything outside {@code [A-Za-z0-9._-]} and capping the
     * length prevents log injection (CWE-117), where an attacker embeds newlines to
     * forge additional log entries.
     */
    private String sanitise(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String cleaned = incoming.replaceAll("[^A-Za-z0-9._\\-]", "");
        if (cleaned.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
