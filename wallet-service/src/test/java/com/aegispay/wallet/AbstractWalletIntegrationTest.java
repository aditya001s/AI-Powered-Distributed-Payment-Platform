package com.aegispay.wallet;

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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for wallet-service integration tests.
 *
 * <p>Runs the real application against real PostgreSQL, Redis and Kafka containers.
 *
 * <p><strong>Why all three, and why not in-memory substitutes.</strong> Everything this
 * service guarantees about money depends on behaviour an in-memory fake does not have:
 *
 * <ul>
 *   <li><strong>PostgreSQL</strong> — the balance invariants are {@code CHECK}
 *       constraints, concurrency safety is {@code SELECT ... FOR UPDATE}, and the outbox
 *       drainer relies on {@code FOR UPDATE SKIP LOCKED}. H2 either lacks these or
 *       implements them differently, so a green suite against H2 would say nothing about
 *       whether a double-spend is actually prevented.</li>
 *   <li><strong>Redis</strong> — the idempotency fast path is a real {@code SET NX PX}.
 *       Tests must confirm that the Redis layer and the database layer agree, and that
 *       the database still wins when they disagree.</li>
 *   <li><strong>Kafka</strong> — the outbox is only half a guarantee until something
 *       drains it. With a broker present, a test can assert that an event was both
 *       recorded in the same transaction as the balance change <em>and</em> subsequently
 *       published.</li>
 * </ul>
 *
 * <p>{@code disabledWithoutDocker = true} skips the whole class when no container runtime
 * is present rather than failing the build, so a developer without Docker still gets the
 * unit and structural suites.
 *
 * <p>Flyway runs the production migrations against the container, so the migrations
 * themselves are exercised on every run.
 *
 * <p><strong>Authentication is real.</strong> {@link TestJwkServer} publishes a JWKS and
 * {@link TestJwtIssuer} signs RS256 tokens against it, so the service validates signature,
 * issuer and expiry through its own resource-server chain. Nothing here is mocked, which is
 * why the suite can meaningfully assert that a forged token, an expired token, or a service
 * token lacking {@code scope=internal} is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        // wallet-service makes no outbound internal calls, so it never needs to obtain a
        // service token. With this off there is no ServiceTokenProvider bean at all.
        "aegis.service-auth.enabled=false",
        // Plain-text logs: JSON output makes a failing test's console unreadable.
        "aegis.logging.json=false",
        // Drain the outbox aggressively so a test does not have to wait for the
        // production 200ms cadence more than once.
        "aegis.outbox.poll-interval=50ms",
        // Single-broker container.
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        // Fixture SQL runs outside Spring-managed transactions and must commit before
        // HTTP request threads can observe it.
        "spring.datasource.hikari.auto-commit=true"
})
abstract class AbstractWalletIntegrationTest {

    protected static final String CURRENCY = "INR";

    /**
     * Shared across every subclass: containers cost seconds to start, and per-test
     * isolation comes from {@link #resetState()} instead.
     *
     * <p>{@code @ServiceConnection} wires the JDBC URL, username and password in with no
     * {@code @DynamicPropertySource} plumbing.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_wallet_test")
                    .withUsername("aegis_test")
                    .withPassword("aegis_test_password");

    /**
     * Redis for the idempotency fast path and the distributed lock.
     *
     * <p>Wired by {@link #containerProperties} rather than {@code @ServiceConnection},
     * because this service's own {@code application.yml} sets
     * {@code spring.data.redis.host} from an environment variable and a dynamic property
     * unambiguously overrides it.
     */
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    /**
     * A real broker so the outbox drainer can be observed completing its job.
     *
     * <p>{@code apache/kafka} runs in KRaft mode, so there is no ZooKeeper container to
     * start alongside it.
     */
    @Container
    static final org.testcontainers.kafka.KafkaContainer KAFKA =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka-native:3.9.1");

