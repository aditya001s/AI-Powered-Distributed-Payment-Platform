package com.aegispay.fraud;

import com.aegispay.common.testsupport.TestJwtIssuer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A working stand-in for ml-service, spoken to over real HTTP.
 *
 * <p><strong>Why a stand-in rather than the real Python service.</strong> The real one is verified by
 * its own 129-test pytest suite; what these integration tests are about is what fraud-service does
 * with a score. Booting a Python process from a Java test would make every fraud test also a model
 * test — one retrained model would move forty assertions — and would need a Python toolchain on every
 * machine that runs the build.
 *
 * <p><strong>Why real HTTP rather than a mocked client.</strong> A mock returns whatever it is told to.
 * It cannot show that the six digit-bearing field names survive serialisation, that a 503 becomes a
 * degraded verdict rather than an exception, or that a read timeout is given up on inside the budget.
 * That behaviour lives in the HTTP layer, and the wire contract is the thing most likely to break.
 *
 * <p><strong>It records what it was sent.</strong> The feature vector the model receives is the
 * assertion that matters most: the window aggregates must exclude the payment being scored, and a stub
 * that discarded the request could not show that.
 */
public final class StubMlService {

    /** The API key this stub requires. Empty in a test means it accepts anything. */
    public static final String API_KEY = "test-ml-api-key";

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;

    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger scoreCalls = new AtomicInteger();
    private final AtomicInteger unauthenticatedCalls = new AtomicInteger();

    /** The probability the next score returns. */
    private final AtomicReference<Double> nextProbability = new AtomicReference<>(0.02);

    /** One-shot failure injection: the status the next score returns instead of a body. */
    private final AtomicReference<Integer> failNextScore = new AtomicReference<>();

    /** When set, every score fails, standing in for a model that is down rather than flaky. */
    private final AtomicReference<Boolean> alwaysFail = new AtomicReference<>(false);

    public StubMlService() {
        startIfNeeded();
    }

    public synchronized String baseUrl() {
        startIfNeeded();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private synchronized void startIfNeeded() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start the ml-service stub", ex);
        }
        server.createContext("/api/auth/token", this::handleToken);
        server.createContext("/score", this::handleScore);
        server.createContext("/model", this::handleModel);
        executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.start();
    }

    /**
     * The client-credentials endpoint fraud-service fetches its own service token from.
     *
     * <p>Hosted here rather than in a second server so a test needs one port and one lifecycle. It is
     * unrelated to ml-service's own API-key auth, which is the point: ml-service is a Python service
     * with no place in the platform's OAuth2 trust chain, and the two mechanisms coexisting on one stub
     * makes that difference visible.
     */
    public String tokenUri() {
        return baseUrl() + "/api/auth/token";
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ------------------------------------------------------------- test setup

    public void reset() {
        requests.clear();
        scoreCalls.set(0);
        unauthenticatedCalls.set(0);
        nextProbability.set(0.02);
        failNextScore.set(null);
        alwaysFail.set(false);
    }

    /** Sets the probability every subsequent score returns. */
    public void scoresAt(double probability) {
        nextProbability.set(probability);
    }

    public void failNextScore(int status) {
        failNextScore.set(status);
    }

    /** Takes the model down for good, so every verdict must come from the rules alone. */
    public void takeDown() {
        alwaysFail.set(true);
    }

    // ------------------------------------------------------------- inspection

    public int scoreCalls() {
        return scoreCalls.get();
    }

    public int unauthenticatedCalls() {
        return unauthenticatedCalls.get();
    }

    /** The feature vector sent for a given payment, so the aggregates can be asserted on. */
    public JsonNode requestFor(String paymentId) {
        return requests.stream()
                .filter(request -> paymentId.equals(request.get("paymentId").asString()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "ml-service was never asked to score " + paymentId));
    }

    public boolean wasAskedToScore(String paymentId) {
        return requests.stream()
                .anyMatch(request -> paymentId.equals(request.get("paymentId").asString()));
    }

    // --------------------------------------------------------------- handlers

    private void handleToken(HttpExchange exchange) throws IOException {
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = parseForm(form);

        if (!"client_credentials".equals(fields.get("grant_type"))) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        String token = TestJwtIssuer.service(fields.get("client_id"));
        respond(exchange, 200, """
                {"access_token":"%s","token_type":"Bearer","expires_in":600,"scope":"internal"}
                """.formatted(token));
    }

    private void handleScore(HttpExchange exchange) throws IOException {
        scoreCalls.incrementAndGet();

        if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-API-Key"))) {
            // Counted rather than rejected, so a test asserting the key is sent gets a clear failure
            // instead of an unrelated degraded verdict that looks like a model outage.
            unauthenticatedCalls.incrementAndGet();
        }

        JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(body);

        if (Boolean.TRUE.equals(alwaysFail.get())) {
            respond(exchange, 503, "{\"code\":\"MODEL_UNAVAILABLE\",\"message\":\"down\"}");
            return;
        }
        Integer injected = failNextScore.getAndSet(null);
        if (injected != null) {
            respond(exchange, injected, "{\"code\":\"STUB\",\"message\":\"injected\"}");
            return;
        }

        double probability = nextProbability.get();
        respond(exchange, 200, """
                {"fraudProbability":%s,"riskScore":%d,
                 "modelVersion":"fraud_hgb-stub",
                 "features":{"amountMinor":%s,"avgAmountMinor":null},
                 "contributions":[
                   {"feature":"amountMinor","description":"The amount was unusually large",
                    "value":1.0,"impactPoints":12.4,"direction":"up"}
                 ],
                 "scoringLatencyMs":4}
                """.formatted(
                java.math.BigDecimal.valueOf(probability).toPlainString(),
                Math.round(probability * 100),
                body.get("amountMinor") == null ? "null" : body.get("amountMinor").asString()));
    }

    private void handleModel(HttpExchange exchange) throws IOException {
        if (Boolean.TRUE.equals(alwaysFail.get())) {
            respond(exchange, 503, "{\"code\":\"MODEL_UNAVAILABLE\",\"message\":\"down\"}");
            return;
        }
        respond(exchange, 200, """
                {"modelName":"fraud_hgb","modelVersion":"fraud_hgb-stub",
                 "rocAuc":0.9353,"averagePrecision":0.7255,
                 "syntheticData":true,
                 "caveat":"Trained on generated transactions. The metrics describe the generator's fraud patterns, not any real population."}
                """);
    }

    // --------------------------------------------------------------- plumbing

    private static Map<String, String> parseForm(String form) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : form.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                fields.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
