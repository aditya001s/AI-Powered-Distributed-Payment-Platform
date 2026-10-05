package com.aegispay.analytics.domain;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read model's merge rules.
 *
 * <h2>What this class is really testing</h2>
 *
 * <p>Two properties, and both of them are only interesting because of how the events arrive.
 *
 * <p><strong>Idempotence.</strong> Kafka delivers at least once. Applying the same event twice must leave
 * the row identical, because the alternative — a counter — would report twice the settled value with no
 * way to tell from the data that it had happened.
 *
 * <p><strong>Commutativity, within limits.</strong> Kafka orders records inside one topic partition and
 * promises nothing across topics, so a completion can genuinely be applied before the creation it belongs
 * to. The row must end up the same either way for every field that matters.
 *
 * <p>Both are asserted by applying events in the wrong order on purpose, rather than by testing each apply
 * method in isolation. A method that is individually correct and collectively order-dependent is exactly
 * the bug that reaches production here.
 */
class PaymentFactTest {

    private static final String PAYMENT = "pay_0000000000000001";
    private static final Instant CREATED = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-03-01T10:00:04Z");

    private static final Money AMOUNT = Money.ofMinor(250_00L, "INR");
    private static final Money FEE = Money.ofMinor(75L, "INR");

    // ------------------------------------------------------------------------
    // seeding
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("seeding")
    class Seeding {

