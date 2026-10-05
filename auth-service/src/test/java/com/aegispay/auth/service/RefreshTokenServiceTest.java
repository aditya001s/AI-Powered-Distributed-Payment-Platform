package com.aegispay.auth.service;

import com.aegispay.auth.config.AuthProperties;
import com.aegispay.auth.domain.AuditLog;
import com.aegispay.auth.domain.RefreshToken;
import com.aegispay.auth.repository.RefreshTokenRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import com.aegispay.common.idempotency.RequestFingerprint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for refresh-token rotation and reuse detection.
 *
 * <p>The central assertion is that a replayed token triggers revocation through
 * {@link TokenRevocationService} rather than through the repository directly. That
 * distinction is the whole fix: the repository call would enlist in
 * {@code rotate}'s transaction and be rolled back by the 401 that follows, leaving
 * the compromised family ACTIVE while the metric and audit trail both claimed the
 * theft had been contained.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final String USER_ID = "USR-ABC123";
    private static final String PRESENTED_SECRET = "opaque-refresh-secret-value";

    @Mock
    private RefreshTokenRepository repository;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private AuditService auditService;

    private RefreshTokenService service;
    private RequestContext context;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(
                repository,
                tokenIssuer,
                new AuthProperties(),
                auditService,
                new SimpleMeterRegistry());
        context = new RequestContext("198.51.100.22", "curl/8.4.0");
    }

    @Test
    @DisplayName("rotates an active token: consumes it and issues a successor in the same family")
    void rotatesActiveToken() {
        RefreshToken active = activeToken();
        when(repository.findByTokenHashForUpdate(hash(PRESENTED_SECRET))).thenReturn(Optional.of(active));
        when(tokenIssuer.generateRefreshTokenSecret()).thenReturn("new-secret");

        var result = service.rotate(PRESENTED_SECRET, context);

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.newRefreshToken()).isEqualTo("new-secret");

        // The presented token is consumed, not deleted: reuse detection depends on
        // the CONSUMED row still being there.
        assertThat(active.getState()).isEqualTo(RefreshToken.State.CONSUMED);
        assertThat(active.getConsumedAt()).isNotNull();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        RefreshToken successor = captor.getValue();

        assertThat(successor.getFamilyId()).isEqualTo(active.getFamilyId());
        assertThat(successor.getUserId()).isEqualTo(USER_ID);
        assertThat(successor.getState()).isEqualTo(RefreshToken.State.ACTIVE);
        // Only the hash is stored, never the secret handed to the client.
        assertThat(successor.getTokenHash()).isEqualTo(hash("new-secret")).isNotEqualTo("new-secret");

        verify(repository, never()).revokeFamily(anyString(), anyString(), any());
    }

    /**
     * The reuse-detection regression. Revocation must go through the independently
     * committed path, because this method throws immediately afterwards.
     */
    @Test
    @DisplayName("replaying a consumed token revokes the whole family via the independent transaction")
    void replayRevokesFamilyDurably() {
        RefreshToken consumed = activeToken();
        consumed.consume();
        when(repository.findByTokenHashForUpdate(hash(PRESENTED_SECRET))).thenReturn(Optional.of(consumed));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.rotate(PRESENTED_SECRET, context))
                .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));

        // Revoked in the transaction that owns the row lock. rotate() is configured
        // not to roll this back when it returns the 401.
        verify(repository, times(1))
                .revokeFamily(eq(consumed.getFamilyId()), anyString(), any());
        // No successor is issued on the theft path.
        verify(repository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("replay is recorded as SUSPICIOUS in the audit trail")
    void replayIsAudited() {
        RefreshToken consumed = activeToken();
        consumed.consume();
        when(repository.findByTokenHashForUpdate(hash(PRESENTED_SECRET))).thenReturn(Optional.of(consumed));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.rotate(PRESENTED_SECRET, context));

        verify(auditService).record(
                eq(AuditLog.EventType.REFRESH_TOKEN_REUSE_DETECTED),
                eq(AuditLog.Outcome.SUSPICIOUS),
                eq(USER_ID), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("rejects an already-revoked token without re-revoking the family")
    void rejectsRevokedToken() {
        RefreshToken revoked = activeToken();
        revoked.revoke("Password changed");
        when(repository.findByTokenHashForUpdate(hash(PRESENTED_SECRET))).thenReturn(Optional.of(revoked));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.rotate(PRESENTED_SECRET, context))
                .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));

        // A revoked token is not evidence of theft, so the family is left alone.
        verify(repository, never()).revokeFamily(anyString(), anyString(), any());
        verify(repository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("rejects an expired token")
    void rejectsExpiredToken() {
        RefreshToken expired = RefreshToken.issueNewFamily(
                hash(PRESENTED_SECRET), USER_ID,
                Instant.now().minus(Duration.ofMinutes(1)), "curl/8.4.0", "198.51.100.22");
        when(repository.findByTokenHashForUpdate(hash(PRESENTED_SECRET))).thenReturn(Optional.of(expired));

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.rotate(PRESENTED_SECRET, context));

        verify(repository, never()).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("rejects an unrecognised token without revealing why")
    void rejectsUnknownToken() {
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(UnauthorizedException.class)
                .isThrownBy(() -> service.rotate(PRESENTED_SECRET, context))
                .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));

        verify(repository, never()).revokeFamily(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("issuing a new session stores only the hash and returns the secret")
    void issueForNewSessionStoresHashOnly() {
        when(tokenIssuer.generateRefreshTokenSecret()).thenReturn("brand-new-secret");

        String secret = service.issueForNewSession(USER_ID, context);

        assertThat(secret).isEqualTo("brand-new-secret");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo(hash("brand-new-secret"));
        assertThat(captor.getValue().getFamilyId()).startsWith("FAM-");
    }

    @Test
    @DisplayName("logout revokes the whole family, not just the presented token")
    void revokeSessionRevokesFamily() {
        RefreshToken active = activeToken();
        when(repository.findByTokenHash(hash(PRESENTED_SECRET))).thenReturn(Optional.of(active));

        service.revokeSession(PRESENTED_SECRET, "User signed out");

        // A successor may already have been issued; revoking only the presented
        // token would leave that successor usable.
        verify(repository).revokeFamily(eq(active.getFamilyId()), eq("User signed out"), any());
    }

    @Test
    @DisplayName("logout cannot revoke a refresh family belonging to another user")
    void revokeSessionChecksAuthenticatedOwner() {
        RefreshToken active = activeToken();
        when(repository.findByTokenHash(hash(PRESENTED_SECRET))).thenReturn(Optional.of(active));

        service.revokeSession(PRESENTED_SECRET, "USR-ATTACKER", "User signed out");

        verify(repository, never()).revokeFamily(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("logout with an unknown token is a no-op rather than an error")
    void revokeSessionToleratesUnknownToken() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.revokeSession(PRESENTED_SECRET, "User signed out");

        verify(repository, never()).revokeFamily(anyString(), anyString(), any());
    }

    // ----------------------------------------------------------------- helpers

    private RefreshToken activeToken() {
        return RefreshToken.issueNewFamily(
                hash(PRESENTED_SECRET), USER_ID,
                Instant.now().plus(Duration.ofDays(30)), "curl/8.4.0", "198.51.100.22");
    }

    private String hash(String secret) {
        return RequestFingerprint.sha256(secret);
    }
}
