package com.aegispay.fraud.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.aegispay.fraud.config.FraudProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the ML client against a real HTTP server on a loopback port.
 *
 * <p><strong>Why a real server rather than a mocked RestClient.</strong> The thing most likely to
 * break here is the JSON on the wire, and a mock cannot fail that way. ml-service is a pydantic
 * application whose default camelCase generator turns {@code payment_count_1h} into
 * {@code paymentCount1H} — capital H — while Jackson serialising the Java field produces
 * {@code paymentCount1h}. Six fields are affected. The Python side pins explicit aliases and asserts
 * them; this is the Java half of that contract, and it is only meaningful if the bytes are real.
 *
 * <p>No Docker and no Spring context, so these run on every build rather than only where containers
 * are available.
 */
class MlScoringClientTest {

    /**
     * The six field names that cannot be left to either framework's camelCase convention.
     *
     * <p>Kept as a literal list rather than derived from the record, because deriving them would make
     * the test agree with whatever the code currently produces — which is exactly the bug.
     */
    private static final List<String> PINNED_FIELD_NAMES = List.of(
            "paymentCount1h",
            "paymentCount24h",
            "amountSum24hMinor",
            "failedPaymentCount24h",
            "distinctDevices30d",
            "distinctCountries30d");

    private static final String SCORE_RESPONSE = """
            {
              "fraudProbability": 0.8734,
              "riskScore": 87,
              "modelVersion": "fraud_hgb-c9026b7d59cb",
              "features": {"amountMinor": 100000, "avgAmountMinor": null},
              "contributions": [
                {"feature": "amountMinor", "description": "The amount was unusually large",
                 "value": 100000.0, "impactPoints": 12.4, "direction": "up"}
              ],
              "scoringLatencyMs": 5
            }
            """;

    private HttpServer server;
    private String baseUrl;
    private FraudProperties properties;

    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new FraudProperties();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ------------------------------------------------------- the wire contract

    @Nested
    @DisplayName("the wire contract")
    class WireContract {

        /**
         * <strong>The assertion this class exists for.</strong> ml-service rejects unknown fields, so a
         * mismatch fails loudly rather than silently — but only once the two are deployed together.
         * Until then the symptom would be six features quietly absent from every score.
         */
        @Test
        @DisplayName("serialises the six digit-bearing fields with the pinned names")
        void pinnedFieldNames() {
            respondWith(200, SCORE_RESPONSE);

            client().score(request());

            String body = lastBody.get();
            assertThat(body).isNotNull();

            for (String field : PINNED_FIELD_NAMES) {
                assertThat(body)
                        .as("""
                                ml-service pins the alias "%s". Jackson must produce exactly that: \
                                pydantic's default generator would emit paymentCount1H with a \
                                capital H, and the two would silently disagree.""", field)
                        .contains("\"" + field + "\"");
            }
        }

        /**
         * The specific spelling the two frameworks disagree about. Asserted as an absence, because the
         * wrong name is what a well-meaning refactor would reintroduce.
         */
        @Test
        @DisplayName("never emits the capital-H spelling pydantic would generate")
        void neverEmitsCapitalH() {
            respondWith(200, SCORE_RESPONSE);

            client().score(request());

            assertThat(lastBody.get())
                    .as("paymentCount1H is the pydantic default and is not the agreed name")
                    .doesNotContain("paymentCount1H");
        }

        /**
         * Absent values must reach the model as null, never as zero. It is trained to treat absence as
         * its own signal, and a zero average would assert that this account's usual payment is nothing.
         */
        @Test
        @DisplayName("sends absent values as null rather than omitting or zeroing them")
        void absentValuesAreNull() {
            respondWith(200, SCORE_RESPONSE);

            client().score(new MlScoringClient.ScoreRequest(
                    "PAY-1", "TXN-1", 100_000L, "INR", "P2P_TRANSFER", 3, 2,
                    null, 0L, 0L,
                    null, null,
                    0L, 0L, 0L, 0L, null,
                    null, 0L, null, 0L, null, null, false,
                    true, null));

            assertThat(lastBody.get())
                    .as("null is a value the model has a trained path for; zero is a false claim")
                    .contains("\"avgAmountMinor\":null")
                    .contains("\"accountAgeDays\":null");
        }

