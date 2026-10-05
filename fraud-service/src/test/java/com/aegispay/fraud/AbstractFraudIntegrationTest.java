package com.aegispay.fraud;

import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.kafka.EventPublisher;
import com.aegispay.common.money.Money;
import com.aegispay.common.testsupport.TestJwkServer;
import com.aegispay.common.testsupport.TestJwtIssuer;
import org.junit.jupiter.api.AfterAll;
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
 * Base class for fraud-service integration tests.
 *
 * <p>Real PostgreSQL, real Redis, real Kafka, and real HTTP to {@link StubMlService}. What is
 * exercised is therefore the whole scoring path: a {@code payment-created} record arrives on a broker
 * and builds the read model, the saga's internal endpoint asks for a verdict, the feature builder runs
 * local SQL over that read model, the client puts a real JSON body on a real socket, and the verdict
 * lands in a committed row plus an outbox event.
 *
 * <p><strong>What the containers buy that a mock cannot.</strong> Three of this service's guarantees
 * live in the database and nowhere else: the unique constraint on {@code payment_id} that makes
 * assessment idempotent under concurrency, the CHECK that keeps {@code risk_score} equal to
 * {@code ROUND(fraud_probability * 100)}, and the trigger that refuses UPDATE on
 * {@code analyst_decisions}. A test with mocked repositories asserts the application's intention; only
 * a real database asserts the guarantee.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "aegis.logging.json=false",
        // Raw JdbcTemplate cleanup/history fixtures must commit before HTTP, Kafka, and outbox
        // worker threads use their own connections.
        "spring.datasource.hikari.auto-commit=true",
        // Drain the outbox quickly so a test waits milliseconds, not the production cadence.
        "aegis.outbox.poll-interval=50ms",
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        // Service auth stays ON: the stub issues the token, so the real interceptor runs and an
        // unauthenticated internal call is detectable rather than invisible.
        "aegis.service-auth.enabled=true",
        "aegis.service-auth.client-id=fraud-service-test",
        "aegis.service-auth.client-secret=test-internal-secret",
        // One attempt, so an injected model failure produces a degraded verdict immediately rather
        // than after a backoff the test would have to wait out.
        "aegis.fraud.ml.max-attempts=1",
        "aegis.fraud.ml.timeout=2s",
        "aegis.fraud.ml.api-key=" + StubMlService.API_KEY
})
abstract class AbstractFraudIntegrationTest {

    protected static final String CURRENCY = "INR";
    protected static final long RUPEE = 100L;

