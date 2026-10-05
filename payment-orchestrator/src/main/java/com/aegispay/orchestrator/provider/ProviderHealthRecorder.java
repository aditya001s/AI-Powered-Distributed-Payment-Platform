package com.aegispay.orchestrator.provider;

import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.domain.ProviderAttempt;
import com.aegispay.orchestrator.domain.ProviderState;
import com.aegispay.orchestrator.repository.ProviderAttemptRepository;
import com.aegispay.orchestrator.repository.ProviderStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Transactional writer for provider probe outcomes, behind a real Spring proxy. */
@Service
public class ProviderHealthRecorder {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthRecorder.class);

    private final ProviderStateRepository states;
    private final ProviderAttemptRepository attempts;
    private final OrchestrationProperties properties;

    public ProviderHealthRecorder(ProviderStateRepository states,
                                  ProviderAttemptRepository attempts,
                                  OrchestrationProperties properties) {
        this.states = states;
        this.attempts = attempts;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String providerId, Optional<Long> latencyMs, boolean slow) {
        ProviderState state = states.findByIdForUpdate(providerId)
                .orElseGet(() -> states.save(ProviderState.of(providerId)));
        ProviderState.Health previous = state.getLastHealth();

        if (latencyMs.isPresent()) {
            state.probeSucceeded(latencyMs.get(), slow);
            attempts.save(ProviderAttempt.healthCheck(providerId,
                    ProviderAttempt.Outcome.APPROVED, latencyMs.get(), null, null));
        } else {
            state.probeFailed(properties.getProviders().getFailuresBeforeUnhealthy());
            attempts.save(ProviderAttempt.healthCheck(providerId,
                    ProviderAttempt.Outcome.FAILED, 0L, "PROBE_FAILED",
                    "liveness probe did not succeed"));
        }
        states.save(state);

        if (previous != state.getLastHealth()) {
            if (state.getLastHealth() == ProviderState.Health.UNHEALTHY) {
                log.error("Provider {} is now UNHEALTHY after {} consecutive failed probe(s); "
                                + "routing will exclude it",
                        providerId, state.getConsecutiveFailures());
            } else {
                log.warn("Provider {} health changed {} -> {}",
                        providerId, previous, state.getLastHealth());
            }
        }
    }
}