        @Test
        @DisplayName("posts to /score")
        void postsToScore() {
            respondWith(200, SCORE_RESPONSE);

            client().score(request());

            assertThat(paths).containsExactly("/score");
        }

        @Test
        @DisplayName("reads the probability, score, version and contributions back")
        void parsesTheResponse() {
            respondWith(200, SCORE_RESPONSE);

            Optional<MlScoringClient.ScoreResponse> response = client().score(request());

            assertThat(response).isPresent();
            MlScoringClient.ScoreResponse score = response.orElseThrow();
            assertThat(score.fraudProbability()).isEqualTo(0.8734);
            assertThat(score.riskScore()).isEqualTo(87);
            assertThat(score.modelVersion()).isEqualTo("fraud_hgb-c9026b7d59cb");
            assertThat(score.contributions()).hasSize(1);
            assertThat(score.contributions().getFirst().impactPoints()).isEqualTo(12.4);
            assertThat(score.features())
                    .as("the exact vector scored, retained so a disputed decision can be reproduced")
                    .containsKey("amountMinor");
        }

        /**
         * ml-service may add fields ahead of a fraud-service deployment. Failing on those would make
         * every ml-service release a coordinated one.
         */
        @Test
        @DisplayName("tolerates unknown fields in the response")
        void toleratesUnknownResponseFields() {
            respondWith(200, """
                    {"fraudProbability": 0.5, "riskScore": 50, "modelVersion": "v1",
                     "features": {}, "contributions": [], "scoringLatencyMs": 3,
                     "somethingAddedLater": {"nested": true}}
                    """);

            assertThat(client().score(request())).isPresent();
        }
    }

    // ------------------------------------------------------------------- auth

    @Nested
    @DisplayName("authentication")
    class Auth {

        /**
         * A static API key rather than a client-credentials token, because ml-service is a Python
         * service with no place in the platform's OAuth2 trust chain. Sending a bearer token would be
         * misleading about how it is actually secured.
         */
        @Test
        @DisplayName("sends the API key as X-API-Key when one is configured")
        void sendsApiKey() {
            respondWith(200, SCORE_RESPONSE);

            client("s3cret").score(request());

            assertThat(lastApiKey.get()).isEqualTo("s3cret");
        }

        @Test
        @DisplayName("omits the header entirely when no key is configured")
        void omitsHeaderWithoutKey() {
            respondWith(200, SCORE_RESPONSE);

            client("").score(request());

            assertThat(lastApiKey.get())
                    .as("an empty header would be a credential claim the service would reject")
                    .isNull();
        }
    }

    // --------------------------------------------------------------- failures

    @Nested
    @DisplayName("when the model does not answer")
    class Failures {

        /**
         * A model that cannot be reached is an expected operating condition, not an error the caller
         * should be interrupted by. Empty means "score this on rules alone and mark it degraded".
         */
        @Test
        @DisplayName("reports an HTTP error as no score rather than throwing")
        void serverErrorBecomesEmpty() {
            respondWith(503, "{\"error\":\"model not loaded\"}");

            assertThat(client().score(request()))
                    .as("the scoring service decides what an absent score means, not this client")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports a refused connection as no score")
        void connectionRefusedBecomesEmpty() {
            server.stop(0);

            assertThat(client().score(request())).isEmpty();
        }

        /**
         * Retrying is safe here precisely because scoring has no side effect: a duplicate call charges
         * nobody and returns the same answer. That is the opposite of a provider authorisation.
         */
        @Test
        @DisplayName("retries a failure, because scoring has no side effect")
        void retriesOnFailure() {
            properties.getMl().setMaxAttempts(3);
            properties.getMl().setRetryInterval(Duration.ofMillis(1));
            respondWith(500, "{\"error\":\"transient\"}");

            // A fresh breaker per test, or an earlier test's failures would open it and this one
            // would observe zero attempts.
            client(CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), "")
                    .score(request());

            assertThat(requestCount.get())
                    .as("three attempts were configured and scoring is safe to repeat")
                    .isEqualTo(3);
        }

