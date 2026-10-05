package com.aegispay.fraud.rules;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import com.aegispay.fraud.config.FraudProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the deterministic rules.
 *
 * <p>These rules exist because the model's weaknesses were <em>measured</em>, not assumed. Recall per
 * archetype on held-out data at the blocking threshold: account_takeover 0.71, card_testing 0.60,
 * mule_cashout 0.33, bust_out 0.15, friendly_fraud 0.00. Bust-out in particular is carried almost
 * entirely by a rule, so the tests below assert both that it fires on the pattern and — more
 * importantly — that it does <em>not</em> fire on the ordinary customer behaviour that looks identical
 * feature by feature.
 *
 * <p>The false-negative tests matter as much as the positives. A rule that held every customer's
 * largest-ever purchase would be withdrawn within a day, and the platform would be left with the
 * model's 0.15 recall on the archetype the rule was written for.
 */
class RuleEngineTest {

    private static final String USER = "USR-PAYER";
    private static final String PAYEE_WALLET = "WLT-PAYEE";

    private FraudProperties properties;
    private RuleEngine engine;

    @BeforeEach
    void setUp() {
        properties = new FraudProperties();
        engine = new RuleEngine(properties, new SimpleMeterRegistry());
    }

    // ------------------------------------------------------------- bust-out

    @Nested
    @DisplayName("the bust-out rule")
    class BustOut {

        /**
         * The pattern the model measurably misses: a long-standing account sending its largest-ever
         * payment to a counterparty it has never paid.
         */
        @Test
        @DisplayName("holds an established account sending its largest payment to a new payee")
        void firesOnThePattern() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(40)
                    .average("500.00")
                    .largest("2000.00")
                    .amount("10000.00")
                    .newPayee(true)
                    .build());

