package com.aegispay.fraud.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Fraud thresholds and rule tuning.
 *
 * <p>Everything here is configuration rather than code because these are business and compliance
 * decisions that change far more often than the pipeline does. Retuning the blocking threshold should
 * be an environment change reviewed by a fraud team, not a release.
 *
 * <p>The one thing deliberately <em>not</em> configurable is the geography list in
 * {@code ml-service}: a country risk list that can change without review is a discrimination
 * exposure, so it lives in reviewed source.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.fraud")
public class FraudProperties {

    private final Thresholds thresholds = new Thresholds();
    private final Ml ml = new Ml();
    private final Rules rules = new Rules();

    /**
     * Where the risk bands fall.
     *
     * <p>Expressed as probabilities, which only means anything because the model is calibrated: the
     * training run reports an expected calibration error, and if that were poor these numbers would be
     * arbitrary dials rather than thresholds.
     */
    public static class Thresholds {

        /**
         * At or above this, the payment is held for a human.
         *
         * <p>Reads directly as a risk score: 0.80 is a score of 80. Raising it lets more fraud
         * through; lowering it holds more customers' money. The training run reports precision, recall
         * and — importantly — the alert rate at each candidate value, because a threshold that flags
         * 10% of traffic is not a fraud control, it is a manual review department.
         */
        @DecimalMin("0.05")
        @DecimalMax("1.0")
        private double highRisk = 0.80;

        /** At or above this, the payment settles but an analyst is asked to look. */
        @DecimalMin("0.01")
        @DecimalMax("1.0")
        private double mediumRisk = 0.45;

        public double getHighRisk() {
            return highRisk;
        }

        public void setHighRisk(double highRisk) {
            this.highRisk = highRisk;
        }

        public double getMediumRisk() {
            return mediumRisk;
        }

        public void setMediumRisk(double mediumRisk) {
            this.mediumRisk = mediumRisk;
        }
    }

    public static class Ml {

        /**
         * Hard ceiling on the scoring call.
                 *
         * <p>Short on purpose. This call is on the payment's critical path, ahead of the provider
         * authorisation that has a three-second budget of its own, so a slow model eats the saga's
         * headroom. Better to fall back to rules quickly than to make every payment wait.
         */
        @NotNull
        private Duration timeout = Duration.ofMillis(1_200);

        /**
         * Attempts against ml-service, including the first.
         *
         * <p>Safe to retry, unlike a provider authorisation: scoring is a pure function with no side
         * effect, so a duplicate call charges nobody and returns the same answer.
         */
        @Min(1)
        private int maxAttempts = 2;

        @NotNull
        private Duration retryInterval = Duration.ofMillis(80);

        /**
         * Whether an unreachable model means the payment is refused rather than scored on rules alone.
         *
         * <p><strong>False by default, and that is a deliberate, arguable choice.</strong> Failing
         * *closed* in the strict sense — refusing every payment while the model is down — converts a
         * fraud-service outage into a total payment outage, which is a far larger incident than the
         * fraud it prevents. So the default is to fall back to the deterministic rules, mark the
         * verdict {@code degradedMode}, and alert: the rules still catch the patterns the model is
         * weakest on, which is exactly the coverage that matters when the model is absent.
         *
         * <p>Set true for an environment where refusing a payment is cheaper than missing fraud.
         */
        private boolean rejectWhenUnavailable;

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(Duration retryInterval) {
            this.retryInterval = retryInterval;
        }

        public boolean isRejectWhenUnavailable() {
            return rejectWhenUnavailable;
        }

        public void setRejectWhenUnavailable(boolean rejectWhenUnavailable) {
            this.rejectWhenUnavailable = rejectWhenUnavailable;
        }
    }

    /**
     * Rule tuning.
     *
     * <p>Each default was chosen against the model's measured weaknesses rather than picked from the
     * air. The bust-out numbers in particular exist because the model's recall on that archetype was
     * measured at 0.147 — see the README — so the rule is carrying that case almost alone.
     */
    public static class Rules {

        /** Window the velocity rules look back over. */
        @NotNull
        private Duration velocityWindow = Duration.ofHours(24);

        // ---- bust-out ----

        /**
         * Multiple of the account's largest-ever payment that counts as a bust-out.
         *
         * <p>1.6 matches the low end of the pattern the model was trained on and catches the case it
         * demonstrably misses. Above the historic maximum alone is far too common — customers make
         * their largest purchase all the time — so the rule also requires a new counterparty.
         */
        @DecimalMin("1.0")
        private double bustOutAmountMultiple = 1.6;

