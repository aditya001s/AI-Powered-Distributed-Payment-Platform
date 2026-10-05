package com.aegispay.common.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints the access tokens used by integration tests.
 *
 * <p>Signs real RS256 JWTs with a keypair generated once per JVM, and publishes the
 * matching public key through {@link TestJwkServer}. Tests therefore authenticate the
 * same way production does: the service under test fetches a JWKS over HTTP, verifies the
 * signature, checks the issuer and expiry, and maps the {@code roles} and {@code scope}
 * claims to authorities through the platform's own
 * {@link com.aegispay.common.security.JwtAuthenticationConverter}.
 *
 * <p><strong>Why not {@code @WithMockUser} or a stubbed {@code JwtDecoder}.</strong> Both
 * bypass token validation entirely, so neither can catch a service that accepts an expired
 * token, ignores the issuer, fails to require {@code SCOPE_internal} on
 * {@code /internal/**}, or reads the user ID from somewhere other than {@code sub}. Those
 * are precisely the failures worth testing, and every one of them is invisible to a mocked
 * security context.
 *
 * <p>{@link #forged} exists for the same reason: a token that is well-formed and carries
 * every expected claim but is signed by a <em>different</em> key must be rejected. Without
 * that case, a service that skipped signature verification would pass every other test in
 * the suite.
 *
 * <p>Lives in this shared module so all ten services authenticate their tests identically.
 * Eight copies of this file would drift, and a drifted test helper quietly stops testing
 * the thing it was written for.
 */
public final class TestJwtIssuer {

    /**
     * Must match {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, which every
     * service defaults to this value. A mismatch is rejected by the issuer validator, which
     * is the point: an attacker with a valid token from a different issuer must not be able
     * to use it here.
     */
    public static final String ISSUER = "https://auth.aegispay.local";

    private static final String KEY_ID = "aegis-test-key";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    /** The keypair the service under test will trust, via the published JWKS. */
    private static final RSAKey SIGNING_KEY = generateKey(KEY_ID);

    /**
     * A second keypair that is deliberately <em>never</em> published. Tokens signed with it
     * are cryptographically valid but untrusted, which is how {@link #forged} proves that
     * signature verification actually runs.
     */
    private static final RSAKey UNTRUSTED_KEY = generateKey("attacker-key");

    private TestJwtIssuer() {
    }

    // ------------------------------------------------------------------- keys

    /** The public JWK set, serialised exactly as the auth service's endpoint returns it. */
    public static String jwkSetJson() {
        return new JWKSet(SIGNING_KEY.toPublicJWK()).toString();
    }

    // ----------------------------------------------------------------- tokens

    /**
     * A customer token.
     *
     * @param userId becomes the {@code sub} claim, which is what
     *               {@link com.aegispay.common.security.CurrentUser#requireUserId()} reads
     */
    public static String user(String userId, String... roles) {
        String[] effective = roles.length == 0 ? new String[]{"USER"} : roles;
        return sign(SIGNING_KEY, claims(userId, userId + "@example.com", DEFAULT_TTL)
                .claim("roles", List.of(effective))
                .build());
    }

    /**
     * A machine token for {@code /internal/**}.
     *
     * <p>Carries both {@code ROLE_SERVICE} and {@code scope=internal}, because the shared
     * security chain requires both. A token with only the role must not get in, which
     * {@link #serviceTokenWithoutScope} exercises.
     */
    public static String service(String clientId) {
        return sign(SIGNING_KEY, claims(clientId, null, DEFAULT_TTL)
                .claim("roles", List.of("SERVICE"))
                .claim("scope", "internal")
                .build());
    }

    /** A machine token missing {@code scope=internal}. Must be refused by /internal/**. */
    public static String serviceTokenWithoutScope(String clientId) {
        return sign(SIGNING_KEY, claims(clientId, null, DEFAULT_TTL)
                .claim("roles", List.of("SERVICE"))
                .build());
    }

    /** A token whose {@code exp} is already in the past. */
    public static String expired(String userId, String... roles) {
        String[] effective = roles.length == 0 ? new String[]{"USER"} : roles;
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        return sign(SIGNING_KEY, new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt))
                // One hour in the past, comfortably beyond the 60s default clock skew
                // Spring Security allows.
                .expirationTime(Date.from(issuedAt.plus(Duration.ofHours(1))))
                .claim("typ", "access")
                .claim("roles", List.of(effective))
                .build());
    }

    /** A token issued by someone else's key. Structurally perfect, must still be rejected. */
    public static String forged(String userId, String... roles) {
        String[] effective = roles.length == 0 ? new String[]{"USER"} : roles;
        return sign(UNTRUSTED_KEY, claims(userId, userId + "@example.com", DEFAULT_TTL)
                .claim("roles", List.of(effective))
                .build());
    }

    /** A token from a different issuer. Signed by the trusted key, so only {@code iss} differs. */
    public static String wrongIssuer(String userId) {
        return sign(SIGNING_KEY, new JWTClaimsSet.Builder()
                .issuer("https://evil.example.com")
                .subject(userId)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(DEFAULT_TTL)))
                .claim("typ", "access")
                .claim("roles", List.of("USER"))
                .build());
    }

    // -------------------------------------------------------------- internals

    private static JWTClaimsSet.Builder claims(String subject, String email, Duration ttl) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .claim("typ", "access");
        if (email != null) {
            builder.claim("email", email);
        }
        return builder;
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign the test token", ex);
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not generate a test signing key", ex);
        }
    }

    /** Convenience for building a role array inline. */
    public static String[] roles(String... roles) {
        return Arrays.copyOf(roles, roles.length);
    }
}
