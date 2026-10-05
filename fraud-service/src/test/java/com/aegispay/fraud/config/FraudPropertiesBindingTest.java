package com.aegispay.fraud.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real {@code application.yml} to {@link FraudProperties}.
 *
 * <p><strong>Why this test exists.</strong> A misspelled configuration key does not fail to compile
 * and does not fail to bind — Spring simply leaves the field at its default. The symptom would be a
 * fraud threshold that silently ignores its environment variable: an operator sets
 * {@code FRAUD_HIGH_RISK_THRESHOLD=0.6}, the service reports 0.6 nowhere, and every payment is
 * scored against 0.80 instead. Nothing errors, and the only way to notice is to compare the alert
 * rate against what was expected.
 *
 * <p>Normally the application context starting would catch a structural mistake. It cannot here: the
 * integration tests that boot a context need Docker, which is unavailable in this environment, so
 * without this test the entire {@code aegis.fraud.*} tree is unverified. This runs in milliseconds
 * with no infrastructure.
 *
 * <p>It reads the shipped file rather than an inline fixture. A fixture would test that binding works
 * in general, which was never in doubt; the question is whether <em>this</em> file's keys match
 * <em>these</em> field names.
 */
class FraudPropertiesBindingTest {

    private static final String PREFIX = "aegis.fraud";

    private final FraudProperties properties = bindShippedConfiguration();

    // ------------------------------------------------------------- thresholds

    /**
     * The two numbers that decide whether a customer's payment is stopped. A key that failed to bind
     * here would leave the platform silently running on the compiled-in defaults.
     */
    @Test
    @DisplayName("binds the risk thresholds from the shipped configuration")
    void bindsThresholds() {
        assertThat(properties.getThresholds().getHighRisk())
                .as("aegis.fraud.thresholds.high-risk")
                .isEqualTo(0.80);
        assertThat(properties.getThresholds().getMediumRisk())
                .as("aegis.fraud.thresholds.medium-risk")
                .isEqualTo(0.45);
    }

    /**
     * The thresholds must stay ordered. A medium above high would make the MEDIUM band empty and
     * every REVIEW disappear, which is a configuration mistake no validation annotation can catch
     * because each value is individually legal.
     */
    @Test
    @DisplayName("keeps the medium threshold below the high one")
    void thresholdsAreOrdered() {
        assertThat(properties.getThresholds().getMediumRisk())
                .as("a medium threshold at or above the high one empties the REVIEW band entirely")
                .isLessThan(properties.getThresholds().getHighRisk());
    }

    // ------------------------------------------------------------- ml client

    @Test
    @DisplayName("binds the model client settings")
    void bindsMlSettings() {
        assertThat(properties.getMl().getTimeout())
                .as("aegis.fraud.ml.timeout — the budget this call has on the payment's "
                        + "critical path")
                .isEqualTo(Duration.ofMillis(1_200));
        assertThat(properties.getMl().getMaxAttempts()).isEqualTo(2);
        assertThat(properties.getMl().getRetryInterval()).isEqualTo(Duration.ofMillis(80));
    }

    /**
     * <strong>The default that matters most.</strong> True here would mean every payment on the
     * platform is held whenever the model is unreachable, turning a fraud-service dependency outage
     * into a total payment outage. Asserted against the shipped file rather than the field default,
     * because a stray {@code true} in configuration would override the field silently.
     */
    @Test
    @DisplayName("falls back to rules rather than refusing payments when the model is down")
    void doesNotRefuseByDefault() {
        assertThat(properties.getMl().isRejectWhenUnavailable())
                .as("""
                        refusing every payment while the model is down is a far larger incident than \
                        the fraud it prevents, and the rules cover the archetypes the model scores \
                        worst on""")
                .isFalse();
    }

    // ------------------------------------------------------------------ rules

    @Test
    @DisplayName("binds every rule tuning value")
    void bindsRuleTuning() {
        FraudProperties.Rules rules = properties.getRules();

        assertThat(rules.getVelocityWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(rules.getBustOutAmountMultiple()).isEqualTo(1.6);
        assertThat(rules.getBustOutMinimumHistory()).isEqualTo(20L);
        assertThat(rules.getCardTestingFailures()).isEqualTo(5L);
        // The one most likely to fail: a digit inside the property name is exactly where Spring's
        // relaxed binding and a hand-written kebab-case key can disagree.
        assertThat(rules.getCardTestingAttempts1h())
                .as("aegis.fraud.rules.card-testing-attempts1h")
                .isEqualTo(5L);
        assertThat(rules.getImpossibleTravelKmh()).isEqualTo(900.0);
        assertThat(rules.getNewDeviceAmountMultiple()).isEqualTo(5.0);
        assertThat(rules.getDormantDays()).isEqualTo(90L);
        assertThat(rules.getDormantAmountMultiple()).isEqualTo(3.0);
        assertThat(rules.getConfirmedFraudReviewCount()).isEqualTo(1L);
        assertThat(rules.getConfirmedFraudBlockCount()).isEqualTo(3L);
    }

    /**
     * The block count must exceed the review count. Equal values would make the only automatic
     * refusal in the platform fire on a single prior fraud — a threshold nobody chose, arrived at by
     * configuration rather than by decision.
     */
    @Test
    @DisplayName("requires more confirmed frauds to refuse than to review")
    void refusalIsStricterThanReview() {
        assertThat(properties.getRules().getConfirmedFraudBlockCount())
                .as("the only automatic REJECT in the platform must be strictly harder to reach "
                        + "than a REVIEW")
                .isGreaterThan(properties.getRules().getConfirmedFraudReviewCount());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Loads {@code application.yml} and binds the {@code aegis.fraud} subtree.
     *
     * <p>Environment placeholders resolve to their defaults, which is what an unconfigured deployment
     * gets — and therefore the values worth asserting.
     */
    private static FraudProperties bindShippedConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            sources.forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (IOException ex) {
            throw new AssertionError("could not read fraud-service's application.yml", ex);
        }

        FraudProperties bound = new FraudProperties();
        Binder.get(environment).bind(PREFIX, org.springframework.boot.context.properties.bind
                .Bindable.ofInstance(bound));
        return bound;
    }
}