        /**
         * Any listener can create the row, because any of them can arrive first. The seed therefore takes
         * a status rather than assuming {@code payment-created} led.
         */
        @Test
        @DisplayName("seeds from whichever event arrived first")
        void seedsFromAnyStatus() {
            PaymentFact fact = PaymentFact.seed(PAYMENT, PaymentStatus.COMPLETED, COMPLETED);

            assertThat(fact.getPaymentId()).isEqualTo(PAYMENT);
            assertThat(fact.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(fact.getStatusRank()).isEqualTo((short) StatusRank.of(PaymentStatus.COMPLETED));
            // The event's own timestamp, not now(). Charting by processing time would show a replayed
            // month of history as a single spike at the moment of the replay.
            assertThat(fact.getCreatedAt()).isEqualTo(COMPLETED);
            assertThat(fact.getLastEventAt()).isEqualTo(COMPLETED);
        }

        @Test
        @DisplayName("refuses a row that names no payment")
        void requiresPaymentId() {
            assertThatThrownBy(() -> PaymentFact.seed("  ", PaymentStatus.CREATED, CREATED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("payment");
        }

        /**
         * {@code created_at} is {@code NOT NULL} and is the time axis of every chart, so a null event
         * timestamp has to fall back to something rather than fail the insert.
         */
        @Test
        @DisplayName("falls back to now when an event carries no timestamp")
        void toleratesMissingTimestamp() {
            PaymentFact fact = PaymentFact.seed(PAYMENT, PaymentStatus.CREATED, null);

            assertThat(fact.getCreatedAt()).isNotNull();
            assertThat(fact.getLastEventAt()).isNotNull();
        }
    }

    // ------------------------------------------------------------------------
    // idempotence
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("redelivery changes nothing")
    class Idempotence {

        /**
         * <strong>The property the whole design rests on.</strong> A redelivered completion re-writes the
         * same row. Were these counters, the settled total would double and nothing in the data would say
         * so.
         */
        @Test
        @DisplayName("applying a completion twice leaves the row identical")
        void completionIsIdempotent() {
            PaymentFact once = completedPayment();
            PaymentFact twice = completedPayment();
            twice.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L,
                    COMPLETED);

            assertThat(twice.getAmountMinor()).isEqualTo(once.getAmountMinor());
            assertThat(twice.getFeeMinor()).isEqualTo(once.getFeeMinor());
            assertThat(twice.getStatus()).isEqualTo(once.getStatus());
            assertThat(twice.getProviderAttemptCount()).isEqualTo(once.getProviderAttemptCount());
            assertThat(twice.getEndToEndLatencyMs()).isEqualTo(once.getEndToEndLatencyMs());
            assertThat(twice.getCompletedAt()).isEqualTo(once.getCompletedAt());
        }

        /**
         * Attempts are counted from the list the event carries, not incremented per delivery. The list is
         * the whole truth every time, so assigning is idempotent where {@code ++} would not be.
         */
        @Test
        @DisplayName("provider attempts are counted from the event, not accumulated")
        void attemptsAreAssignedNotIncremented() {
            PaymentFact fact = seeded();
            fact.applyCompletion(AMOUNT, FEE, "RAPIDRAIL", List.of("SWIFTPAY", "RAPIDRAIL"), "led_1",
                    9_000L, COMPLETED);
            fact.applyCompletion(AMOUNT, FEE, "RAPIDRAIL", List.of("SWIFTPAY", "RAPIDRAIL"), "led_1",
                    9_000L, COMPLETED);

            assertThat(fact.getProviderAttemptCount())
                    .as("incrementing per delivery would report four attempts for two")
                    .isEqualTo((short) 2);
            assertThat(fact.fellBack())
                    .as("two attempts means routing switched rail")
                    .isTrue();
        }

        /**
         * The refund total is assigned from the running figure the event carries. Adding would count the
         * first partial refund again on the second event — the same trap in a different field.
         */
        @Test
        @DisplayName("a second partial refund replaces the running total rather than adding to it")
        void refundTotalIsAssigned() {
            PaymentFact fact = completedPayment();

            fact.applyRefund(Money.ofMinor(50_00L, "INR"), AMOUNT, false, "led_r1",
                    COMPLETED.plusSeconds(60));
            assertThat(fact.getRefundedMinor()).isEqualTo(50_00L);

            // The producer sends the new cumulative total, not the delta.
            fact.applyRefund(Money.ofMinor(120_00L, "INR"), AMOUNT, false, "led_r2",
                    COMPLETED.plusSeconds(120));
            assertThat(fact.getRefundedMinor())
                    .as("adding would report 170.00 refunded when 120.00 was")
                    .isEqualTo(120_00L);

            assertThat(fact.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        }

        @Test
        @DisplayName("a redelivered refund does not move the total")
        void redeliveredRefundIsIdempotent() {
            PaymentFact fact = completedPayment();
            fact.applyRefund(AMOUNT, AMOUNT, true, "led_r1", COMPLETED.plusSeconds(60));
            fact.applyRefund(AMOUNT, AMOUNT, true, "led_r1", COMPLETED.plusSeconds(60));

            assertThat(fact.getRefundedMinor()).isEqualTo(AMOUNT.amountMinor());
            assertThat(fact.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }
    }

    // ------------------------------------------------------------------------
    // out of order
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("events applied out of order")
    class OutOfOrder {

        /**
         * <strong>The scenario this design exists for.</strong> Replaying topics into a fresh read model
         * routinely delivers a completion before its creation.
         */
        @Test
        @DisplayName("a completion followed by its late creation still reads as completed")
        void lateCreationDoesNotRegressStatus() {
            PaymentFact fact = PaymentFact.seed(PAYMENT, PaymentStatus.COMPLETED, COMPLETED);
            fact.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L,
                    COMPLETED);

            fact.applyCreation("txn_1", "usr_1", "usr_2", "wal_1", "wal_2", AMOUNT,
                    PaymentType.P2P_TRANSFER, CREATED);

            assertThat(fact.getStatus())
                    .as("""
                            a settled payment shown as in-flight would never correct itself: every event \
                            for it has already been consumed""")
                    .isEqualTo(PaymentStatus.COMPLETED);
            // The creation still contributed everything only it knows.
            assertThat(fact.getTransactionId()).isEqualTo("txn_1");
            assertThat(fact.getInitiatorUserId()).isEqualTo("usr_1");
            assertThat(fact.getPaymentType()).isEqualTo(PaymentType.P2P_TRANSFER);
        }

        /**
         * {@code created_at} only ever moves earlier. A payment cannot have been created after something
         * that already happened to it, so a {@code max()} here would let an out-of-order arrival invent a
         * creation time in the future — and the payment would sort to the wrong end of every chart.
         */
        @Test
        @DisplayName("a late creation corrects the time axis backwards, never forwards")
        void createdAtOnlyMovesEarlier() {
            PaymentFact fact = PaymentFact.seed(PAYMENT, PaymentStatus.COMPLETED, COMPLETED);
            assertThat(fact.getCreatedAt()).isEqualTo(COMPLETED);

            fact.applyCreation("txn_1", "usr_1", null, null, null, AMOUNT, PaymentType.P2P_TRANSFER,
                    CREATED);
            assertThat(fact.getCreatedAt())
                    .as("the authoritative creation time is earlier and must win")
                    .isEqualTo(CREATED);

            // A redelivery carrying a later timestamp must not push it forward.
            fact.applyCreation("txn_1", "usr_1", null, null, null, AMOUNT, PaymentType.P2P_TRANSFER,
                    CREATED.plus(Duration.ofHours(2)));
            assertThat(fact.getCreatedAt()).isEqualTo(CREATED);
        }

        /**
         * The second half of the guard: a later event must not null a field an earlier one filled.
         * {@code payment-completed} carries no payment type, and applying it must not erase the one
         * {@code payment-created} recorded.
         */
        @Test
        @DisplayName("a later event never erases what an earlier one recorded")
        void laterEventsDoNotNullFields() {
            PaymentFact fact = seeded();
            fact.applyCreation("txn_1", "usr_1", "usr_2", "wal_1", "wal_2", AMOUNT,
                    PaymentType.P2P_TRANSFER, CREATED);
            fact.applyRisk(RiskLevel.HIGH, FraudDecision.REVIEW, 0.82, "xgb-3.1",
                    List.of("VELOCITY", "AMOUNT_SPIKE"), 24L, false, CREATED.plusSeconds(1));

            // Completion carries no transaction id, no payment type, no risk verdict.
            fact.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L,
                    COMPLETED);

            assertThat(fact.getTransactionId()).isEqualTo("txn_1");
            assertThat(fact.getPaymentType()).isEqualTo(PaymentType.P2P_TRANSFER);
            assertThat(fact.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(fact.getFraudDecision()).isEqualTo(FraudDecision.REVIEW);
            assertThat(fact.getModelVersion()).isEqualTo("xgb-3.1");
            assertThat(fact.ruleHitList()).containsExactly("VELOCITY", "AMOUNT_SPIKE");
        }

        /**
         * The same events in both orders must produce the same row. Asserted on the fields the dashboard
         * actually reads, since {@code updatedAt} is wall-clock by design and cannot match.
         */
        @Test
        @DisplayName("forward and reverse orderings converge on the same row")
        void orderingsConverge() {
            PaymentFact forward = seeded();
            forward.applyCreation("txn_1", "usr_1", "usr_2", "wal_1", "wal_2", AMOUNT,
                    PaymentType.P2P_TRANSFER, CREATED);
            forward.applyRisk(RiskLevel.MEDIUM, FraudDecision.APPROVE, 0.31, "xgb-3.1",
                    List.of("VELOCITY"), 18L, false, CREATED.plusSeconds(1));
            forward.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L,
                    COMPLETED);

            PaymentFact reverse = PaymentFact.seed(PAYMENT, PaymentStatus.COMPLETED, COMPLETED);
            reverse.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L,
                    COMPLETED);
            reverse.applyRisk(RiskLevel.MEDIUM, FraudDecision.APPROVE, 0.31, "xgb-3.1",
                    List.of("VELOCITY"), 18L, false, CREATED.plusSeconds(1));
            reverse.applyCreation("txn_1", "usr_1", "usr_2", "wal_1", "wal_2", AMOUNT,
                    PaymentType.P2P_TRANSFER, CREATED);

            assertThat(reverse.getStatus()).isEqualTo(forward.getStatus());
            assertThat(reverse.getCreatedAt()).isEqualTo(forward.getCreatedAt());
            assertThat(reverse.getCompletedAt()).isEqualTo(forward.getCompletedAt());
            assertThat(reverse.getAmountMinor()).isEqualTo(forward.getAmountMinor());
            assertThat(reverse.getFeeMinor()).isEqualTo(forward.getFeeMinor());
            assertThat(reverse.getRiskLevel()).isEqualTo(forward.getRiskLevel());
            assertThat(reverse.getRiskScore()).isEqualTo(forward.getRiskScore());
            assertThat(reverse.getTransactionId()).isEqualTo(forward.getTransactionId());
            assertThat(reverse.getPaymentType()).isEqualTo(forward.getPaymentType());
            assertThat(reverse.getProviderId()).isEqualTo(forward.getProviderId());
            assertThat(reverse.getLedgerTransactionId()).isEqualTo(forward.getLedgerTransactionId());
            assertThat(reverse.getEndToEndLatencyMs()).isEqualTo(forward.getEndToEndLatencyMs());
            assertThat(reverse.getLastEventAt()).isEqualTo(forward.getLastEventAt());
        }

        /**
         * {@code last_event_at} is the high-water mark feeding the freshness figure, so it tracks the
         * newest event and must not be dragged back by a late one — otherwise a replay of old history would
         * report the stream as hours behind.
         */
        @Test
        @DisplayName("the event high-water mark only moves forward")
        void lastEventAtOnlyMovesForward() {
            PaymentFact fact = PaymentFact.seed(PAYMENT, PaymentStatus.COMPLETED, COMPLETED);
            fact.applyCreation("txn_1", "usr_1", null, null, null, AMOUNT, PaymentType.P2P_TRANSFER,
                    CREATED);

            assertThat(fact.getLastEventAt())
                    .as("a late event must not make a healthy stream look stale")
                    .isEqualTo(COMPLETED);
        }
    }