    /**
     * Binds the container endpoints, plus the JWKS URL of the in-JVM key server.
     *
     * <p>The service resolves its trusted signing keys over HTTP exactly as it does in
     * production; only the issuer's location differs.
     */
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
    protected org.springframework.data.redis.core.StringRedisTemplate redis;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    /**
     * Clears mutable state between tests.
     *
     * <p>{@code TRUNCATE ... CASCADE} rather than re-running Flyway: migrating per test
     * would dominate the runtime and truncation gives the same isolation. Redis is flushed
     * too, or an idempotency key cached by one test would silently satisfy the next.
     */
    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        jdbc.execute("""
                TRUNCATE TABLE wallet_transactions, wallet_holds, wallets,
                               idempotency_keys, outbox_events CASCADE
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ------------------------------------------------------------ HTTP helpers

    /** A status code plus a decoded body, so tests can assert both with AssertJ. */
    protected record Response<T>(HttpStatus status, T body) {
    }

    protected <T> Response<T> get(String uri, String token, Class<T> type) {
        return exchange(HttpMethod.GET, uri, null, token, null, type);
    }

    protected <T> Response<T> post(String uri, Object body, String token, Class<T> type) {
        return exchange(HttpMethod.POST, uri, body, token, null, type);
    }

    /** A POST carrying an {@code Idempotency-Key}, which the money-moving endpoints require. */
    protected <T> Response<T> post(String uri, Object body, String token,
                                   String idempotencyKey, Class<T> type) {
        return exchange(HttpMethod.POST, uri, body, token, idempotencyKey, type);
    }

    protected Response<String> postStatus(String uri, Object body, String token) {
        return post(uri, body, token, String.class);
    }

    protected Response<String> postStatus(String uri, Object body, String token,
                                          String idempotencyKey) {
        return post(uri, body, token, idempotencyKey, String.class);
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

    protected static String newPaymentId() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static String newKey() {
        return "idem-" + UUID.randomUUID();
    }

    /**
     * Credits a wallet directly through SQL, bypassing the API.
     *
     * <p>Used only to arrange a starting balance. Going through the deposit endpoint would
     * work, but it makes every test that needs funds also a test of deposits, so a broken
     * deposit would fail forty tests instead of one.
     */
    protected void seedBalance(String walletId, long amountMinor) {
        jdbc.update("UPDATE wallets SET balance_minor = balance_minor + ? WHERE wallet_id = ?",
                amountMinor, walletId);
        jdbc.update("""
                INSERT INTO wallet_transactions
                    (transaction_id, wallet_id, direction, amount_minor, currency,
                     balance_after_minor, type, description)
                SELECT ?, ?, 'CREDIT', ?, currency, balance_minor, 'DEPOSIT', 'Test fixture'
                FROM wallets WHERE wallet_id = ?
                """, "TXN-SEED-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                walletId, amountMinor, walletId);
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected long balanceMinor(String walletId) {
        return queryLong("SELECT balance_minor FROM wallets WHERE wallet_id = ?", walletId);
    }

    protected long heldMinor(String walletId) {
        return queryLong("SELECT held_minor FROM wallets WHERE wallet_id = ?", walletId);
    }

    protected long availableMinor(String walletId) {
        return balanceMinor(walletId) - heldMinor(walletId);
    }

    protected String walletStatus(String walletId) {
        return jdbc.queryForObject(
                "SELECT status FROM wallets WHERE wallet_id = ?", String.class, walletId);
    }

    protected long countTransactions(String walletId) {
        return queryLong("SELECT count(*) FROM wallet_transactions WHERE wallet_id = ?", walletId);
    }

    protected long countTransactionsOfType(String walletId, String type) {
        return queryLong("""
                SELECT count(*) FROM wallet_transactions
                WHERE wallet_id = ? AND type = ?
                """, walletId, type);
    }

    protected String holdState(String paymentId) {
        return jdbc.queryForObject(
                "SELECT state FROM wallet_holds WHERE payment_id = ?", String.class, paymentId);
    }

    protected long countHolds(String paymentId) {
        return queryLong("SELECT count(*) FROM wallet_holds WHERE payment_id = ?", paymentId);
    }

    protected long countIdempotencyRecords(String scope, String key) {
        return queryLong("""
                SELECT count(*) FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?
                """, scope, key);
    }

    protected String idempotencyStatus(String scope, String key) {
        return jdbc.queryForObject("""
                SELECT status FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?
                """, String.class, scope, key);
    }

    protected long countOutboxEvents(String topic, String aggregateId) {
        return queryLong("""
                SELECT count(*) FROM outbox_events WHERE topic = ? AND aggregate_id = ?
                """, topic, aggregateId);
    }

    protected long countPendingOutboxEvents() {
        return queryLong("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'");
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    // ----------------------------------------------------------- concurrency

    /**
     * Runs {@code task} on {@code threads} threads at once and returns every outcome.
     *
     * <p>Used for the double-spend and duplicate-key tests. Those are the cases where a
     * correct-looking implementation fails, so they need genuine parallelism rather than a
     * loop.
     */
    protected static <T> java.util.List<T> inParallel(int threads, Callable<T> task) {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            java.util.List<Callable<T>> tasks = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> task)
                    .toList();
            return pool.invokeAll(tasks).stream()
                    .map(AbstractWalletIntegrationTest::resolve)
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

    /**
     * Waits for an asynchronous condition, failing the test if it never holds.
     *
     * <p>Polling with a deadline rather than a fixed sleep: a sleep long enough to be
     * reliable on a loaded CI machine wastes that time on every run, and a short one is
     * flaky.
     */
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
