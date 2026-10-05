package com.aegispay.fraud;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end scoring: the read model, the feature pipeline, the wire contract, and the verdict.
 *
 * <p>Every test drives the service the only way the saga does — {@code POST /internal/fraud/assess}
 * with a service token — and asserts on three surfaces: what {@link StubMlService} was actually sent,
 * the committed assessment row, and the outbox. A fraud verdict can be wrong in ways where any two
 * agree and the third does not: a stored HOLD with no alert to release it, a snapshot that does not
 * match the score, or a verdict announced for a row that rolled back.
 */
class FraudScoringIT extends AbstractFraudIntegrationTest {

    // ============================================================ the verdict

    @Nested
    @DisplayName("producing a verdict")
    class Verdicts {

        @Test
        @DisplayName("approves a routine payment and records the model that scored it")
        void approvesRoutinePayment() {
            String user = newUserId();
            String payment = newPaymentId();
            ML.scoresAt(0.02);

            Response<Map> response = assess(payment, user, newWalletId(), "500.00");

            assertThat(response.status().is2xxSuccessful()).isTrue();
            assertThat(response.body()).containsEntry("decision", "APPROVE");
            assertThat(response.body()).containsEntry("riskLevel", "LOW");
            assertThat(response.body()).containsEntry("riskScore", 2);

            assertThat(assessmentModelVersion(payment))
                    .as("an unattributable score cannot be reproduced, so it cannot be defended")
                    .isEqualTo("fraud_hgb-stub");
            assertThat(assessmentDegraded(payment)).isFalse();
            assertThat(countAlerts())
                    .as("a routine payment must not put anything in front of a human")
                    .isZero();
        }

        @Test
        @DisplayName("holds a high-scoring payment and raises a blocking alert")
        void holdsHighScoringPayment() {
            String user = newUserId();
            String payment = newPaymentId();
            ML.scoresAt(0.91);

            Response<Map> response = assess(payment, user, newWalletId(), "500.00");

            assertThat(response.body()).containsEntry("decision", "HOLD");
            assertThat(response.body()).containsEntry("riskLevel", "HIGH");

            assertThat(alertIdFor(payment)).isNotNull();
            assertThat(alertBlocksPayment(payment))
                    .as("a HOLD stops the saga with the customer's funds reserved, so this alert is "
                            + "costing somebody something while it sits in the queue")
                    .isTrue();
            assertThat(alertStatus(payment)).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("reviews a mid-scoring payment without blocking it")
        void reviewsMidScoringPayment() {
            String payment = newPaymentId();
            ML.scoresAt(0.55);

            assertThat(assess(payment, newUserId(), newWalletId(), "500.00").body())
                    .containsEntry("decision", "REVIEW");

            assertThat(alertIdFor(payment)).isNotNull();
            assertThat(alertBlocksPayment(payment))
                    .as("a REVIEW settles the payment, so nobody is waiting on this alert")
                    .isFalse();
        }

        /**
         * <strong>The database keeps the two fields honest.</strong> A CHECK enforces
         * {@code risk_score = ROUND(fraud_probability * 100)}, because two fields meant to say the same
         * thing and written separately will eventually diverge — and an analyst would have no way to
         * tell which one was wrong.
         */
        @Test
        @DisplayName("stores a score that matches the probability, enforced by the schema")
        void scoreMatchesProbability() {
            String payment = newPaymentId();
            ML.scoresAt(0.6234);

            assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(assessmentRiskScore(payment)).isEqualTo(62);
        }

        /**
         * The snapshot is what makes a verdict reproducible six months later, which is a regulatory
         * expectation for automated decision-making rather than a nice-to-have.
         */
        @Test
        @DisplayName("stores the exact feature vector the model scored")
        void storesTheFeatureSnapshot() {
            String payment = newPaymentId();

            assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(assessmentSnapshot(payment))
                    .as("without the vector the score cannot be recomputed, so it cannot be defended")
                    .isNotNull()
                    .contains("amountMinor");
        }
    }

    // ======================================================== idempotency

    @Nested
    @DisplayName("assessing the same payment twice")
    class Idempotency {

