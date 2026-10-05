package com.aegispay.common.kafka;

import java.time.Duration;

/**
 * Best-effort cache of events a consumer has already committed successfully.
 *
 * <p>The marker is deliberately written <em>after</em> handler completion. Writing it before
 * the handler creates an event-loss window: a process can die after the external marker is
 * committed but before business state commits, then suppress Kafka's redelivery. Concurrent
 * deliveries may therefore both reach a handler; durable database idempotency remains the
 * correctness boundary and this registry only avoids ordinary later replays.
 */
public interface ProcessedEventRegistry {

    /** How long a successful marker is retained. Comfortably longer than any retry window. */
    Duration DEFAULT_TTL = Duration.ofHours(24);

    /** Returns whether this consumer has previously marked the event successful. */
    boolean isProcessed(String consumerName, String eventId);

    /** Records successful handler completion for later replay suppression. */
    void markProcessed(String consumerName, String eventId, Duration ttl);

    default void markProcessed(String consumerName, String eventId) {
        markProcessed(consumerName, eventId, DEFAULT_TTL);
    }
}
