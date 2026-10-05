package com.aegispay.auth.service;

import com.aegispay.auth.config.AuthProperties;
import com.aegispay.auth.domain.ServiceClient;
import com.aegispay.auth.domain.UserAccount;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mints signed access tokens and generates refresh-token secrets.
 *
 * <p>Claim design is deliberately minimal. Every claim is copied into a token that
 * is readable by anyone who obtains it (a JWT is signed, not encrypted), and every
 * claim becomes stale the moment the underlying data changes. So the token carries
 * only what an authorisation decision needs — identity, roles, scope — and never
 * mutable profile data such as balance, phone number or KYC documents.
 */
@Service
public class TokenIssuer {

    /** Claim carrying platform roles, read by the shared JwtAuthenticationConverter. */
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String CLAIM_SCOPE = "scope";

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_SERVICE = "service";

    /** 256 bits of entropy: infeasible to guess, and fits comfortably in a header. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JwtEncoder jwtEncoder;
    private final AuthProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * {@link SecureRandom} rather than {@code Random}. {@code Random} is a linear
     * congruential generator whose entire future output can be derived from a couple
     * of observed values, which would let an attacker predict other users' refresh
     * tokens. This is one of the few places where the wrong RNG is a critical
     * vulnerability rather than a style issue.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenIssuer(JwtEncoder jwtEncoder, AuthProperties properties, MeterRegistry meterRegistry) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /** Issues an access token for a human user. */
    public IssuedToken issueAccessToken(UserAccount account) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwt().getAccessTokenTtl());
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .audience(List.of(properties.getJwt().getAudience()))
                // sub = the immutable public user ID, never the email. Emails change;
                // authorisation and audit records must not silently follow.
                .subject(account.getUserId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // jti allows a specific token to be identified in audit logs and,
                // if ever needed, denylisted.
                .id(tokenId)
                .claim(CLAIM_ROLES, List.copyOf(account.getRoles()))
                .claim(CLAIM_EMAIL, account.getEmail())
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .build();

        String token = encode(claims);

        meterRegistry.counter(MetricNames.AUTH_TOKEN_ISSUED, "grantType", "password").increment();
        return new IssuedToken(token, tokenId, expiresAt,
                properties.getJwt().getAccessTokenTtl().toSeconds());
    }

    /**
     * Issues a machine token for the client-credentials grant.
     *
     * <p>Shorter-lived than a user token and carries no email. The {@code scope}
     * claim is what {@code /internal/**} endpoints check in addition to the role,
     * so a token minted for one purpose cannot be repurposed.
     */
    public IssuedToken issueServiceToken(ServiceClient client, Set<String> grantedScopes) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwt().getServiceTokenTtl());
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .audience(List.of(properties.getJwt().getAudience()))
                // sub = the client ID, so every internal call is attributable.
                .subject(client.getClientId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(tokenId)
                .claim(CLAIM_ROLES, List.copyOf(client.roleSet()))
                .claim(CLAIM_SCOPE, String.join(" ", grantedScopes))
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_SERVICE)
                .build();

        String token = encode(claims);

        meterRegistry.counter(MetricNames.AUTH_TOKEN_ISSUED, "grantType", "client_credentials").increment();
        return new IssuedToken(token, tokenId, expiresAt,
                properties.getJwt().getServiceTokenTtl().toSeconds());
    }

    /**
     * Generates a refresh-token secret.
     *
     * <p>Opaque random bytes, not a JWT. A refresh token needs no readable claims,
     * and making it opaque means it can be revoked server-side by deleting a row —
     * whereas a self-contained JWT is valid until it expires no matter what the
     * server thinks. Revocability is the entire point of a refresh token.
     */
    public String generateRefreshTokenSecret() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(JwtClaimsSet claims) {
        // RS256: asymmetric, so validators only ever need the public key.
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** A minted token plus the metadata callers need to build a response. */
    public record IssuedToken(String token, String tokenId, Instant expiresAt, long expiresInSeconds) {
    }
}