        /**
         * The saga can legitimately ask twice — a Kafka redelivery, or a recovery sweep resuming an
         * interrupted saga. A payment whose risk verdict changed between attempts would be indefensible
         * in a dispute, and would mean a customer could be held on one attempt and approved on the next
         * purely by timing.
         */
        @Test
        @DisplayName("returns the stored verdict rather than scoring again")
        void returnsTheStoredVerdict() {
            String payment = newPaymentId();
            String user = newUserId();
            String payee = newWalletId();
            ML.scoresAt(0.91);

            Response<Map> first = assess(payment, user, payee, "500.00");

            // A verdict that would differ if the model were consulted again.
            ML.scoresAt(0.01);
            Response<Map> second = assess(payment, user, payee, "500.00");

            assertThat(second.body().get("assessmentId"))
                    .as("the same assessment, not a second one")
                    .isEqualTo(first.body().get("assessmentId"));
            assertThat(second.body()).containsEntry("decision", "HOLD");
            assertThat(second.body()).containsEntry("riskScore", 91);

            assertThat(ML.scoreCalls())
                    .as("re-scoring would be both wasteful and capable of changing the answer")
                    .isEqualTo(1);
            assertThat(countAssessments(payment)).isEqualTo(1);
            assertThat(countAlerts())
                    .as("a second alert would put the same payment in front of two analysts, who "
                            + "could reach opposite conclusions")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("announces the verdict exactly once")
        void announcesOnce() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);

            assess(payment, newUserId(), newWalletId(), "500.00");
            assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(countOutboxEvents(KafkaTopics.FRAUD_CHECKED, payment)).isEqualTo(1);
        }
    }

    // ======================================================== degraded mode

    @Nested
    @DisplayName("when the model is unreachable")
    class Degraded {

        /**
         * Refusing every payment while the model is down would convert a fraud-service outage into a
         * total payment outage — a far larger incident than the fraud it prevents. So the rules decide
         * and the verdict is marked.
         */
        @Test
        @DisplayName("still returns a verdict, marked degraded")
        void stillReturnsAVerdict() {
            String payment = newPaymentId();
            ML.takeDown();

            Response<Map> response = assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(response.status().is2xxSuccessful())
                    .as("an unreachable model is an expected operating condition, not a 500")
                    .isTrue();
            assertThat(response.body()).containsEntry("degradedMode", true);
            assertThat(response.body()).containsEntry("decision", "APPROVE");

            assertThat(assessmentDegraded(payment)).isTrue();
            assertThat(assessmentModelVersion(payment))
                    .as("no model scored this, so none is claimed")
                    .isNull();
        }

        /**
         * Nothing was scored, so there is no vector to store. Keeping one would imply the model saw
         * inputs it never did.
         */
        @Test
        @DisplayName("stores no feature snapshot, because nothing was scored")
        void noSnapshotWhenDegraded() {
            String payment = newPaymentId();
            ML.takeDown();

            assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(assessmentSnapshot(payment)).isNull();
        }

        /**
         * The rules cover the archetypes the model scores worst on, which is exactly the coverage that
         * matters when it is absent. Bust-out is the case: measured model recall 0.147.
         */
        @Test
        @DisplayName("the deterministic rules still fire")
        void rulesStillApply() {
            String user = newUserId();
            String payee = newWalletId();
            Instant now = Instant.now();

            // An established account with a modest history, so the bust-out conjunction can be met.
            for (int i = 0; i < 22; i++) {
                observeCompleted(newPaymentId(), user, newWalletId(), "500.00",
                        now.minus(Duration.ofDays(40 - i)));
            }

            ML.takeDown();
            String payment = newPaymentId();
            Response<Map> response = assess(payment, user, payee, "20000.00",
                    PaymentType.P2P_TRANSFER, signals("fp-known", "IN", null, null), now);

            assertThat(response.body()).containsEntry("degradedMode", true);
            assertThat(assessmentRuleHits(payment))
                    .as("""
                            the model misses this archetype (measured recall 0.147), so the rule has \
                            to carry it - and it must still carry it when the model is absent""")
                    .contains("RULE_BUST_OUT_PATTERN");
            assertThat(assessmentDecision(payment)).isEqualTo("HOLD");
        }
    }

    // ==================================================== the wire contract

    @Nested
    @DisplayName("what ml-service actually receives")
    class WireContract {

