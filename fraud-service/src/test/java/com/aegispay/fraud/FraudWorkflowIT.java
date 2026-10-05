package com.aegispay.fraud;

import com.aegispay.common.events.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The analyst workflow: the queue, taking an alert, deciding a held payment, and the audit trail.
 *
 * <p>Three guarantees here live in the database and nowhere else, which is why this is an integration
 * test rather than a unit one:
 *
 * <ol>
 *   <li>the append-only trigger on {@code analyst_decisions}, which refuses UPDATE and DELETE
 *       regardless of what the application tries;</li>
 *   <li>the unique constraint on {@code fraud_alerts.payment_id}, which stops two analysts working the
 *       same payment;</li>
 *   <li>the atomicity of "record the decision, close the alert, emit the resume event" — a decision
 *       that committed without its event would leave a customer's payment parked forever with the alert
 *       reading as handled.</li>
 * </ol>
 */
class FraudWorkflowIT extends AbstractFraudIntegrationTest {

    // ================================================================ queue

    @Nested
    @DisplayName("the alert queue")
    class Queue {

        /**
         * Ordered by {@code blocksPayment} before risk score. A held payment is a customer waiting
         * right now with their funds reserved; a review on a payment that already settled can wait
         * until tomorrow. Sorting by score alone would bury the first behind the second.
         */
        @Test
        @DisplayName("puts a blocked payment ahead of a higher-scoring settled one")
        void blockedPaymentsComeFirst() {
            // A REVIEW with a lower score, then a HOLD. Score alone would order these the other way.
            ML.scoresAt(0.79);
            String reviewed = newPaymentId();
            assess(reviewed, newUserId(), newWalletId(), "500.00");

            ML.scoresAt(0.81);
            String held = newPaymentId();
            assess(held, newUserId(), newWalletId(), "500.00");

            Response<String> queue = get("/api/fraud/alerts", analystToken(), String.class);

            assertThat(queue.status().is2xxSuccessful()).isTrue();
            assertThat(queue.body().indexOf(held))
                    .as("the customer whose money is reserved must be at the front of the queue")
                    .isLessThan(queue.body().indexOf(reviewed));
        }

        @Test
        @DisplayName("can be narrowed to the alerts holding a payment")
        void blockingOnlyFilter() {
            ML.scoresAt(0.55);
            String reviewed = newPaymentId();
            assess(reviewed, newUserId(), newWalletId(), "500.00");

            ML.scoresAt(0.91);
            String held = newPaymentId();
            assess(held, newUserId(), newWalletId(), "500.00");

            Response<String> blocking = get("/api/fraud/alerts?blockingOnly=true", analystToken(),
                    String.class);

            assertThat(blocking.body()).contains(held).doesNotContain(reviewed);
        }
    }

    // ============================================================== taking

    @Nested
    @DisplayName("taking an alert")
    class Taking {

        /**
         * The assignee comes from the token, never from the request. "Who authorised this" is the first
         * question asked about a released payment that turned out to be fraud.
         */
        @Test
        @DisplayName("assigns the alert to the caller from their token")
        void assignsFromToken() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            String analyst = newUserId();
            Response<Map> taken = post("/api/fraud/alerts/" + alertIdFor(payment) + "/take", null,
                    analystToken(analyst), Map.class);

            assertThat(taken.status().is2xxSuccessful()).isTrue();
            assertThat(taken.body()).containsEntry("assignedTo", analyst);
            assertThat(alertStatus(payment)).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("refuses to take an alert that has already been decided")
        void refusesResolvedAlert() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");
            String alertId = alertIdFor(payment);

            post("/api/fraud/alerts/" + alertId + "/decide",
                    Map.of("action", "RELEASE", "reason", "Confirmed by phone"),
                    analystToken(), Map.class);

            Response<Map> taken = post("/api/fraud/alerts/" + alertId + "/take", null,
                    analystToken(), Map.class);

            assertThat(taken.status().value()).isEqualTo(409);
        }

