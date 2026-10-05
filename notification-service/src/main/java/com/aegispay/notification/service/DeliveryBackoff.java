package com.aegispay.notification.service;

import com.aegispay.notification.config.NotificationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When a failed delivery should next be attempted.
 *
 * <p>A separate bean, and a pure function of the attempt number, so the schedule can be asserted
 * directly. Retry timing is the kind of logic that is easy to write plausibly and get wrong by a factor
 * of two — an off-by-one on the exponent means the first retry waits as long as the second should, and
 * nothing about the running system looks wrong.
 *
 * <h2>Why jitter is not optional</h2>
 *
 * <p>Every instance sweeps on the same interval, and a provider outage fails every delivery at once.
 * Without jitter the whole backlog would come due in the same second and re-overload the provider that
 * had just recovered — then fail together again, in lockstep, until the attempts ran out. The platform
 * already learned this on the payment providers; the same reasoning applies here.
 */
@Component
public class DeliveryBackoff {

    private final NotificationProperties properties;

    public DeliveryBackoff(NotificationProperties properties) {
        this.properties = properties;
    }

    /**
     * The delay before the next attempt, given that {@code attemptNumber} attempts have been made.
     *
     * @param attemptNumber 1-based count of attempts already made
     */
    public Duration delayAfter(int attemptNumber) {
        NotificationProperties.Retry config = properties.getRetry();

        int exponent = Math.max(0, attemptNumber - 1);
        double base = config.getInitialBackoff().toMillis()
                * Math.pow(config.getBackoffMultiplier(), exponent);

        // Capped before jitter is added, so the configured ceiling bounds the actual schedule rather
        // than the pre-jitter figure -- otherwise the effective maximum would quietly exceed the
        // configured one by the jitter factor.
        double capped = Math.min(base, (double) config.getMaxBackoff().toMillis());

        double jitterFactor = config.getJitter();
        if (jitterFactor <= 0.0) {
            return Duration.ofMillis(Math.max(1L, Math.round(capped)));
        }

        // Added, never subtracted. Subtracting could schedule a retry sooner than the configured
        // initial backoff, which for a provider that is rate-limiting is the opposite of the intent.
        double jittered = capped + ThreadLocalRandom.current().nextDouble() * jitterFactor * capped;
        return Duration.ofMillis(Math.max(1L, Math.round(jittered)));
    }

    /** The absolute time of the next attempt. */
    public Instant nextAttemptAfter(int attemptNumber) {
        return Instant.now().plus(delayAfter(attemptNumber));
    }

    /**
     * Whether the budget is spent.
     *
     * <p>{@code >=} rather than {@code >}: {@code maxAttempts} counts the first delivery, so a budget of
     * eight means eight attempts in total and not one plus eight retries. Getting this wrong is invisible
     * — the only symptom is a dead-letter queue that fills slightly later than the configuration claims.
     */
    public boolean isExhausted(int attemptsMade) {
        return attemptsMade >= properties.getRetry().getMaxAttempts();
    }

    /** How many attempts a delivery gets in total, for the admin view. */
    public int maxAttempts() {
        return properties.getRetry().getMaxAttempts();
    }
}