        /**
         * <strong>The six field names both frameworks disagree about.</strong> pydantic's default
         * camelCase generator turns {@code payment_count_1h} into {@code paymentCount1H} — capital H —
         * while Jackson produces {@code paymentCount1h}. The Python side pins explicit aliases and
         * asserts them; this is the other half of that contract, verified against real bytes on a real
         * socket.
         */
        @Test
        @DisplayName("uses the pinned names for the six digit-bearing fields")
        void pinnedFieldNames() {
            String payment = newPaymentId();
            assess(payment, newUserId(), newWalletId(), "500.00");

            JsonNode request = ML.requestFor(payment);

            for (String field : java.util.List.of("paymentCount1h", "paymentCount24h",
                    "amountSum24hMinor", "failedPaymentCount24h", "distinctDevices30d",
                    "distinctCountries30d")) {
                assertThat(request.has(field))
                        .as("ml-service pins the alias \"%s\" and rejects unknown fields; a mismatch "
                                + "would drop this feature from every score", field)
                        .isTrue();
            }
            assertThat(request.has("paymentCount1H"))
                    .as("the pydantic default spelling is not the agreed name")
                    .isFalse();
        }

        /**
         * <strong>The property the whole pipeline rests on.</strong> The saga calls milliseconds after
         * {@code payment-created} is published, so whether the current payment's own observation has
         * been consumed is a race. Including it would make the same payment score differently depending
         * on which consumer won — and the feature contract defines these windows as prior to the
         * payment, so including it would put every request slightly outside the trained distribution
         * with no error anywhere.
         */
        @Test
        @DisplayName("excludes the payment being scored from its own aggregates")
        void excludesTheScoredPayment() {
            String user = newUserId();
            String payee = newWalletId();
            Instant now = Instant.now();
            String payment = newPaymentId();

            // Two prior payments, then the observation for the payment about to be scored — exactly
            // the race the exclusion exists for.
            observePayment(newPaymentId(), user, payee, "100.00", now.minus(Duration.ofMinutes(10)));
            observePayment(newPaymentId(), user, payee, "100.00", now.minus(Duration.ofMinutes(20)));
            observePayment(payment, user, payee, "500.00", now);

            assess(payment, user, payee, "500.00", PaymentType.P2P_TRANSFER,
                    signals("fp-known", "IN", null, null), now);

            JsonNode request = ML.requestFor(payment);
            assertThat(request.get("paymentCount1h").asLong())
                    .as("two prior payments, not three: this payment must not count itself")
                    .isEqualTo(2L);
            assertThat(request.get("amountSum24hMinor").asLong())
                    .as("200.00 of prior spend, with this payment's own 500.00 excluded")
                    .isEqualTo(200 * RUPEE);
        }

        /**
         * A brand-new account has no average amount. Reporting zero would tell the model its usual
         * payment is nothing, which is a different and false claim.
         */
        @Test
        @DisplayName("sends unknown history as null rather than zero")
        void unknownHistoryIsNull() {
            String payment = newPaymentId();
            assess(payment, newUserId(), newWalletId(), "500.00");

            JsonNode request = ML.requestFor(payment);
            assertThat(request.get("avgAmountMinor").isNull())
                    .as("zero would assert that this account's usual payment is nothing")
                    .isTrue();
            assertThat(request.get("maxAmountMinor").isNull()).isTrue();
        }

        /**
         * Time-of-day is a real fraud feature, and the saga can call minutes after the payment. Using
         * the assessment time would score a 03:00 payment as a daytime one after a redelivery.
         */
        @Test
        @DisplayName("derives the hour from when the customer paid, not from now")
        void hourComesFromThePayment() {
            String payment = newPaymentId();
            Instant paidAt = Instant.parse("2026-09-01T03:17:00Z");

            assess(payment, newUserId(), newWalletId(), "500.00", PaymentType.P2P_TRANSFER,
                    signals("fp-known", "IN", null, null), paidAt);

            assertThat(ML.requestFor(payment).get("hourOfDay").asInt())
                    .as("03:17Z is hour 3, whenever this assessment happens to run")
                    .isEqualTo(3);
        }

        /**
         * A static API key rather than a bearer token, because ml-service is a Python service with no
         * place in the platform's OAuth2 trust chain.
         */
        @Test
        @DisplayName("authenticates with the API key")
        void sendsApiKey() {
            assess(newPaymentId(), newUserId(), newWalletId(), "500.00");

            assertThat(ML.unauthenticatedCalls())
                    .as("an unauthenticated scoring call would be accepted by a permissive model and "
                            + "refused by a correctly configured one")
                    .isZero();
        }
    }

    // ================================================== the read model

    @Nested
    @DisplayName("the read model built from events")
    class ReadModel {

