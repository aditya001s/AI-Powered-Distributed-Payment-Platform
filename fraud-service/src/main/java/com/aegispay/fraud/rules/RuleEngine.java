package com.aegispay.fraud.rules;

import com.aegispay.common.observability.MetricNames;
import com.aegispay.fraud.config.FraudProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The deterministic rules, evaluated alongside the model.
 *
 * <h2>Why rules at all, when there is a gradient-boosted model</h2>
 *
 * <p>Not belt-and-braces, and not distrust of the model. The rules exist because the model's
 * weaknesses were <em>measured</em>, and they are specific. Recall per attack archetype on held-out
 * data, at the blocking threshold:
 *
 * <pre>
 *   account_takeover   0.71
 *   card_testing       0.60
 *   mule_cashout       0.33
 *   bust_out           0.15   &lt;-- almost entirely missed
 *   friendly_fraud     0.00   &lt;-- correctly impossible
 * </pre>
 *
 * <p>Bust-out is the genuinely hard case: a long-standing account that behaved normally for a year
 * and then sends its largest-ever payment to a brand-new counterparty is, feature for feature, nearly
 * identical to a customer finally buying a car. The model cannot separate them and the measurement
 * says so. A rule can, because it can insist on the <em>conjunction</em> — established history, a
 * large multiple of the historic maximum, and a new payee — and accept the false positives that
 * conjunction still produces, in exchange for catching a pattern that would otherwise walk through.
 *
 * <p>Two other things rules do that a probability cannot. They express physical impossibility:
 * 6,000km in eleven minutes is not a risk score, it is a contradiction. And they carry the
 * platform's {@code CRITICAL} band, which the {@link com.aegispay.common.domain.RiskLevel} contract
 * reserves for confirmed patterns and hard violations — a model score, however high, is neither.
 *
 * <h2>Rules escalate only</h2>
 *
 * <p>A rule can make a verdict more cautious and never less. If a rule could lower a risk level the
 * model raised, adding a rule would be capable of letting fraud through, and two such rules would
 * interact in ways nobody could reason about. So the combination is a floor, never an override.
 *
 * <h2>Every rule is evaluated, not just the first to fire</h2>
 *
 * <p>Short-circuiting on the first hit would be faster and would lose the reason an analyst most
 * wants: which patterns were present together. Three advisory hits on one payment is a different
 * picture from one, even though neither changes the decision.
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    // Rule identifiers are part of the platform's observable surface: they are stored on the
    // assessment, tagged on a metric and quoted in alerts. Renaming one silently breaks a dashboard
    // and orphans the history, so they are constants rather than inline strings.
    public static final String RULE_BUST_OUT = "RULE_BUST_OUT_PATTERN";
    public static final String RULE_CARD_TESTING = "RULE_CARD_TESTING_VELOCITY";
    public static final String RULE_IMPOSSIBLE_TRAVEL = "RULE_IMPOSSIBLE_TRAVEL";
    public static final String RULE_NEW_DEVICE_HIGH_VALUE = "RULE_NEW_DEVICE_HIGH_VALUE";
    public static final String RULE_CONFIRMED_FRAUD_HISTORY = "RULE_CONFIRMED_FRAUD_HISTORY";
    public static final String RULE_DORMANT_REACTIVATION = "RULE_DORMANT_ACCOUNT_LARGE_PAYMENT";
    public static final String RULE_UNFAMILIAR_CORRIDOR = "RULE_UNFAMILIAR_CASH_OUT_CORRIDOR";
    public static final String RULE_FIRST_PAYMENT_LARGE = "RULE_FIRST_PAYMENT_UNUSUALLY_LARGE";

    /** Every rule, in the order their reasons are reported. */
    private final List<Function<FraudContext, Optional<RuleHit>>> rules;

    private final FraudProperties properties;
    private final MeterRegistry meterRegistry;

    public RuleEngine(FraudProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.rules = List.of(
                this::confirmedFraudHistory,
                this::bustOutPattern,
                this::cardTestingVelocity,
                this::impossibleTravel,
                this::newDeviceHighValue,
                this::dormantReactivation,
                this::unfamiliarCashOutCorridor,
                this::firstPaymentUnusuallyLarge);
    }

    /**
     * Evaluates every rule against one payment.
     *
     * <p>A rule that throws is skipped rather than allowed to fail the assessment. A bug in one rule
     * must not refuse a customer's payment or, worse, take down the whole fraud path — the remaining
     * rules and the model still produce a defensible verdict, and the failure is logged loudly enough
     * to be fixed.
     */
    public List<RuleHit> evaluate(FraudContext context) {
        List<RuleHit> hits = new ArrayList<>();

        for (Function<FraudContext, Optional<RuleHit>> rule : rules) {
            try {
                rule.apply(context).ifPresent(hits::add);
            } catch (RuntimeException ex) {
                log.error("Fraud rule threw while assessing payment {}; skipping it",
                        context.paymentId(), ex);
            }
        }

        for (RuleHit hit : hits) {
            meterRegistry.counter(MetricNames.FRAUD_RULE_HIT,
                    "ruleId", hit.ruleId(),
                    "severity", hit.severity().name()).increment();
        }
        if (!hits.isEmpty()) {
            log.debug("Payment {} matched {} rule(s): {}",
                    context.paymentId(), hits.size(), hits.stream().map(RuleHit::ruleId).toList());
        }
        return List.copyOf(hits);
    }

    /** Every rule id this engine can emit, for the admin view. */
    public List<String> ruleIds() {
        return List.of(RULE_CONFIRMED_FRAUD_HISTORY, RULE_BUST_OUT, RULE_CARD_TESTING,
                RULE_IMPOSSIBLE_TRAVEL, RULE_NEW_DEVICE_HIGH_VALUE, RULE_DORMANT_REACTIVATION,
                RULE_UNFAMILIAR_CORRIDOR, RULE_FIRST_PAYMENT_LARGE);
    }

    // ------------------------------------------------------------------- rules

    /**
     * A human has already confirmed fraud on this account.
     *
     * <p>The only rule that can refuse a payment automatically, and it rests entirely on prior human
     * judgement rather than on a threshold anyone tuned. That is the bar an automatic rejection has to
     * meet: evidence needing no further interpretation.
     */
    private Optional<RuleHit> confirmedFraudHistory(FraudContext context) {
        long confirmed = context.confirmedFraudCount();
        FraudProperties.Rules config = properties.getRules();

        if (confirmed >= config.getConfirmedFraudBlockCount()) {
            return Optional.of(RuleHit.block(RULE_CONFIRMED_FRAUD_HISTORY,
                    "This account has %d payments an analyst confirmed as fraud".formatted(confirmed)));
        }
        if (confirmed >= config.getConfirmedFraudReviewCount()) {
            return Optional.of(RuleHit.review(RULE_CONFIRMED_FRAUD_HISTORY,
                    "This account has %d prior payment(s) confirmed as fraud".formatted(confirmed)));
        }
        return Optional.empty();
    }

    /**
     * Bust-out: a patient account emptying itself in one move.
     *
     * <p><strong>The rule that exists because the model measurably cannot do this.</strong> Recall on
     * this archetype was 0.147 on held-out data, and the reason is that the pattern is nearly
     * indistinguishable from a customer's first genuinely large purchase.
     *
     * <p>The conjunction is what makes it usable. Exceeding the historic maximum alone is far too
     * common to act on; requiring an established history, a substantial multiple of that maximum,
     * <em>and</em> a counterparty never paid before narrows it to something worth a human's time. It
     * holds rather than rejects, because the false positive here is a real customer making a real
     * purchase, and telling them to wait is recoverable where refusing them is not.
     */
    private Optional<RuleHit> bustOutPattern(FraudContext context) {
        FraudProperties.Rules config = properties.getRules();

        if (!context.hasEstablishedHistory(config.getBustOutMinimumHistory())) {
            return Optional.empty();
        }
        if (!context.newPayee()) {
            return Optional.empty();
        }
        return context.amountToLargestRatio()
                .filter(ratio -> ratio >= config.getBustOutAmountMultiple())
                .map(ratio -> RuleHit.hold(RULE_BUST_OUT,
                        ("%s is %.1fx this account's largest payment in %d prior payments, "
                                + "and the payee has never been paid before")
                                .formatted(context.amount(), ratio, context.priorPaymentCount())));
    }

    /**
     * Credential probing: a burst of attempts with several refused.
     *
     * <p>Requires both the failures and the burst. Failures alone are ordinary — an expiring card
     * produces them — and a burst alone is a customer doing their shopping. Together they are somebody
     * finding out which credentials still work.
     */
    private Optional<RuleHit> cardTestingVelocity(FraudContext context) {
        FraudProperties.Rules config = properties.getRules();

        boolean manyFailures = context.failures24h() >= config.getCardTestingFailures();
        boolean burst = context.attempts1h() >= config.getCardTestingAttempts1h();

        if (manyFailures && burst) {
            return Optional.of(RuleHit.hold(RULE_CARD_TESTING,
                    ("%d attempt(s) in the last hour with %d failure(s) in 24 hours, "
                            + "which is the shape of credential testing")
                            .formatted(context.attempts1h(), context.failures24h())));
        }
        // Either signal alone is worth recording without acting: it is what an analyst reading a
        // held payment wants to see next to the reason it was held.
        if (manyFailures) {
            return Optional.of(RuleHit.advisory(RULE_CARD_TESTING,
                    "%d failed attempt(s) in the last 24 hours".formatted(context.failures24h())));
        }
        return Optional.empty();
    }

    /**
     * Two payments that cannot both be the same person travelling.
     *
     * <p>Not a risk score but a contradiction: either the location data is wrong or one of these
     * payments is not the customer. A model can only learn that large distances correlate with fraud;
     * it cannot express "this is physically impossible", because the impossibility is in the ratio of
     * two features rather than in either one.
     */
    private Optional<RuleHit> impossibleTravel(FraudContext context) {
        double ceiling = properties.getRules().getImpossibleTravelKmh();

        return context.impliedSpeedKmh()
                .filter(speed -> speed > ceiling)
                .map(speed -> RuleHit.hold(RULE_IMPOSSIBLE_TRAVEL,
                        ("Implies travelling %.0f km/h since the previous payment (%.0f km in %s), "
                                + "which is not a journey")
                                .formatted(speed,
                                        context.distanceFromLastKm().orElse(0.0),
                                        humanise(context))));
    }

    /**
     * A large payment from a device the account has never used.
     *
     * <p>Review rather than hold: replacing a phone is completely ordinary, and holding every
     * customer's first payment from a new handset would be a self-inflicted outage on upgrade day.
     * The amount multiple is what makes it worth a look at all.
     */
    private Optional<RuleHit> newDeviceHighValue(FraudContext context) {
        if (!context.newDevice()) {
            return Optional.empty();
        }
        double multiple = properties.getRules().getNewDeviceAmountMultiple();

        return context.amountToAverageRatio()
                .filter(ratio -> ratio >= multiple)
                .map(ratio -> RuleHit.review(RULE_NEW_DEVICE_HIGH_VALUE,
                        ("First payment from this device, for %.1fx the account's usual amount")
                                .formatted(ratio)));
    }

    /**
     * A dormant account waking up with a large payment.
     *
     * <p>The pattern behind a resold or recovered credential: an account nobody has used for months
     * is a quieter target than an active one, because the real owner is not watching it.
     */
    private Optional<RuleHit> dormantReactivation(FraudContext context) {
        FraudProperties.Rules config = properties.getRules();

        if (!context.dormantFor(config.getDormantDays())) {
            return Optional.empty();
        }
        return context.amountToAverageRatio()
                .filter(ratio -> ratio >= config.getDormantAmountMultiple())
                .map(ratio -> RuleHit.review(RULE_DORMANT_REACTIVATION,
                        ("Account dormant for %d days, now sending %.1fx its usual amount")
                                .formatted(context.sinceLastPayment()
                                        .map(java.time.Duration::toDays).orElse(0L), ratio)));
    }

    /**
     * A cash-out to a new counterparty from an unfamiliar country.
     *
     * <p>The mule pattern, and the archetype the model scores 0.33 on — better than bust-out but far
     * from covered. The conjunction of all three is what keeps it from firing on an ordinary holiday
     * withdrawal.
     */
    private Optional<RuleHit> unfamiliarCashOutCorridor(FraudContext context) {
        if (!context.isWithdrawal() || !context.newPayee() || !context.fromUnfamiliarCountry()) {
            return Optional.empty();
        }
        return Optional.of(RuleHit.review(RULE_UNFAMILIAR_CORRIDOR,
                ("Withdrawal to a new payee from %s, a country this account has not used before")
                        .formatted(context.countryCode())));
    }

    /**
     * An account's very first payment, for a large amount.
     *
     * <p>Advisory only, and deliberately so. There is no history to compare against, which means
     * there is no evidence either way — so this records the absence rather than pretending it is
     * suspicion. Escalating on it would penalise every new customer's first transaction, which is
     * both wrong and the most expensive moment to get wrong.
     */
    private Optional<RuleHit> firstPaymentUnusuallyLarge(FraudContext context) {
        if (context.priorPaymentCount() > 0 || context.averageAmount().isPresent()) {
            return Optional.empty();
        }
        // A round million minor units: large enough to be worth noting on an account with no
        // history at all, without being a threshold anyone should act on.
        if (context.amount().amountMinor() < 1_000_000L) {
            return Optional.empty();
        }
        return Optional.of(RuleHit.advisory(RULE_FIRST_PAYMENT_LARGE,
                ("First payment on this account, for %s, so there is no history to compare against")
                        .formatted(context.amount())));
    }

    private static String humanise(FraudContext context) {
        return context.sinceLastPayment()
                .map(elapsed -> elapsed.toMinutes() < 60
                        ? elapsed.toMinutes() + " minutes"
                        : elapsed.toHours() + " hours")
                .orElse("an unknown interval");
    }
}
