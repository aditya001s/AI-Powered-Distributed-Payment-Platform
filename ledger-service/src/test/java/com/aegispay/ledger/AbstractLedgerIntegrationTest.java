package com.aegispay.ledger;

import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.kafka.EventPublisher;
import com.aegispay.common.testsupport.TestJwkServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for ledger-service integration tests.
 *
 * <p><strong>PostgreSQL is not a preference here, it is the subject.</strong> Almost everything
 * that makes this journal trustworthy is a database object rather than Java:
 *
 * <ul>
 *   <li>append-only enforcement is a pair of {@code BEFORE UPDATE OR DELETE} triggers;</li>
 *   <li>the debits-equal-credits invariant is a {@code DEFERRABLE INITIALLY DEFERRED}
 *       constraint trigger that fires at commit;</li>
 *   <li>the cached balance is tied to its own totals by a CHECK constraint;</li>
 *   <li>concurrent postings serialise on {@code SELECT ... FOR UPDATE}.</li>
 * </ul>
 *
 * <p>H2 has none of those. A green suite against it would say nothing at all about whether the
 * ledger can be tampered with or driven out of balance, which is the only thing worth testing.
 *
 * <p>Kafka runs because the outbox is only half a guarantee until something drains it, and
 * because the event-driven ingest path — the way postings normally arrive — cannot be exercised
 * without a broker.
 *
 * <p>No Redis: this service does not use it. {@code ProcessedEventRegistry} therefore degrades
 * to a no-op and suppresses no duplicates, which is deliberate. The ledger's idempotency lives
 * in a unique constraint, so running the consumer with de-duplication switched off is the
 * stronger test.
 *
 * <p>{@code disabledWithoutDocker = true} skips the whole class when no container runtime is
 * present rather than failing the build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        // Raw JdbcTemplate fixture setup must commit before HTTP and Kafka worker threads read it.
        "spring.datasource.hikari.auto-commit=true",
        // The ledger issues no outbound internal calls, so it never needs a service token.
        "aegis.service-auth.enabled=false",
        "aegis.logging.json=false",
        // Drain the outbox aggressively so a test waits milliseconds, not the production cadence.
        "aegis.outbox.poll-interval=50ms",
        // Single-broker container.
        "aegis.kafka.topic-replication-factor=1",
        "aegis.kafka.topic-partitions=1",
        // The scheduled trial-balance check is invoked directly where it is under test.
        // Pushing its automatic run beyond any test's lifetime keeps assertions deterministic.
        "aegis.ledger.trial-balance-check-initial-delay=3600000"
})
abstract class AbstractLedgerIntegrationTest {

    protected static final String INR = "INR";
    protected static final long RUPEE = 100L;

    protected static final String FUNDING_INR = "asset:external-funding:INR";
    protected static final String FEES_INR = "revenue:fees:INR";
    protected static final String CLEARING_INR = "clearing:in-flight:INR";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_ledger_test")
                    .withUsername("aegis_test")
                    .withPassword("aegis_test_password");

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

    /** Publishes events onto the topics the service consumes, exactly as a peer service would. */
    @Autowired
    protected EventPublisher eventPublisher;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    /**
     * Resets the journal between tests.
     *
     * <p>{@code TRUNCATE} rather than {@code DELETE}, because {@code DELETE} on the journal is
     * refused by the immutability trigger — which is the point of that trigger. Truncation is
     * deliberately left possible for the table owner; see the migration for why, and for what
     * the production hardening actually is.
     *
     * <p>The seeded system accounts are restored to zero rather than deleted, so every test
     * starts from the chart of accounts the migration defines.
     */
    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        jdbc.execute("TRUNCATE TABLE journal_lines, journal_entries, outbox_events CASCADE");
        // Customer wallet accounts are provisioned on demand, so they can go entirely.
        jdbc.update("DELETE FROM ledger_accounts WHERE system_account = FALSE");
        jdbc.update("""
                UPDATE ledger_accounts
                   SET debit_total_minor = 0, credit_total_minor = 0, balance_minor = 0,
                       status = 'ACTIVE'
                 WHERE system_account = TRUE
                """);
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

