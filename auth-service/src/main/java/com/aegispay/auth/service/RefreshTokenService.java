package com.aegispay.auth.service;

import com.aegispay.auth.config.AuthProperties;
import com.aegispay.auth.domain.AuditLog;
import com.aegispay.auth.domain.RefreshToken;
import com.aegispay.auth.repository.RefreshTokenRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import com.aegispay.common.idempotency.RequestFingerprint;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Refresh-token issuance, rotation and theft detection.
 *
 * <p>Implements OAuth2 <strong>refresh token rotation with reuse detection</strong>:
 *
 * <ol>
 *   <li>Login issues refresh token {@code R1} in a new family.</li>
 *   <li>Refreshing with {@code R1} marks it CONSUMED and issues {@code R2} in the
 *       same family.</li>
 *   <li>Presenting {@code R1} again means one of two things: the legitimate client
 *       retried after a lost response, or an attacker who stole {@code R1} is using
 *       it. These are indistinguishable from the server's position, so the safe
 *       reading is theft and the <em>entire family</em> is revoked.</li>
 * </ol>
 *
 * <p>Revoking the family logs the real user out and forces a fresh sign-in. That is
 * a deliberate trade: a small amount of user friction in a rare case, versus an
 * attacker holding a valid session indefinitely. Without rotation, a stolen refresh
 * token is a permanent account takeover with no signal that anything happened.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** Expired rows are kept this long so reuse detection still works just after expiry. */
    private static final java.time.Duration EXPIRED_RETENTION = java.time.Duration.ofDays(7);

    private final RefreshTokenRepository repository;
    private final TokenIssuer tokenIssuer;
    private final AuthProperties properties;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;

    public RefreshTokenService(RefreshTokenRepository repository,
                              TokenIssuer tokenIssuer,
                              AuthProperties properties,
                              AuditService auditService,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.tokenIssuer = tokenIssuer;
        this.properties = properties;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
    }

    /** Issues the first refresh token of a new session. */
    @Transactional
    public String issueForNewSession(String userId, RequestContext context) {
        String secret = tokenIssuer.generateRefreshTokenSecret();
        RefreshToken token = RefreshToken.issueNewFamily(
                hash(secret),
                userId,
                Instant.now().plus(properties.getJwt().getRefreshTokenTtl()),
                context.userAgent(),
                context.ipAddress());
        repository.save(token);
        return secret;
    }

    /**
     * Validates and rotates a refresh token.
     *
     * <p>Runs in one transaction so consuming the old token and creating its
     * successor are atomic. A crash in between must not leave the old token consumed
     * with no replacement issued, which would silently log the user out.
     *
     * @return the user ID the token belongs to and the new refresh-token secret
     * @throws UnauthorizedException if the token is unknown, expired, revoked, or
     *                               being replayed
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public RotationResult rotate(String presentedSecret, RequestContext context) {
        String presentedHash = hash(presentedSecret);

        RefreshToken existing = repository.findByTokenHashForUpdate(presentedHash)
                .orElseThrow(() -> {
                    // An unknown hash is either a forged token or one already swept.
                    // Nothing is revealed about which.
                    log.warn("Refresh attempted with an unknown token [ip={}]", context.ipAddress());
                    return new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID,
                            "The refresh token is not recognised");
                });

        // ---- Reuse detection ----
        if (existing.getState() == RefreshToken.State.CONSUMED) {
            // handleReuse RETURNS the exception rather than throwing it, so the
            // control flow is visible here and the compiler knows this path
            // terminates. The previous shape (a void method that always threw)
            // required an unreachable throw below it, and that indirection is what
            // concealed the fact that the revocation inside it was being rolled back.
            throw handleReuse(existing, context);
        }

        if (existing.getState() == RefreshToken.State.REVOKED) {
            log.warn("Refresh attempted with a revoked token [user={}, reason={}]",
                    existing.getUserId(), existing.getRevokedReason());
            throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID,
                    "The refresh token has been revoked");
        }

        if (existing.isExpired()) {
            throw new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID,
                    "The refresh token has expired");
        }

        // ---- Rotate ----
        existing.consume();

        String newSecret = tokenIssuer.generateRefreshTokenSecret();
        RefreshToken successor = RefreshToken.issue(
                hash(newSecret),
                existing.getUserId(),
                existing.getFamilyId(),
                Instant.now().plus(properties.getJwt().getRefreshTokenTtl()),
                context.userAgent(),
                context.ipAddress());
        repository.save(successor);

        auditService.record(AuditLog.EventType.REFRESH_TOKEN_ROTATED, AuditLog.Outcome.SUCCESS,
                existing.getUserId(), "Refresh token rotated", context);

        return new RotationResult(existing.getUserId(), newSecret);
    }

    /**
     * Handles a replayed refresh token by revoking the whole family.
     *
     * <p>Returns the exception for the caller to throw, so that the terminating
     * control flow is explicit at the call site.
     *
     * <p><strong>The revocation must outlive the 401.</strong> Rotation owns a
     * pessimistic lock on the presented row, so starting a {@code REQUIRES_NEW}
     * revocation would deadlock waiting for that same lock. The family is revoked
     * in this transaction and {@link #rotate} declares
     * {@code noRollbackFor = UnauthorizedException.class}, committing the security
     * state even though the caller receives an authentication failure.
     *
     * <p>Also emits a metric worth alerting on: a rising reuse-detection rate is one
     * of the clearest available signals of active token theft.
     */
    private UnauthorizedException handleReuse(RefreshToken replayed, RequestContext context) {
        int revoked = repository.revokeFamily(
                replayed.getFamilyId(),
                "Reuse of a consumed refresh token detected (suspected theft)",
                Instant.now());

        meterRegistry.counter(MetricNames.AUTH_REFRESH_REUSE_DETECTED).increment();

        log.error("""
                        Refresh token REUSE DETECTED for user {}: a consumed token was presented again. \
                        Revoked {} tokens in family {}. The user must sign in again. \
                        Original token was consumed at {} from ip={}, replay came from ip={}.""",
                replayed.getUserId(), revoked, replayed.getFamilyId(),
                replayed.getConsumedAt(), replayed.getIpAddress(), context.ipAddress());

        auditService.record(
                AuditLog.EventType.REFRESH_TOKEN_REUSE_DETECTED,
                AuditLog.Outcome.SUSPICIOUS,
                replayed.getUserId(),
                replayed.getFamilyId(),
                "Consumed refresh token replayed; entire token family revoked",
                context,
                Map.of("familyId", replayed.getFamilyId(),
                        "tokensRevoked", revoked,
                        "originalIp", String.valueOf(replayed.getIpAddress()),
                        "replayIp", String.valueOf(context.ipAddress())));

        return new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID,
                "This refresh token has already been used. For your security, all sessions "
                        + "have been signed out. Please sign in again.");
    }

    /** Revokes one session, identified by its refresh token. */
    @Transactional
    public void revokeSession(String presentedSecret, String reason) {
        revokeSession(presentedSecret, null, reason);
    }

    /** Revokes only when the presented token belongs to the authenticated user. */
    @Transactional
    public void revokeSession(String presentedSecret, String expectedUserId, String reason) {
        repository.findByTokenHash(hash(presentedSecret)).ifPresent(token -> {
            if (expectedUserId != null && !expectedUserId.equals(token.getUserId())) {
                log.warn("User {} attempted to revoke a refresh session belonging to another user",
                        expectedUserId);
                return;
            }
            // Revoke the family, not just this token: logging out should end the
            // session, and any successor already issued belongs to it.
            repository.revokeFamily(token.getFamilyId(), reason, Instant.now());
        });
    }

    /**
     * Revokes every session for a user.
     *
     * <p>Called on password change and on administrative disable. A password change
     * that left existing sessions alive would not actually lock out an attacker who
     * already had one, which is the main reason people change their password.
     */
    @Transactional
    public int revokeAllSessions(String userId, String reason) {
        int revoked = repository.revokeAllForUser(userId, reason, Instant.now());
        if (revoked > 0) {
            log.info("Revoked {} active session(s) for user {}: {}", revoked, userId, reason);
        }
        return revoked;
    }

    @Transactional(readOnly = true)
    public java.util.List<RefreshToken> activeSessions(String userId) {
        return repository.findByUserIdAndState(userId, RefreshToken.State.ACTIVE);
    }

    /** Retention sweep for long-expired tokens. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpiredBefore(Instant.now().minus(EXPIRED_RETENTION));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }

    /**
     * SHA-256 of the presented secret.
     *
     * <p>Fast hashing is correct here, unlike for passwords: the input is 256 bits
     * of cryptographic randomness, so there is no low-entropy space to brute force,
     * and lookups happen on every token refresh.
     */
    private String hash(String secret) {
        return RequestFingerprint.sha256(secret);
    }

    /** Outcome of a successful rotation. */
    public record RotationResult(String userId, String newRefreshToken) {
    }
}
