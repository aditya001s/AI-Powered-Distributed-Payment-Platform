package com.aegispay.common.correlation;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Access to the correlation ID of the work currently being performed.
 *
 * <p>A correlation ID follows one logical business operation across every hop:
 * gateway to payment-service, into Kafka as a header, out of Kafka in the
 * orchestrator, on to the fraud service and the ML model. That is what makes it
 * possible to reconstruct a single payment from logs spread over nine services.
 *
 * <p>Storage is SLF4J's {@link MDC}, which means every log line picks the value
 * up automatically via the logging pattern, with no explicit passing. MDC is
 * thread-local, so anything crossing a thread boundary (Kafka listeners, async
 * executors, thread pools) must re-establish it explicitly, which is why
 * {@link #runWith(String, Runnable)} exists.
 */
public final class CorrelationContext {

    /** MDC key and HTTP header name for the business correlation ID. */
    public static final String CORRELATION_ID = "correlationId";
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key for the currently authenticated subject, if any. */
    public static final String USER_ID = "userId";

    private CorrelationContext() {
    }

    /** Returns the current correlation ID, or {@code null} if none is bound. */
    public static String get() {
        return MDC.get(CORRELATION_ID);
    }

    /** Returns the current correlation ID, generating and binding one if absent. */
    public static String getOrCreate() {
        String existing = MDC.get(CORRELATION_ID);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = newId();
        MDC.put(CORRELATION_ID, generated);
        return generated;
    }

    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            MDC.remove(CORRELATION_ID);
        } else {
            MDC.put(CORRELATION_ID, sanitize(correlationId));
        }
    }

    public static void setUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            MDC.remove(USER_ID);
        } else {
            MDC.put(USER_ID, userId);
        }
    }

    public static void clear() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(USER_ID);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Runs {@code action} with {@code correlationId} bound, restoring the previous
     * value afterwards. Use this at every thread boundary (Kafka listeners,
     * scheduled jobs, async tasks) so log continuity is not lost.
     */
    public static void runWith(String correlationId, Runnable action) {
        String previous = MDC.get(CORRELATION_ID);
        try {
            set(correlationId == null || correlationId.isBlank() ? newId() : correlationId);
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(CORRELATION_ID);
            } else {
                MDC.put(CORRELATION_ID, previous);
            }
        }
    }

    /**
     * Strips characters that would let an attacker forge extra log fields or
     * inject newlines into logs (CWE-117), and caps the length. The correlation
     * ID is caller-supplied, so it is untrusted input.
     */
    private static String sanitize(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._\\-]", "");
        if (cleaned.isBlank()) {
            return newId();
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
