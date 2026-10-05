package com.aegispay.analytics;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read model, end to end: events on a broker, aggregates out of PostgreSQL, over HTTP.
 *
 * <p>Responses are read as {@code Map} rather than into the DTO records. Deliberate: it asserts the shape of
 * the JSON the dashboard actually receives, including the field names. Binding into the record would make a
 * renamed field invisible here and visible only as an undefined value in a browser.
 *
 * <p>Timestamps are pinned to whole minutes in the recent past. Charting against {@code now()} would put a
 * payment either side of a bucket boundary depending on when the suite happened to run, which is the classic
 * way a time-series test becomes flaky at 59 seconds past the minute.
 */
@SuppressWarnings("rawtypes")
class AnalyticsFlowIT extends AbstractAnalyticsIntegrationTest {

    /** A stable point inside the default one-day window, aligned so bucket edges are deterministic. */
    private static Instant recently() {
        return Instant.now().minus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MINUTES);
    }

    // ------------------------------------------------------------------------
    // projection
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("projecting the lifecycle")
    class Projecting {

        @Test
        @DisplayName("builds one row per payment from six topics")
        void buildsOneRowPerPayment() {
            String paymentId = newPaymentId();
            String userId = newUserId();
            Instant at = recently();

            created(paymentId, userId, inr(2_000), at);
            scored(paymentId, RiskLevel.MEDIUM, FraudDecision.APPROVE, 0.34, false, at.plusSeconds(1));
            approved(paymentId, userId, inr(2_000), RiskLevel.MEDIUM, 0.34, at.plusSeconds(2));
            completed(paymentId, userId, inr(2_000), Money.ofMinor(150L, "INR"),
                    List.of("SWIFTPAY"), 4_100L, at.plusSeconds(4));

            awaitStatus(paymentId, "COMPLETED");

            assertThat(countFacts())
                    .as("four events for one payment must produce one row, not four")
                    .isEqualTo(1);
            assertThat(amountMinorOf(paymentId)).isEqualTo(200_000L);
            assertThat(riskScoreOf(paymentId))
                    .as("the score is the rounded probability, and the CHECK constraint agrees")
                    .isEqualTo(34);
            assertThat(degradedOf(paymentId)).isFalse();
        }

        /**
         * <strong>The design claim, verified against real infrastructure.</strong> Publishing the same
         * completion twice must leave every figure unchanged. With counters it would double the settled
         * total, and nothing in the stored data would reveal that it had.
         */
        @Test
        @DisplayName("a redelivered completion changes no total")
        void redeliveryChangesNothing() {
            String paymentId = newPaymentId();
            String userId = newUserId();
            Instant at = recently();

            created(paymentId, userId, inr(2_000), at);
            completed(paymentId, userId, inr(2_000), Money.ofMinor(150L, "INR"),
                    List.of("SWIFTPAY"), 4_100L, at.plusSeconds(4));
            awaitStatus(paymentId, "COMPLETED");

            Map before = overview();

            // The same event again, byte for byte as far as the projection is concerned.
            completed(paymentId, userId, inr(2_000), Money.ofMinor(150L, "INR"),
                    List.of("SWIFTPAY"), 4_100L, at.plusSeconds(4));
            sleepBriefly();

            Map after = overview();

            assertThat(countFacts()).isEqualTo(1);
            assertThat(number(after, "payments")).isEqualTo(number(before, "payments"));
            assertThat(number(after, "completed")).isEqualTo(number(before, "completed"));
            assertThat(money(after, "settled"))
                    .as("a doubled settled total is the failure this whole design exists to prevent")
                    .isEqualTo(money(before, "settled"));
            assertThat(money(after, "fees")).isEqualTo(money(before, "fees"));
        }

        /**
         * Out of order across topics, through a real broker. The unique constraint arbitrates the insert race
         * and the rank guard stops the late creation regressing the status.
         */
        @Test
        @DisplayName("a completion arriving before its creation still reads as completed")
        void outOfOrderSurvives() {
            String paymentId = newPaymentId();
            String userId = newUserId();
            Instant at = recently();

            completed(paymentId, userId, inr(5_000), Money.ofMinor(300L, "INR"),
                    List.of("SWIFTPAY"), 3_900L, at.plusSeconds(4));
            awaitStatus(paymentId, "COMPLETED");

            created(paymentId, userId, inr(5_000), at);
            sleepBriefly();

            assertThat(statusOf(paymentId))
                    .as("""
                            a settled payment reported as in-flight would never correct itself: every event \
                            for it has already been consumed""")
                    .isEqualTo("COMPLETED");
            assertThat(countFacts()).isEqualTo(1);

            // The creation still contributed the fields only it carries.
            Map payment = payment(paymentId);
            assertThat(payment.get("initiatorUserId")).isEqualTo(userId);
            assertThat(payment.get("paymentType")).isEqualTo("P2P_TRANSFER");
        }

        /**
         * The three endings share a rank, so the first to arrive is the one recorded. A second terminal event
         * is a redelivery or a producer defect, and in both cases rewriting the outcome would be wrong.
         */
        @Test
        @DisplayName("a completion after a failure does not rewrite the outcome")
        void firstEndingWins() {
            String paymentId = newPaymentId();
            String userId = newUserId();
            Instant at = recently();

            created(paymentId, userId, inr(1_000), at);
            failed(paymentId, userId, inr(1_000), "PROVIDER_TIMEOUT", List.of("SWIFTPAY"),
                    at.plusSeconds(3));
            awaitStatus(paymentId, "FAILED");

            completed(paymentId, userId, inr(1_000), Money.ofMinor(60L, "INR"),
                    List.of("SWIFTPAY"), 3_000L, at.plusSeconds(5));
            sleepBriefly();

            assertThat(statusOf(paymentId)).isEqualTo("FAILED");
        }

        /**
         * A partial refund's cumulative total is assigned, not accumulated. The second event carries the new
         * running figure, so adding would count the first refund twice.
         */
        @Test
        @DisplayName("two partial refunds report the cumulative total once")
        void partialRefundsDoNotAccumulate() {
            String paymentId = newPaymentId();
            String userId = newUserId();
            Instant at = recently();

            created(paymentId, userId, inr(2_000), at);
            completed(paymentId, userId, inr(2_000), Money.ofMinor(120L, "INR"),
                    List.of("SWIFTPAY"), 4_000L, at.plusSeconds(4));
            awaitStatus(paymentId, "COMPLETED");

            refunded(paymentId, userId, inr(500), inr(2_000), false, at.plusSeconds(60));
            awaitStatus(paymentId, "PARTIALLY_REFUNDED");

            refunded(paymentId, userId, inr(1_200), inr(2_000), false, at.plusSeconds(120));
            awaitUntil("the cumulative refund to reach 1,200", Duration.ofSeconds(30),
                    () -> refundedMinorOf(paymentId) == 120_000L);

            assertThat(refundedMinorOf(paymentId))
                    .as("adding the deltas would report 1,700 refunded when 1,200 was")
                    .isEqualTo(120_000L);
        }

        /**
         * Degraded mode latches. Clearing it would leave a rules-only verdict indistinguishable from a scored
         * one, and a LOW band full of them reads as "low risk" rather than "nothing was checked".
         */
        @Test
        @DisplayName("a degraded verdict stays degraded through later events")
        void degradedModeLatches() {
            String paymentId = newPaymentId();
            String userId = newUserId();
            Instant at = recently();

            created(paymentId, userId, inr(2_000), at);
            scored(paymentId, RiskLevel.LOW, FraudDecision.APPROVE, 0.08, true, at.plusSeconds(1));
            awaitUntil("the degraded flag to be set", Duration.ofSeconds(30),
                    () -> Boolean.TRUE.equals(degradedOf(paymentId)));

            completed(paymentId, userId, inr(2_000), Money.ofMinor(120L, "INR"),
                    List.of("SWIFTPAY"), 4_000L, at.plusSeconds(4));
            awaitStatus(paymentId, "COMPLETED");

            assertThat(degradedOf(paymentId)).isTrue();
            assertThat(number(overview(), "degradedVerdicts")).isEqualTo(1L);
        }

        /**
         * An event with no payment id is refused non-retryably and must not damage the projection. The
         * assertion that matters is the second one: the listener that follows still works.
         */
        @Test
        @DisplayName("an unusable event is dead-lettered without stalling the stream")
        void unusableEventDoesNotStallTheStream() {
            String paymentId = newPaymentId();
            String userId = newUserId();
            Instant at = recently();

            // paymentId null: nothing to project, and it will be just as unusable on the fourth attempt.
            publish(com.aegispay.common.events.KafkaTopics.PAYMENT_CREATED, "unusable",
                    new com.aegispay.common.events.payload.PaymentCreatedPayload(
                            null, "TXN-1", "idem-1", userId, "WAL-1", "WAL-2", "USR-2",
                            inr(100), com.aegispay.common.domain.SharedEnums.PaymentType.P2P_TRANSFER,
                            null, null, at));

            created(paymentId, userId, inr(2_000), at.plusSeconds(1));

            assertThat(statusOf(paymentId))
                    .as("a poison record must not stop the partition it arrived on")
                    .isEqualTo("CREATED");
            assertThat(countFacts()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------------
    // aggregates
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("derived aggregates")
    class Aggregates {

        /**
         * The overview is one SQL statement using {@code FILTER}, and not one line of it is executed by a unit
         * test. A miscounted clause here would produce a plausible number that is simply wrong.
         */
        @Test
        @DisplayName("counts each outcome exactly once")
        void countsOutcomes() {
            Instant at = recently();
            String userId = newUserId();

            String settled = newPaymentId();
            created(settled, userId, inr(1_000), at);
            completed(settled, userId, inr(1_000), Money.ofMinor(60L, "INR"), List.of("SWIFTPAY"),
                    3_000L, at.plusSeconds(3));

            String broken = newPaymentId();
            created(broken, userId, inr(2_000), at);
            failed(broken, userId, inr(2_000), "PROVIDER_TIMEOUT", List.of("SWIFTPAY", "RAPIDRAIL"),
                    at.plusSeconds(3));

            String refused = newPaymentId();
            created(refused, userId, inr(9_000), at);
            rejected(refused, userId, inr(9_000), RiskLevel.CRITICAL, at.plusSeconds(2));

            String inFlight = newPaymentId();
            created(inFlight, userId, inr(500), at);

            awaitStatus(settled, "COMPLETED");
            awaitStatus(broken, "FAILED");
            awaitStatus(refused, "REJECTED");

            Map overview = overview();

            assertThat(number(overview, "payments")).isEqualTo(4L);
            assertThat(number(overview, "completed")).isEqualTo(1L);
            assertThat(number(overview, "failed")).isEqualTo(1L);
            assertThat(number(overview, "rejected")).isEqualTo(1L);
            assertThat(number(overview, "inFlight")).isEqualTo(1L);
            assertThat(money(overview, "settled"))
                    .as("settled counts completed payments only; an in-flight one has not settled")
                    .isEqualTo(100_000L);
            assertThat(number(overview, "fellBack"))
                    .as("two provider attempts means routing switched rail")
                    .isEqualTo(1L);
        }

        /**
         * Three payments reached a terminal state and one of them completed, so the rate is a third. The
         * in-flight payment must not be in the denominator: it has not failed, it has not finished.
         */
        @Test
        @DisplayName("the success rate divides by terminal payments, not by all of them")
        void successRateUsesTerminalPayments() {
            Instant at = recently();
            String userId = newUserId();

            String settled = newPaymentId();
            created(settled, userId, inr(1_000), at);
            completed(settled, userId, inr(1_000), Money.ofMinor(60L, "INR"), List.of("SWIFTPAY"),
                    3_000L, at.plusSeconds(3));

            String broken = newPaymentId();
            created(broken, userId, inr(1_000), at);
            failed(broken, userId, inr(1_000), "PROVIDER_TIMEOUT", List.of("SWIFTPAY"),
                    at.plusSeconds(3));

            String refused = newPaymentId();
            created(refused, userId, inr(1_000), at);
            rejected(refused, userId, inr(1_000), RiskLevel.CRITICAL, at.plusSeconds(2));

            String pending = newPaymentId();
            created(pending, userId, inr(1_000), at);

            awaitStatus(settled, "COMPLETED");
            awaitStatus(broken, "FAILED");
            awaitStatus(refused, "REJECTED");

            assertThat((Double) overview().get("successRate"))
                    .as("one of three terminal payments completed")
                    .isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
        }

        /**
         * <strong>Null, not zero.</strong> "Every payment failed" and "nothing has finished yet" render
         * identically on a tile while calling for opposite responses.
         */
        @Test
        @DisplayName("the success rate is null when nothing reached a terminal state")
        void successRateIsNullWhenNothingSettled() {
            Instant at = recently();
            String userId = newUserId();

            String pending = newPaymentId();
            created(pending, userId, inr(1_000), at);

            Map overview = overview();

            assertThat(number(overview, "payments")).isEqualTo(1L);
            assertThat(overview.get("successRate"))
                    .as("a zero rate says every payment failed; null says none have finished")
                    .isNull();
        }

        /**
         * The bucket boundaries are computed twice — in Java for the returned bounds, in SQL for the grouping.
         * If they disagreed the first and last buckets would be partial and the totals would not match the
         * tiles above them.
         */
        @Test
        @DisplayName("the time series sums to the overview total")
        void timeSeriesAgreesWithTheOverview() {
            Instant at = recently();
            String userId = newUserId();

            for (int index = 0; index < 3; index++) {
                String paymentId = newPaymentId();
                created(paymentId, userId, inr(1_000), at.plusSeconds(index));
                completed(paymentId, userId, inr(1_000), Money.ofMinor(60L, "INR"),
                        List.of("SWIFTPAY"), 3_000L, at.plusSeconds(index + 3));
                awaitStatus(paymentId, "COMPLETED");
            }

            Map series = get("/api/admin/analytics/timeseries", adminToken(), Map.class).body();
            List<Map<String, Object>> buckets = buckets(series);

            long charted = buckets.stream()
                    .mapToLong(bucket -> ((Number) bucket.get("payments")).longValue())
                    .sum();

            assertThat(charted)
                    .as("""
                            a chart whose bars do not add up to the tile above it is the symptom of Java and \
                            date_trunc disagreeing about where a bucket starts""")
                    .isEqualTo(number(overview(), "payments"));
        }

        /**
         * The unscored band is its own band. Folding it into LOW would overstate how many genuinely low-risk
         * payments there were, and the band counts would stop adding up to the total.
         */
        @Test
        @DisplayName("risk bands include UNSCORED and add up to the payment total")
        void riskBandsAddUp() {
            Instant at = recently();
            String userId = newUserId();

            String scored = newPaymentId();
            created(scored, userId, inr(1_000), at);
            scored(scored, RiskLevel.HIGH, FraudDecision.HOLD, 0.81, false, at.plusSeconds(1));
            awaitUntil("the score to be projected", Duration.ofSeconds(30),
                    () -> riskScoreOf(scored) != null);

            // Refused before the engine ever scored it: no risk level at all.
            String never = newPaymentId();
            created(never, userId, inr(1_000), at);

            Map risk = get("/api/admin/analytics/risk", adminToken(), Map.class).body();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> bands = (List<Map<String, Object>>) risk.get("bands");

            assertThat(bands).extracting(band -> band.get("riskLevel"))
                    .as("UNSCORED must be a band, or the counts do not sum to the total")
                    .contains("UNSCORED");

            long banded = bands.stream()
                    .mapToLong(band -> ((Number) band.get("payments")).longValue())
                    .sum();

            assertThat(banded).isEqualTo(number(overview(), "payments"));
            assertThat(number(risk, "unscored")).isEqualTo(1L);
        }

        /**
         * Percentiles over completed payments only. Counting an in-flight payment as zero would make the
         * platform look faster the more backlog it had, which is exactly backwards.
         */
        @Test
        @DisplayName("latency percentiles ignore payments that never completed")
        void latencyCountsCompletedOnly() {
            Instant at = recently();
            String userId = newUserId();

            String settled = newPaymentId();
            created(settled, userId, inr(1_000), at);
            completed(settled, userId, inr(1_000), Money.ofMinor(60L, "INR"), List.of("SWIFTPAY"),
                    4_000L, at.plusSeconds(4));
            awaitStatus(settled, "COMPLETED");

            String pending = newPaymentId();
            created(pending, userId, inr(1_000), at);

            String refused = newPaymentId();
            created(refused, userId, inr(1_000), at);
            rejected(refused, userId, inr(1_000), RiskLevel.CRITICAL, at.plusSeconds(2));
            awaitStatus(refused, "REJECTED");

            Map performance = get("/api/admin/analytics/performance", adminToken(), Map.class).body();
            @SuppressWarnings("unchecked")
            Map<String, Object> latency = (Map<String, Object>) performance.get("latency");

            assertThat(((Number) latency.get("samples")).longValue())
                    .as("""
                            three payments, one completed. Two zero-latency samples would halve every \
                            percentile and flatter the platform""")
                    .isEqualTo(1L);
            assertThat(((Number) latency.get("p50")).longValue()).isEqualTo(4_000L);
        }

        /** Only failures appear in the failure mix. A rejection on risk is not a platform fault. */
        @Test
        @DisplayName("the failure mix excludes rejections")
        void failureMixExcludesRejections() {
            Instant at = recently();
            String userId = newUserId();

            String broken = newPaymentId();
            created(broken, userId, inr(1_000), at);
            failed(broken, userId, inr(1_000), "PROVIDER_TIMEOUT", List.of("SWIFTPAY"),
                    at.plusSeconds(3));
            awaitStatus(broken, "FAILED");

            String refused = newPaymentId();
            created(refused, userId, inr(1_000), at);
            rejected(refused, userId, inr(1_000), RiskLevel.CRITICAL, at.plusSeconds(2));
            awaitStatus(refused, "REJECTED");

            Map performance = get("/api/admin/analytics/performance", adminToken(), Map.class).body();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failures = (List<Map<String, Object>>) performance.get("failures");

            assertThat(failures).extracting(failure -> failure.get("failureCode"))
                    .containsExactly("PROVIDER_TIMEOUT");
        }
    }

    // ------------------------------------------------------------------------
    // the payment list
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the platform-wide payment list")
    class PaymentList {

        /** Filters combine, because a dashboard genuinely does want "failed payments over 10,000 yesterday". */
        @Test
        @DisplayName("combines the status, amount and provider filters")
        void filtersCombine() {
            Instant at = recently();
            String userId = newUserId();

            String big = newPaymentId();
            created(big, userId, inr(20_000), at);
            failed(big, userId, inr(20_000), "PROVIDER_TIMEOUT", List.of("SWIFTPAY"),
                    at.plusSeconds(3));

            String small = newPaymentId();
            created(small, userId, inr(100), at);
            failed(small, userId, inr(100), "PROVIDER_TIMEOUT", List.of("SWIFTPAY"),
                    at.plusSeconds(3));

            awaitStatus(big, "FAILED");
            awaitStatus(small, "FAILED");

            Map page = get("/api/admin/analytics/payments?status=FAILED&minAmountMinor=1000000",
                    adminToken(), Map.class).body();

            assertThat(content(page)).extracting(row -> row.get("paymentId")).containsExactly(big);
        }

        /**
         * The degraded filter, which is the one that cannot be inferred from anything else. There is a partial
         * index for exactly this predicate.
         */
        @Test
        @DisplayName("isolates verdicts reached without the model")
        void filtersDegradedOnly() {
            Instant at = recently();
            String userId = newUserId();

            String blind = newPaymentId();
            created(blind, userId, inr(1_000), at);
            scored(blind, RiskLevel.LOW, FraudDecision.APPROVE, 0.09, true, at.plusSeconds(1));

            String checked = newPaymentId();
            created(checked, userId, inr(1_000), at);
            scored(checked, RiskLevel.LOW, FraudDecision.APPROVE, 0.09, false, at.plusSeconds(1));

            awaitUntil("both verdicts to be projected", Duration.ofSeconds(30),
                    () -> degradedOf(blind) != null && degradedOf(checked) != null);

            Map page = get("/api/admin/analytics/payments?degradedOnly=true", adminToken(), Map.class)
                    .body();

            assertThat(content(page)).extracting(row -> row.get("paymentId"))
                    .as("""
                            both are LOW; only one was actually scored. That distinction is the whole reason \
                            this filter exists""")
                    .containsExactly(blind);
        }

        /** A score floor spans bands, which is what somebody reviewing a threshold change needs. */
        @Test
        @DisplayName("filters on a score floor rather than a band")
        void filtersOnScoreFloor() {
            Instant at = recently();
            String userId = newUserId();

            String high = newPaymentId();
            created(high, userId, inr(1_000), at);
            scored(high, RiskLevel.HIGH, FraudDecision.HOLD, 0.78, false, at.plusSeconds(1));

            String low = newPaymentId();
            created(low, userId, inr(1_000), at);
            scored(low, RiskLevel.LOW, FraudDecision.APPROVE, 0.12, false, at.plusSeconds(1));

            awaitUntil("both scores to be projected", Duration.ofSeconds(30),
                    () -> riskScoreOf(high) != null && riskScoreOf(low) != null);

            Map page = get("/api/admin/analytics/payments?minRiskScore=70", adminToken(), Map.class)
                    .body();

            assertThat(content(page)).extracting(row -> row.get("paymentId")).containsExactly(high);
        }

        /**
         * Two fields must never appear on this list: the description, which is customer free text, and any
         * device or location signal. Staff read this across every customer on the platform.
         */
        @Test
        @DisplayName("omits the customer's description and every risk signal")
        void omitsCustomerFreeTextAndSignals() {
            String paymentId = newPaymentId();
            created(paymentId, newUserId(), inr(1_000), recently());

            Map row = content(get("/api/admin/analytics/payments", adminToken(), Map.class).body())
                    .getFirst();

            assertThat(row)
                    .as("""
                            the description is written by the customer and the signals are data they never \
                            knowingly handed to a person""")
                    .doesNotContainKeys("description", "riskSignals", "ipAddress", "deviceId");
        }

        /**
         * A 404 here means not projected, which is not the same as not existing. The message says so and names
         * payment-service as authoritative, because that is the difference between "chase the consumer" and
         * "the payment is not real".
         */
        @Test
        @DisplayName("a payment absent from the read model 404s and names payment-service")
        void unprojectedPaymentIsNotFound() {
            Response<Map> response = get("/api/admin/analytics/payments/" + newPaymentId(),
                    adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(String.valueOf(response.body().get("message")))
                    .contains("payment-service");
        }

        /** The page ceiling exists because this list is platform-wide rather than customer-scoped. */
        @Test
        @DisplayName("caps an oversized page request rather than serving it")
        void capsPageSize() {
            created(newPaymentId(), newUserId(), inr(1_000), recently());

            Map page = get("/api/admin/analytics/payments?size=5000", adminToken(), Map.class).body();

            assertThat(((Number) page.get("size")).intValue())
                    .as("an unbounded page is a way to ask one service to serialise the whole table")
                    .isLessThanOrEqualTo(100);
        }
    }

    // ------------------------------------------------------------------------
    // freshness
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("projection freshness")
    class Freshness {

        /**
         * Per topic, not one global figure. A single number only looks stale once every consumer has stopped;
         * per topic, a broken {@code fraud-checked} consumer is visible while payments still flow.
         */
        @Test
        @DisplayName("reports lag for each topic it has consumed")
        void reportsPerStreamLag() {
            Instant at = recently();
            String paymentId = newPaymentId();
            String userId = newUserId();

            created(paymentId, userId, inr(1_000), at);
            scored(paymentId, RiskLevel.LOW, FraudDecision.APPROVE, 0.1, false, at.plusSeconds(1));
            awaitUntil("both streams to register", Duration.ofSeconds(30), () -> countStreams() >= 2);

            Map projection = get("/api/admin/analytics/projection", adminToken(), Map.class).body();
            @SuppressWarnings("unchecked")
            Map<String, Object> freshness = (Map<String, Object>) projection.get("freshness");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> streams = (List<Map<String, Object>>) freshness.get("streams");

            assertThat(streams).extracting(stream -> stream.get("topic"))
                    .contains("payment-created", "fraud-checked");
            assertThat(streams).allSatisfy(stream ->
                    assertThat(stream).containsKeys("lastEventAt", "lastAppliedAt", "lagSeconds", "stale"));
        }

        /**
         * A 30-minute-old event against a 3-second tolerance is stale, and saying so is the point. An operator
         * reading "0 held payments" needs to know whether the queue is clear or the consumer stopped.
         */
        @Test
        @DisplayName("flags a stream that has fallen behind the tolerance")
        void flagsStaleStreams() {
            created(newPaymentId(), newUserId(), inr(1_000), recently());

            Map projection = get("/api/admin/analytics/projection", adminToken(), Map.class).body();
            @SuppressWarnings("unchecked")
            Map<String, Object> freshness = (Map<String, Object>) projection.get("freshness");

            assertThat(freshness.get("stale"))
                    .as("a projection that goes quietly stale is worse than one that says so")
                    .isEqualTo(true);
            assertThat(((Number) freshness.get("lagSeconds")).longValue()).isGreaterThan(3L);
        }

        /**
         * Nothing projected is its own state. Null rather than a substituted timestamp, because on a new
         * deployment it is normal and on an established one it is the most serious thing this figure can say.
         */
        @Test
        @DisplayName("reports no newest event when nothing has been projected")
        void reportsEmptyProjection() {
            Map projection = get("/api/admin/analytics/projection", adminToken(), Map.class).body();
            @SuppressWarnings("unchecked")
            Map<String, Object> freshness = (Map<String, Object>) projection.get("freshness");

            assertThat(freshness.get("newestEventAt"))
                    .as("a substituted timestamp would report an empty projection as up to date")
                    .isNull();
            assertThat(((Number) projection.get("paymentsProjected")).longValue()).isZero();
        }

        /**
         * The boundary is published rather than left to be inferred from which endpoints happen to exist —
         * otherwise "does the dashboard know about refunds" is answerable only by reading the source.
         */
        @Test
        @DisplayName("names the topics it deliberately does not project")
        void publishesItsOwnBoundary() {
            Map projection = get("/api/admin/analytics/projection", adminToken(), Map.class).body();

            @SuppressWarnings("unchecked")
            List<String> projected = (List<String>) projection.get("projectedTopics");
            @SuppressWarnings("unchecked")
            List<String> notProjected = (List<String>) projection.get("notProjected");

            assertThat(projected).contains("payment-created", "fraud-checked", "payment-completed");
            assertThat(notProjected)
                    .as("a reader has to be able to tell an omission from an oversight")
                    .isNotEmpty();
            assertThat(projection.get("derivedAggregates"))
                    .as("states that totals are recomputed from rows rather than incremented")
                    .isEqualTo(true);
        }
    }

    // ------------------------------------------------------------------------
    // access control
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("access control")
    class AccessControl {

        private static final List<String> ENDPOINTS = List.of(
                "/api/admin/analytics/overview",
                "/api/admin/analytics/timeseries",
                "/api/admin/analytics/risk",
                "/api/admin/analytics/performance",
                "/api/admin/analytics/payments",
                "/api/admin/analytics/projection");

        @Test
        @DisplayName("refuses every endpoint without a token")
        void refusesAnonymous() {
            for (String endpoint : ENDPOINTS) {
                assertThat(get(endpoint, null, Map.class).status())
                        .as("%s must not be readable unauthenticated", endpoint)
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }

        /**
         * A signed customer token is not enough. This is the assertion that separates "authenticated" from
         * "authorised": every row here belongs to somebody else.
         */
        @Test
        @DisplayName("refuses a customer token on every endpoint")
        void refusesCustomers() {
            String token = customerToken();
            for (String endpoint : ENDPOINTS) {
                assertThat(get(endpoint, token, Map.class).status())
                        .as("%s exposes every customer's payments", endpoint)
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        }

        /** Both operational roles can read. An analyst working the alert queue needs the same context. */
        @Test
        @DisplayName("allows an admin and a fraud analyst")
        void allowsOperationalRoles() {
            for (String token : List.of(adminToken(), analystToken())) {
                for (String endpoint : ENDPOINTS) {
                    assertThat(get(endpoint, token, Map.class).status())
                            .as("%s must be readable by an operator", endpoint)
                            .isEqualTo(HttpStatus.OK);
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // window validation
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("window validation over HTTP")
    class WindowValidation {

        @Test
        @DisplayName("refuses a reversed window")
        void refusesReversedWindow() {
            Response<Map> response = get(
                    "/api/admin/analytics/overview?from=2026-03-02T00:00:00Z&to=2026-03-01T00:00:00Z",
                    adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(response.body().get("message"))).contains("before");
        }

        /**
         * Refused, not silently coarsened. Quietly returning day buckets for a minute request would make the
         * chart's own axis labels wrong, and the message names the granularity to use instead.
         */
        @Test
        @DisplayName("refuses too fine a granularity and names the one to use")
        void refusesTooFineGranularity() {
            Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS);
            Instant from = to.minus(Duration.ofDays(30));

            Response<Map> response = get("/api/admin/analytics/timeseries?from=" + from + "&to=" + to
                    + "&granularity=MINUTE", adminToken(), Map.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(response.body().get("message")))
                    .contains("day buckets");
        }

        /**
         * An unknown granularity is rejected at binding, before any SQL is built. That is what makes the unit
         * safe to interpolate into {@code date_trunc}, where PostgreSQL cannot bind it as a parameter.
         */
        @Test
        @DisplayName("refuses an unknown granularity before building any SQL")
        void refusesUnknownGranularity() {
            assertThat(get("/api/admin/analytics/timeseries?granularity=month';DROP%20TABLE%20payment_facts;--",
                    adminToken(), Map.class).status())
                    .as("the enum is what stops caller input reaching a statement fragment")
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            // And the table is still there.
            assertThat(get("/api/admin/analytics/overview", adminToken(), Map.class).status())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Map overview() {
        return get("/api/admin/analytics/overview", adminToken(), Map.class).body();
    }

    private Map payment(String paymentId) {
        return get("/api/admin/analytics/payments/" + paymentId, adminToken(), Map.class).body();
    }

    private static long number(Map body, String field) {
        Object value = body.get(field);
        assertThat(value).as("field '%s' is missing from the response", field).isNotNull();
        return ((Number) value).longValue();
    }

    /** Reads a money object's exact minor units. Never the decimal string — that is for display only. */
    private static long money(Map body, String field) {
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) body.get(field);
        assertThat(amount).as("money field '%s' is missing", field).isNotNull();
        return ((Number) amount.get("amountMinor")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buckets(Map series) {
        return (List<Map<String, Object>>) series.get("buckets");
    }
}
