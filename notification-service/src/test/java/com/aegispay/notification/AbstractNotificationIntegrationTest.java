package com.aegispay.notification;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.common.domain.SharedEnums.NotificationPriority;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.NotificationPayload;
import com.aegispay.common.kafka.EventPublisher;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for notification-service integration tests.
 *
 * <p>Real PostgreSQL, real Redis, real Kafka, and real HTTP to {@link StubAuthService}. What is exercised is
 * therefore the whole path: a {@code notification-required} record arrives on a broker, the address is resolved
 * over a socket, copy is rendered, rows are committed, and the transports are called.
 *
 * <h2>What the containers buy that a mock cannot</h2>
 *
 * <p>Most of this service's guarantees are not in Java at all:
 *
 * <ul>
 *   <li><strong>{@code uq_notification_dedup}.</strong> The de-duplication check in application code is only an
 *       optimisation — two consumer threads on two instances both pass it. Only the unique constraint
 *       arbitrates, and only a real database has one.</li>
 *   <li><strong>{@code uq_delivery_per_channel}.</strong> Stops a retry racing a redelivered event into two
 *       EMAIL rows for one notification, which would send the message twice.</li>
 *   <li><strong>{@code FOR UPDATE SKIP LOCKED}.</strong> The claim query is native SQL. It is what makes the
 *       retry worker safe on every instance, and no unit test executes it.</li>
 *   <li><strong>The CHECK constraints.</strong> {@code read_at} only for IN_APP, a suppressed row always having
 *       a reason, a scheduled row never being terminal. Each encodes a rule the Java aggregate also enforces —
 *       and the database is what enforces it against a direct write.</li>
 *   <li><strong>The append-only triggers.</strong> An audit trail that is append-only only by convention is
 *       not one.</li>
 * </ul>
 *
 * <h2>Honest note on execution</h2>
 *
 * <p>These skip cleanly where Docker is unavailable, via {@code disabledWithoutDocker}. That is a real gap
 * rather than a solved problem: everything listed above is unverified in such an environment, which is why
 * {@code NotificationPropertiesBindingTest} reads the shipped configuration and
 * {@code NotificationTransactionBoundaryTest} inspects the annotations — they cover what they can of the ground
 * these tests would otherwise be the only thing standing on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "aegis.logging.json=false",
        // Fixture DDL and cleanup use JdbcTemplate outside managed transactions.
        "spring.datasource.hikari.auto-commit=true",
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        // Service auth stays ON, matching production: this service genuinely makes an authenticated
        // internal call to resolve a delivery address, so the real interceptor should run.
        "aegis.service-auth.enabled=true",
        "aegis.service-auth.client-id=notification-service-test",
        "aegis.service-auth.client-secret=test-internal-secret",
        // Flow tests keep the retry scheduler out of the immediate-delivery path. The retry suite
        // overrides this to 300ms and exercises the real scheduled worker.
        "aegis.notification.retry.initial-delay=1h",
        // Short backoff, for the same reason. The schedule arithmetic is asserted by DeliveryBackoffTest;
        // what these tests need is for a retry to actually come due.
        "aegis.notification.retry.initial-backoff=500ms",
        "aegis.notification.retry.max-backoff=1s",
        "aegis.notification.retry.jitter=0.0"
})
abstract class AbstractNotificationIntegrationTest {

    /**
     * Started once for the whole JVM rather than per class, because its base URL is bound into the Spring
     * context as a property; a per-class server would change the port and force a fresh context for every
     * test class.
     */
    protected static final StubAuthService AUTH = new StubAuthService();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_notification_test")
                    .withUsername("aegis_test")
                    .withPassword("aegis_test_password");