        @Test
        @DisplayName("reports an unknown alert as not found")
        void unknownAlert() {
            Response<Map> taken = post("/api/fraud/alerts/ALR-DOES-NOT-EXIST/take", null,
                    analystToken(), Map.class);

            assertThat(taken.status().value()).isEqualTo(404);
        }
    }

    // ============================================================ deciding

    @Nested
    @DisplayName("deciding a held payment")
    class Deciding {

        /**
         * <strong>The decision and the resume event must commit together.</strong> A decision recorded
         * without its event would leave the customer's payment parked forever while the alert read as
         * handled — invisible from both ends, because nothing failed.
         */
        @Test
        @DisplayName("records the decision, closes the alert and emits the resume event")
        void releaseEmitsTheResumeEvent() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            String analyst = newUserId();
            Response<Map> decided = post("/api/fraud/alerts/" + alertIdFor(payment) + "/decide",
                    Map.of("action", "RELEASE",
                            "reason", "Customer confirmed the payment by phone on a known number"),
                    analystToken(analyst), Map.class);

            assertThat(decided.status().is2xxSuccessful()).isTrue();
            assertThat(decided.body()).containsEntry("action", "RELEASE");
            assertThat(decided.body())
                    .as("the analyst comes from the token, never from the request")
                    .containsEntry("decidedBy", analyst);

            assertThat(alertStatus(payment)).isEqualTo("RESOLVED");
            assertThat(countDecisions(payment)).isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.FRAUD_DECISION_APPLIED, payment))
                    .as("this event is the ONLY thing that will unpark the payment")
                    .isEqualTo(1);
        }

        /**
         * A rejection is confirmed fraud, and that feeds the feature pipeline: the account's
         * confirmed-fraud count is what the platform uses in place of chargebacks, which it has no card
         * network to produce.
         */
        @Test
        @DisplayName("a refusal marks the payment as confirmed fraud for future scoring")
        void refusalMarksConfirmedFraud() {
            String user = newUserId();
            String payment = newPaymentId();
            observePayment(payment, user, newWalletId(), "500.00", java.time.Instant.now());

            ML.scoresAt(0.91);
            assess(payment, user, newWalletId(), "500.00");

            post("/api/fraud/alerts/" + alertIdFor(payment) + "/decide",
                    Map.of("action", "REJECT", "reason", "Customer denies making this payment"),
                    analystToken(), Map.class);

            assertThat(observationConfirmedFraud(payment))
                    .as("""
                            the platform has no card network, so an analyst's REJECT is the honest \
                            stand-in for a chargeback - and it is what the confirmed-fraud rule reads""")
                    .isTrue();
            assertThat(countOutboxEvents(KafkaTopics.FRAUD_DECISION_APPLIED, payment)).isEqualTo(1);
        }

        /**
         * The reason is the only record of why a customer's payment was released or refused, and it is
         * what an audit reads. Enforced by validation and by a schema CHECK.
         */
        @Test
        @DisplayName("refuses a decision with no reason")
        void requiresAReason() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            Response<Map> decided = post("/api/fraud/alerts/" + alertIdFor(payment) + "/decide",
                    Map.of("action", "RELEASE", "reason", "   "), analystToken(), Map.class);

            assertThat(decided.status().is4xxClientError()).isTrue();
            assertThat(countDecisions(payment)).isZero();
            assertThat(alertStatus(payment))
                    .as("a rejected request must leave the queue untouched")
                    .isEqualTo("OPEN");
        }

        /**
         * Two analysts deciding at once must not both succeed: one would release the payment and the
         * other refuse it, and whichever committed second would win by accident.
         */
        @Test
        @DisplayName("refuses a second decision on the same alert")
        void refusesASecondDecision() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");
            String alertId = alertIdFor(payment);

            post("/api/fraud/alerts/" + alertId + "/decide",
                    Map.of("action", "RELEASE", "reason", "Confirmed by phone"),
                    analystToken(), Map.class);

            Response<Map> second = post("/api/fraud/alerts/" + alertId + "/decide",
                    Map.of("action", "REJECT", "reason", "Actually, refuse it"),
                    analystToken(), Map.class);

            assertThat(second.status().value()).isEqualTo(409);
            assertThat(countDecisions(payment))
                    .as("the first decision stands and no second is recorded")
                    .isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.FRAUD_DECISION_APPLIED, payment)).isEqualTo(1);
        }
    }

    // ======================================================== the audit trail

    @Nested
    @DisplayName("the audit trail")
    class AuditTrail {

        /**
         * <strong>Enforced by the database, not by convention.</strong> A comment saying "append-only"
         * is a hope; a trigger is a guarantee, and it holds against ad-hoc SQL as well as against
         * application code. The reason a customer's payment was refused must not be rewritable by any
         * route.
         */
        @Test
        @DisplayName("refuses to update a recorded decision")
        void refusesUpdate() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            post("/api/fraud/alerts/" + alertIdFor(payment) + "/decide",
                    Map.of("action", "RELEASE", "reason", "Confirmed by phone"),
                    analystToken(), Map.class);

            assertThatExceptionOfType(org.springframework.dao.DataAccessException.class)
                    .as("rewriting why a payment was released would destroy the only record of it")
                    .isThrownBy(() -> jdbc.update(
                            "UPDATE analyst_decisions SET reason = 'something else' "
                                    + "WHERE payment_id = ?", payment));
        }

        @Test
        @DisplayName("refuses to delete a recorded decision")
        void refusesDelete() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            post("/api/fraud/alerts/" + alertIdFor(payment) + "/decide",
                    Map.of("action", "REJECT", "reason", "Customer denies it"),
                    analystToken(), Map.class);

            assertThatExceptionOfType(org.springframework.dao.DataAccessException.class)
                    .isThrownBy(() -> jdbc.update(
                            "DELETE FROM analyst_decisions WHERE payment_id = ?", payment));
        }

        /**
         * The dispute view: the score, the exact inputs it was computed from, and who overrode it. All
         * three together are what makes an automated decision defensible.
         */
        @Test
        @DisplayName("shows the verdict, the feature vector and every human decision together")
        void detailViewIsComplete() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            post("/api/fraud/alerts/" + alertIdFor(payment) + "/decide",
                    Map.of("action", "RELEASE", "reason", "Confirmed by phone"),
                    analystToken(), Map.class);

            Response<String> detail = get("/api/fraud/assessments/" + payment + "/detail",
                    analystToken(), String.class);

            assertThat(detail.status().is2xxSuccessful()).isTrue();
            assertThat(detail.body())
                    .contains("featureSnapshot")
                    .contains("amountMinor")
                    .contains("Confirmed by phone")
                    .contains("fraud_hgb-stub");
        }
    }

    // ================================================================ access

    @Nested
    @DisplayName("who may read the fraud console")
    class Access {

        /**
         * The shared chain restricts {@code /api/admin/**}; these paths are {@code /api/fraud/**}. Every
         * handler therefore carries its own {@code @PreAuthorize}, and without it every customer's risk
         * score, device fingerprint and coordinates would be readable by any authenticated user — data
         * they never knowingly provided.
         */
        @Test
        @DisplayName("refuses an ordinary customer")
        void refusesCustomer() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(get("/api/fraud/alerts", customerToken(), String.class)
                    .status().is4xxClientError()).isTrue();
            assertThat(get("/api/fraud/assessments", customerToken(), String.class)
                    .status().is4xxClientError()).isTrue();
            assertThat(get("/api/fraud/assessments/" + payment + "/detail", customerToken(),
                    String.class).status().is4xxClientError()).isTrue();
            assertThat(get("/api/fraud/stats", customerToken(), String.class)
                    .status().is4xxClientError()).isTrue();
        }

        @Test
        @DisplayName("refuses a customer trying to decide an alert")
        void refusesCustomerDeciding() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);
            assess(payment, newUserId(), newWalletId(), "500.00");

            Response<Map> decided = post("/api/fraud/alerts/" + alertIdFor(payment) + "/decide",
                    Map.of("action", "RELEASE", "reason", "let me through"),
                    customerToken(), Map.class);

            assertThat(decided.status().is4xxClientError()).isTrue();
            assertThat(countDecisions(payment)).isZero();
            assertThat(alertStatus(payment)).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("allows an admin as well as a fraud analyst")
        void allowsAdmin() {
            assertThat(get("/api/fraud/stats", adminToken(), String.class)
                    .status().is2xxSuccessful()).isTrue();
            assertThat(get("/api/fraud/stats", analystToken(), String.class)
                    .status().is2xxSuccessful()).isTrue();
        }
    }

    // ================================================================= stats

    @Nested
    @DisplayName("the overview")
    class Stats {

        /**
         * {@code degradedAssessments24h} is one of the two numbers worth alerting on: verdicts reached
         * without the model produce no errors and are otherwise invisible.
         */
        @Test
        @DisplayName("counts degraded verdicts and blocking alerts")
        void countsWhatMatters() {
            ML.scoresAt(0.91);
            assess(newPaymentId(), newUserId(), newWalletId(), "500.00");

            ML.takeDown();
            assess(newPaymentId(), newUserId(), newWalletId(), "500.00");

            Response<String> stats = get("/api/fraud/stats", adminToken(), String.class);

            assertThat(stats.status().is2xxSuccessful()).isTrue();
            assertThat(stats.body())
                    .contains("\"degradedAssessments24h\":1")
                    .contains("\"blockingAlerts\":1");
        }

        /**
         * Null model metadata is itself the answer an operator investigating a degraded verdict is
         * looking for, so an unreachable model must not fail the whole view.
         */
        @Test
        @DisplayName("reports the engine configuration even when the model is unreachable")
        void engineInfoSurvivesAnOutage() {
            ML.takeDown();

            Response<String> engine = get("/api/fraud/engine", adminToken(), String.class);

            assertThat(engine.status().is2xxSuccessful()).isTrue();
            assertThat(engine.body())
                    .contains("RULE_BUST_OUT_PATTERN")
                    .contains("\"highRiskThreshold\":0.8")
                    .contains("\"model\":null");
        }

        @Test
        @DisplayName("reports the model's provenance and its synthetic-data caveat when it is up")
        void engineInfoIncludesModelProvenance() {
            Response<String> engine = get("/api/fraud/engine", adminToken(), String.class);

            assertThat(engine.body())
                    .contains("fraud_hgb-stub")
                    .as("the caveat travels with the metrics, so nobody reads them as a claim about "
                            + "real-world performance")
                    .contains("syntheticData");
        }
    }
}
