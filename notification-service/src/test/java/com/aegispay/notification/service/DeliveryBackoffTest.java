package com.aegispay.notification.service;

import com.aegispay.notification.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry schedule.
 *
 * <p>Tested directly because retry timing is the kind of logic that is easy to write plausibly and get
 * wrong by a factor of two, and nothing about the running system looks wrong when it is. An off-by-one on
 * the exponent means the first retry waits as long as the second should; a cap applied after jitter means
 * the configured maximum is quietly exceeded. Both are invisible in production.
 */
class DeliveryBackoffTest {

    private NotificationProperties properties;
    private DeliveryBackoff backoff;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties();
        // Jitter off for the arithmetic assertions. It is asserted separately, where randomness is the
        // point rather than an obstacle.
        properties.getRetry().setJitter(0.0);
        properties.getRetry().setInitialBackoff(Duration.ofSeconds(30));
        properties.getRetry().setBackoffMultiplier(2.0);
        properties.getRetry().setMaxBackoff(Duration.ofMinutes(10));
        properties.getRetry().setMaxAttempts(8);
        backoff = new DeliveryBackoff(properties);
    }

    @Nested
    @DisplayName("schedule")
    class Schedule {

        /**
         * The first retry waits the configured initial backoff, not the initial backoff times the
         * multiplier. That is the off-by-one this asserts: with {@code Math.pow(multiplier, attemptNumber)}
         * instead of {@code attemptNumber - 1}, the first wait would be a minute rather than thirty seconds
         * and every subsequent one would be doubled too.
         */
        @Test
        @DisplayName("waits the initial backoff after the first attempt")
        void firstRetryUsesInitialBackoff() {
            assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("doubles on each subsequent attempt")
        void doublesEachTime() {
            assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofSeconds(60));
            assertThat(backoff.delayAfter(3)).isEqualTo(Duration.ofSeconds(120));
            assertThat(backoff.delayAfter(4)).isEqualTo(Duration.ofSeconds(240));
        }

        /**
         * Without a ceiling the eighth attempt would be over two hours after the seventh, and a
         * notification about a payment is worth less the later it arrives.
         */
        @Test
        @DisplayName("stops growing at the configured ceiling")
        void capsAtMaxBackoff() {
            assertThat(backoff.delayAfter(20)).isEqualTo(Duration.ofMinutes(10));
            assertThat(backoff.delayAfter(8)).isLessThanOrEqualTo(Duration.ofMinutes(10));
        }

        /** Defensive: attempt 0 should not produce a negative exponent and a sub-second delay. */
        @Test
        @DisplayName("treats a zeroth attempt as the first")
        void handlesZero() {
            assertThat(backoff.delayAfter(0)).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("never returns a zero delay")
        void neverZero() {
            properties.getRetry().setInitialBackoff(Duration.ofMillis(1));
            assertThat(backoff.delayAfter(1)).isGreaterThan(Duration.ZERO);
        }

        @Test
        @DisplayName("schedules the next attempt in the future")
        void nextAttemptIsInTheFuture() {
            Instant next = backoff.nextAttemptAfter(1);

            assertThat(next).isAfter(Instant.now().plus(Duration.ofSeconds(20)));
        }
    }

    @Nested
    @DisplayName("jitter")
    class Jitter {

        /**
         * Every instance sweeps on the same interval and a provider outage fails every delivery at once.
         * Without jitter the whole backlog comes due in the same second and re-overloads the provider that
         * had just recovered, then fails together again in lockstep until the attempts run out.
         */
        @Test
        @DisplayName("spreads retries so a recovered provider is not hit by the whole backlog at once")
        void jitterSpreadsRetries() {
            properties.getRetry().setJitter(0.5);

            java.util.Set<Duration> observed = new java.util.HashSet<>();
            for (int i = 0; i < 40; i++) {
                observed.add(backoff.delayAfter(1));
            }

            assertThat(observed)
                    .as("identical delays across the fleet reproduce the overload that caused the "
                            + "failure")
                    .hasSizeGreaterThan(1);
        }

        /**
         * Added, never subtracted. Subtracting could schedule a retry sooner than the configured initial
         * backoff, which against a provider that is rate-limiting is the opposite of the intent.
         */
        @Test
        @DisplayName("only ever delays, never brings a retry forward")
        void jitterOnlyAdds() {
            properties.getRetry().setJitter(0.5);

            for (int i = 0; i < 40; i++) {
                assertThat(backoff.delayAfter(1))
                        .isGreaterThanOrEqualTo(Duration.ofSeconds(30))
                        .isLessThanOrEqualTo(Duration.ofSeconds(45));
            }
        }

        /**
         * The ceiling is applied before jitter, so it bounds the actual schedule. Applied afterwards, the
         * effective maximum would exceed the configured one by the jitter factor — a 10-minute cap quietly
         * becoming 12 and a half.
         */
        @Test
        @DisplayName("keeps jittered delays within the configured ceiling plus jitter only")
        void ceilingBoundsTheSchedule() {
            properties.getRetry().setJitter(0.25);

            for (int i = 0; i < 40; i++) {
                assertThat(backoff.delayAfter(20))
                        .isLessThanOrEqualTo(Duration.ofMinutes(10).plus(
                                Duration.ofMinutes(10).dividedBy(4)));
            }
        }
    }

    @Nested
    @DisplayName("budget")
    class Budget {

        /**
         * {@code maxAttempts} counts the first delivery, so a budget of eight means eight attempts in total
         * and not one plus eight retries. Getting this wrong produces no symptom beyond a dead-letter queue
         * that fills slightly later than the configuration claims — which nobody would ever notice.
         */
        @Test
        @DisplayName("counts the first delivery towards the budget")
        void firstAttemptCounts() {
            assertThat(backoff.isExhausted(7)).isFalse();
            assertThat(backoff.isExhausted(8)).isTrue();
            assertThat(backoff.maxAttempts()).isEqualTo(8);
        }

        @Test
        @DisplayName("treats a count beyond the budget as exhausted")
        void beyondBudgetIsExhausted() {
            assertThat(backoff.isExhausted(99)).isTrue();
        }

        /** A single-attempt configuration must dead-letter immediately rather than retry once. */
        @Test
        @DisplayName("honours a single-attempt budget")
        void singleAttemptBudget() {
            properties.getRetry().setMaxAttempts(1);

            assertThat(backoff.isExhausted(1)).isTrue();
        }
    }
}
