package com.aegispay.payment;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentOutcomePayloads;
import com.aegispay.common.money.Money;
import com.aegispay.common.testsupport.TestJwkServer;
import com.aegispay.payment.api.PaymentDtos.PaymentResponse;
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
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for payment-service integration tests.
 *
 * <p>Real PostgreSQL, real Redis, real Kafka, real HTTP to a {@link StubWalletService} that
 * implements genuine balance and hold semantics. The only thing not real is the wallet
 * service's own persistence, which is not this service's responsibility — see
 * {@link StubWalletService} for why substituting it is the right boundary.
 *
 * <p>Kafka is present because the outbox is only half a guarantee until something drains it.
 * With a broker running, a test can assert both halves: that the state change and its event
 * were committed together, and that the event subsequently reached the topic.
 *
 * <p><strong>Service-to-service auth is exercised, not disabled.</strong> The stub serves the
 * client-credentials token endpoint, so the service obtains and attaches a bearer token
 * through its normal interceptor. {@link StubWalletService#requestsWithoutAuth()} then makes
 * a silently unauthenticated internal call a detectable failure rather than an invisible one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "aegis.logging.json=false",
        // Fixture SQL runs outside Spring transactions and must be visible to HTTP/Kafka threads.
        "spring.datasource.hikari.auto-commit=true",
        // Drain the outbox quickly so a test waits milliseconds, not the production cadence.
        "aegis.outbox.poll-interval=50ms",
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        // Service auth stays ON: the stub issues the token, so the real interceptor runs.
        "aegis.service-auth.enabled=true",
        "aegis.service-auth.client-id=payment-service-test",
        "aegis.service-auth.client-secret=test-internal-secret"
})
abstract class AbstractPaymentIntegrationTest {

    protected static final String CURRENCY = "INR";
    protected static final long RUPEE = 100L;