        /**
         * The timeout is short because this call sits on the payment's critical path, ahead of a
         * provider authorisation with a three-second budget of its own. Falling back to rules quickly
         * is better for the customer than making every payment wait.
         */
        @Test
        @DisplayName("gives up on a slow model within its budget")
        void timesOut() {
            properties.getMl().setTimeout(Duration.ofMillis(150));
            properties.getMl().setMaxAttempts(1);
            respondSlowly(Duration.ofSeconds(3));

            long started = System.nanoTime();
            Optional<MlScoringClient.ScoreResponse> response = client().score(request());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(response).isEmpty();
            assertThat(elapsed)
                    .as("a slow model must not eat the saga's provider budget")
                    .isLessThan(Duration.ofSeconds(2));
        }

        /**
         * Once the breaker is open nothing is attempted, which is the whole value of having one: the
         * payment falls back to rules immediately instead of waiting out another timeout.
         */
        @Test
        @DisplayName("stops calling once the breaker has opened")
        void breakerStopsCalling() {
            properties.getMl().setMaxAttempts(1);
            respondWith(500, "{\"error\":\"down\"}");

            CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
            MlScoringClient client = client(breakers, RetryRegistry.ofDefaults(), "");

            // The default sliding window needs a minimum number of calls before it can trip.
            for (int i = 0; i < 120; i++) {
                client.score(request());
            }
            int callsBeforeOpen = requestCount.get();
            client.score(request());

            assertThat(breakers.circuitBreaker("ml-service").getState())
                    .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
            assertThat(requestCount.get())
                    .as("an open breaker must not put another request on the wire")
                    .isEqualTo(callsBeforeOpen);
        }
    }

    // --------------------------------------------------------------- metadata

    @Nested
    @DisplayName("model metadata")
    class ModelInfo {

        @Test
        @DisplayName("reads the model provenance from /model")
        void readsModelInfo() {
            respondWith(200, """
                    {"modelName": "fraud_hgb", "modelVersion": "fraud_hgb-c9026b7d59cb",
                     "rocAuc": 0.9353, "syntheticData": true}
                    """);

            Optional<java.util.Map<String, Object>> info = client().modelInfo();

            assertThat(info).isPresent();
            assertThat(info.orElseThrow()).containsEntry("modelName", "fraud_hgb");
            assertThat(paths).containsExactly("/model");
        }

        /**
         * Null model metadata is itself the answer an operator investigating a degraded verdict is
         * looking for, so an unreachable model must not fail the whole admin view.
         */
        @Test
        @DisplayName("reports an unreachable model as absent metadata, not an error")
        void unreachableModelIsEmpty() {
            server.stop(0);

            assertThat(client().modelInfo()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    private MlScoringClient client() {
        return client("");
    }

    private MlScoringClient client(String apiKey) {
        return client(CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), apiKey);
    }

    private MlScoringClient client(CircuitBreakerRegistry breakers, RetryRegistry retries,
                                   String apiKey) {
        return new MlScoringClient(RestClient.builder(), baseUrl, apiKey, properties,
                breakers, retries, new SimpleMeterRegistry());
    }

    private void respondWith(int status, String body) {
        handle(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void respondSlowly(Duration delay) {
        handle(exchange -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
    }

    /** Registers a handler that records what arrived, so the request can be asserted on. */
    private void handle(ThrowingHandler handler) {
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            lastApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));

            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            handler.handle(exchange);
        });
    }

    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** A request with every field populated, so the serialised shape is fully exercised. */
    private static MlScoringClient.ScoreRequest request() {
        return new MlScoringClient.ScoreRequest(
                "PAY-1", "TXN-1",
                100_000L, "INR", "P2P_TRANSFER", 3, 2,
                365L, 40L, 0L,
                50_000L, 200_000L,
                2L, 5L, 250_000L, 1L, 3_600L,
                Boolean.FALSE, 2L, Boolean.FALSE, 1L, "IN", 12.5, Boolean.TRUE,
                Boolean.TRUE, "5411");
    }
}
