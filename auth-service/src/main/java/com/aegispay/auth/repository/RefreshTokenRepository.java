package com.aegispay.auth.repository;

import com.aegispay.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Serializes rotation of one presented token. Without this row lock two refresh
     * requests can both observe ACTIVE and each mint a valid successor.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByUserIdAndState(String userId, RefreshToken.State state);

    List<RefreshToken> findByFamilyId(String familyId);

    /**
     * Revokes an entire token family.
     *
     * <p>Invoked when a consumed token is replayed. A single bulk UPDATE rather than
     * load-then-save, because a race here would leave part of a compromised family
     * still usable.
     *
     * <p><strong>{@code flushAutomatically} / {@code clearAutomatically} are load
     * bearing.</strong> A JPQL bulk UPDATE goes straight to the database and bypasses
     * the persistence context entirely, which creates two hazards this pair closes:
     *
     * <ul>
     *   <li>Without {@code flushAutomatically}, pending changes to managed
     *       {@code RefreshToken} entities have not reached the database yet, so the
     *       UPDATE cannot see them — and a later flush can overwrite the rows this
     *       statement just modified. Concretely: {@code rotate} calls
     *       {@code existing.consume()} leaving a dirty entity, and a subsequent bulk
     *       revoke could be undone by that entity's own flush writing
     *       {@code state = CONSUMED} back over {@code state = REVOKED}.</li>
     *   <li>Without {@code clearAutomatically}, entities already loaded keep their
     *       stale pre-UPDATE state for the rest of the transaction, so code that
     *       re-reads them sees tokens as ACTIVE after they were revoked.</li>
     * </ul>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
            SET t.state = 'REVOKED', t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.familyId = :familyId AND t.state <> 'REVOKED'
            """)
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("reason") String reason,
                     @Param("now") Instant now);

    /**
     * Revokes every active session for a user (password change, admin disable).
     *
     * <p>Same flush/clear reasoning as {@link #revokeFamily}: this runs alongside
     * managed entities in {@code changePassword} and {@code refresh}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
            SET t.state = 'REVOKED', t.revokedAt = :now, t.revokedReason = :reason
            WHERE t.userId = :userId AND t.state = 'ACTIVE'
            """)
    int revokeAllForUser(@Param("userId") String userId,
                         @Param("reason") String reason,
                         @Param("now") Instant now);

    /**
     * Retention sweep. Expired rows are kept for a grace period so reuse detection
     * still works just after expiry, then removed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);

    long countByUserIdAndState(String userId, RefreshToken.State state);
}
