package com.aegispay.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable hash of the semantically-significant parts of a request.
 *
 * <p>An idempotency key alone is not enough. If a client sends key {@code K} with
 * "transfer ₹2,000 to Bob" and later reuses {@code K} for "transfer ₹50,000 to
 * Carol", returning the first response would be wrong (the second transfer never
 * happens, silently) and executing it would also be wrong (the key was supposed
 * to prevent a second charge). The only correct answer is to reject the second
 * request as a client bug, which requires knowing the payloads differ.
 *
 * <p>The fingerprint is computed from named business fields rather than from the
 * raw JSON body, so cosmetically different but semantically identical requests
 * (whitespace, key order, an added client-only field) still match. SHA-256 is used
 * because a collision would let a genuinely different request be mistaken for a
 * replay.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /**
     * Hashes an ordered list of field values.
     *
     * <p>Values are length-prefixed before concatenation so that
     * {@code ("ab", "c")} and {@code ("a", "bc")} cannot produce the same digest.
     *
     * @param values business-significant values, in a fixed order defined by the caller
     * @return lowercase hex SHA-256 digest
     */
    public static String of(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = value == null ? "\u0000" : value.toString();
            canonical.append(text.length()).append(':').append(text).append('|');
        }
        return sha256(canonical.toString());
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Constant-time comparison.
     *
     * <p>Both operands are server-side digests here, so a timing side channel is
     * not a realistic threat. Used anyway because comparing digests with
     * {@code equals} is a habit worth not forming.
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
