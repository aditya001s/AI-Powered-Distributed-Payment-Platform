package com.aegispay.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link RefreshToken} aggregate.
 *
 * <p>Focused on the state machine that makes rotation and theft detection work: a
 * token must be usable exactly once, and a consumed token must remain distinguishable
 * from a revoked or expired one, because those three cases call for different
 * responses.
 */
class RefreshTokenTest {

    private static final String USER_ID = "USR-ABC123";
    private static final String HASH = "a".repeat(64);

    @Test
    @DisplayName("a newly issued token is usable and starts a new family")
    void newTokenIsUsable() {
        RefreshToken token = RefreshToken.issueNewFamily(
                HASH, USER_ID, future(), "Mozilla/5.0", "203.0.113.7");

        assertThat(token.isUsable()).isTrue();
        assertThat(token.isExpired()).isFalse();
        assertThat(token.getState()).isEqualTo(RefreshToken.State.ACTIVE);
        assertThat(token.getUserId()).isEqualTo(USER_ID);
        assertThat(token.getFamilyId()).startsWith("FAM-");
        assertThat(token.getId()).isNotNull();
    }

    @Test
    @DisplayName("each login starts a distinct family")
    void separateLoginsGetSeparateFamilies() {
        // Distinct families are what let one device be signed out without
        // disturbing the user's other sessions.
        RefreshToken first = RefreshToken.issueNewFamily(HASH, USER_ID, future(), null, null);
        RefreshToken second = RefreshToken.issueNewFamily("b".repeat(64), USER_ID, future(), null, null);

        assertThat(first.getFamilyId()).isNotEqualTo(second.getFamilyId());
    }

    @Test
    @DisplayName("a rotated successor stays in the same family")
    void rotationPreservesFamily() {
        RefreshToken original = RefreshToken.issueNewFamily(HASH, USER_ID, future(), null, null);
        RefreshToken successor = RefreshToken.issue(
                "c".repeat(64), USER_ID, original.getFamilyId(), future(), null, null);

        assertThat(successor.getFamilyId()).isEqualTo(original.getFamilyId());
        assertThat(successor.getTokenHash()).isNotEqualTo(original.getTokenHash());
    }

    /**
     * The core of reuse detection: once consumed, the token is no longer usable but
     * is still <em>present</em> with a CONSUMED state. If consumption deleted the row
     * instead, a replay would look like an unknown token and the theft signal would be
     * lost.
     */
    @Test
    @DisplayName("consuming a token makes it unusable but keeps it detectable")
    void consumedTokenIsUnusableButRetained() {
        RefreshToken token = RefreshToken.issueNewFamily(HASH, USER_ID, future(), null, null);

        token.consume();

        assertThat(token.isUsable()).isFalse();
        assertThat(token.getState()).isEqualTo(RefreshToken.State.CONSUMED);
        assertThat(token.getConsumedAt()).isNotNull();
    }

    @Test
    @DisplayName("revoking records the reason")
    void revocationRecordsReason() {
        RefreshToken token = RefreshToken.issueNewFamily(HASH, USER_ID, future(), null, null);

        token.revoke("Password changed");

        assertThat(token.isUsable()).isFalse();
        assertThat(token.getState()).isEqualTo(RefreshToken.State.REVOKED);
        assertThat(token.getRevokedReason()).isEqualTo("Password changed");
    }

    @Test
    @DisplayName("an expired token is not usable even while ACTIVE")
    void expiredTokenIsNotUsable() {
        // Expiry is evaluated against the clock, so a token expires on time whether
        // or not the retention sweep has run.
        RefreshToken token = RefreshToken.issueNewFamily(
                HASH, USER_ID, Instant.now().minus(1, ChronoUnit.MINUTES), null, null);

        assertThat(token.getState()).isEqualTo(RefreshToken.State.ACTIVE);
        assertThat(token.isExpired()).isTrue();
        assertThat(token.isUsable()).isFalse();
    }

    @Test
    @DisplayName("truncates oversized client metadata to fit its column")
    void truncatesOversizedMetadata() {
        // User-Agent is attacker-controlled. Truncating here prevents a database
        // error from a 10KB header turning into a failed login.
        RefreshToken token = RefreshToken.issueNewFamily(
                HASH, USER_ID, future(), "U".repeat(500), "1".repeat(200));

        assertThat(token.getUserAgent()).hasSize(256);
        assertThat(token.getIpAddress()).hasSize(64);
    }

    @Test
    @DisplayName("tolerates absent client metadata")
    void toleratesNullMetadata() {
        RefreshToken token = RefreshToken.issueNewFamily(HASH, USER_ID, future(), null, null);

        assertThat(token.getUserAgent()).isNull();
        assertThat(token.getIpAddress()).isNull();
        assertThat(token.isUsable()).isTrue();
    }

    private Instant future() {
        return Instant.now().plus(30, ChronoUnit.DAYS);
    }
}