    /**
     * Started once for the whole JVM rather than per class, because both the wallet base URL
     * and the token URI are bound into the Spring context as properties and a per-class
     * server would change the port and force a fresh context.
     */
    protected static final StubWalletService WALLET = new StubWalletService();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_payment_test")
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
        registry.add("aegis.clients.wallet-service-url", WALLET::baseUrl);
        registry.add("aegis.service-auth.token-uri", WALLET::tokenUri);
    }

    @AfterAll
    static void stopStub() {
        // Only meaningful for the last class to run; stopping an already-stopped server is
        // harmless, and leaving a listener behind would leak a port across the build.
        WALLET.stop();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    /**
     * Used to stand in for payment-orchestrator.
     *
     * <p>Since Phase 5, payment-service learns what happened to a payment only from the saga's
     * outcome events. The orchestrator is a different service with its own database, so an
     * integration test of <em>this</em> service cannot and should not run it — what it must prove is
     * that the events are consumed correctly when they arrive.
     *
     * <p>Publishing through the real {@link EventPublisher} onto the real broker is the faithful way
     * to do that: the record is keyed, headed and encoded exactly as the orchestrator's outbox
     * drainer would encode it, and it travels through the same listener container, de-duplication
     * registry and error handler. Calling the listener method directly would skip all of that, which
     * is where the interesting failures live.
     */
    @Autowired
    protected com.aegispay.common.kafka.EventPublisher events;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        jdbc.execute("TRUNCATE TABLE payment_refunds, payments, idempotency_keys, outbox_events CASCADE");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        WALLET.reset();
    }

    // ------------------------------------------------------------ HTTP helpers

    protected record Response<T>(HttpStatus status, T body) {
    }

    protected <T> Response<T> get(String uri, String token, Class<T> type) {
        return exchange(HttpMethod.GET, uri, null, token, null, type);
    }

    protected <T> Response<T> post(String uri, Object body, String token,
                                   String idempotencyKey, Class<T> type) {
        return exchange(HttpMethod.POST, uri, body, token, idempotencyKey, type);
    }

    protected <T> Response<T> exchange(HttpMethod method, String uri, Object body,
                                       String token, String idempotencyKey, Class<T> type) {
        RestTestClient.RequestBodySpec request = client.method(method)
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON);

        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }

        RestTestClient.RequestHeadersSpec<?> ready = body == null ? request : request.body(body);

        EntityExchangeResult<T> result = ready.exchange().returnResult(type);
        return new Response<>(HttpStatus.valueOf(result.getStatus().value()), result.getResponseBody());
    }

    // --------------------------------------------------------------- fixtures

    protected static String newUserId() {
        return "USR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static String newKey() {
        return "idem-" + UUID.randomUUID();
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected String paymentStatus(String paymentId) {
        return jdbc.queryForObject(
                "SELECT status FROM payments WHERE payment_id = ?", String.class, paymentId);
    }

    protected String failureCode(String paymentId) {
        return jdbc.queryForObject(
                "SELECT failure_code FROM payments WHERE payment_id = ?", String.class, paymentId);
    }

    protected long refundedMinor(String paymentId) {
        return queryLong("SELECT refunded_minor FROM payments WHERE payment_id = ?", paymentId);
    }

    protected long countPayments() {
        return queryLong("SELECT count(*) FROM payments");
    }

    protected long countRefundRecords(String paymentId) {
        return queryLong("SELECT count(*) FROM payment_refunds WHERE payment_id = ?", paymentId);
    }

    protected long countPaymentsWithStatus(String status) {
        return queryLong("SELECT count(*) FROM payments WHERE status = ?", status);
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

    protected String idempotencyStatus(String scope, String key) {
        return jdbc.queryForObject("""
                SELECT status FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?
                """, String.class, scope, key);
    }

    protected String storedIpAddress(String paymentId) {
        return jdbc.queryForObject(
                "SELECT ip_address FROM payments WHERE payment_id = ?", String.class, paymentId);
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    // ------------------------------------------------- standing in for the saga

    private static final String ORCHESTRATOR = "payment-orchestrator";
    private static final String AGGREGATE = "Payment";

    /**
     * Publishes {@code payment-approved}, as the orchestrator does once a provider authorises.
     */
    protected void sagaApproved(PaymentResponse payment, String payer) {
        publish(KafkaTopics.PAYMENT_APPROVED, payment.paymentId(),
                new PaymentOutcomePayloads.PaymentApprovedPayload(
                        payment.paymentId(),
                        payment.transactionId(),
                        payer,
                        Money.ofMinor(payment.amount().amountMinor(), payment.amount().currency()),
                        RiskLevel.LOW,
                        0.02d,
                        Instant.now()));
    }

    /**
     * Publishes {@code payment-completed}, as the orchestrator does after the ledger posting and
     * the capture.
     *
     * <p>Carries a provider and a fee, because those are facts only the orchestrator has and this
     * service is expected to store them: a refund and any later dispute both need to know which rail
     * settled the payment and what it cost the platform.
     */
    protected void sagaCompleted(PaymentResponse payment, String payer, String providerId,
                                 long feeMinor) {
        publish(KafkaTopics.PAYMENT_COMPLETED, payment.paymentId(),
                new PaymentOutcomePayloads.PaymentCompletedPayload(
                        payment.paymentId(),
                        payment.transactionId(),
                        payer,
                        payment.destinationUserId(),
                        payment.sourceWalletId(),
                        payment.destinationWalletId(),
                        Money.ofMinor(payment.amount().amountMinor(), payment.amount().currency()),
                        Money.ofMinor(feeMinor, payment.amount().currency()),
                        providerId,
                        providerId + "-REF-1",
                        List.of(providerId),
                        "LTX-PAY-" + payment.paymentId(),
                        842L,
                        Instant.now()));
    }

    protected void sagaFailed(PaymentResponse payment, String payer, String failureCode,
                              String failedStep, boolean compensationComplete) {
        publish(KafkaTopics.PAYMENT_FAILED, payment.paymentId(),
                new PaymentOutcomePayloads.PaymentFailedPayload(
                        payment.paymentId(),
                        payment.transactionId(),
                        payer,
                        Money.ofMinor(payment.amount().amountMinor(), payment.amount().currency()),
                        failureCode,
                        "simulated saga failure",
                        failedStep,
                        List.of("SWIFTPAY", "NEXUSPAY"),
                        1,
                        compensationComplete,
                        Instant.now()));
    }

    protected void sagaRejected(PaymentResponse payment, String payer, String reasonCode) {
        publish(KafkaTopics.PAYMENT_REJECTED, payment.paymentId(),
                new PaymentOutcomePayloads.PaymentRejectedPayload(
                        payment.paymentId(),
                        payment.transactionId(),
                        payer,
                        Money.ofMinor(payment.amount().amountMinor(), payment.amount().currency()),
                        RiskLevel.LOW,
                        FraudDecision.APPROVE,
                        reasonCode,
                        "every provider declined the charge",
                        List.of(reasonCode),
                        true,
                        false,
                        Instant.now()));
    }

    private void publish(String topic, String paymentId, Object payload) {
        events.publishSync(topic,
                EventEnvelope.of(topic, AGGREGATE, paymentId, ORCHESTRATOR, payload));
    }

    /**
     * Waits for the listener to apply an outcome.
     *
     * <p>Asserts on the committed row rather than on a response, because the whole point of the
     * asynchronous contract is that the HTTP call has already returned by now.
     */
    protected void awaitPaymentStatus(String paymentId, String status) {
        awaitUntil("payment " + paymentId + " to reach " + status, Duration.ofSeconds(30),
                () -> status.equals(paymentStatus(paymentId)));
    }

    /**
     * Drives a payment all the way to {@code COMPLETED}, the way production does.
     *
     * <p>Used by the refund tests, which need a genuinely completed payment. Note what this does
     * <em>not</em> do: move any money. The saga would have captured the funds inside wallet-service,
     * so a test that also cares about balances arranges them on the stub directly. Pretending the
     * event moves money would make the fixture lie about which service owns balances.
     */
    protected PaymentResponse completedPayment(PaymentResponse payment, String payer,
                                               String providerId) {
        sagaCompleted(payment, payer, providerId, 0L);
        awaitPaymentStatus(payment.paymentId(), "COMPLETED");
        return payment;
    }

    // ----------------------------------------------------------- concurrency

    protected static <T> List<T> inParallel(int threads, Callable<T> task) {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<T>> tasks = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> task)
                    .toList();
            return pool.invokeAll(tasks).stream()
                    .map(AbstractPaymentIntegrationTest::resolve)
                    .toList();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test run was interrupted", ex);
        }
    }

    private static <T> T resolve(Future<T> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new IllegalStateException("A concurrent request failed unexpectedly", ex);
        }
    }

    /** Polls with a deadline: a fixed sleep is either flaky or wasteful. */
    protected static void awaitUntil(String description, Duration timeout, BooleanSupplier condition) {
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
}
