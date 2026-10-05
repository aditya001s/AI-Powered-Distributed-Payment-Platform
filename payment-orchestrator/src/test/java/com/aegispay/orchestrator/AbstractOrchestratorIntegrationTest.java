package com.aegispay.orchestrator;

import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.kafka.EventPublisher;
import com.aegispay.common.money.Money;
import com.aegispay.common.testsupport.TestJwkServer;
import com.aegispay.orchestrator.provider.SimulatedPaymentProvider;
import com.aegispay.orchestrator.provider.SimulationControls;
import com.aegispay.orchestrator.provider.ProviderGateway;
import com.aegispay.orchestrator.provider.ProviderRouter;
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
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for payment-orchestrator integration tests.
 *
 * <p>Real PostgreSQL, real Redis, real Kafka, real HTTP to {@link StubDownstreams}, and the real
 * simulated providers wired into the running application context. What is exercised is therefore the
 * whole orchestration path: a {@code payment-created} record arrives on a broker, a listener picks it
 * up, the saga drives it through routing, retry, fallback and compensation, and the outcome lands in a
 * committed row plus an outbox event.
 *
 * <p><strong>Why the recovery sweep is disabled by default.</strong> It would otherwise race every
 * test that deliberately leaves a saga mid-flight — picking it up and finishing it while the test was
 * still asserting on the intermediate state. The one test that cares drives {@code sweep()} directly,
 * which is also how an operator triggers it.
 *
 * <p><strong>Why the health probe interval is long.</strong> The probe writes provider health rows and
 * would otherwise interleave with the attempt history that routing scores on, making the ranking
 * assertions depend on timing. The tests that care about health call the sweep explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "aegis.logging.json=false",
        // Raw JdbcTemplate fixtures run outside Spring transactions and must be visible to the
        // Kafka listener, HTTP server, and scheduled outbox worker immediately.
        "spring.datasource.hikari.auto-commit=true",
        // Drain the outbox quickly so a test waits milliseconds, not the production cadence.
        "aegis.outbox.poll-interval=50ms",
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        // Service auth stays ON: the stub issues the token, so the real interceptor runs and an
        // unauthenticated internal call is detectable rather than invisible.
        "aegis.service-auth.enabled=true",
        "aegis.service-auth.client-id=payment-orchestrator-test",
        "aegis.service-auth.client-secret=test-internal-secret",
        // Off by default: it would race any test that leaves a saga mid-flight on purpose.
        "aegis.orchestration.recovery.enabled=false",
        "aegis.orchestration.recovery.stalled-after=1s",
        // Long enough not to interleave with the attempt history routing scores on.
        "aegis.orchestration.providers.health-check-interval=1h",
        "aegis.orchestration.providers.health-check-initial-delay=1h",
        // Short, so a SLOW provider blows the deadline without the test waiting for it.
        "aegis.orchestration.providers.call-timeout=800ms",
        "aegis.orchestration.providers.probe-timeout=800ms",
        "aegis.orchestration.providers.retry-initial-interval=10ms",
        // One attempt at the risk step, so an injected fraud-service failure surfaces immediately
        // rather than after a backoff the test would have to wait out.
        "aegis.orchestration.fraud.max-attempts=1",
        "aegis.orchestration.fraud.call-timeout=2s",
        // Short enough to assert against without waiting, long enough that a released payment does
        // not race the expiry it was extended past.
        "aegis.orchestration.fraud.review-hold-ttl=1h"
})
abstract class AbstractOrchestratorIntegrationTest {

    protected static final String CURRENCY = "INR";
    protected static final long RUPEE = 100L;