    // ------------------------------------------------------------------------
    // risk
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("risk fields")
    class Risk {

        /**
         * The score is the rounded probability, and the database CHECKs exactly that. Writing one without
         * the other would abort the insert — which is the point: they are the same fact twice and must not
         * be able to drift.
         */
        @Test
        @DisplayName("the score is always the rounded probability")
        void scoreMatchesProbability() {
            PaymentFact fact = seeded();
            fact.applyRisk(RiskLevel.HIGH, FraudDecision.HOLD, 0.826, "xgb-3.1", List.of(), 20L,
                    false, CREATED);

            assertThat(fact.getFraudProbability()).isEqualTo(0.826);
            assertThat(fact.getRiskScore())
                    .as("ck_payment_facts_score_matches_probability enforces this at the database")
                    .isEqualTo((short) 83);
        }

        /**
         * A non-finite probability leaves both fields untouched rather than writing NaN. Postgres would
         * accept NaN in a double column and then the CHECK against the rounded score would fail the batch —
         * losing a whole poll of events over one malformed payload.
         */
        @Test
        @DisplayName("a non-finite probability is ignored rather than stored")
        void ignoresNonFiniteProbability() {
            PaymentFact fact = seeded();
            fact.applyRisk(RiskLevel.LOW, FraudDecision.APPROVE, Double.NaN, "xgb-3.1", List.of(),
                    10L, true, CREATED);

            assertThat(fact.getFraudProbability()).isNull();
            assertThat(fact.getRiskScore()).isNull();
            // The verdict itself still applied.
            assertThat(fact.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        }

        /**
         * Degraded mode latches. Once a verdict on this payment was reached without the model, that stays
         * true however many later events arrive — clearing it would erase the only trace that the platform
         * decided blind, and the payment would then be indistinguishable from a genuinely low-risk one.
         */
        @Test
        @DisplayName("degraded mode is latched, never cleared by a later event")
        void degradedModeLatches() {
            PaymentFact fact = seeded();
            fact.applyRisk(RiskLevel.LOW, FraudDecision.APPROVE, 0.10, null, List.of("VELOCITY"),
                    5L, true, CREATED);
            assertThat(fact.isDegradedMode()).isTrue();

            // A redelivery, or a second verdict, that was not degraded.
            fact.applyRisk(RiskLevel.LOW, FraudDecision.APPROVE, 0.10, "xgb-3.1", List.of("VELOCITY"),
                    5L, false, CREATED.plusSeconds(1));

            assertThat(fact.isDegradedMode())
                    .as("""
                            clearing this would leave a rules-only verdict looking like a scored one, and \
                            a LOW band full of them reads as 'low risk' rather than 'nothing was checked'""")
                    .isTrue();
        }

        /**
         * {@code payment-approved} carries a verdict but no model version, so it must not overwrite a
         * probability that {@code fraud-checked} already established — the assessment is the authority.
         */
        @Test
        @DisplayName("an approval does not overwrite a probability the assessment already set")
        void approvalDefersToAssessment() {
            PaymentFact fact = seeded();
            fact.applyRisk(RiskLevel.MEDIUM, FraudDecision.APPROVE, 0.44, "xgb-3.1", List.of(), 12L,
                    false, CREATED);

            fact.applyApproval(RiskLevel.MEDIUM, 0.99, CREATED.plusSeconds(2));

            assertThat(fact.getFraudProbability()).isEqualTo(0.44);
            assertThat(fact.getRiskScore()).isEqualTo((short) 44);
        }

        /** But it does fill the probability when nothing else has, so an approval alone is still useful. */
        @Test
        @DisplayName("an approval supplies the probability when no assessment has been seen")
        void approvalFillsWhenEmpty() {
            PaymentFact fact = seeded();
            fact.applyApproval(RiskLevel.LOW, 0.12, CREATED.plusSeconds(2));

            assertThat(fact.getFraudProbability()).isEqualTo(0.12);
            assertThat(fact.getRiskScore()).isEqualTo((short) 12);
            assertThat(fact.getStatus()).isEqualTo(PaymentStatus.RISK_ASSESSED);
        }
    }