    /**
     * Started once for the whole JVM rather than per class, because its base URL and token URI are
     * bound into the Spring context as properties; a per-class server would change the port and force
     * a fresh context for every test class.
     */
    protected static final StubMlService ML = new StubMlService();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_fraud_test")
                    .withUsername("aegis_test")
                    .withPassword("aegis_test_password");

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
        registry.add("aegis.clients.ml-service-url", ML::baseUrl);
        registry.add("aegis.service-auth.token-uri", ML::tokenUri);
    }

    @AfterAll
    static void stopStub() {
        ML.stop();
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

    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // analyst_decisions refuses DELETE by trigger, so it has to be disabled for the truncate.
        // Done explicitly and re-enabled immediately: leaving it off would let a later test pass
        // while the append-only guarantee was absent, which is the one thing this table is for.
        jdbc.execute("ALTER TABLE analyst_decisions DISABLE TRIGGER trg_analyst_decisions_append_only");
        jdbc.execute("TRUNCATE TABLE payment_observations, fraud_assessments, fraud_alerts, "
                + "analyst_decisions, outbox_events CASCADE");
        jdbc.execute("ALTER TABLE analyst_decisions ENABLE TRIGGER trg_analyst_decisions_append_only");

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        ML.reset();
    }

    // ------------------------------------------------------- driving the service

    /** Asks for a verdict the way the saga does: the internal endpoint, with a service token. */
    protected Response<Map> assess(String paymentId, String userId, String payeeWallet,
                                   String amount) {
        return assess(paymentId, userId, payeeWallet, amount, PaymentType.P2P_TRANSFER,
                signals("fp-known", "IN", null, null), Instant.now());
    }

    protected Response<Map> assess(String paymentId, String userId, String payeeWallet,
                                   String amount, PaymentType paymentType, RiskSignals signals,
                                   Instant occurredAt) {
        return post("/internal/fraud/assess", Map.of(
                        "paymentId", paymentId,
                        "transactionId", "TXN-" + paymentId.substring(4, 12),
                        "initiatorUserId", userId,
                        "destinationWalletId", payeeWallet,
                        "amount", amount,
                        "currency", CURRENCY,
                        "paymentType", paymentType.name(),
                        "riskSignals", signals,
                        "occurredAt", occurredAt.toString()),
                serviceToken(), Map.class);
    }

    /**
     * Publishes {@code payment-created}, which is how the read model is built.
     *
     * <p>Through the real broker onto the real listener, so the record is keyed, headed and encoded
     * exactly as payment-service's outbox drainer would encode it.
     */
    protected void observePayment(String paymentId, String userId, String payeeWallet,
                                  String amount, Instant occurredAt) {
        observePayment(paymentId, userId, payeeWallet, amount, occurredAt,
                PaymentType.P2P_TRANSFER, signals("fp-known", "IN", null, null));
    }

    protected void observePayment(String paymentId, String userId, String payeeWallet,
                                  String amount, Instant occurredAt, PaymentType paymentType,
                                  RiskSignals signals) {
        PaymentCreatedPayload payload = new PaymentCreatedPayload(
                paymentId,
                "TXN-" + paymentId.substring(4, 12),
                "idem-" + paymentId,
                userId,
                "WLT-SOURCE-" + userId.substring(4, 10),
                payeeWallet,
                "USR-PAYEE",
                Money.of(amount, CURRENCY),
                paymentType,
                "Integration test payment",
                signals,
                occurredAt);

        events.publishSync(KafkaTopics.PAYMENT_CREATED,
                EventEnvelope.of(KafkaTopics.PAYMENT_CREATED, "Payment", paymentId,
                        "payment-service", payload));

        awaitUntil("observation for " + paymentId, Duration.ofSeconds(30),
                () -> countObservations(paymentId) == 1);
    }

    /** Records a completed payment in the read model, so it counts towards the account's norms. */
    protected void observeCompleted(String paymentId, String userId, String payeeWallet,
                                    String amount, Instant occurredAt) {
        observePayment(paymentId, userId, payeeWallet, amount, occurredAt);
        // Written directly rather than through payment-completed: the outcome listener is exercised
        // by its own test, and going through it here would make every history fixture depend on it.
        jdbc.update("UPDATE payment_observations SET outcome = 'COMPLETED' WHERE payment_id = ?",
                paymentId);
    }

    // ------------------------------------------------------------ HTTP helpers

    protected record Response<T>(HttpStatus status, T body) {
    }

    protected <T> Response<T> get(String uri, String token, Class<T> type) {
        return exchange(HttpMethod.GET, uri, null, token, type);
    }

    protected <T> Response<T> post(String uri, Object body, String token, Class<T> type) {
        return exchange(HttpMethod.POST, uri, body, token, type);
    }

    protected <T> Response<T> exchange(HttpMethod method, String uri, Object body,
                                       String token, Class<T> type) {
        RestTestClient.RequestBodySpec request = client.method(method)
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON);

        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        RestTestClient.RequestHeadersSpec<?> ready = body == null ? request : request.body(body);

        EntityExchangeResult<T> result = ready.exchange().returnResult(type);
        return new Response<>(HttpStatus.valueOf(result.getStatus().value()),
                result.getResponseBody());
    }

    // ---------------------------------------------------------------- tokens

    protected static String serviceToken() {
        return TestJwtIssuer.service("payment-orchestrator");
    }

    protected static String analystToken() {
        return TestJwtIssuer.user(newUserId(), "FRAUD_ANALYST");
    }

    protected static String analystToken(String userId) {
        return TestJwtIssuer.user(userId, "FRAUD_ANALYST");
    }

    protected static String adminToken() {
        return TestJwtIssuer.user(newUserId(), "ADMIN");
    }

    protected static String customerToken() {
        return TestJwtIssuer.user(newUserId(), "USER");
    }

    // --------------------------------------------------------------- fixtures

    protected static String newUserId() {
        return "USR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static String newPaymentId() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                .toUpperCase();
    }

    protected static String newWalletId() {
        return "WLT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static RiskSignals signals(String fingerprint, String country,
                                         Double latitude, Double longitude) {
        return new RiskSignals(null, fingerprint, "203.0.113.7", "curl/8.4.0",
                country, null, latitude, longitude, Channel.MOBILE_ANDROID, null, null);
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected long countObservations(String paymentId) {
        return queryLong("SELECT count(*) FROM payment_observations WHERE payment_id = ?",
                paymentId);
    }

    protected long countAssessments() {
        return queryLong("SELECT count(*) FROM fraud_assessments");
    }

    protected long countAssessments(String paymentId) {
        return queryLong("SELECT count(*) FROM fraud_assessments WHERE payment_id = ?", paymentId);
    }

    protected String assessmentDecision(String paymentId) {
        return jdbc.queryForObject(
                "SELECT decision FROM fraud_assessments WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected String assessmentRiskLevel(String paymentId) {
        return jdbc.queryForObject(
                "SELECT risk_level FROM fraud_assessments WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected int assessmentRiskScore(String paymentId) {
        Integer value = jdbc.queryForObject(
                "SELECT risk_score FROM fraud_assessments WHERE payment_id = ?", Integer.class,
                paymentId);
        return value == null ? -1 : value;
    }

    protected boolean assessmentDegraded(String paymentId) {
        Boolean value = jdbc.queryForObject(
                "SELECT degraded_mode FROM fraud_assessments WHERE payment_id = ?", Boolean.class,
                paymentId);
        return Boolean.TRUE.equals(value);
    }

    protected String assessmentModelVersion(String paymentId) {
        return jdbc.queryForObject(
                "SELECT model_version FROM fraud_assessments WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected String assessmentSnapshot(String paymentId) {
        return jdbc.queryForObject(
                "SELECT feature_snapshot::text FROM fraud_assessments WHERE payment_id = ?",
                String.class, paymentId);
    }

    protected String assessmentRuleHits(String paymentId) {
        return jdbc.queryForObject(
                "SELECT rule_hits FROM fraud_assessments WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected long countAlerts() {
        return queryLong("SELECT count(*) FROM fraud_alerts");
    }

    protected String alertIdFor(String paymentId) {
        List<String> rows = jdbc.queryForList(
                "SELECT alert_id FROM fraud_alerts WHERE payment_id = ?", String.class, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected String alertStatus(String paymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM fraud_alerts WHERE payment_id = ?", String.class, paymentId);
    }

    protected boolean alertBlocksPayment(String paymentId) {
        Boolean value = jdbc.queryForObject(
                "SELECT blocks_payment FROM fraud_alerts WHERE payment_id = ?", Boolean.class,
                paymentId);
        return Boolean.TRUE.equals(value);
    }

    protected long countDecisions(String paymentId) {
        return queryLong("SELECT count(*) FROM analyst_decisions WHERE payment_id = ?", paymentId);
    }

    protected boolean observationConfirmedFraud(String paymentId) {
        Boolean value = jdbc.queryForObject(
                "SELECT confirmed_fraud FROM payment_observations WHERE payment_id = ?",
                Boolean.class, paymentId);
        return Boolean.TRUE.equals(value);
    }

    protected long countOutboxEvents(String topic, String aggregateId) {
        return queryLong("""
                SELECT count(*) FROM outbox_events WHERE topic = ? AND aggregate_id = ?
                """, topic, aggregateId);
    }

    protected long countOutboxEvents(String topic) {
        return queryLong("SELECT count(*) FROM outbox_events WHERE topic = ?", topic);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    // ------------------------------------------------------------- waiting

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
     * <p>Ordinary waits use {@link #awaitUntil}. Proving an absence has no condition to poll for, so
     * the test allows a plausible window and then asserts nothing changed.
     */
    protected static void sleepBriefly() {
        try {
            Thread.sleep(1_500L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while proving an absence", ex);
        }
    }
}
