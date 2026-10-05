package com.aegispay.ai;

import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.InvestigationPayloads.InvestigationRequestedPayload;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.kafka.EventPublisher;
import com.aegispay.common.money.Money;
import com.aegispay.common.testsupport.TestJwkServer;
import com.aegispay.common.testsupport.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
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
 * Base class for ai-investigation-service integration tests.
 *
 * <p>Real PostgreSQL <em>with pgvector</em>, real Redis, real Kafka. No stub model provider: these tests
 * run against the offline composer, which is the shipped default and the only generator that can be
 * asserted deterministically. The HTTP generator has its own test against a local server.
 *
 * <h2>What the containers buy that a mock cannot</h2>
 *
 * <p>Most of this service's guarantees are not in Java at all:
 *
 * <ul>
 *   <li>The {@code vector(384)} column and the {@code <=>} cosine operator. Retrieval is a SQL query with
 *       an HNSW index, a subquery for the ANN ordering and an outer similarity floor — none of which any
 *       unit test can execute. A mocked {@code VectorStore} asserts what the retriever does with results;
 *       only pgvector asserts that the results are right.</li>
 *   <li>The {@code LIKE} with comma delimiters on both sides that stops {@code RULE_CARD_TESTING} matching
 *       {@code RULE_CARD_TESTING_VELOCITY} by prefix.</li>
 *   <li>The partial unique index {@code WHERE NOT on_demand}, which is what makes an automatic
 *       investigation idempotent per payment while still allowing an analyst's re-run.</li>
 *   <li>The {@code refuse_investigation_mutation()} trigger on all three tables. An append-only audit
 *       trail that is only append-only by convention is not one.</li>
 * </ul>
 *
 * <h2>Honest note on execution</h2>
 *
 * <p>These skip cleanly where Docker is unavailable, via {@code disabledWithoutDocker}. That is a real gap
 * rather than a solved problem: everything listed above is unverified in such an environment, which is
 * exactly why {@code AiPropertiesBindingTest} reads the shipped configuration and
 * {@code AiTransactionBoundaryTest} scans the source — they cover what they can of the ground these tests
 * would otherwise be the only thing standing on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "aegis.logging.json=false",
        "spring.datasource.hikari.auto-commit=true",
        // Drain the outbox quickly so a test waits milliseconds, not the production cadence.
        "aegis.outbox.poll-interval=50ms",
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        // Stays OFF, matching production. This is the authority boundary: with no credential there is
        // nothing this service could authenticate to any internal write API with. Turning it on for a
        // test would mean the tests ran against a configuration the service never ships.
        "aegis.service-auth.enabled=false",
        // The offline composer, which is the shipped default. Deterministic, so a citation assertion is
        // an assertion about retrieval rather than about a model's mood.
        "aegis.ai.generation.provider=local"
})
abstract class AbstractAiIntegrationTest {

    protected static final String CURRENCY = "INR";
    protected static final String EMBEDDER_ID = "hashing-384/v1";