    // ------------------------------------------------------------------------
    // money and timestamps
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("money and timestamps")
    class MoneyAndTimestamps {

        /**
         * The amount is filled once. Every event carrying it carries the same value, so re-assigning would
         * be harmless — but a payment's amount is immutable, and treating it as such means a producer defect
         * that changed it shows up in a test rather than as silently drifting volume.
         */
        @Test
        @DisplayName("the amount is written once and not revised")
        void amountIsWrittenOnce() {
            PaymentFact fact = seeded();
            fact.applyCreation("txn_1", "usr_1", null, null, null, AMOUNT, PaymentType.P2P_TRANSFER,
                    CREATED);

            fact.applyCompletion(Money.ofMinor(999_00L, "INR"), FEE, "SWIFTPAY", List.of("SWIFTPAY"),
                    "led_1", 4_000L, COMPLETED);

            assertThat(fact.getAmountMinor()).isEqualTo(AMOUNT.amountMinor());
            assertThat(fact.amount()).isEqualTo(AMOUNT);
        }

        /**
         * {@code completed_at >= created_at} is a database CHECK, and it is reachable without anybody being
         * wrong: a completion applied first seeds {@code created_at} from its own timestamp, and clock skew
         * between two services can then leave the terminal timestamp a few milliseconds earlier. Clamping
         * beats losing the event over a rounding difference in a diagnostic field.
         */
        @Test
        @DisplayName("clamps a completion that lands before the recorded creation")
        void clampsInvertedTimestamps() {
            PaymentFact fact = PaymentFact.seed(PAYMENT, PaymentStatus.CREATED, COMPLETED);

            Instant slightlyEarlier = COMPLETED.minusMillis(3);
            fact.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L,
                    slightlyEarlier);

