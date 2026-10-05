package com.aegispay.common.testsupport;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Serves {@link TestJwtIssuer}'s public key as a JWKS document over real HTTP.
 *
 * <p>Integration tests point
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} at {@link #jwkSetUri()},
 * so the service under test resolves its trusted keys exactly as it does in production —
 * an HTTP fetch, a parsed JWK set, and a signature check against it.
 *
 * <p><strong>Why a real server rather than a stubbed {@code JwtDecoder} bean.</strong>
 * Replacing the decoder replaces the code under test. Spring Boot assembles the decoder
 * from {@code jwk-set-uri}, {@code issuer-uri} and the allowed algorithms, and adds the
 * issuer and timestamp validators; a hand-built stub silently discards all of that. Every
 * one of those pieces is a control that stops a forged, expired or foreign token from being
 * accepted, so they belong inside the test boundary, not outside it.
 *
 * <p>One server per JVM, started on first use and bound to an ephemeral loopback port so
 * parallel builds on one machine cannot collide. It is never stopped: the daemon executor
 * and the JVM's own exit take care of that, and a shutdown hook would only add a way for
 * the suite to fail during teardown.
 */
public final class TestJwkServer {

    private static volatile HttpServer server;

    private TestJwkServer() {
    }

    /** The JWKS URL, starting the server on first call. */
    public static String jwkSetUri() {
        return "http://localhost:" + port() + "/.well-known/jwks.json";
    }

    public static int port() {
        return start().getAddress().getPort();
    }

    private static HttpServer start() {
        HttpServer current = server;
        if (current != null) {
            return current;
        }
        synchronized (TestJwkServer.class) {
            if (server != null) {
                return server;
            }
            try {
                // Port 0: let the OS pick a free one, so concurrent module builds on the
                // same machine cannot fight over a fixed port.
                HttpServer created = HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

                created.createContext("/", exchange -> {
                    byte[] body = TestJwtIssuer.jwkSetJson().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    // No caching: a decoder that cached a stale key set would hide a real
                    // key-rotation bug.
                    exchange.getResponseHeaders().add("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });

                created.setExecutor(null); // default executor: a single daemon-ish thread
                created.start();
                server = created;
                return created;
            } catch (IOException ex) {
                throw new IllegalStateException("Could not start the test JWKS server", ex);
            }
        }
    }
}