    /**
     * Started once for the whole JVM rather than per class, because the wallet and ledger base URLs
     * and the token URI are all bound into the Spring context as properties; a per-class server would
     * change the port and force a fresh context for every test class.
     */
    protected static final StubDownstreams DOWNSTREAM = new StubDownstreams();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_orchestrator_test")
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
        registry.add("aegis.clients.wallet-service-url", DOWNSTREAM::baseUrl);
        registry.add("aegis.clients.ledger-service-url", DOWNSTREAM::baseUrl);
        registry.add("aegis.clients.fraud-service-url", DOWNSTREAM::baseUrl);
        registry.add("aegis.service-auth.token-uri", DOWNSTREAM::tokenUri);
    }

    @AfterAll
    static void stopStub() {
        // The pre-reset barrier ensures no prior-test callback can reach the stub during teardown.
        DOWNSTREAM.stop();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected EventPublisher events;

    @Autowired
    protected ProviderRouter router;

    @Autowired
    protected ProviderGateway gateway;

    @Autowired
    protected java.util.concurrent.ScheduledExecutorService providerCallExecutor;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    @BeforeEach
    void resetState() {
        // A previous test can finish its assertion before the Kafka record has been consumed or the
        // outbox transaction has committed. Never clear shared fixtures underneath that work.
        awaitPreviousWorkQuiescent();

        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // provider_state is seeded by the migration and must survive: truncating it would remove the
        // rows routing consults for the kill switch. Re-enabling instead restores the seeded state.
        jdbc.execute("TRUNCATE TABLE payment_sagas, saga_steps, provider_attempts, outbox_events "
                + "CASCADE");
        jdbc.execute("UPDATE provider_state SET enabled = TRUE, disabled_reason = NULL, "
                + "disabled_by = NULL, disabled_at = NULL, last_health = 'UNKNOWN', "
                + "consecutive_failures = 0");

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        DOWNSTREAM.reset();

        // Every breaker back to closed and every fake rail back to a clean slate, so a fault armed
        // by one test cannot fail the next for no visible reason.
        for (var provider : router.allProviders()) {
            gateway.reset(provider.id());
            if (provider instanceof SimulatedPaymentProvider simulated) {
                simulated.resetSimulatedState();
            }
        }
    }

    /**
     * Waits until all work admitted by the preceding test is outside its transaction and callback.
     *
     * <p>A terminal saga row alone is too early: RECORD-mode Kafka acknowledgment happens after the
     * listener returns, provider timeouts can leave a task unwinding, and the outbox publisher
     * commits independently. These three boundaries cover those execution paths without stopping
     * and restarting the Spring context between every test.
     */
    private void awaitPreviousWorkQuiescent() {
        java.util.Map<org.apache.kafka.common.TopicPartition, Long> targetOffsets;
        try (org.apache.kafka.clients.admin.Admin admin = org.apache.kafka.clients.admin.Admin.create(
                java.util.Map.of(
                        org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers()))) {
            java.util.Set<String> inputTopics = java.util.Set.of(
                    KafkaTopics.PAYMENT_CREATED, KafkaTopics.FRAUD_DECISION_APPLIED);
            java.util.Map<String, org.apache.kafka.clients.admin.TopicDescription> descriptions =
                    admin.describeTopics(inputTopics).allTopicNames()
                            .get(30, java.util.concurrent.TimeUnit.SECONDS);

            java.util.Map<org.apache.kafka.common.TopicPartition,
                    org.apache.kafka.clients.admin.OffsetSpec> requests = new java.util.HashMap<>();
            descriptions.forEach((topic, description) -> description.partitions().forEach(partition ->
                    requests.put(new org.apache.kafka.common.TopicPartition(
                                    topic, partition.partition()),
                            org.apache.kafka.clients.admin.OffsetSpec.latest())));

            targetOffsets = new java.util.HashMap<>();
            admin.listOffsets(requests).all()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS)
                    .forEach((partition, info) -> targetOffsets.put(partition, info.offset()));

            awaitUntil("orchestrator Kafka consumers to finish records " + targetOffsets,
                    Duration.ofSeconds(45),
                    () -> consumerHasReached(admin, targetOffsets));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking Kafka quiescence", ex);
        } catch (java.util.concurrent.ExecutionException
                 | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Could not check Kafka quiescence", ex);
        }

        if (!(providerCallExecutor
                instanceof java.util.concurrent.ScheduledThreadPoolExecutor executor)) {
            throw new IllegalStateException("Unexpected provider executor type: "
                    + providerCallExecutor.getClass().getName());
        }
        // Once the input consumer has committed through the snapshotted offsets, no new provider
        // call can be submitted for that test. Active tasks are the only work still capable of
        // mutating provider state. ScheduledThreadPoolExecutor may retain completed/cancelled queue
        // nodes temporarily, so queue emptiness is not a valid idleness signal.
        awaitUntil("provider calls to finish", Duration.ofSeconds(45),
                () -> executor.getActiveCount() == 0);

        // An uncommitted outbox drain still appears PENDING to this separate connection, so zero
        // proves the drainer has committed and released its row locks before TRUNCATE begins.
        awaitUntil("outbox transactions to finish", Duration.ofSeconds(45),
                () -> queryLong("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'") == 0L);
    }

    private static boolean consumerHasReached(
            org.apache.kafka.clients.admin.Admin admin,
            java.util.Map<org.apache.kafka.common.TopicPartition, Long> targetOffsets) {
        try {
            java.util.Map<org.apache.kafka.common.TopicPartition,
                    org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets("payment-orchestrator")
                            .partitionsToOffsetAndMetadata()
                            .get(10, java.util.concurrent.TimeUnit.SECONDS);
            return targetOffsets.entrySet().stream().allMatch(entry -> {
                org.apache.kafka.clients.consumer.OffsetAndMetadata offset =
                        committed.get(entry.getKey());
                return offset == null ? entry.getValue() == 0L
                        : offset.offset() >= entry.getValue();
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading committed Kafka offsets", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException
                    && targetOffsets.values().stream().allMatch(offset -> offset == 0L)) {
                return true;
            }
            throw new IllegalStateException("Could not read committed Kafka offsets", ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("Timed out reading committed Kafka offsets", ex);
        }
    }

    // ----------------------------------------------------- driving the saga

    /**
     * Publishes {@code payment-created}, which is the only way into orchestration.
     *
     * <p>Through the real {@link EventPublisher} onto the real broker, so the record is keyed, headed
     * and encoded exactly as payment-service's outbox drainer would encode it and travels through the
     * same listener container, de-duplication registry and error handler.
     */
    protected String submitPayment(String payer, String sourceWalletId, String destinationWalletId,
                                   String amount) {
        return submitPayment(payer, sourceWalletId, destinationWalletId, amount,
                PaymentType.P2P_TRANSFER, CURRENCY);
    }

    protected String submitPayment(String payer, String sourceWalletId, String destinationWalletId,
                                   String amount, PaymentType paymentType, String currency) {
        String paymentId = newPaymentId();

        PaymentCreatedPayload payload = new PaymentCreatedPayload(
                paymentId,
                "TXN" + paymentId.substring(4, 16),
                "idem-" + paymentId,
                payer,
                sourceWalletId,
                destinationWalletId,
                "USR-PAYEE-" + paymentId.substring(4, 10),
                Money.of(amount, currency),
                paymentType,
                "Integration test payment",
                new RiskSignals(null, null, "203.0.113.7", "curl/8.4.0",
                        null, null, null, null, Channel.API, null, null),
                Instant.now());

        events.publishSync(KafkaTopics.PAYMENT_CREATED,
                EventEnvelope.of(KafkaTopics.PAYMENT_CREATED, "Payment", paymentId,
                        "payment-service", payload));
        return paymentId;
    }

    /**
     * Publishes an analyst's decision on a held payment.
     *
     * <p>Through the real broker, because that is the only path that unparks a saga. fraud-service
     * records the decision and this event in one commit through its outbox, so a lost call cannot
     * leave an alert resolved with the payment parked forever.
     */
    protected void applyAnalystDecision(String paymentId, String action, String analyst,
                                        String reason) {
        var payload = new com.aegispay.common.events.payload.AnalystDecisionPayload(
                paymentId, "TXN-" + paymentId.substring(4, 12), "ALR-TEST-" + paymentId.substring(4, 12),
                "FRA-TEST-" + paymentId.substring(4, 12), action, reason, analyst, Instant.now());

        events.publishSync(KafkaTopics.FRAUD_DECISION_APPLIED,
                EventEnvelope.of(KafkaTopics.FRAUD_DECISION_APPLIED, "Payment", paymentId,
                        "fraud-service", payload));
    }

    // ---- risk-hold state, read straight from the committed row ----

    protected Instant sagaRiskHeldAt(String paymentId) {
        return jdbc.queryForObject(
                "SELECT risk_held_at FROM payment_sagas WHERE payment_id = ?",
                Instant.class, paymentId);
    }

    protected String sagaRiskReviewedBy(String paymentId) {
        return jdbc.queryForObject(
                "SELECT risk_reviewed_by FROM payment_sagas WHERE payment_id = ?",
                String.class, paymentId);
    }

    protected String sagaRiskAssessmentId(String paymentId) {
        return jdbc.queryForObject(
                "SELECT risk_assessment_id FROM payment_sagas WHERE payment_id = ?",
                String.class, paymentId);
    }

    protected boolean sagaRiskDegraded(String paymentId) {
        Boolean value = jdbc.queryForObject(
                "SELECT risk_degraded FROM payment_sagas WHERE payment_id = ?",
                Boolean.class, paymentId);
        return Boolean.TRUE.equals(value);
    }

    protected String sagaFraudDecision(String paymentId) {
        return jdbc.queryForObject(
                "SELECT fraud_decision FROM payment_sagas WHERE payment_id = ?",
                String.class, paymentId);
    }

    protected String sagaRiskSignals(String paymentId) {
        return jdbc.queryForObject(
                "SELECT risk_signals::text FROM payment_sagas WHERE payment_id = ?",
                String.class, paymentId);
    }

    /** Waits for the saga to reach a terminal state and returns which one. */
    protected String awaitTerminalSaga(String paymentId) {
        awaitUntil("saga for payment " + paymentId + " to settle", Duration.ofSeconds(45),
                () -> {
                    String status = sagaStatusOrNull(paymentId);
                    return status != null
                            && List.of("COMPLETED", "FAILED", "REJECTED").contains(status);
                });
        return sagaStatus(paymentId);
    }

    protected void awaitSagaStatus(String paymentId, String status) {
        awaitUntil("saga for payment " + paymentId + " to reach " + status, Duration.ofSeconds(45),
                () -> status.equals(sagaStatusOrNull(paymentId)));
    }

    // ------------------------------------------------------ arming the rails

    /** Arms a fault on a simulated provider, the same way the internal API does. */
    protected void arm(String providerId, SimulationControls.FaultMode mode, int calls) {
        simulated(providerId).controls().injectFault(mode, calls);
    }

    protected SimulatedPaymentProvider simulated(String providerId) {
        return router.byId(providerId)
                .filter(SimulatedPaymentProvider.class::isInstance)
                .map(SimulatedPaymentProvider.class::cast)
                .orElseThrow(() -> new IllegalArgumentException(
                        providerId + " is not a registered simulated provider"));
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

    // --------------------------------------------------------------- fixtures

    protected static String newUserId() {
        return "USR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    /** Same shape as payment-service's generator, so nothing depends on the difference. */
    protected static String newPaymentId() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                .toUpperCase();
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected String sagaStatus(String paymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM payment_sagas WHERE payment_id = ?", String.class, paymentId);
    }

    protected String sagaStatusOrNull(String paymentId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM payment_sagas WHERE payment_id = ?", String.class, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected String sagaProvider(String paymentId) {
        return jdbc.queryForObject(
                "SELECT provider_id FROM payment_sagas WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected String sagaProviderAttempts(String paymentId) {
        return jdbc.queryForObject(
                "SELECT provider_attempts FROM payment_sagas WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected String sagaFailureCode(String paymentId) {
        return jdbc.queryForObject(
                "SELECT failure_code FROM payment_sagas WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected boolean sagaCompensationComplete(String paymentId) {
        Boolean value = jdbc.queryForObject(
                "SELECT compensation_complete FROM payment_sagas WHERE payment_id = ?",
                Boolean.class, paymentId);
        return Boolean.TRUE.equals(value);
    }

    protected String sagaHoldId(String paymentId) {
        return jdbc.queryForObject(
                "SELECT hold_id FROM payment_sagas WHERE payment_id = ?", String.class, paymentId);
    }

    protected String sagaAuthorizationId(String paymentId) {
        return jdbc.queryForObject(
                "SELECT authorization_id FROM payment_sagas WHERE payment_id = ?", String.class,
                paymentId);
    }

    protected String sagaLedgerTransactionId(String paymentId) {
        return jdbc.queryForObject(
                "SELECT ledger_transaction_id FROM payment_sagas WHERE payment_id = ?",
                String.class, paymentId);
    }

    protected long sagaFeeMinor(String paymentId) {
        return queryLong("SELECT fee_minor FROM payment_sagas WHERE payment_id = ?", paymentId);
    }

    protected long countSagas() {
        return queryLong("SELECT count(*) FROM payment_sagas");
    }

    /**
     * How many times the saga has been picked up.
     *
     * <p>Watched by the parked-payment test: this rising on a held payment is the symptom of the
     * recovery sweep treating a deliberate wait as an abandonment.
     */
    protected long sagaAttemptCount(String paymentId) {
        return queryLong("SELECT attempt_count FROM payment_sagas WHERE payment_id = ?", paymentId);
    }

    protected long countSteps(String paymentId, String stepName) {
        return queryLong("""
                SELECT count(*) FROM saga_steps WHERE payment_id = ? AND step_name = ?
                """, paymentId, stepName);
    }

    protected long countProviderAttempts(String paymentId, String outcome) {
        return queryLong("""
                SELECT count(*) FROM provider_attempts WHERE payment_id = ? AND outcome = ?
                """, paymentId, outcome);
    }

    protected long countOutboxEvents(String topic, String aggregateId) {
        return queryLong("""
                SELECT count(*) FROM outbox_events WHERE topic = ? AND aggregate_id = ?
                """, topic, aggregateId);
    }

    protected long countOutboxEvents(String topic) {
        return queryLong("SELECT count(*) FROM outbox_events WHERE topic = ?", topic);
    }

    protected long countPublishedOutboxEvents() {
        return queryLong("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'");
    }

    protected long countOutboxEventsTotal() {
        return queryLong("SELECT count(*) FROM outbox_events");
    }

    protected boolean providerEnabled(String providerId) {
        Boolean value = jdbc.queryForObject(
                "SELECT enabled FROM provider_state WHERE provider_id = ?", Boolean.class,
                providerId);
        return Boolean.TRUE.equals(value);
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
     * <p>Ordinary waits use {@link #awaitUntil}, which polls for a condition. Proving an absence has
     * no condition to poll for, so the test allows a plausible window and then asserts nothing
     * changed.
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