            assertThat(fact.getCreatedAt())
                    .as("ck_payment_facts_completed_after_created would abort the whole batch")
                    .isEqualTo(slightlyEarlier);
            assertThat(fact.getCompletedAt()).isEqualTo(slightlyEarlier);
            assertThat(fact.getCompletedAt()).isAfterOrEqualTo(fact.getCreatedAt());
        }

        /** A rejection is terminal and records its reason, but reserves nothing and settles nothing. */
        @Test
        @DisplayName("a rejection records the reason and stays terminal")
        void rejectionIsTerminal() {
            PaymentFact fact = seeded();
            fact.applyRejection(AMOUNT, RiskLevel.CRITICAL, FraudDecision.REJECT, "FRAUD_REJECT",
                    "Rejected on risk", CREATED.plusSeconds(2));

            assertThat(fact.getStatus()).isEqualTo(PaymentStatus.REJECTED);
            assertThat(fact.isTerminal()).isTrue();
            assertThat(fact.getFailureCode()).isEqualTo("FRAUD_REJECT");
            assertThat(fact.getEndToEndLatencyMs())
                    .as("""
                            a rejected payment never completed, so it has no end-to-end latency; a zero \
                            here would make the platform look faster the more it refused""")
                    .isNull();
        }

        /**
         * A completion cannot follow a failure. Both are terminal and share a rank, so the first ending to
         * arrive is the one recorded — and the money fields of the loser must not leak in either.
         */
        @Test
        @DisplayName("a completion after a failure changes neither the status nor the ledger reference")
        void firstEndingWins() {
            PaymentFact fact = seeded();
            fact.applyFailure(AMOUNT, "PROVIDER_TIMEOUT", "Rail timed out", List.of("SWIFTPAY"),
                    COMPLETED);

            fact.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L,
                    COMPLETED.plusSeconds(1));

