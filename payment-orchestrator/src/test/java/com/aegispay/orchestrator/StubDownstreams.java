package com.aegispay.orchestrator;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Working stand-ins for wallet-service and ledger-service, spoken to over real HTTP.
 *
 * <p><strong>Why stand-ins and not the real services.</strong> The orchestrator owns the saga, not
 * balances and not the chart of accounts. Testing it end-to-end means testing routing, retry,
 * fallback, breaking and compensation. Booting two more Spring applications with their own databases
 * inside these tests would triple the runtime and, worse, make every saga test also a wallet and
 * ledger test — one broken hold would fail forty saga assertions instead of one wallet assertion.
 *
 * <p><strong>Why real HTTP rather than mocked clients.</strong> A mock returns whatever it is told
 * to. It cannot show that a 422 becomes a business decline and a 503 a retryable fault, that a
 * connection failure becomes {@code UpstreamUnavailableException}, or that the client-credentials
 * interceptor actually attaches a bearer token. That translation lives in the HTTP layer, and getting
 * the 4xx/5xx split wrong is how a system retries a decline until it accidentally succeeds.
 *
 * <p><strong>The semantics are real where they matter.</strong> Integer minor units, separate balance
 * and held amounts, one hold per payment, idempotent reserve / capture / release, 422 on insufficient
 * available funds, and a ledger that is idempotent on its transaction key and refuses to reverse the
 * same posting twice. A stub that always said yes would let the orchestrator pass while being wrong
 * about the only things that matter.
 *
 * <p>Fault injection exists because the compensation paths cannot be reached at all if every
 * dependency always succeeds — and those paths are what decide whether a customer's money is stranded
 * after a failure.
 */
final class StubDownstreams {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    /** Guards every mutation. Saga tests fire concurrent requests at this. */
    private final Object lock = new Object();

    private final Map<String, Wallet> walletsById = new LinkedHashMap<>();
    private final Map<String, Hold> holdsByPayment = new HashMap<>();

    /** Ledger postings, keyed by the derived transaction id so replays are recognised. */
    private final Map<String, Posting> postingsByKey = new LinkedHashMap<>();
    private final AtomicLong journalSequence = new AtomicLong();

    /** Risk verdicts already given, so a second assessment of the same payment matches the first. */
    private final Map<String, Verdict> verdictsByPayment = new HashMap<>();

    private final AtomicInteger reserveCalls = new AtomicInteger();
    private final AtomicInteger captureCalls = new AtomicInteger();
    private final AtomicInteger releaseCalls = new AtomicInteger();
    private final AtomicInteger extendCalls = new AtomicInteger();
    private final AtomicInteger postCalls = new AtomicInteger();
    private final AtomicInteger reverseCalls = new AtomicInteger();
    private final AtomicInteger assessCalls = new AtomicInteger();
    private final AtomicInteger requestsWithoutAuth = new AtomicInteger();

    /** One-shot fault injections, consumed by the next matching call. */
    private final AtomicReference<Integer> failNextReserve = new AtomicReference<>();
    private final AtomicReference<Integer> failNextCapture = new AtomicReference<>();
    private final AtomicReference<Boolean> rejectNextRelease = new AtomicReference<>(false);
    private final AtomicReference<Boolean> rejectNextExtend = new AtomicReference<>(false);
    private final AtomicReference<Integer> failNextPosting = new AtomicReference<>();
    private final AtomicReference<Boolean> rejectNextReversal = new AtomicReference<>(false);
    private final AtomicReference<Integer> failNextAssessment = new AtomicReference<>();

    /** The verdict the next assessment returns. Null means a routine approval. */
    private final AtomicReference<Verdict> nextVerdict = new AtomicReference<>();

    /** Fees the ledger was asked to book, so a test can assert what the saga reported. */
    private final List<Long> bookedFeesMinor = new CopyOnWriteArrayList<>();

    StubDownstreams() {
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start the downstream stubs", ex);
        }