        /**
         * fraud-service keeps its own history rather than calling payment-service at scoring time. Two
         * more network calls on the payment's critical path, and a fraud decision that fails during an
         * unrelated redeploy, is the alternative.
         */
        @Test
        @DisplayName("records a payment it hears about on payment-created")
        void recordsObservations() {
            String payment = newPaymentId();
            observePayment(payment, newUserId(), newWalletId(), "500.00", Instant.now());

            assertThat(countObservations(payment)).isEqualTo(1);
        }

        /**
         * Kafka delivers at least once, and the primary key would abort the transaction on a second
         * insert. The first observation is the true one: a redelivery carries the same facts.
         */
        @Test
        @DisplayName("tolerates a redelivered creation event")
        void observationIsIdempotent() {
            String payment = newPaymentId();
            String user = newUserId();
            String payee = newWalletId();
            Instant now = Instant.now();

            observePayment(payment, user, payee, "500.00", now);
            observePayment(payment, user, payee, "500.00", now);

            assertThat(countObservations(payment)).isEqualTo(1);
        }

        /**
         * The account's norms come from completed payments only. If a card-testing burst raised the
         * baseline, the next fraudulent payment would look proportionate against it — the attacker
         * would be setting their own threshold.
         */
        @Test
        @DisplayName("derives the account's norms from completed payments only")
        void normsUseCompletedPaymentsOnly() {
            String user = newUserId();
            String payee = newWalletId();
            Instant now = Instant.now();

            observeCompleted(newPaymentId(), user, payee, "500.00",
                    now.minus(Duration.ofDays(3)));
            // A pending attempt for a much larger amount, which must not move the average.
            observePayment(newPaymentId(), user, payee, "50000.00",
                    now.minus(Duration.ofHours(2)));

            String payment = newPaymentId();
            assess(payment, user, payee, "500.00", PaymentType.P2P_TRANSFER,
                    signals("fp-known", "IN", null, null), now);

            assertThat(ML.requestFor(payment).get("avgAmountMinor").asLong())
                    .as("an attacker must not be able to raise the account's own baseline")
                    .isEqualTo(500 * RUPEE);
        }
    }

    // ============================================== investigation handoff

    @Nested
    @DisplayName("the investigation handoff")
    class Investigations {

        /**
         * Emitted here because the risk decision is what triggers it. Nothing consumes it until Phase
         * 7, and a topic with no consumer is a queue rather than a loss — the same reasoning Phase 5
         * applied to its DLQ wiring.
         */
        @Test
        @DisplayName("requests an investigation for a high-risk payment")
        void requestsInvestigationForHighRisk() {
            String payment = newPaymentId();
            ML.scoresAt(0.91);

            assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(countOutboxEvents(KafkaTopics.INVESTIGATION_REQUESTED, payment))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("does not request one for a routine payment")
        void noInvestigationForRoutinePayment() {
            String payment = newPaymentId();
            ML.scoresAt(0.02);

            assess(payment, newUserId(), newWalletId(), "500.00");

            assertThat(countOutboxEvents(KafkaTopics.INVESTIGATION_REQUESTED, payment)).isZero();
        }
    }

    // ================================================================ auth

    @Nested
    @DisplayName("the internal surface")
    class InternalAuth {

        @Test
        @DisplayName("refuses a customer token")
        void refusesCustomerToken() {
            Response<Map> response = post("/internal/fraud/assess", Map.of(
                            "paymentId", newPaymentId(),
                            "initiatorUserId", newUserId(),
                            "destinationWalletId", newWalletId(),
                            "amount", "500.00",
                            "currency", CURRENCY),
                    customerToken(), Map.class);

            assertThat(response.status().is4xxClientError())
                    .as("an endpoint that decides whether a payment proceeds must not be reachable "
                            + "by a customer")
                    .isTrue();
            assertThat(countAssessments()).isZero();
        }

        @Test
        @DisplayName("refuses an unauthenticated request")
        void refusesAnonymous() {
            Response<Map> response = post("/internal/fraud/assess",
                    Map.of("paymentId", newPaymentId()), null, Map.class);

            assertThat(response.status().is4xxClientError()).isTrue();
        }

        @Test
        @DisplayName("rejects a request with no amount rather than scoring a guess")
        void rejectsInvalidRequest() {
            Response<Map> response = post("/internal/fraud/assess", Map.of(
                            "paymentId", newPaymentId(),
                            "initiatorUserId", newUserId(),
                            "destinationWalletId", newWalletId(),
                            "currency", CURRENCY),
                    serviceToken(), Map.class);

            assertThat(response.status().is4xxClientError()).isTrue();
            assertThat(countAssessments()).isZero();
        }
    }
}
