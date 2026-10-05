package com.aegispay.common.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the idempotency request fingerprint.
 *
 * <p>The fingerprint decides whether a replayed idempotency key represents the same
 * request (return the stored response) or a different one (reject as a client bug).
 * Getting that wrong in either direction is a money bug: a false match silently
 * swallows a genuine second payment, and a false mismatch rejects a legitimate retry.
 */
class RequestFingerprintTest {

    @Test
    @DisplayName("is stable for identical inputs")
    void isDeterministic() {
        String first = RequestFingerprint.of("USR-1", "WAL-A", "WAL-B", 200_000L, "INR");
        String second = RequestFingerprint.of("USR-1", "WAL-A", "WAL-B", 200_000L, "INR");

        assertThat(first).isEqualTo(second);
        // SHA-256 hex
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("changes when the amount changes")
    void detectsChangedAmount() {
        String original = RequestFingerprint.of("USR-1", "WAL-A", "WAL-B", 200_000L, "INR");
        String tampered = RequestFingerprint.of("USR-1", "WAL-A", "WAL-B", 5_000_000L, "INR");

        // The exact scenario the fingerprint exists for: same idempotency key reused
        // for "₹2,000 to Bob" and then "₹50,000 to Carol".
        assertThat(original).isNotEqualTo(tampered);
    }

    @Test
    @DisplayName("changes when the destination changes")
    void detectsChangedDestination() {
        assertThat(RequestFingerprint.of("USR-1", "WAL-A", "WAL-B", 200_000L, "INR"))
                .isNotEqualTo(RequestFingerprint.of("USR-1", "WAL-A", "WAL-C", 200_000L, "INR"));
    }

    /**
     * Length-prefixing is what prevents this collision. Naive concatenation would
     * render both of these as {@code "abc"} and treat two different requests as
     * identical.
     */
    @Test
    @DisplayName("is not fooled by ambiguous field boundaries")
    void resistsFieldBoundaryCollisions() {
        assertThat(RequestFingerprint.of("ab", "c"))
                .isNotEqualTo(RequestFingerprint.of("a", "bc"));

        assertThat(RequestFingerprint.of("WAL-A", "WAL-BB"))
                .isNotEqualTo(RequestFingerprint.of("WAL-AW", "AL-BB"));
    }

    @Test
    @DisplayName("distinguishes null from an empty string")
    void distinguishesNullFromEmpty() {
        // "no description supplied" and "description deliberately blank" are
        // different requests and must not share a fingerprint.
        assertThat(RequestFingerprint.of("USR-1", null))
                .isNotEqualTo(RequestFingerprint.of("USR-1", ""));
    }

    @Test
    @DisplayName("is order sensitive")
    void isOrderSensitive() {
        // Swapping source and destination reverses the direction of the transfer.
        assertThat(RequestFingerprint.of("WAL-A", "WAL-B"))
                .isNotEqualTo(RequestFingerprint.of("WAL-B", "WAL-A"));
    }

    @Test
    @DisplayName("matches equal digests and rejects unequal or absent ones")
    void comparesDigests() {
        String digest = RequestFingerprint.sha256("payload");

        assertThat(RequestFingerprint.matches(digest, digest)).isTrue();
        assertThat(RequestFingerprint.matches(digest, RequestFingerprint.sha256("other"))).isFalse();

        // A missing stored fingerprint must never be treated as a match, or an
        // unknown key would be accepted as a replay.
        assertThat(RequestFingerprint.matches(null, digest)).isFalse();
        assertThat(RequestFingerprint.matches(digest, null)).isFalse();
        assertThat(RequestFingerprint.matches(null, null)).isFalse();
    }

    @Test
    @DisplayName("produces the known SHA-256 digest for a known input")
    void matchesKnownVector() {
        // Pins the algorithm itself: if someone swapped SHA-256 for another hash,
        // every stored fingerprint would silently stop matching after deployment.
        assertThat(RequestFingerprint.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