        server.createContext("/api/auth/token", this::handleToken);
        server.createContext("/internal/wallets", this::handleWallets);
        server.createContext("/internal/ledger", this::handleLedger);
        server.createContext("/internal/fraud", this::handleFraud);
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

    void reset() {
        synchronized (lock) {
            walletsById.clear();
            holdsByPayment.clear();
            postingsByKey.clear();
            verdictsByPayment.clear();
        }
        journalSequence.set(0);
        reserveCalls.set(0);
        captureCalls.set(0);
        releaseCalls.set(0);
        extendCalls.set(0);
        postCalls.set(0);
        reverseCalls.set(0);
        assessCalls.set(0);
        requestsWithoutAuth.set(0);
        failNextReserve.set(null);
        failNextCapture.set(null);
        rejectNextRelease.set(false);
        rejectNextExtend.set(false);
        failNextPosting.set(null);
        rejectNextReversal.set(false);
        failNextAssessment.set(null);
        nextVerdict.set(null);
        bookedFeesMinor.clear();
    }

    void stop() {
        server.stop(0);
    }

    // -------------------------------------------------------- fault injection

    /** Makes the next reserve return {@code status}. Use 422 for a decline, 503 for a fault. */
    void failNextReserve(int status) {
        failNextReserve.set(status);
    }

    /**
     * Makes the next capture return {@code status} <em>without</em> moving money.
     *
     * <p>The worst point to fail: the ledger has already been posted and the provider has already
     * authorised, so both must be undone as well as the reservation released.
     */
    void failNextCapture(int status) {
        failNextCapture.set(status);
    }

    void rejectNextRelease() {
        rejectNextRelease.set(true);
    }

    void failNextPosting(int status) {
        failNextPosting.set(status);
    }

    void rejectNextReversal() {
        rejectNextReversal.set(true);
    }

    void rejectNextExtend() {
        rejectNextExtend.set(true);
    }

    /** Makes the next assessment return {@code status}, standing in for a fraud-service outage. */
    void failNextAssessment(int status) {
        failNextAssessment.set(status);
    }

    /** Arms the verdict the next assessed payment receives. */
    void nextVerdictHolds(double probability, String reason) {
        nextVerdict.set(new Verdict("HIGH", "HOLD", probability, reason, false));
    }

    void nextVerdictRefuses(String reason) {
        nextVerdict.set(new Verdict("CRITICAL", "REJECT", 0.02, reason, false));
    }

    void nextVerdictReviews(double probability, String reason) {
        nextVerdict.set(new Verdict("MEDIUM", "REVIEW", probability, reason, false));
    }

