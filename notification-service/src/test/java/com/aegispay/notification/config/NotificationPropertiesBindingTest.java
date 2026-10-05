package com.aegispay.notification.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Binds the real {@code application.yml} to {@link NotificationProperties}.
 *
 * <p><strong>Why this test exists.</strong> A misspelled configuration key neither fails to compile nor
 * fails to bind — Spring leaves the field at its default and says nothing. Here the symptom would be
 * especially quiet: if {@code max-attempts} failed to bind, deliveries would still be retried, still be
 * dead-lettered, and still be reported; they would simply give up after a different number of tries than the
 * configuration claims. Nothing errors and no log line disagrees with itself.
 *
 * <p>Ordinarily the context starting would catch a structural mistake. It cannot here: the integration tests
 * that boot a context need Docker, which is unavailable in this environment, so without this test the whole
 * {@code aegis.notification.*} tree ships unverified. This runs in milliseconds against no infrastructure.
 *
 * <p>It reads the shipped file rather than an inline fixture. A fixture would prove that binding works in
 * general, which was never in doubt; the question is whether <em>this</em> file's keys match <em>these</em>
 * field names.
 */
class NotificationPropertiesBindingTest {

    private static final String PREFIX = "aegis.notification";

    private final NotificationProperties properties = bindShippedConfiguration();

    // ------------------------------------------------------------------------
    // retry
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("retry")
    class Retry {

        @Test
        @DisplayName("binds every retry key from the shipped configuration")
        void bindsRetry() {
            NotificationProperties.Retry retry = properties.getRetry();

            assertThat(retry.getPollInterval())
                    .as("aegis.notification.retry.poll-interval")
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(retry.getInitialDelay())
                    .as("aegis.notification.retry.initial-delay")
                    .isEqualTo(Duration.ofSeconds(15));
            assertThat(retry.getBatchSize())
                    .as("aegis.notification.retry.batch-size")
                    .isEqualTo(50);
            assertThat(retry.getMaxAttempts())
                    .as("aegis.notification.retry.max-attempts")
                    .isEqualTo(8);
            assertThat(retry.getInitialBackoff())
                    .as("aegis.notification.retry.initial-backoff")
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(retry.getBackoffMultiplier())
                    .as("aegis.notification.retry.backoff-multiplier")
                    .isEqualTo(2.0);
            assertThat(retry.getMaxBackoff())
                    .as("aegis.notification.retry.max-backoff")
                    .isEqualTo(Duration.ofMinutes(10));
            assertThat(retry.getJitter())
                    .as("aegis.notification.retry.jitter")
                    .isEqualTo(0.25);
            assertThat(retry.getReplayAttemptGrant())
                    .as("aegis.notification.retry.replay-attempt-grant")
                    .isEqualTo(3);
        }

        /**
         * <strong>The assertion that matters most here.</strong> A single attempt means no retry at all: the
         * first transient failure dead-letters the notification, and a customer goes untold because an SMTP
         * host was briefly slow. Nothing about that looks wrong — the dead-letter queue simply fills, and it
         * is meant to have things in it.
         */
        @Test
        @DisplayName("allows more than one attempt, so a transient failure is survivable")
        void retriesAtLeastOnce() {
            assertThat(properties.getRetry().getMaxAttempts())
                    .as("""
                            with one attempt the first slow SMTP response dead-letters the notification, \
                            and the queue filling looks exactly like the queue working""")
                    .isGreaterThan(1);
        }

        /**
         * Non-zero jitter. Every instance sweeps on the same interval and a provider outage fails every
         * delivery at once — without jitter the whole backlog comes due in the same second and re-overloads
         * the provider that had just recovered.
         */
        @Test
        @DisplayName("keeps jitter non-zero, so a recovered provider is not hit by the whole backlog")
        void jitterIsOn() {
            assertThat(properties.getRetry().getJitter())
                    .as("a synchronised backlog reproduces the overload that caused the failure")
                    .isGreaterThan(0.0);
        }

        /**
         * The ceiling has to exceed the starting point or the backoff is flat, and a flat backoff against a
         * provider that is rate-limiting is just a slower version of the same problem.
         */
        @Test
        @DisplayName("keeps the backoff ceiling above the initial backoff")
        void backoffGrows() {
            assertThat(properties.getRetry().getMaxBackoff())
                    .isGreaterThan(properties.getRetry().getInitialBackoff());
            assertThat(properties.getRetry().getBackoffMultiplier()).isGreaterThan(1.0);
        }