    /**
     * pgvector's own image, not {@code postgres:17-alpine}.
     *
     * <p>The plain image does not ship the extension, so {@code CREATE EXTENSION vector} fails with
     * "could not open extension control file" and every migration after V2 never runs. The init script
     * creates the extension as the container's superuser, because the application role deliberately
     * cannot.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("aegis_ai_test")
                    .withUsername("aegis_test")
                    .withPassword("aegis_test_password")
                    .withInitScript("testcontainers/create-vector-extension.sql");

    /**
     * No Redis container, deliberately.
     *
     * <p>Every other service in the platform needs one, so its absence here is worth stating rather than
     * leaving as an apparent omission. This service has no idempotency cache and no rate limiter of its own:
     * request throttling belongs to the gateway, and investigation idempotency is a partial unique index in
     * PostgreSQL rather than a cached marker — which is what makes it survive a Redis restart.
     */
    @Container
    static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka-native:3.9.1");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("aegis.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                TestJwkServer::jwkSetUri);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected EventPublisher events;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    /**
     * Clears investigation state between tests, and deliberately <strong>not</strong> the corpus.
     *
     * <p>The knowledge base is seeded once at context startup, which is what production does. Truncating
     * and re-seeding it per test would be slower and would also stop these tests exercising the state that
     * actually matters — a corpus that was already there when the request arrived.
     *
     * <p>All three investigation tables refuse UPDATE and DELETE by trigger, so the triggers have to be
     * disabled for the truncate and re-enabled immediately. Leaving them off would let a later test pass
     * while the append-only guarantee was absent, which is the one thing these tables exist for.
     */
    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        setAppendOnlyTriggers(false);
        jdbc.execute("TRUNCATE TABLE investigation_citations, investigation_reviews, "
                + "investigations, outbox_events CASCADE");
        setAppendOnlyTriggers(true);
    }

    private void setAppendOnlyTriggers(boolean enabled) {
        String action = enabled ? "ENABLE" : "DISABLE";
        jdbc.execute("ALTER TABLE investigations " + action
                + " TRIGGER trg_investigations_append_only");
        jdbc.execute("ALTER TABLE investigation_citations " + action
                + " TRIGGER trg_investigation_citations_append_only");
        jdbc.execute("ALTER TABLE investigation_reviews " + action
                + " TRIGGER trg_investigation_reviews_append_only");
    }

    // ------------------------------------------------------- driving the service

    /**
     * Publishes {@code investigation-requested}, which is how fraud-service refers a payment.
     *
     * <p>Through the real broker onto the real listener, so the record is keyed, headed and encoded exactly
     * as fraud-service's outbox drainer would encode it.
     */
    protected void requestInvestigation(InvestigationRequestedPayload payload) {
        events.publishSync(KafkaTopics.INVESTIGATION_REQUESTED,
                EventEnvelope.of(KafkaTopics.INVESTIGATION_REQUESTED, "Payment",
                        payload.paymentId(), "fraud-service", payload));

        awaitUntil("investigation of " + payload.paymentId(), Duration.ofSeconds(60),
                () -> countInvestigations(payload.paymentId()) >= 1);
    }

    /** A referral for a payment that fired the bust-out rule. */
    protected InvestigationRequestedPayload referral(String paymentId) {
        return referral(paymentId, List.of("RULE_BUST_OUT_PATTERN"));
    }

    protected InvestigationRequestedPayload referral(String paymentId, List<String> ruleHits) {
        return new InvestigationRequestedPayload(
                paymentId,
                "TXN-" + paymentId.substring(4, 12),
                newUserId(),
                Money.ofMinor(500_000L, CURRENCY),
                RiskLevel.HIGH,
                0.91,
                List.of("amount far above this account's usual"),
                ruleHits,
                Map.of("amount_to_avg_ratio", 6.4, "payee_seen_before", 0),
                signals("fp-known", "IN"),
                "fraud_hgb/3",
                Instant.now());
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
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }

    protected static RiskSignals signals(String fingerprint, String country) {
        return new RiskSignals(null, fingerprint, "203.0.113.7", "curl/8.4.0",
                country, "Pune", 18.52, 73.85, Channel.MOBILE_ANDROID, null, null);
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected long countInvestigations() {
        return queryLong("SELECT count(*) FROM investigations");
    }

    protected long countInvestigations(String paymentId) {
        return queryLong("SELECT count(*) FROM investigations WHERE payment_id = ?", paymentId);
    }

    protected String investigationIdFor(String paymentId) {
        List<String> rows = jdbc.queryForList("""
                SELECT investigation_id FROM investigations WHERE payment_id = ?
                ORDER BY created_at LIMIT 1
                """, String.class, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected String recommendationFor(String paymentId) {
        return jdbc.queryForObject(
                "SELECT recommendation FROM investigations WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected String narrativeFor(String paymentId) {
        return jdbc.queryForObject(
                "SELECT narrative FROM investigations WHERE payment_id = ?", String.class, paymentId);
    }

    protected int retrievedChunksFor(String paymentId) {
        Integer value = jdbc.queryForObject(
                "SELECT retrieved_chunks FROM investigations WHERE payment_id = ?", Integer.class,
                paymentId);
        return value == null ? -1 : value;
    }

    protected int hallucinatedCitationsFor(String paymentId) {
        Integer value = jdbc.queryForObject(
                "SELECT hallucinated_citations FROM investigations WHERE payment_id = ?",
                Integer.class, paymentId);
        return value == null ? -1 : value;
    }

    protected String embedderFor(String paymentId) {
        return jdbc.queryForObject(
                "SELECT embedder FROM investigations WHERE payment_id = ?", String.class, paymentId);
    }

    protected boolean deterministicFallbackFor(String paymentId) {
        Boolean value = jdbc.queryForObject(
                "SELECT deterministic_fallback FROM investigations WHERE payment_id = ?",
                Boolean.class, paymentId);
        return Boolean.TRUE.equals(value);
    }

    protected List<String> citedDocumentsFor(String investigationId) {
        return jdbc.queryForList("""
                SELECT document_id FROM investigation_citations
                WHERE investigation_id = ? ORDER BY rank
                """, String.class, investigationId);
    }

    protected long countHallucinatedCitations(String investigationId) {
        return queryLong("""
                SELECT count(*) FROM investigation_citations
                WHERE investigation_id = ? AND hallucinated
                """, investigationId);
    }

    protected long countPolicyDocuments() {
        return queryLong("SELECT count(*) FROM policy_documents");
    }

    protected long countPolicyChunks() {
        return queryLong("SELECT count(*) FROM policy_chunks");
    }

    protected long countChunksWithVector(String embedder) {
        return queryLong("""
                SELECT count(*) FROM policy_chunks WHERE embedder = ? AND embedding IS NOT NULL
                """, embedder);
    }

    protected long countOutboxEvents(String topic, String aggregateId) {
        return queryLong("SELECT count(*) FROM outbox_events WHERE topic = ? AND aggregate_id = ?",
                topic, aggregateId);
    }

    protected String outboxPayload(String topic, String aggregateId) {
        List<String> rows = jdbc.queryForList("""
                SELECT payload FROM outbox_events WHERE topic = ? AND aggregate_id = ?
                ORDER BY id LIMIT 1
                """, String.class, topic, aggregateId);
        return rows.isEmpty() ? null : rows.getFirst();
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
     * <p>Ordinary waits use {@link #awaitUntil}. Proving an absence has no condition to poll for, so the
     * test allows a plausible window and then asserts nothing changed.
     */
    protected static void sleepBriefly() {
        try {
            Thread.sleep(2_000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while proving an absence", ex);
        }
    }
}