    /** Returns only the status when concurrent outcomes may have different body schemas. */
    protected HttpStatus postStatus(String uri, Object body, String token) {
        RestTestClient.RequestBodySpec request = client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        EntityExchangeResult<byte[]> result = request.body(body).exchange().returnResult(byte[].class);
        return HttpStatus.valueOf(result.getStatus().value());
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
        return new Response<>(HttpStatus.valueOf(result.getStatus().value()), result.getResponseBody());
    }

    // --------------------------------------------------------------- fixtures

    protected static String newWalletId() {
        return "WAL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static String newPaymentId() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    protected static String walletAccount(String walletId) {
        return "wallet:" + walletId;
    }

    /** Publishes an event onto a topic the service consumes and blocks until it is durable. */
    protected void publish(String topic, EventEnvelope<?> envelope) {
        eventPublisher.publishSync(topic, envelope);
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected long accountBalance(String accountId) {
        return queryLong("SELECT balance_minor FROM ledger_accounts WHERE account_id = ?", accountId);
    }

    protected long accountDebitTotal(String accountId) {
        return queryLong("SELECT debit_total_minor FROM ledger_accounts WHERE account_id = ?",
                accountId);
    }

    protected long accountCreditTotal(String accountId) {
        return queryLong("SELECT credit_total_minor FROM ledger_accounts WHERE account_id = ?",
                accountId);
    }

    protected boolean accountExists(String accountId) {
        return queryLong("SELECT count(*) FROM ledger_accounts WHERE account_id = ?", accountId) > 0;
    }

    protected String accountType(String accountId) {
        return jdbc.queryForObject(
                "SELECT account_type FROM ledger_accounts WHERE account_id = ?", String.class,
                accountId);
    }

    protected long countEntries() {
        return queryLong("SELECT count(*) FROM journal_entries");
    }

    protected long countEntriesWithKey(String ledgerTransactionId) {
        return queryLong("SELECT count(*) FROM journal_entries WHERE ledger_transaction_id = ?",
                ledgerTransactionId);
    }

    protected long countLines(long journalSequence) {
        return queryLong("SELECT count(*) FROM journal_lines WHERE journal_sequence = ?",
                journalSequence);
    }

    protected Long journalSequenceOf(String ledgerTransactionId) {
        List<Long> found = jdbc.queryForList(
                "SELECT journal_sequence FROM journal_entries WHERE ledger_transaction_id = ?",
                Long.class, ledgerTransactionId);
        return found.isEmpty() ? null : found.getFirst();
    }

    protected String postingTypeOf(String ledgerTransactionId) {
        return jdbc.queryForObject(
                "SELECT posting_type FROM journal_entries WHERE ledger_transaction_id = ?",
                String.class, ledgerTransactionId);
    }

    /**
     * The trial balance, read straight from SQL.
     *
     * <p>Deliberately not via the service: an assertion that used the same code path as the
     * feature under test could only ever confirm that the code agrees with itself.
     */
    protected long trialBalanceDifference(String currency) {
        return queryLong("""
                SELECT COALESCE(SUM(debit_total_minor) - SUM(credit_total_minor), 0)
                  FROM ledger_accounts WHERE currency = ?
                """, currency);
    }

    protected long lineTotalByDirection(String currency, String direction) {
        return queryLong("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM journal_lines
                 WHERE currency = ? AND direction = ?
                """, currency, direction);
    }

    protected long countOutboxEvents(String topic) {
        return queryLong("SELECT count(*) FROM outbox_events WHERE topic = ?", topic);
    }

    protected long countPublishedOutboxEvents() {
        return queryLong("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'");
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    // -------------------------------------------------------------- raw SQL
    //
    // These helpers deliberately bypass the application. Two different suites need them, for
    // opposite reasons: one to prove the database refuses what the application would never
    // attempt, the other to manufacture the corruption that reconciliation has to detect. A
    // reconciliation report that can only ever pass is worse than none, so being able to break
    // the ledger on purpose is a requirement rather than a convenience.

    /** One raw journal line. */
    protected record RawLine(int lineNumber, String accountId, String accountType,
                             String direction, long amountMinor, long balanceAfterMinor) {
    }

    protected static RawLine rawLine(int lineNumber, String accountId, String accountType,
                                     String direction, long amountMinor) {
        return new RawLine(lineNumber, accountId, accountType, direction, amountMinor, 0L);
    }

    protected static RawLine rawLine(int lineNumber, String accountId, String accountType,
                                     String direction, long amountMinor, long balanceAfterMinor) {
        return new RawLine(lineNumber, accountId, accountType, direction, amountMinor,
                balanceAfterMinor);
    }

    /**
     * Inserts an entry and its lines with plain SQL, committing once at the end.
     *
     * <p>The single commit matters: the balance constraint trigger is
     * {@code DEFERRABLE INITIALLY DEFERRED}, so it fires there and nowhere else. Committing per
     * statement would see an incomplete posting and reject a valid one.
     *
     * <p>Note that this does <strong>not</strong> update {@code ledger_accounts}. That is what
     * makes it useful for producing drift between the journal and the cached balances.
     */
    protected void insertRawPosting(String ledgerTransactionId, String postingType,
                                    long totalMinor, List<RawLine> lines) {
        ConnectionCallback<Void> work = connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO journal_entries
                            (ledger_transaction_id, posting_type, currency, total_minor,
                             narrative, source, occurred_at)
                        VALUES (?, ?, 'INR', ?, 'raw sql fixture', 'test', now())
                        """)) {
                    statement.setString(1, ledgerTransactionId);
                    statement.setString(2, postingType);
                    statement.setLong(3, totalMinor);
                    statement.executeUpdate();
                }

                long sequence;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT journal_sequence FROM journal_entries "
                                + "WHERE ledger_transaction_id = ?")) {
                    statement.setString(1, ledgerTransactionId);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        sequence = rows.getLong(1);
                    }
                }

                for (RawLine line : lines) {
                    insertRawLine(connection, sequence, line);
                }

                // The deferred constraint trigger fires HERE, not on any statement above.
                connection.commit();
                return null;
            } catch (SQLException ex) {
                rollbackQuietly(connection);
                throw ex;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        };

        jdbc.execute(work);
    }

    private static void insertRawLine(Connection connection, long sequence, RawLine line)
            throws SQLException {
        // Mirrors ck_journal_lines_signed_effect, so the line is accepted unless the test is
        // specifically attacking that constraint.
        boolean debitIncreases = List.of("ASSET", "EXPENSE", "CLEARING").contains(line.accountType());
        long signedEffect = debitIncreases == "DEBIT".equals(line.direction())
                ? line.amountMinor()
                : -line.amountMinor();

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_lines
                    (journal_sequence, line_number, account_id, account_type, direction,
                     amount_minor, currency, signed_effect_minor, balance_after_minor, memo)
                VALUES (?, ?, ?, ?, ?, ?, 'INR', ?, ?, 'raw sql fixture')
                """)) {
            statement.setLong(1, sequence);
            statement.setInt(2, line.lineNumber());
            statement.setString(3, line.accountId());
            statement.setString(4, line.accountType());
            statement.setString(5, line.direction());
            statement.setLong(6, line.amountMinor());
            statement.setLong(7, signedEffect);
            statement.setLong(8, line.balanceAfterMinor());
            statement.executeUpdate();
        }
    }

    /**
     * Rolls back, ignoring failure.
     *
     * <p>The transaction has usually already been aborted by the failed commit, so this is a
     * formality. Letting it throw would replace the constraint violation a test is about with a
     * meaningless teardown error.
     */
    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is the interesting one.
        }
    }

    // ----------------------------------------------------------- concurrency

    protected static <T> List<T> inParallel(int threads, Callable<T> task) {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<T>> tasks = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> task)
                    .toList();
            return pool.invokeAll(tasks).stream()
                    .map(AbstractLedgerIntegrationTest::resolve)
                    .toList();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test run was interrupted", ex);
        }
    }

    /** Runs two different tasks at once, for cases where the two are not interchangeable. */
    protected static <T> List<T> inParallel(Callable<T> first, Callable<T> second) {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = pool.invokeAll(List.of(first, second));
            return List.of(resolve(futures.get(0)), resolve(futures.get(1)));
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
