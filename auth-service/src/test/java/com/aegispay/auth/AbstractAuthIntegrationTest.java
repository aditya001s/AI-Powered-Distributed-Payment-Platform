package com.aegispay.auth;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base class for auth-service integration tests.
 *
 * <p>Runs the real application against a real PostgreSQL container. That matters for
 * this particular service: the three defects these tests exist to cover were all
 * <em>transaction rollback</em> bugs, and a rollback is invisible to a test with mocked
 * repositories — the mock faithfully records a call whose effect the database then
 * discards. Only a genuine commit boundary can tell the difference.
 *
 * <p>PostgreSQL specifically, not H2 or another in-memory substitute. The schema uses
 * {@code TIMESTAMPTZ}, partial indexes with {@code WHERE} clauses and {@code pg_trgm};
 * and the behaviour under test includes {@code SELECT ... FOR UPDATE} row locking,
 * which an in-memory database either does not implement or implements differently.
 * Testing against a different engine than production would prove very little here.
 *
 * <p>{@code disabledWithoutDocker = true} skips the whole class when no container
 * runtime is present, rather than failing the build. CI provides Docker and runs them;
 * a developer without it still gets the full unit and structural suites.
 *
 * <p>Flyway runs the production migrations against the container, so the migrations are
 * exercised on every run.
 *
 * <p>HTTP is driven through {@link RestTestClient}. Spring Boot 4 removed
 * {@code TestRestTemplate}; {@code RestTestClient} is the Spring Framework 7
 * replacement. Responses are consumed with {@code returnResult(...)} rather than the
 * fluent {@code expectStatus()} assertions so that ordinary AssertJ assertions can be
 * used in the tests, consistent with the rest of the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        // Fixture cleanup must commit before HTTP request threads inspect the database.
        "spring.datasource.hikari.auto-commit=true",
        // auth-service issues tokens, so it never obtains one for itself.
        "aegis.service-auth.enabled=false",
        // Deterministic fixtures: no demo accounts appearing in assertions.
        "aegis.auth.demo.seed-enabled=false",
        // A machine client must exist or CredentialSeeder logs an error at startup.
        "aegis.auth.service-clients[0].client-id=test-internal",
        "aegis.auth.service-clients[0].client-secret=test-internal-secret",
        // Lock after 3 attempts instead of 6 to keep the lockout test quick while
        // still exercising the real threshold logic.
        "aegis.auth.lockout.max-failed-attempts=3",
        // Lowest value the configuration validation permits. BCrypt dominates the
        // runtime of this suite; cost 10 keeps it to about a second in total.
        "aegis.auth.password.bcrypt-strength=10",
        "aegis.logging.json=false"
})
abstract class AbstractAuthIntegrationTest {

    /**
     * Shared across every subclass because the container costs a few seconds to start.
     * {@code static} means one container for the whole suite; per-test isolation comes
     * from {@link #resetState()} instead.
     *
     * <p>{@code @ServiceConnection} wires the JDBC URL, username and password into the
     * context automatically, with no {@code @DynamicPropertySource} plumbing.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("aegis_auth_test")
                    .withUsername("aegis_test")
                    .withPassword("aegis_test_password");

    @Autowired
    protected JdbcTemplate jdbc;

    @LocalServerPort
    protected int port;

    protected RestTestClient client;

    /**
     * Truncates the mutable tables between tests.
     *
     * <p>{@code TRUNCATE ... CASCADE} rather than re-running Flyway: migrating per test
     * would dominate the runtime and truncation gives the same isolation.
     * {@code service_clients} is left alone because it is populated once at startup by
     * {@code CredentialSeeder} and never mutated here.
     */
    @BeforeEach
    void resetState() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        jdbc.execute("TRUNCATE TABLE refresh_tokens, audit_logs, user_roles, users CASCADE");
    }

    // ------------------------------------------------------------ HTTP helpers

    /** A status code plus a decoded body, so tests can assert both with AssertJ. */
    protected record Response<T>(HttpStatus status, T body) {
    }

    protected <T> Response<T> post(String uri, Object body, Class<T> type) {
        return exchange(HttpMethod.POST, uri, body, null, type);
    }

    protected <T> Response<T> post(String uri, Object body, String accessToken, Class<T> type) {
        return exchange(HttpMethod.POST, uri, body, accessToken, type);
    }

    /** Returns only the status when concurrent outcomes have different response schemas. */
    protected HttpStatus postStatus(String uri, Object body) {
        EntityExchangeResult<byte[]> result = client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .returnResult(byte[].class);
        return HttpStatus.valueOf(result.getStatus().value());
    }

    protected <T> Response<T> get(String uri, Class<T> type) {
        return exchange(HttpMethod.GET, uri, null, null, type);
    }

    protected <T> Response<T> get(String uri, String accessToken, Class<T> type) {
        return exchange(HttpMethod.GET, uri, null, accessToken, type);
    }

    protected <T> Response<T> exchange(HttpMethod method, String uri, Object body,
                                       String accessToken, Class<T> type) {
        RestTestClient.RequestBodySpec request = client.method(method)
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON);

        if (accessToken != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }

        RestTestClient.RequestHeadersSpec<?> ready = body == null ? request : request.body(body);

        EntityExchangeResult<T> result = ready.exchange().returnResult(type);
        return new Response<>(HttpStatus.valueOf(result.getStatus().value()), result.getResponseBody());
    }

    // ---- direct database reads, used to assert what actually COMMITTED ----

    protected int failedLoginAttempts(String email) {
        Integer value = jdbc.queryForObject(
                "SELECT failed_login_attempts FROM users WHERE email = ?", Integer.class, email);
        return value == null ? 0 : value;
    }

    protected boolean isLocked(String email) {
        Boolean locked = jdbc.queryForObject(
                "SELECT locked_until IS NOT NULL AND locked_until > now() FROM users WHERE email = ?",
                Boolean.class, email);
        return Boolean.TRUE.equals(locked);
    }

    protected String accountStatus(String email) {
        return jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
    }

    protected long countTokensInState(String userId, String state) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND state = ?",
                Long.class, userId, state);
        return count == null ? 0L : count;
    }

    protected long countRolesFor(String email) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM user_roles r
                JOIN users u ON u.id = r.user_id
                WHERE u.email = ?
                """, Long.class, email);
        return count == null ? 0L : count;
    }

    protected long countUsers(String email) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Long.class, email);
        return count == null ? 0L : count;
    }

    protected long countAuditEvents(String eventType) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE event_type = ?", Long.class, eventType);
        return count == null ? 0L : count;
    }

    protected void grantRole(String email, String role) {
        jdbc.update("""
                INSERT INTO user_roles (user_id, role)
                SELECT id, ? FROM users WHERE email = ?
                ON CONFLICT DO NOTHING
                """, role, email);
    }
}
