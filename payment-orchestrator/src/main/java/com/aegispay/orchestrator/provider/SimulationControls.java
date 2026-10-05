package com.aegispay.orchestrator.provider;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime knobs on a simulated provider's behaviour.
 *
 * <p><strong>This exists only because the providers are simulated.</strong> A real integration
 * would have no such control, and this class would be deleted along with the simulation. It is not
 * a backdoor into production behaviour: it changes what the <em>fake rail</em> does, not what the
 * orchestrator does with the answer.
 *
 * <h2>Why deterministic, and not a failure probability</h2>
 *
 * <p>The obvious way to simulate an unreliable provider is a random failure rate. That would make
 * every test of fallback, retry and circuit breaking flaky — the behaviour under test would only
 * appear on some runs, and a genuine regression would be indistinguishable from an unlucky seed.
 *
 * <p>So failures are <em>injected on demand and counted down</em>: "fail the next N calls". A test
 * arms the fault, makes exactly the calls it needs, and asserts the outcome with no randomness
 * anywhere. The same mechanism lets an operator rehearse a provider outage against a live sandbox
 * without waiting for a real one.
 *
 * <p>Thread-safe, because the health monitor probes on a scheduled thread while payments are being
 * authorised on consumer threads.
 */
public final class SimulationControls {

    /** How a simulated provider misbehaves while a fault is armed. */
    public enum FaultMode {
        /** Behave normally. */
        NONE,
        /** Refuse to answer, as if the connection were refused. Safe to retry. */
        UNREACHABLE,
        /** Accept the request and never answer. NOT safe to retry on the same provider. */
        TIMEOUT,
        /** Answer with an error of the provider's own. */
        UPSTREAM_ERROR,
        /** Answer correctly, but say no. Must not count against health. */
        DECLINE,
        /** Answer correctly but slowly enough to count as a slow call. */
        SLOW
    }

    private final AtomicReference<FaultMode> faultMode = new AtomicReference<>(FaultMode.NONE);

    /**
     * Calls still affected by the armed fault.
     *
     * <p>A negative value means "until disarmed", which is what an operator rehearsing an outage
     * wants; a positive count is what a test wants so it can assert on exactly N failures.
     */
    private final AtomicInteger remainingFaults = new AtomicInteger();

    /** Extra latency added to every call, on top of the provider's own baseline. */
    private final AtomicLong extraLatencyMs = new AtomicLong();

    /** Arms a fault for the next {@code calls} calls. */
    public void injectFault(FaultMode mode, int calls) {
        this.faultMode.set(mode == null ? FaultMode.NONE : mode);
        this.remainingFaults.set(calls);
    }

    /** Arms a fault until it is explicitly cleared. */
    public void injectFaultIndefinitely(FaultMode mode) {
        injectFault(mode, -1);
    }

    public void clearFaults() {
        this.faultMode.set(FaultMode.NONE);
        this.remainingFaults.set(0);
        this.extraLatencyMs.set(0L);
    }

    public void setExtraLatencyMs(long extraLatencyMs) {
        this.extraLatencyMs.set(Math.max(0L, extraLatencyMs));
    }

    public long extraLatencyMs() {
        return extraLatencyMs.get();
    }

    public FaultMode currentFaultMode() {
        return remainingFaults.get() == 0 ? FaultMode.NONE : faultMode.get();
    }

    public int remainingFaults() {
        return remainingFaults.get();
    }

    /**
     * Consumes one call's worth of the armed fault and reports what to do.
     *
     * <p>Decrementing here rather than at the call site means the countdown cannot be forgotten by
     * one of the operations, which would leave a fault armed for longer than the test expected and
     * fail a later assertion for no visible reason.
     */
    public FaultMode nextFault() {
        int remaining = remainingFaults.get();
        if (remaining == 0) {
            return FaultMode.NONE;
        }
        if (remaining > 0) {
            // compareAndSet rather than decrementAndGet: two concurrent calls must not both
            // consume the last remaining fault and drive the counter negative, which would
            // silently switch the countdown into indefinite mode.
            if (!remainingFaults.compareAndSet(remaining, remaining - 1)) {
                return nextFault();
            }
        }
        return faultMode.get();
    }
}