        /**
         * Prior payments before an account counts as established.
         *
         * <p>Without this, every second payment from a new account would look like a bust-out, because
         * a small history makes any amount a large multiple of it.
         */
        @Min(1)
        private long bustOutMinimumHistory = 20;

        // ---- card testing ----

        @Min(1)
        private long cardTestingFailures = 5;

        @Min(1)
        private long cardTestingAttempts1h = 5;

        // ---- impossible travel ----

        /**
         * Implied ground speed that cannot be a real journey, in km/h.
         *
         * <p>900 is roughly a commercial jet. Above it the two payments cannot both be the same
         * person travelling, so either the location data is wrong or one of them is not the customer —
         * and both are worth a human look. Deliberately not lower: a genuine flight plus an airport
         * VPN produces surprisingly high figures.
         */
        @DecimalMin("100.0")
        private double impossibleTravelKmh = 900.0;

        // ---- new device ----

        /** Multiple of the account's mean that makes a first payment from a new device notable. */
        @DecimalMin("1.0")
        private double newDeviceAmountMultiple = 5.0;

        // ---- dormancy ----

        @Min(1)
        private long dormantDays = 90;

        @DecimalMin("1.0")
        private double dormantAmountMultiple = 3.0;

        // ---- confirmed fraud history ----

        /** Prior confirmed frauds that put the account under review automatically. */
        @Min(1)
        private long confirmedFraudReviewCount = 1;

        /**
         * Prior confirmed frauds that refuse the payment outright.
         *
         * <p>The only automatic rejection in the set, and it rests on a human having already confirmed
         * fraud on this account this many times. That is evidence needing no further judgement, which
         * is the bar an automatic refusal has to meet.
         */
        @Min(2)
        private long confirmedFraudBlockCount = 3;

        public Duration getVelocityWindow() {
            return velocityWindow;
        }

        public void setVelocityWindow(Duration velocityWindow) {
            this.velocityWindow = velocityWindow;
        }

        public double getBustOutAmountMultiple() {
            return bustOutAmountMultiple;
        }

        public void setBustOutAmountMultiple(double bustOutAmountMultiple) {
            this.bustOutAmountMultiple = bustOutAmountMultiple;
        }

        public long getBustOutMinimumHistory() {
            return bustOutMinimumHistory;
        }

        public void setBustOutMinimumHistory(long bustOutMinimumHistory) {
            this.bustOutMinimumHistory = bustOutMinimumHistory;
        }

        public long getCardTestingFailures() {
            return cardTestingFailures;
        }

        public void setCardTestingFailures(long cardTestingFailures) {
            this.cardTestingFailures = cardTestingFailures;
        }

        public long getCardTestingAttempts1h() {
            return cardTestingAttempts1h;
        }

        public void setCardTestingAttempts1h(long cardTestingAttempts1h) {
            this.cardTestingAttempts1h = cardTestingAttempts1h;
        }

        public double getImpossibleTravelKmh() {
            return impossibleTravelKmh;
        }

        public void setImpossibleTravelKmh(double impossibleTravelKmh) {
            this.impossibleTravelKmh = impossibleTravelKmh;
        }

        public double getNewDeviceAmountMultiple() {
            return newDeviceAmountMultiple;
        }

        public void setNewDeviceAmountMultiple(double newDeviceAmountMultiple) {
            this.newDeviceAmountMultiple = newDeviceAmountMultiple;
        }

        public long getDormantDays() {
            return dormantDays;
        }

        public void setDormantDays(long dormantDays) {
            this.dormantDays = dormantDays;
        }

        public double getDormantAmountMultiple() {
            return dormantAmountMultiple;
        }

        public void setDormantAmountMultiple(double dormantAmountMultiple) {
            this.dormantAmountMultiple = dormantAmountMultiple;
        }

        public long getConfirmedFraudReviewCount() {
            return confirmedFraudReviewCount;
        }

        public void setConfirmedFraudReviewCount(long confirmedFraudReviewCount) {
            this.confirmedFraudReviewCount = confirmedFraudReviewCount;
        }

        public long getConfirmedFraudBlockCount() {
            return confirmedFraudBlockCount;
        }

        public void setConfirmedFraudBlockCount(long confirmedFraudBlockCount) {
            this.confirmedFraudBlockCount = confirmedFraudBlockCount;
        }
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public Ml getMl() {
        return ml;
    }

    public Rules getRules() {
        return rules;
    }
}
