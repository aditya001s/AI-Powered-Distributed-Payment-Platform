package com.aegispay.payment;

import com.aegispay.common.testsupport.TestJwtIssuer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A working stand-in for wallet-service, spoken to over real HTTP.
 *
 * <p><strong>Why a stand-in and not the real service.</strong> payment-service owns the
 * payment lifecycle, not balances. Testing it end-to-end means testing the orchestration:
 * the state machine, the outbox, idempotency, and above all what happens when a step in the
 * middle fails. Booting a second Spring application with its own database inside these tests
 * would double the runtime and, worse, make every payment test also a wallet test — a broken
 * hold would fail thirty payment assertions instead of one wallet assertion.
 *
 * <p><strong>Why it is a real HTTP server rather than a mocked {@code WalletClient}.</strong>
 * A mock returns whatever it is told to. It cannot show that a 422 is translated into a
 * business decline and a 503 into a retryable fault, that a connection failure becomes
 * {@code UpstreamUnavailableException}, or that the client-credentials interceptor actually
 * attaches a bearer token. Those live in the HTTP layer, and getting the 4xx/5xx split wrong
 * is how a system ends up retrying a decline until it accidentally succeeds. So this speaks
 * the wire protocol, including the status codes.
 *
 * <p><strong>The balance semantics are real.</strong> Integer minor units, separate balance
 * and held columns, one hold per payment, idempotent reserve/capture/release, and 422 on
 * insufficient available funds. A stub that always said "yes" would let the payment service
 * pass while being wrong about the only thing that matters.
 *
 * <p>It also serves {@code POST /api/auth/token}, so the service under test obtains a
 * client-credentials token through its normal path instead of having service auth disabled.
 * Every internal request is checked for a bearer token, and {@link #requestsWithoutAuth()}
 * reports any that arrived without one — a silently unauthenticated internal call would
 * otherwise be invisible.
 *
 * <p>Fault injection ({@link #failNextReserve}, {@link #failNextCapture},
 * {@link #rejectNextRelease}) exists to drive the compensation paths. Those paths are the
 * ones that decide whether a customer's money is stranded after a failure, and they cannot
 * be reached at all if every dependency always succeeds.
 */
final class StubWalletService {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    /** Guards every mutation. Payment tests fire concurrent requests at this. */
    private final Object lock = new Object();

    private final Map<String, Wallet> walletsById = new LinkedHashMap<>();
    private final Map<String, Hold> holdsByPayment = new HashMap<>();

    private final AtomicInteger reserveCalls = new AtomicInteger();
    private final AtomicInteger captureCalls = new AtomicInteger();
    private final AtomicInteger releaseCalls = new AtomicInteger();
    private final AtomicInteger requestsWithoutAuth = new AtomicInteger();

    /** One-shot fault injections, consumed by the next matching call. */
    private final AtomicReference<Integer> failNextReserve = new AtomicReference<>();
    private final AtomicReference<Integer> failNextCapture = new AtomicReference<>();
    private final AtomicReference<Boolean> rejectNextRelease = new AtomicReference<>(false);

    StubWalletService() {
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start the stub wallet service", ex);
        }

        server.createContext("/api/auth/token", this::handleToken);
        server.createContext("/internal/wallets", this::handleWallets);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    String tokenUri() {
        return baseUrl() + "/api/auth/token";
    }

    // ------------------------------------------------------------- test setup

    /** Registers a wallet with a starting balance, in minor units. */
    String addWallet(String userId, String currency, long balanceMinor) {
        String walletId = "WAL-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16).toUpperCase();
        synchronized (lock) {
            walletsById.put(walletId, new Wallet(walletId, userId, currency, balanceMinor));
        }
        return walletId;
    }

    void setBalanceMinor(String walletId, long balanceMinor) {
        synchronized (lock) {
            Wallet wallet = walletsById.get(walletId);
            if (wallet == null) {
                throw new IllegalArgumentException("Unknown wallet " + walletId);
            }
            if (balanceMinor < wallet.heldMinor) {
                throw new IllegalArgumentException("Balance cannot be lower than held funds");
            }
            wallet.balanceMinor = balanceMinor;
        }
    }

    void freeze(String walletId) {
        synchronized (lock) {
            walletsById.get(walletId).status = "FROZEN";
        }
    }

    void reset() {
        synchronized (lock) {
            walletsById.clear();
            holdsByPayment.clear();
        }
        reserveCalls.set(0);
        captureCalls.set(0);
        releaseCalls.set(0);
        requestsWithoutAuth.set(0);
        failNextReserve.set(null);
        failNextCapture.set(null);
        rejectNextRelease.set(false);
    }

    void stop() {
        server.stop(0);
    }

    // -------------------------------------------------------- fault injection

    /** Makes the next reserve return {@code status}. Use 503 for a technical fault. */
    void failNextReserve(int status) {
        failNextReserve.set(status);
    }

    /**
     * Makes the next capture return {@code status} <em>without</em> moving money.
     *
     * <p>This is the important failure: the reservation exists, so compensation must release
     * it or the customer's funds stay frozen.
     */
    void failNextCapture(int status) {
        failNextCapture.set(status);
    }

    /** Makes the next release fail, so the compensation-failed path can be observed. */
    void rejectNextRelease() {
        rejectNextRelease.set(true);
    }

    // ------------------------------------------------------------ inspection

    long balanceMinor(String walletId) {
        synchronized (lock) {
            return walletsById.get(walletId).balanceMinor;
        }
    }

    long heldMinor(String walletId) {
        synchronized (lock) {
            return walletsById.get(walletId).heldMinor;
        }
    }

    long availableMinor(String walletId) {
        synchronized (lock) {
            Wallet wallet = walletsById.get(walletId);
            return wallet.balanceMinor - wallet.heldMinor;
        }
    }

    boolean hasHold(String paymentId) {
        synchronized (lock) {
            return holdsByPayment.containsKey(paymentId);
        }
    }

    String holdState(String paymentId) {
        synchronized (lock) {
            Hold hold = holdsByPayment.get(paymentId);
            return hold == null ? null : hold.state;
        }
    }

    /** True when no reservation anywhere is still holding funds. */
    boolean allHoldsSettled() {
        synchronized (lock) {
            return holdsByPayment.values().stream().noneMatch(h -> "ACTIVE".equals(h.state));
        }
    }

    int reserveCalls() {
        return reserveCalls.get();
    }

    int captureCalls() {
        return captureCalls.get();
    }

    int releaseCalls() {
        return releaseCalls.get();
    }

    int requestsWithoutAuth() {
        return requestsWithoutAuth.get();
    }

    // --------------------------------------------------------------- handlers

    /** The OAuth2 client-credentials endpoint the service fetches its own token from. */
    private void handleToken(HttpExchange exchange) throws IOException {
        String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> fields = parseForm(form);

        if (!"client_credentials".equals(fields.get("grant_type"))
                || fields.get("client_id") == null
                || fields.get("client_secret") == null) {
            respond(exchange, 400, "{\"error\":\"invalid_request\"}");
            return;
        }

        // A genuine signed token, so the value on the wire looks like the real thing.
        String token = TestJwtIssuer.service(fields.get("client_id"));
        respond(exchange, 200, """
                {"access_token":"%s","token_type":"Bearer","expires_in":600,"scope":"internal"}
                """.formatted(token));
    }

    private void handleWallets(HttpExchange exchange) throws IOException {
        // Every internal call must present a bearer token. Counted rather than rejected so
        // a test can assert the count is zero and get a clear failure, instead of an
        // unrelated 401 surfacing somewhere downstream.
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            requestsWithoutAuth.incrementAndGet();
        }

        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();

        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                if (path.startsWith("/internal/wallets/by-user/")) {
                    handleByUser(exchange, path.substring("/internal/wallets/by-user/".length()),
                            currencyFrom(query));
                } else {
                    handleById(exchange, path.substring("/internal/wallets/".length()));
                }
                return;
            }
            switch (path) {
                case "/internal/wallets/reserve" -> handleReserve(exchange);
                case "/internal/wallets/capture" -> handleCapture(exchange);
                case "/internal/wallets/release" -> handleRelease(exchange);
                default -> respond(exchange, 404, error("no such endpoint"));
            }
        } catch (RuntimeException ex) {
            respond(exchange, 500, error("stub failure: " + ex.getMessage()));
        }
    }

    private void handleById(HttpExchange exchange, String walletId) throws IOException {
        synchronized (lock) {
            Wallet wallet = walletsById.get(walletId);
            if (wallet == null) {
                respond(exchange, 404, error("wallet not found"));
                return;
            }
            respond(exchange, 200, wallet.toJson());
        }
    }

    private void handleByUser(HttpExchange exchange, String userId, String currency)
            throws IOException {
        synchronized (lock) {
            Wallet wallet = walletsById.values().stream()
                    .filter(w -> w.userId.equals(userId) && w.currency.equalsIgnoreCase(currency))
                    .findFirst().orElse(null);
            if (wallet == null) {
                respond(exchange, 404, error("wallet not found"));
                return;
            }
            respond(exchange, 200, wallet.toJson());
        }
    }

    private void handleReserve(HttpExchange exchange) throws IOException {
        reserveCalls.incrementAndGet();

        Integer injected = failNextReserve.getAndSet(null);
        if (injected != null) {
            respond(exchange, injected, error("injected reserve failure"));
            return;
        }

        JsonNode body = readBody(exchange);
        String paymentId = body.get("paymentId").asString();
        String walletId = body.get("walletId").asString();
        long amountMinor = minorUnits(body.get("amount").asString());

        synchronized (lock) {
            Hold existing = holdsByPayment.get(paymentId);
            if (existing != null) {
                // Idempotent on paymentId, exactly as the real service is.
                if (existing.amountMinor != amountMinor) {
                    respond(exchange, 409, error("already reserved a different amount"));
                    return;
                }
                respond(exchange, 200, existing.toJson(availableMinorUnlocked(existing.walletId)));
                return;
            }

            Wallet wallet = walletsById.get(walletId);
            if (wallet == null) {
                respond(exchange, 404, error("wallet not found"));
                return;
            }
            if (!"ACTIVE".equals(wallet.status)) {
                respond(exchange, 409, error("wallet is " + wallet.status));
                return;
            }
            if (wallet.balanceMinor - wallet.heldMinor < amountMinor) {
                // 422, the status the real service uses for insufficient AVAILABLE funds.
                respond(exchange, 422, error("insufficient funds"));
                return;
            }

            wallet.heldMinor += amountMinor;
            Hold hold = new Hold(paymentId, walletId, amountMinor, wallet.currency);
            holdsByPayment.put(paymentId, hold);
            respond(exchange, 200, hold.toJson(availableMinorUnlocked(walletId)));
        }
    }

    private void handleCapture(HttpExchange exchange) throws IOException {
        captureCalls.incrementAndGet();

        Integer injected = failNextCapture.getAndSet(null);
        if (injected != null) {
            // Deliberately fails BEFORE moving money, leaving the reservation intact. That
            // is the state compensation has to clean up.
            respond(exchange, injected, error("injected capture failure"));
            return;
        }

        JsonNode body = readBody(exchange);
        String paymentId = body.get("paymentId").asString();
        String sourceWalletId = body.get("sourceWalletId").asString();
        String destinationWalletId = body.get("destinationWalletId").asString();

        synchronized (lock) {
            Hold hold = holdsByPayment.get(paymentId);
            if (hold == null) {
                respond(exchange, 404, error("no reservation for this payment"));
                return;
            }
            if ("CAPTURED".equals(hold.state)) {
                // Idempotent replay.
                respond(exchange, 200, captureJson(hold, sourceWalletId, destinationWalletId));
                return;
            }
            if ("RELEASED".equals(hold.state)) {
                respond(exchange, 409, error("reservation was released"));
                return;
            }
            if (!hold.walletId.equals(sourceWalletId)) {
                respond(exchange, 409, error("reservation belongs to another wallet"));
                return;
            }

            Wallet source = walletsById.get(sourceWalletId);
            Wallet destination = walletsById.get(destinationWalletId);
            if (destination == null) {
                respond(exchange, 404, error("destination wallet not found"));
                return;
            }
            if ("CLOSED".equals(destination.status)) {
                respond(exchange, 409, error("destination cannot receive"));
                return;
            }

            // Debit and credit together: money is never in neither wallet.
            source.heldMinor -= hold.amountMinor;
            source.balanceMinor -= hold.amountMinor;
            destination.balanceMinor += hold.amountMinor;
            hold.state = "CAPTURED";
            hold.capturedAt = Instant.now();

            respond(exchange, 200, captureJson(hold, sourceWalletId, destinationWalletId));
        }
    }

    private void handleRelease(HttpExchange exchange) throws IOException {
        releaseCalls.incrementAndGet();

        if (Boolean.TRUE.equals(rejectNextRelease.getAndSet(false))) {
            respond(exchange, 503, error("injected release failure"));
            return;
        }

        JsonNode body = readBody(exchange);
        String paymentId = body.get("paymentId").asString();

        synchronized (lock) {
            Hold hold = holdsByPayment.get(paymentId);
            if (hold == null) {
                // The real service answers 404 here. payment-service calls release even
                // when it is unsure a hold exists, and tolerates this.
                respond(exchange, 404, error("no reservation for this payment"));
                return;
            }
            if ("CAPTURED".equals(hold.state)) {
                respond(exchange, 409, error("reservation was already captured"));
                return;
            }
            if ("ACTIVE".equals(hold.state)) {
                walletsById.get(hold.walletId).heldMinor -= hold.amountMinor;
                hold.state = "RELEASED";
            }
            // Releasing twice is a no-op, matching the real contract.
            respond(exchange, 200, hold.toJson(availableMinorUnlocked(hold.walletId)));
        }
    }

    // --------------------------------------------------------------- plumbing

    private long availableMinorUnlocked(String walletId) {
        Wallet wallet = walletsById.get(walletId);
        return wallet.balanceMinor - wallet.heldMinor;
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        return json.readTree(exchange.getRequestBody().readAllBytes());
    }

    private static String currencyFrom(String query) {
        if (query == null) {
            return "INR";
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && "currency".equals(pair.substring(0, equals))) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return "INR";
    }

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

    /**
     * Converts the decimal amount on the wire into minor units.
     *
     * <p>Via {@link BigDecimal}, never a double: {@code 0.01} has no exact double
     * representation, so a double round-trip here would introduce the very rounding error
     * the platform's integer money representation exists to prevent — and it would do it
     * inside the test harness, where it would look like a production bug.
     */
    private static long minorUnits(String decimal) {
        return new BigDecimal(decimal).movePointRight(2).longValueExact();
    }

    private static String decimal(long amountMinor) {
        return BigDecimal.valueOf(amountMinor, 2).toPlainString();
    }

    private static String error(String message) {
        return "{\"code\":\"STUB_ERROR\",\"message\":\"" + message + "\"}";
    }

    private String captureJson(Hold hold, String source, String destination) {
        return """
                {"paymentId":"%s","holdId":"%s",
                 "amount":{"amount":%s,"amountMinor":%d,"currency":"%s"},
                 "sourceWalletId":"%s","destinationWalletId":"%s",
                 "debitTransactionId":"TXN-DR-%s","creditTransactionId":"TXN-CR-%s",
                 "capturedAt":"%s"}
                """.formatted(hold.paymentId, hold.holdId,
                decimal(hold.amountMinor), hold.amountMinor, hold.currency,
                source, destination,
                hold.holdId, hold.holdId,
                hold.capturedAt == null ? Instant.now() : hold.capturedAt);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ----------------------------------------------------------------- state

    private static final class Wallet {
        private final String walletId;
        private final String userId;
        private final String currency;
        private long balanceMinor;
        private long heldMinor;
        private String status = "ACTIVE";

        private Wallet(String walletId, String userId, String currency, long balanceMinor) {
            this.walletId = walletId;
            this.userId = userId;
            this.currency = currency;
            this.balanceMinor = balanceMinor;
        }

        private String toJson() {
            return """
                    {"walletId":"%s","userId":"%s","currency":"%s",
                     "balanceMinor":%d,"heldMinor":%d,"availableMinor":%d,
                     "status":"%s","canSpend":%s}
                    """.formatted(walletId, userId, currency,
                    balanceMinor, heldMinor, balanceMinor - heldMinor,
                    status, "ACTIVE".equals(status));
        }
    }

    private static final class Hold {
        private final String holdId;
        private final String paymentId;
        private final String walletId;
        private final long amountMinor;
        private final String currency;
        private String state = "ACTIVE";
        private Instant capturedAt;

        private Hold(String paymentId, String walletId, long amountMinor, String currency) {
            this.holdId = "HLD-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 20).toUpperCase();
            this.paymentId = paymentId;
            this.walletId = walletId;
            this.amountMinor = amountMinor;
            this.currency = currency;
        }

        private String toJson(long availableAfterMinor) {
            return """
                    {"holdId":"%s","paymentId":"%s","walletId":"%s",
                     "amount":{"amount":%s,"amountMinor":%d,"currency":"%s"},
                     "state":"%s","expiresAt":"%s",
                     "availableAfter":{"amount":%s,"amountMinor":%d,"currency":"%s"}}
                    """.formatted(holdId, paymentId, walletId,
                    decimal(amountMinor), amountMinor, currency,
                    state, Instant.now().plusSeconds(900),
                    decimal(availableAfterMinor), availableAfterMinor, currency);
        }
    }
}
