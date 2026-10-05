package com.aegispay.common.idempotency;

import com.aegispay.common.exception.DomainExceptions.IdempotencyConflictException;
import com.aegispay.common.exception.DomainExceptions.IdempotentRequestInFlightException;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per idempotency key.
 *
 * <p>Two layers, deliberately:
 *
 * <ol>
 *   <li><strong>Redis</strong> answers a replay in microseconds and absorbs duplicate
 *       bursts — an impatient user double-tapping "Send", or a client library retrying
 *       a request whose response was lost. It keeps that traffic off the database
 *       entirely.</li>
 *   <li><strong>PostgreSQL</strong> is the guarantee. The composite primary key on
 *       {@code idempotency_keys} is what actually makes a second execution impossible.</li>
 * </ol>
 *
 * <p>The layering is what allows Redis to <strong>fail open</strong>. If it is down,
 * empty or has been flushed, this class proceeds straight to the database and
 * correctness is unaffected — only the fast path is lost. A design where Redis were the
 * sole guard would silently start double-charging during a cache outage.
 *
 * <p>Not transactional itself. {@link IdempotencyStore} owns short {@code REQUIRES_NEW}
 * transactions so the claim is committed and visible before the guarded operation runs;
 * a claim invisible to concurrent transactions would let two identical requests both
 * proceed.
 *
 * <p>Usage:
 * <pre>{@code
 * return idempotency.execute(
 *         "payment.create",
 *         idempotencyKey,
 *         RequestFingerprint.of(userId, sourceWallet, destWallet, amountMinor, currency),
 *         PaymentResponse.class,
 *         () -> reallyCreateThePayment(request));
 * }</pre>
 */
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * How long an in-progress claim may sit before it is treated as abandoned.
     *
     * <p>Must exceed the slowest guarded operation. Too short and a legitimately slow
     * payment gets its key stolen by the client's own retry; too long and a client
     * whose request died is locked out of its key for that whole window.
     */
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** Absent when the service runs without Redis; the database path still applies. */
    private final Optional<IdempotencyGuard> cache;

    public IdempotencyService(IdempotencyStore store,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry,
                              Optional<IdempotencyGuard> cache) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.cache = cache;
        if (cache.isEmpty()) {
            log.warn("Redis is not configured: idempotency will use the database only. "
                    + "Correctness is unaffected; the fast path is not available.");
        }
    }

    /**
     * Executes {@code action} unless this key has already been used.
     *
     * @param scope        namespaces the key per operation, so one client-generated key
     *                     cannot collide across different endpoints
     * @param key          the client-supplied idempotency key
     * @param fingerprint  hash of the request's business fields, from
     *                     {@link RequestFingerprint}
     * @param responseType type used to deserialise a replayed response
     * @param action       the operation. Must be side-effect-free until it succeeds.
     * @throws IdempotencyConflictException           the key was reused with a different payload
     * @throws IdempotentRequestInFlightException     an identical request is executing now
     */
    public <T> T execute(String scope, String key, String fingerprint,
                         Class<T> responseType, Supplier<T> action) {

        // ---- Layer 1: Redis fast path ----
        Optional<T> cached = checkCache(scope, key, fingerprint, responseType);
        if (cached.isPresent()) {
            meterRegistry.counter(MetricNames.PAYMENT_IDEMPOTENT_REPLAY,
                    "scope", scope, "layer", "cache").increment();
            return cached.get();
        }

        // ---- Layer 2: the database, which is the actual guarantee ----
        IdempotencyStore.BeginResult begin = store.begin(
                scope, key, fingerprint, IdempotencyRecord.DEFAULT_RETENTION, STALE_AFTER);

        switch (begin.outcome()) {
            case CONFLICT -> throw new IdempotencyConflictException(key);
            case IN_FLIGHT -> throw new IdempotentRequestInFlightException(key);
            case REPLAY -> {
                meterRegistry.counter(MetricNames.PAYMENT_IDEMPOTENT_REPLAY,
                        "scope", scope, "layer", "database").increment();
                T replayed = deserialise(begin.responseBody(), responseType);
                // Warm the cache so the next replay is answered without a query.
                cache.ifPresent(guard -> guard.complete(scope, key, fingerprint, begin.responseBody()));
                return replayed;
            }
            case PROCEED -> {
                // fall through to execution
            }
        }

        // ---- Run the operation ----
        T result;
        try {
            result = action.get();
        } catch (RuntimeException ex) {
            // Release the claim so a retry with the same key is possible. Committed in
            // its own transaction, so it survives the rethrow below — the same
            // requirement that applies to the auth service's failed-login counter.
            store.fail(scope, key);
            cache.ifPresent(guard -> guard.release(scope, key));
            throw ex;
        }

        // ---- Record the outcome for future replays ----
        String responseBody = serialise(result);
        store.complete(scope, key, responseBody, 200, null);
        cache.ifPresent(guard -> guard.complete(scope, key, fingerprint, responseBody));

        return result;
    }

    /**
     * Consults Redis.
     *
     * <p>Returns a value only for a definite cached completion. Every other signal —
     * including a cache error — falls through to the database, because the database is
     * the authority and the cache must never be able to turn a correct outcome into an
     * incorrect one.
     */
    private <T> Optional<T> checkCache(String scope, String key, String fingerprint,
                                       Class<T> responseType) {
        if (cache.isEmpty()) {
            return Optional.empty();
        }
        try {
            IdempotencyGuard.Reservation reservation = cache.get().reserve(scope, key, fingerprint);
            return switch (reservation.state()) {
                case CONFLICT -> throw new IdempotencyConflictException(key);
                case IN_FLIGHT -> throw new IdempotentRequestInFlightException(key);
                case COMPLETED -> reservation.response().map(body -> deserialise(body, responseType));
                case NEW -> Optional.empty();
            };
        } catch (IdempotencyConflictException | IdempotentRequestInFlightException ex) {
            // Genuine idempotency verdicts from the cache: propagate.
            throw ex;
        } catch (RuntimeException ex) {
            // Redis unreachable or misbehaving. Degrade to the database rather than
            // failing the request: the guarantee does not depend on the cache.
            log.warn("Idempotency cache unavailable, falling back to the database: {}",
                    ex.getMessage());
            return Optional.empty();
        }
    }

    private String serialise(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            // The operation already succeeded, so failing the request now would be
            // worse than losing replayability for this one key.
            log.error("Could not serialise the idempotent response; replay will be unavailable "
                    + "for this key", ex);
            return null;
        }
    }

    private <T> T deserialise(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (JacksonException ex) {
            // A stored response we cannot read is unusable. Failing loudly is correct:
            // silently re-executing would defeat the guarantee.
            throw new IllegalStateException(
                    "Stored idempotent response could not be deserialised into "
                            + type.getSimpleName(), ex);
        }
    }
}
