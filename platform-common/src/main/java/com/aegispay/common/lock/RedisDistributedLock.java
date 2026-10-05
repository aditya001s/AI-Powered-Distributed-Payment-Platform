package com.aegispay.common.lock;

import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Redis-backed {@link DistributedLock}.
 *
 * <p><strong>Acquire</strong> is a single {@code SET key token NX PX ttl}. One
 * atomic command, so two contenders cannot both succeed. The value is a random
 * per-holder token, not a constant.
 *
 * <p><strong>Release</strong> must be a compare-and-delete, which Redis has no
 * single command for, so it runs as a Lua script (scripts execute atomically):
 *
 * <pre>
 *   if redis.call('GET', key) == token then return redis.call('DEL', key) else return 0 end
 * </pre>
 *
 * A plain {@code DEL} would be a real bug: if holder A stalls long enough for its
 * lease to expire and holder B acquires the lock, A's {@code DEL} on wake-up would
 * delete <em>B's</em> lease and let a third holder in while B is still working.
 * Comparing the token first makes that impossible.
 *
 * <p><strong>Waiting</strong> is bounded polling with jittered backoff. Jitter
 * matters under contention: without it, every waiter retries on the same cadence
 * and they collide repeatedly (the thundering-herd problem). Blocking Redis
 * primitives were avoided deliberately, since holding a connection per waiter
 * scales badly.
 *
 * <p>See {@link DistributedLock} for why this is an optimisation rather than the
 * source of financial correctness.
 */
public class RedisDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    private static final String KEY_PREFIX = "aegis:lock:";

    /** Compare-and-delete: only the token owner may release. */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    /** Compare-and-expire: only the token owner may extend. */
    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final Duration MIN_RETRY_DELAY = Duration.ofMillis(15);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMillis(200);

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;

    public RedisDistributedLock(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public <T> T executeLocked(String resourceKey, Duration leaseTime, Duration waitTime, Supplier<T> action) {
        long startNanos = System.nanoTime();
        try (LockHandle handle = acquire(resourceKey, leaseTime, waitTime)) {
            return action.get();
        } finally {
            meterRegistry.timer(MetricNames.WALLET_LOCK_HELD, "resource", resourceType(resourceKey))
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    @Override
    public <T> T executeLockedOrdered(String firstKey, String secondKey,
                                      Duration leaseTime, Duration waitTime, Supplier<T> action) {
        if (firstKey.equals(secondKey)) {
            return executeLocked(firstKey, leaseTime, waitTime, action);
        }
        // Deterministic global ordering is what prevents A->B / B->A deadlock.
        String lower = firstKey.compareTo(secondKey) <= 0 ? firstKey : secondKey;
        String higher = firstKey.compareTo(secondKey) <= 0 ? secondKey : firstKey;

        return executeLocked(lower, leaseTime, waitTime,
                () -> executeLocked(higher, leaseTime, waitTime, action));
    }

    @Override
    public LockHandle acquire(String resourceKey, Duration leaseTime, Duration waitTime) {
        String key = KEY_PREFIX + resourceKey;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitTime.toNanos();

        int attempt = 0;
        while (true) {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, leaseTime);
            if (Boolean.TRUE.equals(acquired)) {
                log.trace("Acquired lock {} with token {}", resourceKey, token);
                return new RedisLockHandle(resourceKey, key, token);
            }

            if (System.nanoTime() >= deadline) {
                meterRegistry.counter(MetricNames.WALLET_LOCK_TIMEOUT,
                        "resource", resourceType(resourceKey)).increment();
                log.warn("Lock acquisition timed out for {} after {}ms ({} attempts)",
                        resourceKey, waitTime.toMillis(), attempt);
                throw new LockAcquisitionException(resourceKey, waitTime);
            }

            sleepWithJitter(++attempt);
        }
    }

    /**
     * Exponential backoff with full jitter, capped. The random component
     * de-synchronises competing waiters so they stop colliding on every retry.
     */
    private void sleepWithJitter(int attempt) {
        long exponentialMs = Math.min(
                MIN_RETRY_DELAY.toMillis() * (1L << Math.min(attempt, 5)),
                MAX_RETRY_DELAY.toMillis());
        long jitteredMs = ThreadLocalRandom.current().nextLong(MIN_RETRY_DELAY.toMillis(), exponentialMs + 1);
        try {
            Thread.sleep(jitteredMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("interrupted:" + attempt, Duration.ZERO);
        }
    }

    /** Low-cardinality metric tag: {@code wallet}, not {@code wallet:WAL-000123}. */
    private String resourceType(String resourceKey) {
        int colon = resourceKey.indexOf(':');
        return colon > 0 ? resourceKey.substring(0, colon) : resourceKey;
    }

    private final class RedisLockHandle implements LockHandle {

        private final String resourceKey;
        private final String redisKey;
        private final String token;
        private volatile boolean released;

        private RedisLockHandle(String resourceKey, String redisKey, String token) {
            this.resourceKey = resourceKey;
            this.redisKey = redisKey;
            this.token = token;
        }

        @Override
        public String resourceKey() {
            return resourceKey;
        }

        @Override
        public String fencingToken() {
            return token;
        }

        @Override
        public boolean renew(Duration extension) {
            if (released) {
                return false;
            }
            Long result = redis.execute(RENEW_SCRIPT, List.of(redisKey),
                    token, String.valueOf(extension.toMillis()));
            return result != null && result == 1L;
        }

        @Override
        public boolean isHeld() {
            if (released) {
                return false;
            }
            return token.equals(redis.opsForValue().get(redisKey));
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            try {
                Long deleted = redis.execute(RELEASE_SCRIPT, List.of(redisKey), token);
                if (deleted == null || deleted == 0L) {
                    // Our lease had already expired and possibly been re-acquired.
                    // Nothing was deleted, which is the safe outcome, but it means
                    // the critical section outlived its lease: worth investigating.
                    log.warn("Lock {} was already lost before release (lease expired "
                            + "while the critical section was still running)", resourceKey);
                }
            } catch (RuntimeException ex) {
                // Never propagate from close(): the lease expires on its own, and
                // masking the caller's real exception would be worse.
                log.error("Failed to release lock {}; it will expire naturally", resourceKey, ex);
            }
        }
    }

    /** Diagnostic helper used by health checks; not part of the locking protocol. */
    public boolean isLocked(String resourceKey) {
        return Boolean.TRUE.equals(redis.execute(
                (RedisCallback<Boolean>) connection ->
                        connection.keyCommands().exists((KEY_PREFIX + resourceKey).getBytes())));
    }
}
