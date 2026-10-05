package com.aegispay.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Signing keys and the JOSE encoder/decoder pair.
 *
 * <p><strong>Why asymmetric (RS256) rather than a shared HMAC secret.</strong>
 * With HS256 every service that validates a token also holds the key that can
 * <em>mint</em> one, so compromising the notification service would let an attacker
 * forge an admin token. With RS256 the private key never leaves this service, and
 * validators only ever see the public half over
 * {@code /.well-known/jwks.json}. Adding a tenth service requires no key
 * distribution at all.
 *
 * <p>Key rotation is supported by design: the JWK set can serve multiple keys, and
 * each token carries a {@code kid} header so validators pick the right one. New
 * tokens are signed with the current key while tokens signed by the previous key
 * remain valid until they expire.
 *
 * <p>PEM parsing is done with plain JDK APIs rather than a helper library, so this
 * code has no dependency on any particular Spring Security or BouncyCastle version.
 */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    private static final int GENERATED_KEY_SIZE = 2048;

    private final AuthProperties properties;
    private final String platformEnv;

    public JwtKeyConfig(AuthProperties properties,
                        @Value("${aegis.platform-env:local}") String platformEnv) {
        this.properties = properties;
        this.platformEnv = platformEnv;
    }

    /**
     * The JWK set this service signs with and publishes.
     *
     * <p>Refuses to fall back to an ephemeral key in Kubernetes. An ephemeral key
     * would mean every pod signs with a <em>different</em> key while all of them
     * publish only their own, so a token issued by pod A is rejected by every
     * validator that happened to fetch pod B's JWKS. That failure is intermittent
     * and extremely confusing to debug, so it is turned into a loud startup error.
     */
    @Bean
    public JWKSet jwkSet() {
        RSAKey rsaKey = loadOrGenerateKey();
        log.info("JWT signing key ready [kid={}, issuer={}]", rsaKey.getKeyID(), properties.getJwt().getIssuer());
        return new JWKSet(rsaKey);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(JWKSet jwkSet) {
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Local decoder, used when this service validates tokens presented to its own
     * protected endpoints (for example {@code GET /api/users/me}).
     *
     * <p>It verifies against the in-memory public key rather than fetching its own
     * JWKS endpoint over HTTP, which would be a pointless network round trip to
     * itself and would make the service depend on its own availability.
     */
    @Bean
    public JwtDecoder jwtDecoder(JWKSet jwkSet) throws Exception {
        RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                .createDefaultWithIssuer(properties.getJwt().getIssuer()));
        return decoder;
    }

    private RSAKey loadOrGenerateKey() {
        String privateLocation = properties.getJwt().getPrivateKeyLocation();

        if (privateLocation == null || privateLocation.isBlank()) {
            if ("kubernetes".equalsIgnoreCase(platformEnv)) {
                throw new IllegalStateException("""
                        No JWT signing key configured (aegis.auth.jwt.private-key-location is blank) \
                        and aegis.platform-env=kubernetes.

                        Generating an ephemeral key would give every replica a different signing key, \
                        so tokens issued by one pod would be rejected by validators that fetched \
                        another pod's JWKS. Mount an RSA private key from a Secret and set \
                        JWT_PRIVATE_KEY_LOCATION.""");
            }
            log.warn("""
                    No JWT signing key configured; generating an EPHEMERAL {}-bit RSA key pair. \
                    All issued tokens become invalid when this service restarts. \
                    Acceptable for local development only.""", GENERATED_KEY_SIZE);
            return generateKey();
        }

        try {
            RSAPrivateKey privateKey = readPrivateKey(privateLocation);
            RSAPublicKey publicKey = resolvePublicKey(privateKey);
            // Deterministic kid derived from the key's modulus, so every replica
            // that loads the same key advertises the same kid.
            String keyId = deriveKeyId(publicKey);
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to load the JWT signing key from '" + privateLocation
                            + "'. Expected an unencrypted PKCS#8 PEM "
                            + "(-----BEGIN PRIVATE KEY-----).", ex);
        }
    }

    /**
     * Reads the configured public key, or derives it from the private key.
     *
     * <p>An RSA private key in CRT form already contains the modulus and public
     * exponent, so a separate public key file is redundant. Supporting both means
     * an operator only has to mount one secret.
     */
    private RSAPublicKey resolvePublicKey(RSAPrivateKey privateKey) throws Exception {
        String publicLocation = properties.getJwt().getPublicKeyLocation();
        if (publicLocation != null && !publicLocation.isBlank()) {
            return readPublicKey(publicLocation);
        }
        if (privateKey instanceof RSAPrivateCrtKey crt) {
            RSAPublicKeySpec spec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        }
        throw new IllegalStateException(
                "The private key is not in CRT form, so the public key cannot be derived. "
                        + "Set aegis.auth.jwt.public-key-location explicitly.");
    }

    private RSAPrivateKey readPrivateKey(String location) throws IOException, NoSuchAlgorithmException,
            InvalidKeySpecException {
        byte[] der = decodePem(readResource(location), "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private RSAPublicKey readPublicKey(String location) throws IOException, NoSuchAlgorithmException,
            InvalidKeySpecException {
        byte[] der = decodePem(readResource(location), "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * Reads a key from a Spring resource location, or accepts the PEM inline.
     *
     * <p>Inline support exists because Kubernetes Secrets and {@code .env} files
     * commonly carry the PEM as a value with literal {@code \n} escapes rather than
     * as a mounted file.
     */
    private String readResource(String location) throws IOException {
        if (location.contains("BEGIN")) {
            return location.replace("\\n", "\n");
        }
        Resource resource = new DefaultResourceLoader().getResource(location);
        if (!resource.exists()) {
            throw new IOException("Key resource not found: " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Strips PEM armour and whitespace, then Base64-decodes the body. */
    private byte[] decodePem(String pem, String expectedType) {
        String normalised = pem
                .replace("-----BEGIN " + expectedType + "-----", "")
                .replace("-----END " + expectedType + "-----", "")
                .replace("-----BEGIN RSA " + expectedType + "-----", "")
                .replace("-----END RSA " + expectedType + "-----", "")
                .replaceAll("\\s", "");
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Base64 body found; expected a PEM block of type '" + expectedType + "'");
        }
        return Base64.getDecoder().decode(normalised);
    }

    private RSAKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA key generation is unavailable on this JVM", ex);
        }
    }

    /**
     * A stable key ID derived from the modulus. Two replicas loading the same key
     * file must advertise the same {@code kid}, or validators would reject tokens
     * from one of them.
     */
    private String deriveKeyId(RSAPublicKey publicKey) {
        BigInteger modulus = publicKey.getModulus();
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256").digest(modulus.toByteArray());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
    }
}