    /** A verdict reached without the model: the rules alone decided. */
    void nextVerdictIsDegraded(String decision, String reason) {
        nextVerdict.set(new Verdict(
                "REJECT".equals(decision) ? "CRITICAL" : "HOLD".equals(decision) ? "HIGH" : "LOW",
                decision, 0.0, reason, true));
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

    String holdState(String paymentId) {
        synchronized (lock) {
            Hold hold = holdsByPayment.get(paymentId);
            return hold == null ? null : hold.state;
        }
    }

    /** True when no reservation anywhere is still holding funds. */
    boolean allHoldsSettled() {
        synchronized (lock) {
            return holdsByPayment.values().stream().noneMatch(hold -> "ACTIVE".equals(hold.state));
        }
    }

    boolean hasPosting(String ledgerTransactionId) {
        synchronized (lock) {
            return postingsByKey.containsKey(ledgerTransactionId);
        }
    }

    boolean postingReversed(String ledgerTransactionId) {
        synchronized (lock) {
            Posting posting = postingsByKey.get(ledgerTransactionId);
            return posting != null && posting.reversed;
        }
    }

    List<Long> bookedFeesMinor() {
        return List.copyOf(bookedFeesMinor);
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

    int extendCalls() {
        return extendCalls.get();
    }

    int assessCalls() {
        return assessCalls.get();
    }

    /** How long the reservation for a payment currently has left. */
    Instant holdExpiry(String paymentId) {
        synchronized (lock) {
            Hold hold = holdsByPayment.get(paymentId);
            return hold == null ? null : hold.expiresAt;
        }
    }

    int postCalls() {
        return postCalls.get();
    }

    int reverseCalls() {
        return reverseCalls.get();
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
        countAuth(exchange);
        String path = exchange.getRequestURI().getPath();

        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                if (path.startsWith("/internal/wallets/by-user/")) {
                    handleByUser(exchange, path.substring("/internal/wallets/by-user/".length()),
                            currencyFrom(exchange.getRequestURI().getQuery()));
                } else {
                    handleById(exchange, path.substring("/internal/wallets/".length()));
                }
                return;
            }
            switch (path) {
                case "/internal/wallets/reserve" -> handleReserve(exchange);
                case "/internal/wallets/capture" -> handleCapture(exchange);
                case "/internal/wallets/release" -> handleRelease(exchange);
                case "/internal/wallets/extend" -> handleExtend(exchange);
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
                // Idempotent on paymentId, exactly as the real service is. This is what stops a
                // redelivered event reserving the customer's funds twice.
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
            // Deliberately fails BEFORE moving money, leaving the reservation intact.
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
                respond(exchange, 200, captureJson(hold, sourceWalletId, destinationWalletId));
                return;
            }
            if ("RELEASED".equals(hold.state)) {
                respond(exchange, 409, error("reservation was released"));
                return;
            }

            Wallet source = walletsById.get(sourceWalletId);
            Wallet destination = walletsById.get(destinationWalletId);
            if (destination == null) {
                respond(exchange, 404, error("destination wallet not found"));
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
                // The real service answers 404. The saga releases even when unsure a hold
                // exists, and treats this as "nothing outstanding".
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
            respond(exchange, 200, hold.toJson(availableMinorUnlocked(hold.walletId)));
        }
    }

    /**
     * Extends a reservation's expiry.
     *
     * <p>Monotonic, exactly as the real service is: a request for a shorter lifetime never shortens an
     * expiry that is already further out. That property matters because the saga extends twice — once
     * when it parks a payment and again when an analyst releases it — and a naive stub that simply
     * overwrote the expiry would let the second call silently shorten the first.
     */
    private void handleExtend(HttpExchange exchange) throws IOException {
        extendCalls.incrementAndGet();

        if (Boolean.TRUE.equals(rejectNextExtend.getAndSet(false))) {
            respond(exchange, 503, error("injected extend failure"));
            return;
        }

        JsonNode body = readBody(exchange);
        String paymentId = body.get("paymentId").asString();
        long ttlSeconds = body.get("ttlSeconds").asLong();

        synchronized (lock) {
            Hold hold = holdsByPayment.get(paymentId);
            if (hold == null) {
                respond(exchange, 404, error("no reservation for this payment"));
                return;
            }
            if (!"ACTIVE".equals(hold.state)) {
                // Extending a captured or released hold would claim funds are reserved when they are
                // not. The real service answers 409 and so does this.
                respond(exchange, 409, error("reservation is " + hold.state));
                return;
            }
            Instant candidate = Instant.now().plusSeconds(ttlSeconds);
            if (candidate.isAfter(hold.expiresAt)) {
                hold.expiresAt = candidate;
            }
            respond(exchange, 200, hold.toJson(availableMinorUnlocked(hold.walletId)));
        }
    }

    // ------------------------------------------------------------------ fraud

    /**
     * Stands in for fraud-service's risk assessment.
     *
     * <p>Deliberately dumb: the verdict is whatever a test armed. What the orchestrator's tests are
     * about is what the saga <em>does</em> with a verdict — continue, park with the funds still
     * reserved, or refuse and compensate — and reproducing the rule engine and the model here would
     * make every saga test also a fraud test, so one retuned threshold would fail forty saga
     * assertions.
     *
     * <p>Idempotent on the payment id, as the real service is: a second call returns the verdict
     * already given. Without that the saga's own idempotency could look correct while resting on a
     * stub that happened to answer consistently.
     */
    private void handleFraud(HttpExchange exchange) throws IOException {
        countAuth(exchange);
        String path = exchange.getRequestURI().getPath();

        try {
            if (!path.equals("/internal/fraud/assess")) {
                respond(exchange, 404, error("no such endpoint"));
                return;
            }
            assessCalls.incrementAndGet();

            Integer injected = failNextAssessment.getAndSet(null);
            if (injected != null) {
                respond(exchange, injected, error("injected fraud failure"));
                return;
            }

            JsonNode body = readBody(exchange);
            String paymentId = body.get("paymentId").asString();

            synchronized (lock) {
                Verdict existing = verdictsByPayment.get(paymentId);
                if (existing != null) {
                    respond(exchange, 200, existing.toJson(paymentId));
                    return;
                }
                Verdict verdict = nextVerdict.getAndSet(null);
                if (verdict == null) {
                    verdict = Verdict.approve();
                }
                verdictsByPayment.put(paymentId, verdict);
                respond(exchange, 200, verdict.toJson(paymentId));
            }
        } catch (RuntimeException ex) {
            respond(exchange, 500, error("stub failure: " + ex.getMessage()));
        }
    }

    // ----------------------------------------------------------------- ledger

    private void handleLedger(HttpExchange exchange) throws IOException {
        countAuth(exchange);
        String path = exchange.getRequestURI().getPath();

        try {
            if (path.equals("/internal/ledger/postings/for-payment")) {
                handleBookPayment(exchange);
                return;
            }
            if (path.startsWith("/internal/ledger/postings/") && path.endsWith("/reverse")) {
                String key = path.substring("/internal/ledger/postings/".length(),
                        path.length() - "/reverse".length());
                handleReverse(exchange, key);
                return;
            }
            respond(exchange, 404, error("no such endpoint"));
        } catch (RuntimeException ex) {
            respond(exchange, 500, error("stub failure: " + ex.getMessage()));
        }
    }

    /**
     * Books a completed payment, deriving the transaction key exactly as ledger-service does.
     *
     * <p>The derivation is the point. The saga posts synchronously and the {@code payment-completed}
     * event books the same payment again later; both must land on the same key so the second is
     * recognised as a replay rather than double-booking the transfer.
     */
    private void handleBookPayment(HttpExchange exchange) throws IOException {
        postCalls.incrementAndGet();

        Integer injected = failNextPosting.getAndSet(null);
        if (injected != null) {
            respond(exchange, injected, error("injected posting failure"));
            return;
        }

        JsonNode body = readBody(exchange);
        String paymentId = body.get("paymentId").asString();
        JsonNode fee = body.get("fee");
        long feeMinor = fee == null || fee.isNull() ? 0L : fee.get("amountMinor").asLong();
        long amountMinor = body.get("amount").get("amountMinor").asLong();
        String currency = body.get("amount").get("currency").asString();

        String key = "LTX-PAY-" + paymentId;
        bookedFeesMinor.add(feeMinor);

        synchronized (lock) {
            Posting existing = postingsByKey.get(key);
            if (existing != null) {
                respond(exchange, 200, postingJson(existing, paymentId, amountMinor, currency, true));
                return;
            }
            Posting posting = new Posting(key, journalSequence.incrementAndGet());
            postingsByKey.put(key, posting);
            respond(exchange, 201, postingJson(posting, paymentId, amountMinor, currency, false));
        }
    }

    private void handleReverse(HttpExchange exchange, String key) throws IOException {
        reverseCalls.incrementAndGet();

        if (Boolean.TRUE.equals(rejectNextReversal.getAndSet(false))) {
            respond(exchange, 503, error("injected reversal failure"));
            return;
        }

        synchronized (lock) {
            Posting posting = postingsByKey.get(key);
            if (posting == null) {
                respond(exchange, 404, error("no such posting"));
                return;
            }
            if (posting.reversed) {
                // The real service enforces one reversal per posting with a partial unique index.
                respond(exchange, 409, error("already reversed"));
                return;
            }
            posting.reversed = true;
            respond(exchange, 201, """
                    {"journalSequence":%d,"ledgerTransactionId":"%s","paymentId":null,
                     "postingType":"REVERSAL",
                     "total":{"amount":0.00,"amountMinor":0,"currency":"INR"},
                     "duplicate":false}
                    """.formatted(journalSequence.incrementAndGet(), key + "-REV"));
        }
    }

    // --------------------------------------------------------------- plumbing

    /**
     * Counts unauthenticated internal calls rather than rejecting them.
     *
     * <p>A test can then assert the count is zero and get a clear failure, instead of an unrelated
     * 401 surfacing as a saga that mysteriously would not route.
     */
    private void countAuth(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            requestsWithoutAuth.incrementAndGet();
        }
    }

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
     * <p>Via {@link BigDecimal}, never a double: {@code 0.01} has no exact double representation, so
     * a double round-trip here would introduce the very rounding error the platform's integer money
     * representation exists to prevent — inside the harness, where it would look like a production
     * bug.
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

