package com.aegispay.notification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for auth-service's internal identity endpoint.
 *
 * <p>Real HTTP on a loopback port rather than a mocked {@code RestClient}. The interesting behaviour is in the
 * bytes: whether the path is right, whether the JSON deserialises into this service's locally-declared view,
 * and whether an unknown user produces a 404 that the resolver survives. A mock would let all of that be wrong
 * while the test passed.
 *
 * <p>It also serves the token endpoint, because {@code aegis.service-auth.enabled} stays true in these tests —
 * this service genuinely makes an authenticated internal call, so the real interceptor should run rather than
 * be configured away.
 */
final class StubAuthService {

    private final HttpServer server;
    private final Map<String, String> emails = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();

    /** When true, every identity lookup fails, to exercise the resolver's suppression path. */
    private volatile boolean failing;

    StubAuthService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("could not start the stub auth service", ex);
        }
        server.createContext("/internal/users/", this::handleUser);
        server.createContext("/api/auth/token", this::handleToken);
        server.setExecutor(null);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String tokenUri() {
        return baseUrl() + "/api/auth/token";
    }

    void stop() {
        server.stop(0);
    }

    /** Registers a user so the resolver can find an address for them. */
    void register(String userId, String email, String fullName) {
        emails.put(userId, email);
        names.put(userId, fullName);
    }

    void forget(String userId) {
        emails.remove(userId);
        names.remove(userId);
    }

    void reset() {
        emails.clear();
        names.clear();
        lookups.set(0);
        failing = false;
    }

    void setFailing(boolean failing) {
        this.failing = failing;
    }

    /** How many identity lookups were made, so caching can be asserted rather than assumed. */
    int lookups() {
        return lookups.get();
    }

    private void handleUser(HttpExchange exchange) throws IOException {
        lookups.incrementAndGet();

        String path = exchange.getRequestURI().getPath();
        String userId = path.substring(path.lastIndexOf('/') + 1);

        if (failing) {
            respond(exchange, 503, "{\"error\":\"identity service unavailable\"}");
            return;
        }
        String email = emails.get(userId);
        if (email == null) {
            respond(exchange, 404, "{\"error\":\"no such user\"}");
            return;
        }
        respond(exchange, 200, """
                {"userId":"%s","email":"%s","fullName":"%s","active":true}"""
                .formatted(userId, email, names.getOrDefault(userId, "")));
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        respond(exchange, 200, """
                {"access_token":"stub-service-token","token_type":"Bearer","expires_in":900}""");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
