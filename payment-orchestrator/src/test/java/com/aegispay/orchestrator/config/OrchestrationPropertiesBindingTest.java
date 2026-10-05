package com.aegispay.orchestrator.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real {@code application.yml} to {@link OrchestrationProperties}.
 *
 * <p><strong>Why this test exists.</strong> A misspelled configuration key neither fails to compile
 * nor fails to bind — Spring leaves the field at its default and says nothing. For the risk settings
 * added in Phase 6 the symptom would be particularly quiet: an operator sets
 * {@code ALLOW_UNASSESSED_PAYMENTS=true} to keep payments flowing during a fraud-service outage, the
 * key does not bind, and every payment fails instead. Nothing errors; the setting simply has no
 * effect.
 *
 * <p>The application context starting would normally catch this, but the integration tests that boot
 * one need Docker, which is unavailable here. Without this test the whole
 * {@code aegis.orchestration.fraud} subtree is unverified.
 *
 * <p>Scoped to the Phase 6 additions plus the recovery window they interact with. The provider and
 * routing trees have been in place since Phase 5 and are exercised by the orchestration tests.
 */
class OrchestrationPropertiesBindingTest {

    private final OrchestrationProperties properties = bindShippedConfiguration();

    @Test
    @DisplayName("binds the risk-step call budget")
    void bindsRiskBudget() {
        assertThat(properties.getFraud().getCallTimeout())
                .as("""
                        aegis.orchestration.fraud.call-timeout — wider than fraud-service's own model \
                        timeout, because fraud-service is expected to absorb a slow model and still \
                        answer with a degraded verdict""")
                .isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.getFraud().getMaxAttempts()).isEqualTo(2);
    }

    /**
     * <strong>The default that decides what an outage costs.</strong> True here would let payments
     * through with no fraud control at all — no score, no rules — recorded as approved. That is a
     * false statement in the audit trail of an automated decision, which is why the default fails the
     * payment instead: nothing was charged, the reservation is released, and the outage is visible as
     * failed payments rather than invisible as unprotected ones.
     */
    @Test
    @DisplayName("fails a payment rather than letting an unassessed one through")
    void doesNotAllowUnassessedPaymentsByDefault() {
        assertThat(properties.getFraud().isAllowUnassessedPayments())
                .as("an unassessed payment recorded as approved is worse than a failed one")
                .isFalse();
    }

    /**
     * The review extension has to outlive the saga's own reservation window by a wide margin, or the
     * wallet's expiry sweeper releases the funds while the saga still believes they are held — and the
     * analyst releasing the payment captures against a balance that has moved.
     */
    @Test
    @DisplayName("extends a held reservation far beyond the saga's own hold window")
    void reviewHoldOutlivesTheDefaultReservation() {
        assertThat(properties.getFraud().getReviewHoldTtl())
                .as("aegis.orchestration.fraud.review-hold-ttl")
                .isEqualTo(Duration.ofDays(2));
        assertThat(properties.getFraud().getReviewHoldTtl())
                .as("""
                        the saga reserves funds for 15 minutes; a review that outran the reservation \
                        would leave the customer free to spend money the saga still counts as held""")
                .isGreaterThan(Duration.ofMinutes(15));
    }

    /**
     * A parked payment is excluded from the sweep by status, so this window never applies to one.
     * Asserted anyway because the two settings are read together during an incident, and a reader who
     * assumed the window governed held payments would draw the wrong conclusion about why they are
     * not being recovered.
     */
    @Test
    @DisplayName("binds the recovery window that RISK_HELD is deliberately exempt from")
    void bindsRecoveryWindow() {
        assertThat(properties.getRecovery().isEnabled()).isTrue();
        assertThat(properties.getRecovery().getStalledAfter()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getFraud().getReviewHoldTtl())
                .as("a review lasts orders of magnitude longer than the stalled-saga window, which "
                        + "is exactly why RISK_HELD must be excluded from the sweep rather than "
                        + "given a longer one")
                .isGreaterThan(properties.getRecovery().getStalledAfter());
    }

    // ---------------------------------------------------------------- helpers

    private static OrchestrationProperties bindShippedConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            sources.forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (IOException ex) {
            throw new AssertionError("could not read payment-orchestrator's application.yml", ex);
        }

        OrchestrationProperties bound = new OrchestrationProperties();
        Binder.get(environment).bind("aegis.orchestration", Bindable.ofInstance(bound));
        return bound;
    }
}