            assertThat(ruleIds(hits)).contains(RuleEngine.RULE_BUST_OUT);
            assertThat(severityOf(hits, RuleEngine.RULE_BUST_OUT))
                    .as("holds rather than refuses: the false positive here is a real customer "
                            + "making a real purchase, and a delay is recoverable where a refusal "
                            + "is not")
                    .isEqualTo(RuleHit.Severity.HOLD);
        }

        /**
         * <strong>The most important negative in this class.</strong> Customers make their
         * largest-ever purchase all the time. If exceeding the historic maximum were enough on its
         * own, the rule would hold a large share of legitimate traffic and would have to be turned
         * off — leaving the archetype uncovered.
         */
        @Test
        @DisplayName("does not fire on a large payment to a payee the account already knows")
        void ignoresAFamiliarPayee() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(40)
                    .average("500.00")
                    .largest("2000.00")
                    .amount("10000.00")
                    .newPayee(false)
                    .build());

            assertThat(ruleIds(hits)).doesNotContain(RuleEngine.RULE_BUST_OUT);
        }

        /**
         * Without a history requirement every second payment from a new account would look like a
         * bust-out, because a tiny history makes any amount a large multiple of it.
         */
        @Test
        @DisplayName("does not fire on an account with too little history to depart from")
        void requiresEstablishedHistory() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(3)
                    .average("500.00")
                    .largest("600.00")
                    .amount("5000.00")
                    .newPayee(true)
                    .build());

            assertThat(ruleIds(hits)).doesNotContain(RuleEngine.RULE_BUST_OUT);
        }

        @Test
        @DisplayName("does not fire when the amount is within the account's normal range")
        void requiresTheAmountMultiple() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(40)
                    .average("500.00")
                    .largest("2000.00")
                    // 1.1x the historic maximum, below the 1.6x threshold.
                    .amount("2200.00")
                    .newPayee(true)
                    .build());

            assertThat(ruleIds(hits)).doesNotContain(RuleEngine.RULE_BUST_OUT);
        }
    }

    // --------------------------------------------------------- card testing

    @Nested
    @DisplayName("the card-testing rule")
    class CardTesting {

        @Test
        @DisplayName("holds a burst of attempts with several refused")
        void firesOnBurstWithFailures() {
            List<RuleHit> hits = engine.evaluate(context()
                    .attempts1h(8)
                    .failures24h(6)
                    .build());

            assertThat(ruleIds(hits)).contains(RuleEngine.RULE_CARD_TESTING);
            assertThat(severityOf(hits, RuleEngine.RULE_CARD_TESTING))
                    .isEqualTo(RuleHit.Severity.HOLD);
        }

        /**
         * Failures alone are ordinary — an expiring card produces them — so this records the signal
         * without acting on it. Advisory rather than nothing, because it is what an analyst reading a
         * held payment wants to see beside the reason it was held.
         */
        @Test
        @DisplayName("records failures alone as advisory, without escalating")
        void failuresAloneAreAdvisory() {
            List<RuleHit> hits = engine.evaluate(context()
                    .attempts1h(1)
                    .failures24h(6)
                    .build());

            assertThat(ruleIds(hits)).contains(RuleEngine.RULE_CARD_TESTING);
            assertThat(severityOf(hits, RuleEngine.RULE_CARD_TESTING))
                    .isEqualTo(RuleHit.Severity.ADVISORY);
            assertThat(severityOf(hits, RuleEngine.RULE_CARD_TESTING).escalates()).isFalse();
        }

        /** A burst with nothing failing is a customer doing their shopping. */
        @Test
        @DisplayName("does not fire on a burst of successful attempts")
        void burstAloneDoesNotFire() {
            List<RuleHit> hits = engine.evaluate(context()
                    .attempts1h(9)
                    .failures24h(0)
                    .build());

            assertThat(ruleIds(hits)).doesNotContain(RuleEngine.RULE_CARD_TESTING);
        }
    }

    // ----------------------------------------------------- impossible travel

    @Nested
    @DisplayName("the impossible-travel rule")
    class ImpossibleTravel {

        /**
         * Not a risk score but a contradiction. A model can only learn that large distances correlate
         * with fraud; it cannot express "this is physically impossible", because the impossibility is
         * in the ratio of two features rather than in either one.
         */
        @Test
        @DisplayName("holds 6,000km in eleven minutes")
        void firesOnTheImpossible() {
            List<RuleHit> hits = engine.evaluate(context()
                    .distanceKm(6_000.0)
                    .sinceLast(Duration.ofMinutes(11))
                    .build());

            assertThat(ruleIds(hits)).contains(RuleEngine.RULE_IMPOSSIBLE_TRAVEL);
            assertThat(severityOf(hits, RuleEngine.RULE_IMPOSSIBLE_TRAVEL))
                    .isEqualTo(RuleHit.Severity.HOLD);
        }

        /**
         * A real long-haul flight. The ceiling is deliberately set above commercial cruise speed,
         * because a genuine flight plus an airport VPN produces surprisingly high figures.
         */
        @Test
        @DisplayName("does not fire on a plausible flight")
        void ignoresARealJourney() {
            List<RuleHit> hits = engine.evaluate(context()
                    .distanceKm(6_000.0)
                    .sinceLast(Duration.ofHours(9))
                    .build());

            assertThat(ruleIds(hits)).doesNotContain(RuleEngine.RULE_IMPOSSIBLE_TRAVEL);
        }

        /** No coordinates means no claim either way, not a claim of zero distance. */
        @Test
        @DisplayName("does not fire when there is no location to compare")
        void needsBothEnds() {
            assertThat(ruleIds(engine.evaluate(context()
                    .sinceLast(Duration.ofMinutes(2))
                    .build())))
                    .doesNotContain(RuleEngine.RULE_IMPOSSIBLE_TRAVEL);

            assertThat(ruleIds(engine.evaluate(context()
                    .distanceKm(6_000.0)
                    .build())))
                    .as("without an elapsed time there is no speed to compute")
                    .doesNotContain(RuleEngine.RULE_IMPOSSIBLE_TRAVEL);
        }
    }

    // ------------------------------------------------------ confirmed fraud

    @Nested
    @DisplayName("the confirmed-fraud-history rule")
    class ConfirmedFraud {

        /**
         * The only automatic refusal in the set, and it rests on prior human judgement rather than on
         * a threshold anyone tuned. That is the bar an automatic refusal has to meet: evidence needing
         * no further interpretation.
         */
        @Test
        @DisplayName("refuses an account with three confirmed frauds")
        void refusesAtTheBlockCount() {
            List<RuleHit> hits = engine.evaluate(context().confirmedFraud(3).build());

            assertThat(ruleIds(hits)).contains(RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY);
            assertThat(severityOf(hits, RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY))
                    .isEqualTo(RuleHit.Severity.BLOCK);
            assertThat(severityOf(hits, RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY).minimumDecision())
                    .isEqualTo(FraudDecision.REJECT);
        }

        @Test
        @DisplayName("only reviews a single prior confirmed fraud")
        void reviewsAtTheReviewCount() {
            List<RuleHit> hits = engine.evaluate(context().confirmedFraud(1).build());

            assertThat(severityOf(hits, RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY))
                    .as("one prior fraud is worth a look, not an automatic refusal")
                    .isEqualTo(RuleHit.Severity.REVIEW);
        }

        @Test
        @DisplayName("does not fire on a clean account")
        void quietOnACleanAccount() {
            assertThat(ruleIds(engine.evaluate(context().build())))
                    .doesNotContain(RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY);
        }
    }

    // -------------------------------------------------------- other patterns

    @Nested
    @DisplayName("the remaining rules")
    class Others {

        /**
         * Review rather than hold: replacing a phone is completely ordinary, and holding every
         * customer's first payment from a new handset would be a self-inflicted outage on upgrade day.
         */
        @Test
        @DisplayName("a large payment from a new device is reviewed, not held")
        void newDeviceIsReviewed() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(10)
                    .average("500.00")
                    .amount("5000.00")
                    .newDevice(true)
                    .deviceFingerprint("fp-new")
                    .build());

            assertThat(severityOf(hits, RuleEngine.RULE_NEW_DEVICE_HIGH_VALUE))
                    .isEqualTo(RuleHit.Severity.REVIEW);
        }

        @Test
        @DisplayName("a dormant account waking up with a large payment is reviewed")
        void dormantReactivation() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(10)
                    .average("500.00")
                    .amount("2000.00")
                    .sinceLast(Duration.ofDays(120))
                    .build());

            assertThat(severityOf(hits, RuleEngine.RULE_DORMANT_REACTIVATION))
                    .isEqualTo(RuleHit.Severity.REVIEW);
        }

        @Test
        @DisplayName("a cash-out to a new payee from an unfamiliar country is reviewed")
        void unfamiliarCorridor() {
            List<RuleHit> hits = engine.evaluate(context()
                    .paymentType(PaymentType.WALLET_WITHDRAWAL)
                    .newPayee(true)
                    .newCountry(true)
                    .country("RO")
                    .build());

            assertThat(severityOf(hits, RuleEngine.RULE_UNFAMILIAR_CORRIDOR))
                    .isEqualTo(RuleHit.Severity.REVIEW);
        }

        /** An ordinary holiday withdrawal to a payee the account knows must not fire it. */
        @Test
        @DisplayName("a holiday withdrawal to a familiar payee does not fire the corridor rule")
        void familiarPayeeAbroadIsFine() {
            List<RuleHit> hits = engine.evaluate(context()
                    .paymentType(PaymentType.WALLET_WITHDRAWAL)
                    .newPayee(false)
                    .newCountry(true)
                    .country("ES")
                    .build());

            assertThat(ruleIds(hits)).doesNotContain(RuleEngine.RULE_UNFAMILIAR_CORRIDOR);
        }

        /**
         * Advisory only, and deliberately so. There is no history to compare against, which means
         * there is no evidence either way — escalating would penalise every new customer's first
         * transaction, which is both wrong and the most expensive moment to get wrong.
         */
        @Test
        @DisplayName("a large first payment is recorded without escalating")
        void firstPaymentIsAdvisoryOnly() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(0)
                    .amount("50000.00")
                    .build());

            assertThat(severityOf(hits, RuleEngine.RULE_FIRST_PAYMENT_LARGE))
                    .isEqualTo(RuleHit.Severity.ADVISORY);
            assertThat(severityOf(hits, RuleEngine.RULE_FIRST_PAYMENT_LARGE).escalates())
                    .as("a new customer's first payment must not be held for lack of history")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------- the engine

    @Nested
    @DisplayName("the engine itself")
    class Engine {

        /**
         * Short-circuiting on the first hit would be faster and would lose the reason an analyst most
         * wants: which patterns were present together. Three advisory hits on one payment is a
         * different picture from one.
         */
        @Test
        @DisplayName("evaluates every rule rather than stopping at the first hit")
        void evaluatesEveryRule() {
            List<RuleHit> hits = engine.evaluate(context()
                    .confirmedFraud(1)
                    .attempts1h(1)
                    .failures24h(6)
                    .distanceKm(6_000.0)
                    .sinceLast(Duration.ofMinutes(11))
                    .build());

            assertThat(ruleIds(hits)).contains(
                    RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY,
                    RuleEngine.RULE_CARD_TESTING,
                    RuleEngine.RULE_IMPOSSIBLE_TRAVEL);
        }

        /** A clean payment must produce nothing. Otherwise the queue fills with noise. */
        @Test
        @DisplayName("finds nothing on a routine payment from an established account")
        void quietOnRoutineTraffic() {
            List<RuleHit> hits = engine.evaluate(context()
                    .priorPayments(50)
                    .average("500.00")
                    .largest("900.00")
                    .amount("450.00")
                    .attempts1h(1)
                    .attempts24h(3)
                    .newPayee(false)
                    .sinceLast(Duration.ofHours(6))
                    .build());

            assertThat(hits).isEmpty();
        }

        /**
         * A bug in one rule must not refuse a customer's payment, and must not take down the whole
         * fraud path. The remaining rules and the model still produce a defensible verdict.
         */
        @Test
        @DisplayName("survives a rule that throws")
        void survivesAThrowingRule() {
            // Forces an arithmetic path a rule reads: a zero-valued average would divide by zero if
            // the context did not guard it. The engine must return rather than propagate whatever
            // happens.
            properties.getRules().setImpossibleTravelKmh(100.0);

            List<RuleHit> hits = engine.evaluate(context()
                    .distanceKm(Double.NaN)
                    .sinceLast(Duration.ofMinutes(5))
                    .build());

            assertThat(hits).as("a rule fault must never fail the assessment").isNotNull();
        }

        /**
         * Rule ids are stored on assessments, tagged on metrics and quoted in alerts. Renaming one
         * silently breaks a dashboard and orphans the history, so the advertised list has to be
         * complete and stable.
         */
        @Test
        @DisplayName("advertises every rule id it can emit")
        void advertisesEveryRuleId() {
            assertThat(engine.ruleIds()).containsExactlyInAnyOrder(
                    RuleEngine.RULE_BUST_OUT,
                    RuleEngine.RULE_CARD_TESTING,
                    RuleEngine.RULE_IMPOSSIBLE_TRAVEL,
                    RuleEngine.RULE_NEW_DEVICE_HIGH_VALUE,
                    RuleEngine.RULE_CONFIRMED_FRAUD_HISTORY,
                    RuleEngine.RULE_DORMANT_REACTIVATION,
                    RuleEngine.RULE_UNFAMILIAR_CORRIDOR,
                    RuleEngine.RULE_FIRST_PAYMENT_LARGE);
        }

        /**
         * No rule may lower a verdict. If one could, adding a rule would be capable of letting fraud
         * through, and two such rules would interact in ways nobody could reason about.
         */
        @Test
        @DisplayName("no rule can de-escalate")
        void rulesOnlyEscalate() {
            for (RuleHit.Severity severity : RuleHit.Severity.values()) {
                if (severity.escalates()) {
                    assertThat(severity.minimumRiskLevel())
                            .as("%s escalates, so it must name a floor", severity)
                            .isNotNull();
                    assertThat(severity.minimumDecision()).isNotNull();
                } else {
                    assertThat(severity.minimumRiskLevel())
                            .as("%s does not escalate, so it must not impose a level", severity)
                            .isNull();
                }
            }
        }

        /** CRITICAL and REJECT must be reachable from a rule, since no probability produces them. */
        @Test
        @DisplayName("only a rule can reach CRITICAL")
        void onlyARuleReachesCritical() {
            assertThat(RuleHit.Severity.BLOCK.minimumRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
            assertThat(RuleHit.Severity.BLOCK.minimumDecision()).isEqualTo(FraudDecision.REJECT);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> ruleIds(List<RuleHit> hits) {
        return hits.stream().map(RuleHit::ruleId).toList();
    }

    private static RuleHit.Severity severityOf(List<RuleHit> hits, String ruleId) {
        return hits.stream()
                .filter(hit -> hit.ruleId().equals(ruleId))
                .map(RuleHit::severity)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected rule " + ruleId + " to fire, but only these did: "
                                + ruleIds(hits)));
    }

    private static Builder context() {
        return new Builder();
    }

    /**
     * Builds a routine payment that fires nothing, so each test changes only the facts it is about.
     *
     * <p>Deliberately verbose rather than a partial mock: a context assembled from real values is the
     * only way to be sure a rule is reading the field it claims to.
     */
    private static final class Builder {

        private Money amount = Money.of("100.00", "INR");
        private PaymentType paymentType = PaymentType.P2P_TRANSFER;
        private long priorPayments;
        private long confirmedFraud;
        private Optional<Money> average = Optional.empty();
        private Optional<Money> largest = Optional.empty();
        private long attempts1h;
        private long attempts24h;
        private long failures24h;
        private Optional<Duration> sinceLast = Optional.empty();
        private boolean newDevice;
        private String deviceFingerprint;
        private boolean newCountry;
        private String country;
        private Optional<Double> distanceKm = Optional.empty();
        private boolean newPayee;

        Builder amount(String value) {
            this.amount = Money.of(value, "INR");
            return this;
        }

        Builder paymentType(PaymentType value) {
            this.paymentType = value;
            return this;
        }

        Builder priorPayments(long value) {
            this.priorPayments = value;
            return this;
        }

        Builder confirmedFraud(long value) {
            this.confirmedFraud = value;
            return this;
        }

        Builder average(String value) {
            this.average = Optional.of(Money.of(value, "INR"));
            return this;
        }

        Builder largest(String value) {
            this.largest = Optional.of(Money.of(value, "INR"));
            return this;
        }

        Builder attempts1h(long value) {
            this.attempts1h = value;
            return this;
        }

        Builder attempts24h(long value) {
            this.attempts24h = value;
            return this;
        }

        Builder failures24h(long value) {
            this.failures24h = value;
            return this;
        }

        Builder sinceLast(Duration value) {
            this.sinceLast = Optional.of(value);
            return this;
        }

        Builder newDevice(boolean value) {
            this.newDevice = value;
            return this;
        }

        Builder deviceFingerprint(String value) {
            this.deviceFingerprint = value;
            return this;
        }

        Builder newCountry(boolean value) {
            this.newCountry = value;
            return this;
        }

        Builder country(String value) {
            this.country = value;
            return this;
        }

        Builder distanceKm(double value) {
            this.distanceKm = Optional.of(value);
            return this;
        }

        Builder newPayee(boolean value) {
            this.newPayee = value;
            return this;
        }

        FraudContext build() {
            RiskSignals signals = new RiskSignals(null, deviceFingerprint, "203.0.113.7",
                    "curl/8.4.0", country, null, null, null, Channel.API, null, null);

            return new FraudContext("PAY-TEST-1", "TXN-1", USER, PAYEE_WALLET,
                    amount, paymentType, signals, Instant.parse("2026-09-01T12:00:00Z"),
                    Optional.of(365L), priorPayments, confirmedFraud,
                    average, largest,
                    attempts1h, attempts24h, Money.ofMinor(0L, "INR"), failures24h, sinceLast,
                    newDevice, 1L, newCountry, 1L, distanceKm,
                    newPayee);
        }
    }
}
