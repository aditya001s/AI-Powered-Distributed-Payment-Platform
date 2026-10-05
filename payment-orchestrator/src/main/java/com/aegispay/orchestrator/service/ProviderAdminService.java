package com.aegispay.orchestrator.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.orchestrator.api.OrchestratorDtos.ProviderHealthView;
import com.aegispay.orchestrator.domain.ProviderState;
import com.aegispay.orchestrator.provider.PaymentProvider;
import com.aegispay.orchestrator.provider.ProviderGateway;
import com.aegispay.orchestrator.provider.ProviderHealthMonitor;
import com.aegispay.orchestrator.provider.ProviderRouter;
import com.aegispay.orchestrator.repository.ProviderStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator kill switch: taking a provider out of service and putting it back.
 *
 * <p><strong>Why this is persistent when the circuit breakers are not.</strong> A breaker measures
 * what this instance is seeing right now, so it belongs in memory. A human decision does not: an
 * operator disabling a provider during an incident must stay disabled through a rolling deploy, or
 * the deploy silently undoes the mitigation at the worst possible moment. So the switch is a row in
 * {@code provider_state} and the breaker state is not.
 *
 * <p>Both operations touch two things that cannot be committed together — a database row and an
 * in-memory breaker. The order is chosen so that a failure leaves the safer state:
 *
 * <ul>
 *   <li>On <em>disable</em>, the breaker is forced open before the transaction commits. If the
 *       commit then fails, the breaker is open while the row still says enabled — traffic is
 *       refused, which is the direction the operator was asking for anyway.</li>
 *   <li>On <em>enable</em>, resetting the breaker early is equally safe, because routing reads the
 *       {@code enabled} column as a hard exclusion. A closed breaker on a row that is still
 *       disabled routes nothing.</li>
 * </ul>
 *
 * <p>Neither operation touches payments already in flight. An authorisation a provider has already
 * granted still has to be captured or voided by the saga that created it; abandoning it because an
 * operator flipped a switch would strand a real charge.
 */
@Service
public class ProviderAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProviderAdminService.class);

    private final ProviderStateRepository states;
    private final ProviderRouter router;
    private final ProviderGateway gateway;
    private final ProviderHealthMonitor healthMonitor;

    public ProviderAdminService(ProviderStateRepository states,
                                ProviderRouter router,
                                ProviderGateway gateway,
                                ProviderHealthMonitor healthMonitor) {
        this.states = states;
        this.router = router;
        this.gateway = gateway;
        this.healthMonitor = healthMonitor;
    }

    /**
     * Takes a provider out of service until an operator puts it back.
     *
     * <p>The row is read under a pessimistic lock, so two operators acting on the same provider at
     * the same moment cannot interleave into a state where one's reason is recorded against the
     * other's decision.
     *
     * @param reason mandatory, and enforced by {@link ProviderState#disable} and by the schema. It
     *               is what decides whether re-enabling later is safe.
     * @param by     taken from the validated token by the caller, never from a request field
     */
    @Transactional
    public ProviderHealthView disable(String providerId, String reason, String by) {
        PaymentProvider provider = requireProvider(providerId);
        ProviderState state = lockOrCreate(provider.id());

        if (!state.isEnabled()) {
            // Already off. Re-recording the reason would overwrite the original diagnosis, which
            // is the one piece of information the eventual re-enable decision depends on.
            log.info("Provider {} is already disabled ({}); leaving the original reason in place",
                    provider.id(), state.getDisabledReason());
            return ProviderHealthView.from(healthMonitor.toHealth(state), provider);
        }

        state.disable(reason, by);
        states.save(state);

        // Forced open, not merely reset: routing already excludes a disabled provider, but any
        // saga that read the routing table a moment ago is still holding a candidate list with
        // this provider on it, and the breaker is what stops that call.
        gateway.forceOpen(provider.id());

        log.warn("Provider {} DISABLED by {}: {}", provider.id(), by, reason);
        return ProviderHealthView.from(healthMonitor.toHealth(state), provider);
    }

    /**
     * Returns a provider to service.
     *
     * <p>Clears the switch and resets the breaker's window, so the provider gets a genuine trial
     * rather than being judged on failures from before the incident was fixed. Observed health goes
     * back to {@code UNKNOWN} rather than {@code HEALTHY}: whether it has actually recovered is for
     * the next probe to establish, not for the operator to assert.
     */
    @Transactional
    public ProviderHealthView enable(String providerId, String by) {
        PaymentProvider provider = requireProvider(providerId);
        ProviderState state = lockOrCreate(provider.id());

        if (state.isEnabled()) {
            log.info("Provider {} is already enabled; nothing to do", provider.id());
            return ProviderHealthView.from(healthMonitor.toHealth(state), provider);
        }

        String previousReason = state.getDisabledReason();
        state.enable();
        states.save(state);

        gateway.reset(provider.id());

        log.warn("Provider {} ENABLED by {} (was disabled: {})",
                provider.id(), by, previousReason);
        return ProviderHealthView.from(healthMonitor.toHealth(state), provider);
    }

    // --------------------------------------------------------------- internals

    /**
     * Resolves the provider by id, rejecting anything not registered in this build.
     *
     * <p>Checked against the registered providers rather than against the table, so a typo cannot
     * create a row that disables a provider which does not exist — an operator would see a
     * successful response and believe they had mitigated something.
     */
    private PaymentProvider requireProvider(String providerId) {
        return router.byId(providerId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No payment provider is registered with id " + providerId));
    }

    /**
     * The provider's row, locked for update.
     *
     * <p>Created on demand. The migration seeds a row per provider, but a provider added in a later
     * release would otherwise be unmanageable until someone remembered to write a migration for it.
     */
    private ProviderState lockOrCreate(String providerId) {
        return states.findByIdForUpdate(providerId)
                .orElseGet(() -> states.save(ProviderState.of(providerId)));
    }
}
