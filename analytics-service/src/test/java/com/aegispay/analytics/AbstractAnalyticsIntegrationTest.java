package com.aegispay.analytics;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.FraudCheckedPayload;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentApprovedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentFailedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRefundedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRejectedPayload;
import com.aegispay.common.kafka.EventPublisher;
import com.aegispay.common.money.Money;
import com.aegispay.common.testsupport.TestJwkServer;
import com.aegispay.common.testsupport.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for analytics-service integration tests.
 *
 * <p>Real PostgreSQL, real Redis, real Kafka. Events are published onto an actual broker and the assertions
 * read the projection back through HTTP, so what is exercised is the whole path: a record arrives, the
 * listener decodes it, the projection merges it into a row, and an aggregate is derived from that row by
 * PostgreSQL.
 *
 * <h2>What the containers buy that a unit test cannot</h2>
 *
 * <p>Nearly every guarantee this service makes lives outside Java:
 *
 * <ul>
 *   <li><strong>Every aggregate is SQL.</strong> The overview, the time series, the risk bands, the score
 *       histogram and the latency percentiles are native queries using {@code FILTER}, {@code date_trunc}
 *       and {@code percentile_cont}. Not one line of that is executed by a unit test. A miscounted
 *       {@code FILTER} clause would produce a plausible number that is simply wrong.</li>
 *   <li><strong>{@code uq_payment_facts_payment}.</strong> The find-or-create in application code is only
 *       an optimisation — two consumer threads on two topics both pass it. Only the unique constraint
 *       arbitrates, and only a real database has one.</li>
 *   <li><strong>{@code ck_payment_facts_score_matches_probability}.</strong> Keeps the score and the
 *       probability from drifting apart. The Java side maintains it; the database is what enforces it.</li>
 *   <li><strong>{@code ck_payment_facts_completed_after_created}.</strong> The clamp in the aggregate
 *       exists to satisfy this, and whether it actually does is a question only PostgreSQL answers.</li>
 *   <li><strong>{@code date_trunc} agreeing with {@link com.aegispay.analytics.service.AnalyticsWindow}.</strong>
 *       Bucket boundaries are computed twice, once in Java for the returned bounds and once in SQL for the
 *       grouping. If they disagreed, the first and last buckets would be partial and the totals would not
 *       match the tiles above them.</li>
 *   <li><strong>Idempotence end to end.</strong> Publishing the same event twice must leave every total
 *       unchanged. That is the whole design claim, and it is only a claim until a real consumer, a real
 *       de-duplication cache and a real database have all seen the redelivery.</li>
 * </ul>
 *
 * <h2>Honest note on execution</h2>
 *
 * <p>These skip cleanly where Docker is unavailable, via {@code disabledWithoutDocker}. That is a real gap
 * rather than a solved problem: everything listed above is unverified in such an environment. It is why
 * {@code AnalyticsPropertiesBindingTest} binds the shipped configuration and
 * {@code AnalyticsTransactionBoundaryTest} inspects the annotations — they cover what can be covered of the
 * ground these tests would otherwise be the only thing standing on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "aegis.logging.json=false",
        "spring.datasource.hikari.auto-commit=true",
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        /*
         * Off, unlike every other service's integration tests.
         *
         * This service has no service client and makes no outbound authenticated call — it consumes events
         * and answers queries. Enabling the interceptor would mean standing up a stub token endpoint for a
         * credential nothing ever spends, which would test the stub rather than the service.
         */
        "aegis.service-auth.enabled=false",
        /*
         * Short, so a staleness assertion does not have to wait out the production tolerance. The two-minute
         * default is asserted separately by AnalyticsPropertiesBindingTest against the shipped file; what
         * these tests need is for a stream to actually cross the line.
         */
        "aegis.analytics.stale-after=3s"
})
abstract class AbstractAnalyticsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_analytics_test")
                    .withUsername("aegis_test")
                    .withPassword("aegis_test_password");

    /**
     * Needed because {@code ProcessedEventRegistry} is Redis-backed and readiness includes it.
     *
     * <p>Without Redis the registry degrades to a no-op that suppresses nothing — and most of these tests
     * would still pass, because the durable guarantee is the projection being idempotent rather than the
     * cache. Which is precisely why it is here: the tests should run against the arrangement production
     * uses, not the one that happens to work.
     */
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Container
    static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka-native:3.9.1");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("aegis.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                TestJwkServer::jwkSetUri);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected EventPublisher events;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    /**
     * Clears the projection between tests.
     *
     * <p>Both tables, and Redis. The de-duplication marker matters: a leftover marker from a previous test
     * would suppress this test's event, and the test would fail with an empty projection for a reason that
     * has nothing to do with what it was asserting.
     */
    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        jdbc.execute("TRUNCATE TABLE payment_facts, projection_stream_state");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ------------------------------------------------------- driving the service

    /**
     * Publishes {@code payment-created} exactly as payment-service's outbox drainer would.
     *
     * <p>Through the real broker onto the real listener, so the record is keyed, headed and encoded the same
     * way. Waits for the row, because every subsequent assertion depends on it existing.
     */
    protected void created(String paymentId, String userId, Money amount, Instant at) {
        publish(KafkaTopics.PAYMENT_CREATED, paymentId,
                new PaymentCreatedPayload(paymentId, "TXN-" + shortOf(paymentId), "idem-" + shortOf(paymentId),
                        userId, "WAL-SRC-" + shortOf(userId), "WAL-DST-" + shortOf(userId),
                        "USR-DEST-" + shortOf(userId), amount, PaymentType.P2P_TRANSFER,
                        "Rent", null, at));

        awaitProjected(paymentId);
    }

    /** Publishes {@code fraud-checked}: the verdict, and whether the model was involved. */
    protected void scored(String paymentId, RiskLevel level, FraudDecision decision,
                          double probability, boolean degraded, Instant at) {
        publish(KafkaTopics.FRAUD_CHECKED, paymentId,
                new FraudCheckedPayload(paymentId, "TXN-" + shortOf(paymentId), probability, level,
                        decision, List.of("velocity above baseline"), List.of("RULE_VELOCITY"),
                        degraded ? null : "fraud_lgbm-20260901T104500Z",
                        degraded ? Map.of() : Map.of("amount", 2000), 24L, degraded, at));
    }

    protected void approved(String paymentId, String userId, Money amount, RiskLevel level,
                            double probability, Instant at) {
        publish(KafkaTopics.PAYMENT_APPROVED, paymentId,
                new PaymentApprovedPayload(paymentId, "TXN-" + shortOf(paymentId), userId, amount,
                        level, probability, at));
    }

    /**
     * Publishes {@code payment-completed}.
     *
     * <p>{@code providerAttempts} carries the whole list rather than a count, because that is what makes the
     * fallback figure idempotent: the list is the complete truth on every delivery, so assigning its size is
     * safe where incrementing would not be.
     */
    protected void completed(String paymentId, String userId, Money amount, Money fee,
                             List<String> attempts, long latencyMs, Instant at) {
        publish(KafkaTopics.PAYMENT_COMPLETED, paymentId,
                new PaymentCompletedPayload(paymentId, "TXN-" + shortOf(paymentId), userId,
                        "USR-DEST-" + shortOf(userId), "WAL-SRC-" + shortOf(userId),
                        "WAL-DST-" + shortOf(userId), amount, fee, attempts.getLast(),
                        "PROV-REF-" + shortOf(paymentId), attempts, "LED-" + shortOf(paymentId),
                        latencyMs, at));
    }

    protected void failed(String paymentId, String userId, Money amount, String code,
                          List<String> attempts, Instant at) {
        publish(KafkaTopics.PAYMENT_FAILED, paymentId,
                new PaymentFailedPayload(paymentId, "TXN-" + shortOf(paymentId), userId, amount, code,
                        "The rail did not respond", "CHARGE_PROVIDER", attempts, attempts.size(),
                        true, at));
    }

    protected void rejected(String paymentId, String userId, Money amount, RiskLevel level,
                            Instant at) {
        publish(KafkaTopics.PAYMENT_REJECTED, paymentId,
                new PaymentRejectedPayload(paymentId, "TXN-" + shortOf(paymentId), userId, amount,
                        level, FraudDecision.REJECT, "FRAUD_REJECT", "Refused on risk",
                        List.of("score above threshold"), true, false, at));
    }

    protected void refunded(String paymentId, String userId, Money totalRefunded, Money original,
                            boolean full, Instant at) {
        publish(KafkaTopics.PAYMENT_REFUNDED, paymentId,
                new PaymentRefundedPayload(paymentId, "TXN-" + shortOf(paymentId),
                        "REF-" + shortOf(paymentId), userId, "WAL-SRC-" + shortOf(userId),
                        "WAL-DST-" + shortOf(userId), totalRefunded, totalRefunded, original, full,
                        "Customer request", userId, "LED-REF-" + shortOf(paymentId), at));
    }

    /** Publishes without waiting. Used where a test asserts an absence, or drives ordering itself. */
    protected void publish(String topic, String paymentId, Object payload) {
        events.publishSync(topic,
                EventEnvelope.of(topic, "Payment", paymentId, "payment-service", payload));
    }

    // ------------------------------------------------------------ HTTP helpers

    protected record Response<T>(HttpStatus status, T body) {
    }

    protected <T> Response<T> get(String uri, String token, Class<T> type) {
        RestTestClient.RequestBodySpec request = client.method(HttpMethod.GET)
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON);

        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        EntityExchangeResult<T> result = request.exchange().returnResult(type);
        return new Response<>(HttpStatus.valueOf(result.getStatus().value()),
                result.getResponseBody());
    }

    // ---------------------------------------------------------------- tokens

    protected static String adminToken() {
        return TestJwtIssuer.user(newUserId(), "ADMIN");
    }

    protected static String analystToken() {
        return TestJwtIssuer.user(newUserId(), "FRAUD_ANALYST");
    }

    /** A signed token with no privilege here. Used to assert the endpoints are not merely authenticated. */
    protected static String customerToken() {
        return TestJwtIssuer.user(newUserId(), "USER");
    }

    // --------------------------------------------------------------- fixtures

    protected static String newUserId() {
        return "USR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static String newPaymentId() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }

    protected static Money inr(long major) {
        return Money.ofMinor(major * 100L, "INR");
    }

    private static String shortOf(String value) {
        return value.length() <= 12 ? value : value.substring(value.length() - 12);
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected long countFacts() {
        return queryLong("SELECT count(*) FROM payment_facts");
    }

    protected String statusOf(String paymentId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM payment_facts WHERE payment_id = ?", String.class, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected Long amountMinorOf(String paymentId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT amount_minor FROM payment_facts WHERE payment_id = ?", Long.class, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected Integer riskScoreOf(String paymentId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT risk_score FROM payment_facts WHERE payment_id = ?", Integer.class, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected Boolean degradedOf(String paymentId) {
        List<Boolean> rows = jdbc.queryForList(
                "SELECT degraded_mode FROM payment_facts WHERE payment_id = ?", Boolean.class,
                paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected long refundedMinorOf(String paymentId) {
        return queryLong("SELECT refunded_minor FROM payment_facts WHERE payment_id = ?", paymentId);
    }

    protected long countStreams() {
        return queryLong("SELECT count(*) FROM projection_stream_state");
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    // ------------------------------------------------------------- waiting

    protected void awaitProjected(String paymentId) {
        awaitUntil("payment " + paymentId + " to be projected", Duration.ofSeconds(60),
                () -> statusOf(paymentId) != null);
    }

    protected void awaitStatus(String paymentId, String status) {
        awaitUntil("payment " + paymentId + " to reach " + status, Duration.ofSeconds(60),
                () -> status.equals(statusOf(paymentId)));
    }

    /** Polls with a deadline: a fixed sleep is either flaky or wasteful. */
    protected static void awaitUntil(String description, Duration timeout,
                                     BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, ex);
            }
        }
        assertThat(condition.getAsBoolean())
                .as("timed out after %s waiting for %s", timeout, description)
                .isTrue();
    }

    /**
     * A short pause used only to give something that should <em>not</em> happen a chance to happen.
     *
     * <p>Ordinary waits use {@link #awaitUntil}. Proving an absence — that a redelivery changed nothing —
     * has no condition to poll for, so the test allows a plausible window and then asserts nothing moved.
     */
    protected static void sleepBriefly() {
        try {
            Thread.sleep(2_500L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while proving an absence", ex);
        }
    }
}
