package com.aegispay.orchestrator.provider;

import com.aegispay.common.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared mechanics for the simulated providers.
 *
 * <p>Holds everything the three concrete rails have in common — latency, authorisation handles, fee
 * calculation, fault injection — so each subclass is left describing only its own personality: what
 * it accepts, what it charges, and when it says no.
 *
 * <p><strong>Simulated throughout.</strong> No subclass contacts a bank, a card network or a UPI
 * rail, and none should ever be described as doing so. What is faithfully modelled is the behaviour a
 * caller has to defend against: authorisations that can be voided, declines that are not faults,
 * fees, capability limits, latency, and — importantly — a charge that succeeds while its
 * acknowledgement is lost.
 */
public abstract class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    /** Latency at which a call is slow enough to be worth routing around. */
    protected static final Duration SLOW_CALL_LATENCY = Duration.ofMillis(2_000);

    private final SimulationControls controls = new SimulationControls();

    /**
     * Authorisations this provider currently holds.
     *
     * <p>In memory, which is honest about what this is: a fake rail, not a durable one. The saga
     * never relies on it — the authoritative record of what was authorised is the saga's own
     * {@code authorization_id}, precisely because a real provider's state is not ours to inspect.
     */
    private final Map<String, Money> outstandingAuthorizations = new ConcurrentHashMap<>();

    /**
     * Idempotency key to authorisation id, so a lost response can be recovered from.
     *
     * <p>Populated <em>before</em> a simulated timeout is thrown, which is the whole point: the
     * charge exists at the provider even though the caller never learned its id. That is exactly the
     * state {@link #findAuthorizationId} lets the saga discover instead of leaving an orphaned
     * authorisation behind.
     */
    private final Map<String, String> authorizationsByIdempotencyKey = new ConcurrentHashMap<>();

    /** Baseline latency this provider answers in when healthy. */
    protected abstract Duration baselineLatency();

    /**
     * This provider's own decline rules.
     *
     * <p>Deterministic, and expressed per subclass because "when does this rail say no" is the most
     * characteristic thing about a provider. Returning empty means approve.
     */
    protected abstract Optional<AuthorizationResult.Declined> declineRule(
            AuthorizationRequest request, long latencyMs);

    // ------------------------------------------------------------- authorise

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        if (!capabilities().supports(request.amount(), request.paymentType())) {
            // A capability mismatch is the router's mistake, not the provider's. Thrown as an
            // IllegalArgumentException so the shared configuration treats it as non-retryable and
            // it does not count against this provider's health: the rail did nothing wrong.
            throw new IllegalArgumentException(
                    id() + " cannot process " + request.amount() + " of type "
                            + request.paymentType() + "; the router should not have selected it");
        }

        // A key already known means the caller is resubmitting. A real rail would collapse the
        // duplicate rather than charge twice, and so does this one.
        String existing = authorizationsByIdempotencyKey.get(request.idempotencyKey());
        if (existing != null) {
            log.debug("{} already holds an authorisation for idempotency key {}; returning it",
                    id(), request.idempotencyKey());
            return new AuthorizationResult.Approved(existing, newProviderReference(),
                    capabilities().feeFor(request.amount()), 0L);
        }

        SimulationControls.FaultMode fault = controls.nextFault();

        switch (fault) {
            case UNREACHABLE ->
                // The request never arrived, so nothing is recorded and a retry is safe.
                    throw ProviderFaultException.unreachable(id(),
                            id() + " could not be reached (simulated)");

            case UPSTREAM_ERROR ->
                // The provider answered with an error of its own; no charge was created.
                    throw ProviderFaultException.upstreamError(id(),
                            id() + " returned an internal error (simulated)");

            case TIMEOUT -> {
                // The important simulation. The charge DOES go through and the acknowledgement is
                // lost, which is the failure that makes naive compensation wrong: the saga has no
                // authorisation id, so without findAuthorizationId the charge would sit here
                // forever. Recorded before throwing, precisely so the recovery path has something
                // to find.
                sleepFor(Math.min(SLOW_CALL_LATENCY.toMillis(), 250L));
                String orphaned = commitAuthorization(request);
                log.debug("{} authorised {} then timed out; authorisation {} is now orphaned "
                        + "(simulated)", id(), request.paymentId(), orphaned);
                throw ProviderFaultException.timedOut(id(),
                        id() + " did not answer within the deadline (simulated)");
            }

            case DECLINE -> {
                long latencyMs = sleepFor(callLatencyMs());
                log.debug("{} declined payment {} (simulated)", id(), request.paymentId());
                return new AuthorizationResult.Declined("SIMULATED_DECLINE",
                        id() + " declined the charge (simulated)", latencyMs);
            }

            case SLOW, NONE -> {
                // fall through to the normal path
            }
        }

        long latencyMs = fault == SimulationControls.FaultMode.SLOW
                ? sleepFor(SLOW_CALL_LATENCY.toMillis() + controls.extraLatencyMs())
                : sleepFor(callLatencyMs());

        Optional<AuthorizationResult.Declined> declined = declineRule(request, latencyMs);
        if (declined.isPresent()) {
            log.debug("{} declined payment {}: {}", id(), request.paymentId(),
                    declined.get().declineCode());
            return declined.get();
        }

        String authorizationId = commitAuthorization(request);
        Money fee = capabilities().feeFor(request.amount());

        log.debug("{} authorised payment {} for {} (fee {})",
                id(), request.paymentId(), request.amount(), fee);

        return new AuthorizationResult.Approved(
                authorizationId, newProviderReference(), fee, latencyMs);
    }

    /** Creates and remembers an authorisation, keyed both ways. */
    private String commitAuthorization(AuthorizationRequest request) {
        String authorizationId = newAuthorizationId();
        outstandingAuthorizations.put(authorizationId, request.amount());
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            authorizationsByIdempotencyKey.put(request.idempotencyKey(), authorizationId);
        }
        return authorizationId;
    }

    // ----------------------------------------------------------------- void

    @Override
    public void voidAuthorization(String authorizationId) {
        failIfFaultArmed();

        if (authorizationId == null || authorizationId.isBlank()) {
            return;
        }
        // An unknown id is a no-op, not an error. Compensation legitimately runs more than once, and
        // it also runs when the saga cannot tell whether the original authorisation succeeded — so
        // voiding something that never existed is a normal occurrence.
        Money released = outstandingAuthorizations.remove(authorizationId);
        authorizationsByIdempotencyKey.values().remove(authorizationId);

        if (released == null) {
            log.debug("{} was asked to void unknown authorisation {}; treating as already released",
                    id(), authorizationId);
        } else {
            log.debug("{} voided authorisation {} for {}", id(), authorizationId, released);
        }
    }

    @Override
    public Optional<String> findAuthorizationId(String idempotencyKey) {
        failIfFaultArmed();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(authorizationsByIdempotencyKey.get(idempotencyKey));
    }

    @Override
    public void probe() {
        // A probe must be able to fail, or the health monitor would report every provider healthy no
        // matter what fault is armed.
        failIfFaultArmed();
        sleepFor(callLatencyMs());
    }

    // ------------------------------------------------------------- simulation

    /** Runtime knobs. Present only because this rail is simulated. */
    public SimulationControls controls() {
        return controls;
    }

    /** Authorisations this fake rail still holds, for assertions and the admin view. */
    public int outstandingAuthorizationCount() {
        return outstandingAuthorizations.size();
    }

    public boolean holdsAuthorization(String authorizationId) {
        return authorizationId != null && outstandingAuthorizations.containsKey(authorizationId);
    }

    /** Clears every simulated authorisation. Used to reset between tests. */
    public void resetSimulatedState() {
        outstandingAuthorizations.clear();
        authorizationsByIdempotencyKey.clear();
        controls.clearFaults();
    }

    /**
     * Applies an armed fault to an operation that has no notion of a decline.
     *
     * <p>A decline reaching here is treated as a normal call: refusing to answer a probe or a void
     * because a <em>decline</em> was armed would misreport a working provider as broken.
     */
    private void failIfFaultArmed() {
        switch (controls.nextFault()) {
            case UNREACHABLE -> throw ProviderFaultException.unreachable(id(),
                    id() + " could not be reached (simulated)");
            case TIMEOUT -> {
                sleepFor(Math.min(SLOW_CALL_LATENCY.toMillis(), 250L));
                throw ProviderFaultException.timedOut(id(),
                        id() + " did not answer within the deadline (simulated)");
            }
            case UPSTREAM_ERROR -> throw ProviderFaultException.upstreamError(id(),
                    id() + " returned an internal error (simulated)");
            case SLOW -> sleepFor(SLOW_CALL_LATENCY.toMillis() + controls.extraLatencyMs());
            case NONE, DECLINE -> {
                // Nothing to do.
            }
        }
    }

    private long callLatencyMs() {
        return baselineLatency().toMillis() + controls.extraLatencyMs();
    }

    /**
     * Sleeps for the simulated latency and reports the elapsed time.
     *
     * <p>A real sleep rather than a fabricated number, because the time limiter and the breaker's
     * slow-call detection both measure wall-clock duration. Fabricating it would leave both
     * protections untestable.
     */
    private long sleepFor(long millis) {
        long start = System.nanoTime();
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                // The time limiter cancels the running future on timeout, which interrupts this
                // thread. Restore the flag and report it as what it is.
                Thread.currentThread().interrupt();
                throw ProviderFaultException.timedOut(id(),
                        id() + " call was interrupted, most likely by the time limiter");
            }
        }
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    protected String newAuthorizationId() {
        return id() + "-AUTH-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase();
    }

    protected String newProviderReference() {
        return id() + "-REF-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + id() + "}";
    }
}
