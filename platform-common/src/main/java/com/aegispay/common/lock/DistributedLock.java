package com.aegispay.common.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * A mutually-exclusive lease over a named resource, shared across all instances
 * of all services.
 *
 * <p><strong>What this is for, and what it is not.</strong> Correctness of wallet
 * balances is guaranteed by the database: the balance update runs
 * {@code SELECT ... FOR UPDATE} inside a serialised transaction, so even with no
 * distributed lock at all the books stay right. This lock sits <em>in front</em> of
 * that to reduce lock contention and deadlock retries when many requests hit the
 * same wallet, and to serialise multi-step operations that span more than one
 * transaction (reserve, then call a provider, then capture).
 *
 * <p>That distinction matters because a Redis lock is <em>not</em> a safety
 * primitive on its own. Redis replication is asynchronous, so a failover can
 * hand the same lease to two holders; process pauses (GC, container throttling)
 * can let a lease expire while its holder still believes it is held. Any design
 * that relies on a Redis lock for financial correctness is broken. Here the lock
 * is an optimisation and the database is the arbiter, which is a safe combination.
 *
 * <p>Always time-bounded. A lease that cannot expire turns one crashed pod into a
 * permanently frozen wallet.
 */
public interface DistributedLock {

    /**
     * Acquires the lease, runs {@code action}, and always releases.
     *
     * @param resourceKey logical resource, e.g. {@code wallet:WAL-123}
     * @param leaseTime   how long the lease survives without renewal; must exceed
     *                    the expected critical-section duration
     * @param waitTime    how long to wait for the lease before giving up
     * @throws LockAcquisitionException if the lease could not be taken in time
     */
    <T> T executeLocked(String resourceKey, Duration leaseTime, Duration waitTime, Supplier<T> action);

    default void executeLocked(String resourceKey, Duration leaseTime, Duration waitTime, Runnable action) {
        executeLocked(resourceKey, leaseTime, waitTime, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Locks two resources in a globally consistent order.
     *
     * <p>Required for wallet-to-wallet transfers. If request 1 locks A then B while
     * request 2 locks B then A, they deadlock. Sorting the keys means every caller
     * agrees on the order, which makes that cycle impossible.
     */
    <T> T executeLockedOrdered(String firstKey, String secondKey,
                               Duration leaseTime, Duration waitTime, Supplier<T> action);

    /**
     * Manual acquisition for callers that cannot express their work as a single
     * callback. Prefer {@link #executeLocked}; if you use this, release in a
     * {@code finally} block or use the handle as an {@link AutoCloseable}.
     */
    LockHandle acquire(String resourceKey, Duration leaseTime, Duration waitTime);

    /** A held lease. Closing it releases the lease. */
    interface LockHandle extends AutoCloseable {

        String resourceKey();

        /**
         * The unique token proving this holder owns the lease. Release compares it,
         * so a holder whose lease already expired cannot release the lease a
         * different holder now owns.
         */
        String fencingToken();

        /** Extends the lease. Returns false if it was already lost. */
        boolean renew(Duration extension);

        /** True if this holder still owns the lease. */
        boolean isHeld();

        @Override
        void close();
    }

    /** Thrown when a lease could not be acquired within the wait window. */
    class LockAcquisitionException extends RuntimeException {
        private final String resourceKey;

        public LockAcquisitionException(String resourceKey, Duration waitTime) {
            super("Could not acquire lock on '" + resourceKey + "' within " + waitTime.toMillis() + "ms");
            this.resourceKey = resourceKey;
        }

        public String resourceKey() {
            return resourceKey;
        }
    }
}
