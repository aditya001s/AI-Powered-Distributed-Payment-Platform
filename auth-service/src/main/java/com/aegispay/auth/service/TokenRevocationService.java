package com.aegispay.auth.service;

import com.aegispay.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Revokes refresh tokens in a transaction that survives the caller's rollback.
 *
 * <p><strong>Why this is separate from {@link RefreshTokenService}.</strong> Two
 * revocation paths must commit even though the surrounding operation ends in an
 * exception:
 *
 * <ol>
 *   <li><strong>Reuse detection.</strong> A replayed refresh token means the token
 *       was probably stolen, so the whole family is revoked and the request is
 *       rejected with 401. If the revocation shares the rejection's transaction it
 *       is rolled back, and the single most important defence in refresh-token
 *       rotation does nothing: the attacker gets a 401 but the family stays ACTIVE
 *       and their other tokens keep working.</li>
 *   <li><strong>Refresh by a disabled account.</strong> Sessions are revoked and the
 *       request rejected with 403. Same rollback problem.</li>
 * </ol>
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends the caller's transaction and commits
 * this one independently, so the revocation is durable regardless of what the caller
 * throws next.
 *
 * <p>It has to live on a <em>different bean</em> from the caller. Spring's
 * transaction advice is applied by a proxy, and a self-invocation
 * ({@code this.revokeFamily(...)}) does not pass through that proxy, so the
 * annotation would be silently ignored and the bug would remain — which is exactly
 * how the original defect arose: {@code handleReuse} was a private method called on
 * {@code this}.
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RefreshTokenRepository repository;

    public TokenRevocationService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * Revokes every token in one session family, committing immediately.
     *
     * @return the number of tokens revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamilyImmediately(String familyId, String reason) {
        int revoked = repository.revokeFamily(familyId, reason, Instant.now());
        log.info("Revoked {} token(s) in family {} (committed independently): {}",
                revoked, familyId, reason);
        return revoked;
    }

    /**
     * Revokes every active session for a user, committing immediately.
     *
     * @return the number of sessions revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllSessionsImmediately(String userId, String reason) {
        int revoked = repository.revokeAllForUser(userId, reason, Instant.now());
        if (revoked > 0) {
            log.info("Revoked {} session(s) for user {} (committed independently): {}",
                    revoked, userId, reason);
        }
        return revoked;
    }
}