    private static String postingJson(Posting posting, String paymentId, long amountMinor,
                                      String currency, boolean duplicate) {
        return """
                {"journalSequence":%d,"ledgerTransactionId":"%s","paymentId":"%s",
                 "postingType":"PAYMENT_TRANSFER",
                 "total":{"amount":%s,"amountMinor":%d,"currency":"%s"},
                 "duplicate":%s}
                """.formatted(posting.journalSequence, posting.key, paymentId,
                decimal(amountMinor), amountMinor, currency, duplicate);
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

        /**
         * Tracked as real state rather than computed on each render.
         *
         * <p>The original stub reported {@code now() + 900s} every time it serialised a hold, which
         * would make an extension test pass whether or not the extension did anything.
         */
        private Instant expiresAt = Instant.now().plusSeconds(900);

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
                    state, expiresAt,
                    decimal(availableAfterMinor), availableAfterMinor, currency);
        }
    }

    /**
     * A canned risk verdict.
     *
     * <p>Shaped exactly like {@code FraudDtos.AssessmentView}, including {@code degradedMode}, because
     * the saga reads that flag and records it on its own row: a LOW verdict reached without the model
     * means "nothing was found", not "nothing is there", and a stub that omitted the field would let
     * the distinction go untested.
     */
    private record Verdict(String riskLevel, String decision, double probability, String reason,
                           boolean degraded) {

        private static Verdict approve() {
            return new Verdict("LOW", "APPROVE", 0.02,
                    "No fraud rules matched and the model scored this payment as routine", false);
        }

        private String toJson(String paymentId) {
            return """
                    {"assessmentId":"FRA-STUB-%s","paymentId":"%s","transactionId":null,
                     "fraudProbability":%s,"riskScore":%d,
                     "riskLevel":"%s","decision":"%s",
                     "amount":null,
                     "reasons":["%s"],"ruleHits":[],
                     "modelVersion":%s,"scoringLatencyMs":5,"degradedMode":%s,
                     "evaluatedAt":"%s"}
                    """.formatted(
                    paymentId.length() > 12 ? paymentId.substring(4, 12) : paymentId,
                    paymentId,
                    BigDecimal.valueOf(probability).toPlainString(),
                    Math.round(probability * 100),
                    riskLevel, decision,
                    reason,
                    degraded ? "null" : "\"fraud_hgb-stub\"",
                    degraded,
                    Instant.now());
        }
    }

    private static final class Posting {
        private final String key;
        private final long journalSequence;
        private boolean reversed;

        private Posting(String key, long journalSequence) {
            this.key = key;
            this.journalSequence = journalSequence;
        }
    }
}