        /**
         * The whole retry window has to be long enough to outlast a provider restart and short enough that a
         * broken destination surfaces while somebody is on shift. Asserted as a range rather than a value,
         * because the exact figure is a judgement and the order of magnitude is not.
         */
        @Test
        @DisplayName("spans a window long enough to ride out an outage and short enough to notice")
        void retryWindowIsSensible() {
            NotificationProperties.Retry retry = properties.getRetry();

            double totalSeconds = 0;
            for (int attempt = 1; attempt < retry.getMaxAttempts(); attempt++) {
                double delay = retry.getInitialBackoff().toSeconds()
                        * Math.pow(retry.getBackoffMultiplier(), attempt - 1);
                totalSeconds += Math.min(delay, retry.getMaxBackoff().toSeconds());
            }

            assertThat(Duration.ofSeconds((long) totalSeconds))
                    .as("total retry window across all attempts")
                    .isGreaterThan(Duration.ofMinutes(5))
                    .isLessThan(Duration.ofHours(4));
        }

        /**
         * A replay grant above the total budget would effectively reset the counter, which is exactly what
         * the design refuses: a permanently broken destination could then be replayed indefinitely, each
         * replay looking like a fresh start.
         */
        @Test
        @DisplayName("grants a replay less than the whole budget")
        void replayGrantIsBounded() {
            assertThat(properties.getRetry().getReplayAttemptGrant())
                    .as("a grant equal to the budget is a reset by another name")
                    .isLessThan(properties.getRetry().getMaxAttempts());
        }

        /** The claim happens in a transaction, so an unbounded batch would hold a connection open. */
        @Test
        @DisplayName("bounds the batch a single sweep claims")
        void batchIsBounded() {
            assertThat(properties.getRetry().getBatchSize()).isBetween(1, 500);
        }
    }

    // ------------------------------------------------------------------------
    // transports
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("transports")
    class Transports {

        @Test
        @DisplayName("binds every transport key from the shipped configuration")
        void bindsTransports() {
            NotificationProperties.Transports transports = properties.getTransports();

            assertThat(transports.isEmailEnabled()).isTrue();
            assertThat(transports.isSmsEnabled()).isTrue();
            assertThat(transports.getSimulatedLatency()).isEqualTo(Duration.ZERO);
            assertThat(transports.getEmailFromAddress()).isEqualTo("no-reply@aegispay.local");
            assertThat(transports.getEmailFromName()).isEqualTo("AegisPay");
            assertThat(transports.getSmsSenderId()).isEqualTo("AEGISPAY");
        }

        /**
         * <strong>Zero, and it matters.</strong> A default that injected failures would make every developer's
         * local run produce dead letters, and a queue that is always full is a queue nobody reads. The
         * failure path is reached deliberately in tests instead.
         */
        @Test
        @DisplayName("injects no simulated failures by default")
        void noSimulatedFailuresByDefault() {
            assertThat(properties.getTransports().getSimulatedFailureRate())
                    .as("""
                            a dead-letter queue that is always full teaches everyone to ignore it, which \
                            is worse than not having one""")
                    .isZero();
        }

        /**
         * A sender address that was blank or clearly a placeholder would produce mail that every receiving
         * server treats as spam — and the symptom would be a delivery this platform recorded as successful.
         */
        @Test
        @DisplayName("ships a usable sender address")
        void senderAddressIsUsable() {
            assertThat(properties.getTransports().getEmailFromAddress())
                    .isNotBlank()
                    .contains("@");
            assertThat(properties.getTransports().getEmailFromName()).isNotBlank();
        }

        /**
         * Real networks cap an alphanumeric sender id at eleven characters. A longer one is rejected or
         * silently truncated by the carrier, which would be discovered only from a customer's screenshot.
         */
        @Test
        @DisplayName("keeps the SMS sender id within the length carriers accept")
        void senderIdFitsCarrierLimits() {
            assertThat(properties.getTransports().getSmsSenderId())
                    .isNotBlank()
                    .hasSizeLessThanOrEqualTo(11);
        }

        /** Latency injection off by default, or every local run would be needlessly slow. */
        @Test
        @DisplayName("adds no simulated latency by default")
        void noSimulatedLatencyByDefault() {
            assertThat(properties.getTransports().getSimulatedLatency()).isEqualTo(Duration.ZERO);
        }
    }

    // ------------------------------------------------------------------------
    // recipients
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("recipients")
    class Recipients {

