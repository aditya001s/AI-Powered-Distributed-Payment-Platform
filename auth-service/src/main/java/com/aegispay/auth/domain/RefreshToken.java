package com.aegispay.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token, stored as a hash and rotated on every use.
 *
 * <p><strong>Why only the hash is stored.</strong> A refresh token is a long-lived
 * bearer credential. If the table were dumped (SQL injection, a leaked backup, an
 * over-broad read replica grant) plaintext tokens would let an attacker mint access
 * tokens for every user until each one expired. Storing SHA-256 makes a stolen
 * table useless: the value presented by the client is hashed and compared, exactly
 * like a password. SHA-256 rather than BCrypt is appropriate here because the input
 * is 256 bits of cryptographic randomness, so there is nothing to brute force and
 * lookup must be fast.
 *
 * <p><strong>Rotation and reuse detection.</strong> Each refresh consumes the
 * presented token and issues a new one, linked by {@code familyId}. If a
 * <em>consumed</em> token is presented again, either the client is retrying or the
 * token was stolen and replayed. The safe interpretation is theft, so the whole
 * family is revoked: the legitimate user is logged out and must sign in again,
 * which is a far better outcome than an attacker holding a valid session
 * indefinitely. This is the standard OAuth2 refresh-token-rotation defence.
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_refresh_user", columnList = "user_id"),
        @Index(name = "idx_refresh_family", columnList = "family_id"),
        @Index(name = "idx_refresh_expires", columnList = "expires_at")
})
public class RefreshToken {

    public enum State {
        /** Valid and unused. */
        ACTIVE,
        /** Exchanged for a new token. Presenting it again signals replay. */
        CONSUMED,
        /** Invalidated by logout, password change, or family revocation. */
        REVOKED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** SHA-256 hex of the token. The token itself is never persisted. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Public user ID this token authenticates. */
    @Column(name = "user_id", nullable = false, length = 40)
    private String userId;

    /**
     * Groups every token descended from one login. Revoking a family logs out that
     * session everywhere without touching the user's other devices.
     */
    @Column(name = "family_id", nullable = false, length = 40)
    private String familyId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state = State.ACTIVE;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 120)
    private String revokedReason;

    /** Device/agent context, so a user can review and revoke their sessions. */
    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    protected RefreshToken() {
        // JPA
    }

    /** Issues the first token of a new family (a fresh login). */
    public static RefreshToken issueNewFamily(String tokenHash, String userId,
                                              Instant expiresAt, String userAgent, String ipAddress) {
        return issue(tokenHash, userId, "FAM-" + UUID.randomUUID(), expiresAt, userAgent, ipAddress);
    }

    /** Issues a successor within an existing family (a rotation). */
    public static RefreshToken issue(String tokenHash, String userId, String familyId,
                                     Instant expiresAt, String userAgent, String ipAddress) {
        RefreshToken token = new RefreshToken();
        token.id = UUID.randomUUID();
        token.tokenHash = tokenHash;
        token.userId = userId;
        token.familyId = familyId;
        token.state = State.ACTIVE;
        token.issuedAt = Instant.now();
        token.expiresAt = expiresAt;
        token.userAgent = truncate(userAgent, 256);
        token.ipAddress = truncate(ipAddress, 64);
        return token;
    }

    public boolean isUsable() {
        return state == State.ACTIVE && Instant.now().isBefore(expiresAt);
    }

    public boolean isExpired() {
        return !Instant.now().isBefore(expiresAt);
    }

    /** Marks this token as exchanged. Called inside the rotation transaction. */
    public void consume() {
        this.state = State.CONSUMED;
        this.consumedAt = Instant.now();
    }

    public void revoke(String reason) {
        this.state = State.REVOKED;
        this.revokedAt = Instant.now();
        this.revokedReason = truncate(reason, 120);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getUserId() {
        return userId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public State getState() {
        return state;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getRevokedReason() {
        return revokedReason;
    }
}
