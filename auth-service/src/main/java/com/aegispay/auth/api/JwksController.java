package com.aegispay.auth.api;

import com.nimbusds.jose.jwk.JWKSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the JSON Web Key Set.
 *
 * <p>This is the mechanism that lets nine other services validate tokens without
 * ever holding signing material. Each service is configured with
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} pointing here,
 * fetches the public key on first use, and caches it.
 *
 * <p>Unauthenticated by necessity: requiring a token to fetch the key needed to
 * validate tokens would be circular. That is safe because the response contains only
 * public key material — the modulus and exponent, from which the private key cannot
 * be derived.
 */
@RestController
@Tag(name = "JWKS", description = "Public keys for token validation")
public class JwksController {

    /**
     * Cache window advertised to clients.
     *
     * <p>Long enough that validators are not re-fetching constantly, short enough
     * that a rotated key propagates within minutes. Spring's
     * {@code NimbusJwtDecoder} also refreshes on encountering an unknown {@code kid},
     * so rotation works even mid-window.
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final JWKSet jwkSet;

    public JwksController(JWKSet jwkSet) {
        this.jwkSet = jwkSet;
    }

    @GetMapping(path = {"/.well-known/jwks.json", "/oauth2/jwks"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get the JSON Web Key Set",
            description = """
                    Public RSA keys used to verify AegisPay access tokens.

                    Contains no private key material. Every other service points its
                    resource-server jwk-set-uri here, which is why no service besides
                    auth-service can mint a token.""")
    public ResponseEntity<Map<String, Object>> jwks() {
        // toJSONObject(true) exports PUBLIC parameters only. Passing false here
        // would serialise the private exponent and hand out the signing key.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .body(jwkSet.toJSONObject(true));
    }
}