        @Test
        @DisplayName("binds every recipient key from the shipped configuration")
        void bindsRecipients() {
            NotificationProperties.Recipients recipients = properties.getRecipients();

            assertThat(recipients.getLookupTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(recipients.getCacheTtl()).isEqualTo(Duration.ofMinutes(10));
            assertThat(recipients.getCacheMaxEntries()).isEqualTo(10_000);
        }

        /**
         * The lookup sits between a Kafka record arriving and a durable write. A generous timeout would hold
         * a consumer thread on a slow identity service, and since the notification can be retried, failing
         * fast is strictly better than waiting.
         */
        @Test
        @DisplayName("keeps the address lookup short enough not to hold a consumer thread")
        void lookupIsShort() {
            assertThat(properties.getRecipients().getLookupTimeout())
                    .isLessThanOrEqualTo(Duration.ofSeconds(5))
                    .isGreaterThan(Duration.ZERO);
        }

        /**
         * Caching is an honest trade: a customer who changes their email keeps receiving notifications at the
         * old address until the entry expires. The window has to stay inside one support call rather than one
         * billing cycle.
         */
        @Test
        @DisplayName("expires a cached address soon enough that a changed one takes effect")
        void cacheWindowIsShort() {
            assertThat(properties.getRecipients().getCacheTtl())
                    .as("""
                            a stale address means notifications going to somewhere the customer no longer \
                            reads, which they cannot fix by correcting their profile""")
                    .isLessThanOrEqualTo(Duration.ofHours(1))
                    .isGreaterThan(Duration.ZERO);
        }

        @Test
        @DisplayName("bounds the cache")
        void cacheIsBounded() {
            assertThat(properties.getRecipients().getCacheMaxEntries()).isPositive();
        }
    }

    // ------------------------------------------------------------------------
    // the rest of the shipped file
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("shipped configuration")
    class ShippedConfiguration {

        private final StandardEnvironment environment = shippedEnvironment();

        /**
         * This service publishes no domain events of its own. Enabling the outbox would start a scheduled
         * drainer against a table this service has no migration for, so the failure would be a startup error
         * — but it would also imply that something here emits events, which nothing does.
         */
        @Test
        @DisplayName("keeps the outbox disabled, because this service emits no domain events")
        void outboxIsDisabled() {
            assertThat(environment.getProperty("aegis.outbox.enabled")).isEqualTo("false");
        }

        /**
         * Service-to-service auth stays <em>on</em> here, unlike ai-investigation-service. This service does
         * make an internal call — it resolves a delivery address from auth-service — so it needs a credential.
         * Worth asserting because the two services sit next to each other in the repository and the
         * reasoning is opposite.
         */
        @Test
        @DisplayName("keeps service auth enabled, because it must call auth-service")
        void serviceAuthIsEnabled() {
            assertThat(environment.getProperty("aegis.service-auth.enabled"))
                    .as("""
                            resolving a delivery address is an authenticated internal call; unlike the AI \
                            service, this one genuinely needs a credential""")
                    .isEqualTo("true");
        }

        @Test
        @DisplayName("points the address lookup at auth-service")
        void authServiceUrlIsConfigured() {
            assertThat(environment.getProperty("aegis.clients.auth-service-url")).isNotBlank();
        }

        /**
         * A distinct consumer group. Sharing one would make two services steal each other's partitions, so
         * each would see roughly half the events and neither would report a problem.
         */
        @Test
        @DisplayName("uses its own Kafka consumer group")
        void ownConsumerGroup() {
            assertThat(environment.getProperty("aegis.kafka.consumer-group-id"))
                    .isEqualTo("notification-service");
        }

        /**
         * Flyway owns the schema. {@code update} would let Hibernate quietly ALTER a live database, and this
         * schema contains append-only triggers and CHECK constraints that encode rules Java does not enforce
         * on its own.
         */
        @Test
        @DisplayName("never lets Hibernate touch the schema")
        void hibernateDoesNotOwnTheSchema() {
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                    .isEqualTo("validate");
        }

        /**
         * No credential may be baked into a shipped file. Asserted rather than assumed because a committed
         * secret is invisible in review once a file is long enough.
         */
        @Test
        @DisplayName("ships no secrets")
        void shipsNoSecrets() {
            assertThat(environment.getProperty("aegis.service-auth.client-secret"))
                    .satisfiesAnyOf(
                            value -> assertThat(value).isNull(),
                            value -> assertThat(value).isEmpty());
            assertThat(environment.getProperty("spring.datasource.password"))
                    .satisfiesAnyOf(
                            value -> assertThat(value).isNull(),
                            value -> assertThat(value).isEmpty());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static NotificationProperties bindShippedConfiguration() {
        NotificationProperties bound = new NotificationProperties();
        Binder.get(shippedEnvironment()).bind(PREFIX, Bindable.ofInstance(bound));
        return bound;
    }

    private static StandardEnvironment shippedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            sources.forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (IOException ex) {
            throw new AssertionError("could not read notification-service's application.yml", ex);
        }
        return environment;
    }
}