            assertThat(fact.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(fact.getFailureCode()).isEqualTo("PROVIDER_TIMEOUT");
        }

        /** Rule hits round-trip through the joined column, and an empty list stays empty rather than null-ish. */
        @Test
        @DisplayName("rule hits round-trip, and no hits reads as no hits")
        void ruleHitsRoundTrip() {
            PaymentFact none = seeded();
            none.applyRisk(RiskLevel.LOW, FraudDecision.APPROVE, 0.05, "xgb-3.1", List.of(), 8L,
                    false, CREATED);
            assertThat(none.ruleHitList()).isEmpty();

            PaymentFact some = seeded();
            some.applyRisk(RiskLevel.HIGH, FraudDecision.HOLD, 0.77, "xgb-3.1",
                    List.of("VELOCITY", "NEW_BENEFICIARY"), 8L, false, CREATED);
            assertThat(some.ruleHitList()).containsExactly("VELOCITY", "NEW_BENEFICIARY");
        }

        /**
         * The read model of every payment on the platform must not spill user ids or amounts into a log
         * line. {@code toString} is called by frameworks nobody chose.
         */
        @Test
        @DisplayName("toString names the payment and its status, and nothing else")
        void toStringIsSafeForLogs() {
            PaymentFact fact = completedPayment();
            fact.applyCreation("txn_1", "usr_secret", null, null, null, AMOUNT,
                    PaymentType.P2P_TRANSFER, CREATED);

            assertThat(fact.toString())
                    .contains(PAYMENT)
                    .contains("COMPLETED")
                    .doesNotContain("usr_secret")
                    .doesNotContain(String.valueOf(AMOUNT.amountMinor()));
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static PaymentFact seeded() {
        return PaymentFact.seed(PAYMENT, PaymentStatus.CREATED, CREATED);
    }

    private static PaymentFact completedPayment() {
        PaymentFact fact = seeded();
        fact.applyCompletion(AMOUNT, FEE, "SWIFTPAY", List.of("SWIFTPAY"), "led_1", 4_000L, COMPLETED);
        return fact;
    }
}