    /**
     * Needed, unlike ai-investigation-service.
     *
     * <p>{@code ProcessedEventRegistry} is Redis-backed, and readiness includes it. Without Redis the registry
     * degrades to a no-op that suppresses nothing — which would still pass most of these tests, because the
     * durable guarantee is the unique constraint rather than the cache. That is exactly why it is here: the
     * tests should run against the arrangement production uses, not the one that happens to work.
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
        registry.add("aegis.clients.auth-service-url", AUTH::baseUrl);
        registry.add("aegis.service-auth.token-uri", AUTH::tokenUri);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected EventPublisher events;

    @Autowired
    protected com.aegispay.notification.service.RecipientResolver recipients;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    /**
     * Clears notification state between tests.
     *
     * <p>PostgreSQL row-level append-only triggers do not run for {@code TRUNCATE}; toggling them
     * with {@code ALTER TABLE} only adds access-exclusive locks that can deadlock an event finishing
     * on another thread. Flow tests wait for terminal delivery state before returning, then this one
     * statement clears all durable state.
     *
     * <p>Redis is flushed too, so a de-duplication marker from a previous test cannot suppress this test's
     * event.
     */
    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        jdbc.execute("TRUNCATE TABLE notification_delivery_attempts, notification_deliveries, "
                + "notifications, notification_preferences CASCADE");

        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        AUTH.reset();
        recipients.invalidate(null);
    }

    // ------------------------------------------------------- driving the service

    /**
     * Publishes {@code notification-required}, which is how payment-service asks for a notification.
     *
     * <p>Through the real broker onto the real listener, so the record is keyed, headed and encoded exactly as
     * payment-service's outbox drainer would encode it.
     */
    protected void requestNotification(NotificationPayload payload) {
        publish(payload);
        awaitUntil("notification for " + payload.deduplicationKey(), Duration.ofSeconds(60),
                () -> countNotifications(payload.deduplicationKey()) >= 1);
    }

    /** Publishes without waiting, for tests that assert an absence. */
    protected void publish(NotificationPayload payload) {
        events.publishSync(KafkaTopics.NOTIFICATION_REQUIRED,
                EventEnvelope.of(KafkaTopics.NOTIFICATION_REQUIRED, "Payment",
                        payload.paymentId(), "payment-service", payload));
    }

    /** A routine payment notification, exactly as payment-service builds one. */
    protected NotificationPayload transactional(String userId, String paymentId, String kind) {
        return new NotificationPayload(userId, null, null,
                List.of(NotificationChannel.EMAIL, NotificationChannel.IN_APP),
                "payment.sent",
                Map.of("transactionId", "TXN-" + paymentId.substring(4, 12),
                        "amount", "2000.00",
                        "currency", "INR",
                        "description", "Rent"),
                NotificationPriority.NORMAL,
                paymentId + ":" + kind + ":" + userId,
                paymentId);
    }

    protected NotificationPayload withChannels(String userId, String paymentId,
                                               NotificationChannel... channels) {
        NotificationPayload base = transactional(userId, paymentId, "sent");
        return new NotificationPayload(base.recipientUserId(), null, null, List.of(channels),
                base.templateKey(), base.variables(), base.priority(), base.deduplicationKey(),
                base.paymentId());
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

    protected <T> Response<T> put(String uri, Object body, String token, Class<T> type) {
        return exchange(HttpMethod.PUT, uri, body, token, type);
    }

    protected <T> Response<T> delete(String uri, String token, Class<T> type) {
        return exchange(HttpMethod.DELETE, uri, null, token, type);
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

    protected static String customerToken(String userId) {
        return TestJwtIssuer.user(userId, "USER");
    }

    protected static String adminToken() {
        return TestJwtIssuer.user(newUserId(), "ADMIN");
    }

    protected static String analystToken() {
        return TestJwtIssuer.user(newUserId(), "FRAUD_ANALYST");
    }

    // --------------------------------------------------------------- fixtures

    protected static String newUserId() {
        return "USR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static String newPaymentId() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected long countNotifications() {
        return queryLong("SELECT count(*) FROM notifications");
    }

    protected long countNotifications(String deduplicationKey) {
        return queryLong("SELECT count(*) FROM notifications WHERE deduplication_key = ?",
                deduplicationKey);
    }

    protected String notificationIdFor(String deduplicationKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT notification_id FROM notifications WHERE deduplication_key = ?",
                String.class, deduplicationKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected String variablesFor(String deduplicationKey) {
        return jdbc.queryForObject(
                "SELECT variables::text FROM notifications WHERE deduplication_key = ?",
                String.class, deduplicationKey);
    }

    protected long countDeliveries(String notificationId) {
        return queryLong("SELECT count(*) FROM notification_deliveries WHERE notification_id = ?",
                notificationId);
    }

    protected long countRetryableDeliveries(String deduplicationKey) {
        return queryLong("""
                SELECT count(*)
                FROM notification_deliveries d
                JOIN notifications n ON n.notification_id = d.notification_id
                WHERE n.deduplication_key = ? AND d.status IN ('PENDING', 'FAILED')
                """, deduplicationKey);
    }

    protected String deliveryStatus(String notificationId, NotificationChannel channel) {
        List<String> rows = jdbc.queryForList("""
                SELECT status FROM notification_deliveries
                WHERE notification_id = ? AND channel = ?
                """, String.class, notificationId, channel.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected String deliveryIdFor(String notificationId, NotificationChannel channel) {
        List<String> rows = jdbc.queryForList("""
                SELECT delivery_id FROM notification_deliveries
                WHERE notification_id = ? AND channel = ?
                """, String.class, notificationId, channel.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected String suppressedReason(String notificationId, NotificationChannel channel) {
        List<String> rows = jdbc.queryForList("""
                SELECT suppressed_reason FROM notification_deliveries
                WHERE notification_id = ? AND channel = ?
                """, String.class, notificationId, channel.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected String renderedBody(String notificationId, NotificationChannel channel) {
        List<String> rows = jdbc.queryForList("""
                SELECT rendered_body FROM notification_deliveries
                WHERE notification_id = ? AND channel = ?
                """, String.class, notificationId, channel.name());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected int attemptCount(String deliveryId) {
        Integer value = jdbc.queryForObject(
                "SELECT attempt_count FROM notification_deliveries WHERE delivery_id = ?",
                Integer.class, deliveryId);
        return value == null ? -1 : value;
    }

    protected String statusOf(String deliveryId) {
        return jdbc.queryForObject(
                "SELECT status FROM notification_deliveries WHERE delivery_id = ?",
                String.class, deliveryId);
    }

    protected long countAttempts(String deliveryId) {
        return queryLong(
                "SELECT count(*) FROM notification_delivery_attempts WHERE delivery_id = ?",
                deliveryId);
    }

    protected long countDeliveriesByStatus(String status) {
        return queryLong("SELECT count(*) FROM notification_deliveries WHERE status = ?", status);
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
     * <p>Ordinary waits use {@link #awaitUntil}. Proving an absence has no condition to poll for, so the test
     * allows a plausible window and then asserts nothing changed.
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
