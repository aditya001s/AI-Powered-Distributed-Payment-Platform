package com.aegispay.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis fast path for idempotent request handling.
 *
 * <p><strong>This is a cache, not the source of truth.</strong> The authoritative
 * record is a uniquely-indexed {@code idempotency_keys} row in the owning
 * service's database, written in the same transaction as the payment. Redis is
 * checked first because it answers a replay in well under a millisecond and keeps
 * duplicate bursts (an impatient user double-tapping "Send", a client library
 * retrying a timed-out request) off the database entirely.
 *
 * <p>Correctness does not depend on Redis being available or consistent. If Redis
 * is empty, down, or has been flushed, the request proceeds and the unique
 * constraint catches the duplicate. That layering is deliberate: the fast path may
 * fail open because a slower, stronger check sits behind it.
 *
 * <p>Three outcomes matter, and conflating them is a common bug:
 * <ul>
 *   <li><strong>New</strong> — first time; proceed.</li>
 *   <li><strong>In flight</strong> — an identical request is executing right now.
 *       Returning 409 and letting the client retry is correct; executing it again
 *       would double-charge.</li>
 *   <li><strong>Completed</strong> — replay of a finished request. Return the
 *       stored response with the original status code. This is what makes a client
 *       retry after a network timeout safe.</li>
 * </ul>
 */
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private static final String KEY_PREFIX = "aegis:idem:";
    private static final String IN_FLIGHT_MARKER = "IN_FLIGHT";
    private static final String FIELD_SEPARATOR = "\u001F"; // ASCII unit separator

    private final StringRedisTemplate redis;

    /** How long a completed response is replayable. Matches the DB row's retention. */
    private final Duration completedTtl;

    /**
     * Safety valve on the in-flight marker. If a process dies mid-request the
     * marker must expire, or the key would be permanently unusable and the client
     * could never retry.
     */
    private final Duration inFlightTtl;

    public IdempotencyGuard(StringRedisTemplate redis) {
        this(redis, Duration.ofHours(24), Duration.ofSeconds(60));
    }

    public IdempotencyGuard(StringRedisTemplate redis, Duration completedTtl, Duration inFlightTtl) {
        this.redis = redis;
        this.completedTtl = completedTtl;
        this.inFlightTtl = inFlightTtl;
    }

    /**
     * Attempts to claim {@code idempotencyKey} for a request whose body hashes to
     * {@code fingerprint}.
     *
     * <p>The claim is a single {@code SET NX PX}, so two concurrent identical
     * requests cannot both be told "new".
     */
    public Reservation reserve(String scope, String idempotencyKey, String fingerprint) {
        String key = key(scope, idempotencyKey);

        Boolean claimed = redis.opsForValue()
                .setIfAbsent(key, IN_FLIGHT_MARKER + FIELD_SEPARATOR + fingerprint, inFlightTtl);

        if (Boolean.TRUE.equals(claimed)) {
            return Reservation.fresh();
        }

        String existing = redis.opsForValue().get(key);
        if (existing == null) {
            // Expired between the SET NX and the GET. Treat as new; the database's
            // unique constraint remains the backstop.
            return Reservation.fresh();
        }

        String[] parts = existing.split(FIELD_SEPARATOR, 3);
        String state = parts[0];
        String storedFingerprint = parts.length > 1 ? parts[1] : null;

        if (!RequestFingerprint.matches(storedFingerprint, fingerprint)) {
            log.warn("Idempotency key reused with a different payload [scope={}]", scope);
            return Reservation.conflict();
        }

        if (IN_FLIGHT_MARKER.equals(state)) {
            return Reservation.inFlight();
        }

        String storedResponse = parts.length > 2 ? parts[2] : null;
        return Reservation.completed(storedResponse);
    }

    /**
     * Stores the final response so later replays of the same key can be answered
     * from cache. Called after the business transaction commits.
     */
    public void complete(String scope, String idempotencyKey, String fingerprint, String responseJson) {
        String key = key(scope, idempotencyKey);
        String value = "COMPLETED" + FIELD_SEPARATOR + fingerprint + FIELD_SEPARATOR
                + (responseJson == null ? "" : responseJson);
        redis.opsForValue().set(key, value, completedTtl);
    }

    /**
     * Drops the reservation because the request failed in a way the client may
     * legitimately retry with the same key (validation error, transient upstream
     * failure). Without this the client would be locked out of its own key until
     * the in-flight TTL expired.
     */
    public void release(String scope, String idempotencyKey) {
        redis.delete(key(scope, idempotencyKey));
    }

    private String key(String scope, String idempotencyKey) {
        return KEY_PREFIX + scope + ":" + idempotencyKey;
    }

    /** The result of attempting to claim an idempotency key. */
    public record Reservation(State state, String cachedResponse) {

        public enum State {
            /** First time this key has been seen; proceed. */
            NEW,
            /** An identical request is executing now; tell the client to retry. */
            IN_FLIGHT,
            /** Already finished; replay the stored response. */
            COMPLETED,
            /** Key reused with a different payload; reject as a client bug. */
            CONFLICT
        }

        static Reservation fresh() {
            return new Reservation(State.NEW, null);
        }

        static Reservation inFlight() {
            return new Reservation(State.IN_FLIGHT, null);
        }

        static Reservation completed(String cachedResponse) {
            return new Reservation(State.COMPLETED, cachedResponse);
        }

        static Reservation conflict() {
            return new Reservation(State.CONFLICT, null);
        }

        public boolean isNew() {
            return state == State.NEW;
        }

        public Optional<String> response() {
            return Optional.ofNullable(cachedResponse).filter(s -> !s.isBlank());
        }
    }
}
