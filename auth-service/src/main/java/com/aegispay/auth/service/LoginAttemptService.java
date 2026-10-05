package com.aegispay.auth.service;

import com.aegispay.auth.config.AuthProperties;
import com.aegispay.auth.domain.UserAccount;
import com.aegispay.auth.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Records the outcome of a login attempt in its own committed transaction.
 *
 * <p><strong>Why this class exists.</strong> Recording a failed login and rejecting
 * the request are contradictory transactional requirements. The rejection is an
 * exception, and Spring rolls back on any {@code RuntimeException} — so code shaped
 * like this silently loses the write:
 *
 * <pre>{@code
 * @Transactional
 * public void login(...) {
 *     account.recordFailedLogin(...);   // increments the counter
 *     userRepository.save(account);
 *     throw new UnauthorizedException(...);   // <-- rolls the increment back
 * }
 * }</pre>
 *
 * <p>The counter therefore never advances, {@code locked_until} is never set, and
 * brute-force protection does nothing at all. It fails on every single attempt
 * rather than intermittently, and it fails <em>invisibly</em>: the audit trail
 * writes its own row in a separate transaction, so the logs claim an account was
 * locked while the database says otherwise.
 *
 * <p>{@link Propagation#REQUIRES_NEW} makes the write independent of whatever the
 * caller does afterwards. The current caller ({@code AuthService.login}) is
 * deliberately non-transactional, so a plain {@code REQUIRED} would also commit —
 * but relying on that would make this class silently breakable by any future caller
 * that adds {@code @Transactional}. Declaring the requirement here keeps the
 * guarantee local to the code that depends on it.
 *
 * <p>This class also owns the row lock. {@code findByEmailForUpdate} takes
 * {@code SELECT ... FOR UPDATE} so concurrent failed attempts serialise and each
 * increment is preserved; without it, a parallel burst would read the same counter
 * value, all write the same result, and the account would never reach the threshold.
 * The lock is held only for the counter update — never across BCrypt verification.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UserAccountRepository userRepository;
    private final AuthProperties properties;

    public LoginAttemptService(UserAccountRepository userRepository, AuthProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    /**
     * Records a failed login attempt and applies lockout if the threshold is reached.
     *
     * <p>Safe to call for an unknown email: there is nothing to increment, and the
     * returned outcome reports {@code accountExists = false}. The caller must still
     * return the same generic error either way, so that this endpoint cannot be used
     * to discover which addresses are registered.
     *
     * @param email normalised email address
     * @return what happened, so the caller can audit it without re-reading the row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureOutcome recordFailure(String email) {
        AuthProperties.Lockout lockout = properties.getLockout();

        Optional<UserAccount> maybeAccount = userRepository.findByEmailForUpdate(email);
        if (maybeAccount.isEmpty()) {
            return FailureOutcome.unknownAccount();
        }

        UserAccount account = maybeAccount.get();
        boolean wasAlreadyLocked = account.isCurrentlyLocked();

        account.recordFailedLogin(
                lockout.getMaxFailedAttempts(),
                lockout.getLockDuration(),
                lockout.getAttemptWindow());
        userRepository.save(account);

        boolean lockedNow = account.isCurrentlyLocked() && !wasAlreadyLocked;
        if (lockedNow) {
            log.warn("Account {} locked until {} after {} failed attempts",
                    account.getUserId(), account.getLockedUntil(), account.getFailedLoginAttempts());
        }

        return new FailureOutcome(
                true,
                account.getUserId(),
                account.getFailedLoginAttempts(),
                account.getLockedUntil(),
                lockedNow);
    }

    /**
     * Clears throttling state after a successful login and stamps the login context.
     *
     * <p>Independent transaction for symmetry with {@link #recordFailure}: the token
     * pair is issued after this returns, and a failure during token issuance must not
     * roll back the fact that the user authenticated successfully.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String userId, String ipAddress) {
        userRepository.findByUserId(userId).ifPresent(account -> {
            account.recordSuccessfulLogin(ipAddress);
            userRepository.save(account);
        });
    }

    /**
     * What {@link #recordFailure} did.
     *
     * @param accountExists  false when the email matched no account
     * @param userId         the account's public ID, or {@code null} when unknown
     * @param failedAttempts the counter value after the increment
     * @param lockedUntil    when the lock expires, or {@code null} if not locked
     * @param lockedNow      true only on the attempt that crossed the threshold, so
     *                       the caller emits one lockout audit entry rather than one
     *                       per subsequent attempt
     */
    public record FailureOutcome(boolean accountExists,
                                 String userId,
                                 int failedAttempts,
                                 Instant lockedUntil,
                                 boolean lockedNow) {

        static FailureOutcome unknownAccount() {
            return new FailureOutcome(false, null, 0, null, false);
        }
    }
}
