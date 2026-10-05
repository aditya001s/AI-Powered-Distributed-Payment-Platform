package com.aegispay.auth.repository;

import com.aegispay.auth.domain.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findByUserId(String userId);

    boolean existsByEmail(String email);

    /**
     * Loads a user with the row locked, for the login flow.
     *
     * <p>Needed because login both reads the failure counter and writes it back.
     * Without the lock, a burst of parallel guesses can each read
     * {@code attempts = 3}, each write 4, and the account never reaches the lockout
     * threshold — the throttle silently stops working under exactly the conditions
     * it exists for.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserAccount u WHERE u.email = :email")
    Optional<UserAccount> findByEmailForUpdate(@Param("email") String email);

    Page<UserAccount> findAllByStatus(UserAccount.Status status, Pageable pageable);

    @Query("""
            SELECT u FROM UserAccount u
            WHERE :query IS NULL
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%'))
            """)
    Page<UserAccount> search(@Param("query") String query, Pageable pageable);

    /**
     * Bulk unlock sweep. A safety net only: {@code UserAccount.isCurrentlyLocked()}
     * already compares {@code lockedUntil} against the clock on every login, so a
     * lock expires on time even if this job never runs.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE UserAccount u
            SET u.status = 'ACTIVE', u.lockedUntil = NULL, u.failedLoginAttempts = 0
            WHERE u.status = 'LOCKED' AND u.lockedUntil < :now
            """)
    int unlockExpiredLocks(@Param("now") Instant now);

    long countByStatus(UserAccount.Status status);
}
