package com.aegispay.orchestrator.domain;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The part of a provider's status that has to survive a restart.
 *
 * <p><strong>What is here and what is deliberately not.</strong> The circuit breakers live in
 * memory, and that is correct: a breaker measures what <em>this instance</em> is currently
 * seeing, and one restored from a database would be reasoning about another instance's past
 * failures. What must survive is the deliberate human decision — an operator disabling a
 * provider during an incident has to stay disabled through a rolling deploy, or the deploy
 * silently undoes the mitigation at the worst possible moment.
 *
 * <p>{@link #lastHealth} is therefore advisory only, for the admin view. Routing consults the
 * live breaker instead, because a stored health value is stale the moment it is written.
 */
@Entity
@Table(name = "provider_state")
public class ProviderState {

    public enum Health {
        HEALTHY,
        /** Answering, but slowly or with intermittent faults. Routable as a last resort. */
        DEGRADED,
        UNHEALTHY,
        /** Never probed yet. Treated as routable: assuming the worst would refuse all traffic
         *  on a cold start, which is a self-inflicted outage. */
        UNKNOWN;

        public boolean isRoutable() {
            return this != UNHEALTHY;
        }
    }

    @Id
    @Column(name = "provider_id", nullable = false, updatable = false, length = 32)
    private String providerId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_health", nullable = false, length = 16)
    private Health lastHealth = Health.UNKNOWN;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_latency_ms")
    private Long lastLatencyMs;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "disabled_reason", length = 256)
    private String disabledReason;

    @Column(name = "disabled_by", length = 64)
    private String disabledBy;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProviderState() {
        // JPA
    }

    public static ProviderState of(String providerId) {
        ProviderState state = new ProviderState();
        state.providerId = providerId;
        state.enabled = true;
        state.lastHealth = Health.UNKNOWN;
        state.updatedAt = Instant.now();
        return state;
    }

    // ------------------------------------------------------------------ queries

    /** Whether the router may consider this provider at all. */
    public boolean isRoutable() {
        return enabled && lastHealth.isRoutable();
    }

    // ----------------------------------------------------------------- mutators

    /** Records a successful probe. */
    public void probeSucceeded(long latencyMs, boolean slow) {
        this.lastHealth = slow ? Health.DEGRADED : Health.HEALTHY;
        this.lastLatencyMs = latencyMs;
        this.consecutiveFailures = 0;
        this.lastCheckedAt = Instant.now();
        touch();
    }

    /**
     * Records a failed probe.
     *
     * <p>Requires {@code failuresBeforeUnhealthy} consecutive failures before declaring the
     * provider unhealthy. A single failed probe is far more likely to be a blip in the prober
     * than an outage at the provider, and reacting to it would flap traffic between providers on
     * noise.
     */
    public void probeFailed(int failuresBeforeUnhealthy) {
        this.consecutiveFailures++;
        this.lastHealth = consecutiveFailures >= Math.max(1, failuresBeforeUnhealthy)
                ? Health.UNHEALTHY
                : Health.DEGRADED;
        this.lastCheckedAt = Instant.now();
        touch();
    }

    /**
     * Takes the provider out of service.
     *
     * <p>A reason is mandatory, and the schema enforces it too. "Who turned this off and why" is
     * unanswerable an hour later otherwise, and the answer is what decides whether it is safe to
     * turn back on.
     */
    public void disable(String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "Disabling a provider requires a reason: it is what decides whether "
                            + "re-enabling it later is safe");
        }
        this.enabled = false;
        this.disabledReason = reason.length() <= 256 ? reason : reason.substring(0, 256);
        this.disabledBy = by;
        this.disabledAt = Instant.now();
        touch();
    }

    /**
     * Returns the provider to service.
     *
     * <p>Resets the observed health to {@code UNKNOWN} rather than to {@code HEALTHY}: whether it
     * has actually recovered is for the next probe to establish, not for the operator to assert.
     */
    public void enable() {
        this.enabled = true;
        this.disabledReason = null;
        this.disabledBy = null;
        this.disabledAt = null;
        this.lastHealth = Health.UNKNOWN;
        this.consecutiveFailures = 0;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getProviderId() {
        return providerId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Health getLastHealth() {
        return lastHealth;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Long getLastLatencyMs() {
        return lastLatencyMs;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public String getDisabledBy() {
        return disabledBy;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return "ProviderState{" + providerId + ", enabled=" + enabled + ", " + lastHealth + '}';
    }
}
