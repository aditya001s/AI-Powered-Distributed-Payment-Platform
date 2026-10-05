package com.aegispay.auth.service;

import com.aegispay.auth.api.AuthDtos.ChangePasswordRequest;
import com.aegispay.auth.api.AuthDtos.InternalUserView;
import com.aegispay.auth.api.AuthDtos.SessionResponse;
import com.aegispay.auth.api.AuthDtos.UpdateProfileRequest;
import com.aegispay.auth.api.AuthDtos.UserProfileResponse;
import com.aegispay.auth.domain.AuditLog;
import com.aegispay.auth.domain.UserAccount;
import com.aegispay.auth.repository.UserAccountRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import com.aegispay.common.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Profile management, credential changes and administrative user operations.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public UserService(UserAccountRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy,
                       RefreshTokenService refreshTokenService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse profile(String userId) {
        return UserProfileResponse.from(requireUser(userId));
    }

    @Transactional
    public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request,
                                             RequestContext context) {
        UserAccount account = requireUser(userId);
        account.updateProfile(request.fullName(), request.phone());
        userRepository.save(account);

        auditService.record(AuditLog.EventType.PROFILE_UPDATED, AuditLog.Outcome.SUCCESS,
                userId, "Profile updated", context);
        return UserProfileResponse.from(account);
    }

    /**
     * Changes a password.
     *
     * <p>Two things happen that are easy to omit and important to get right:
     *
     * <ol>
     *   <li>The current password is re-verified. Without it, anyone holding a stolen
     *       access token could lock the real owner out of their own account.</li>
     *   <li>Every existing session is revoked. A password change whose whole purpose
     *       is "someone else may have my credentials" is meaningless if the
     *       attacker's existing session keeps working.</li>
     * </ol>
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> This method performs
     * up to three BCrypt operations (verify current, compare against new, hash new) at
     * roughly 250ms each. Holding a database connection for ~750ms of pure CPU is
     * exactly the "no long-running work inside a transaction" problem, and it scales
     * badly under concurrent use.
     *
     * <p>Atomicity is preserved without it. The account is read as a detached entity,
     * mutated, and saved — which becomes a {@code merge} inside the repository's own
     * transaction, a single-row update. {@code UserAccount} carries an
     * {@code @Version} column, so a concurrent modification is caught by optimistic
     * locking and surfaces as 409 rather than silently overwriting the other change.
     */
    public void changePassword(String userId, ChangePasswordRequest request, RequestContext context) {
        UserAccount account = requireUser(userId);

        if (!passwordEncoder.matches(request.currentPassword(), account.getPasswordHash())) {
            auditService.record(AuditLog.EventType.PASSWORD_CHANGED, AuditLog.Outcome.FAILURE,
                    userId, "Password change rejected: current password incorrect", context);
            throw new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS,
                    "The current password is incorrect");
        }

        if (passwordEncoder.matches(request.newPassword(), account.getPasswordHash())) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "The new password must be different from the current one");
        }

        passwordPolicy.validate(request.newPassword());

        // Hashing happens with no transaction open; the save below is the only
        // database work and it is a single-row merge.
        account.changePassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(account);

        // Its own transaction. Ordered after the password write so a failure to
        // revoke cannot leave the old password in place while sessions are gone.
        int revoked = refreshTokenService.revokeAllSessions(userId, "Password changed");

        log.info("Password changed for user {}; revoked {} session(s)", userId, revoked);
        auditService.record(AuditLog.EventType.PASSWORD_CHANGED, AuditLog.Outcome.SUCCESS,
                userId, null, "Password changed; all sessions revoked", context,
                Map.of("sessionsRevoked", revoked));
    }

    /** The user's active sessions, so they can see and revoke unfamiliar devices. */
    @Transactional(readOnly = true)
    public List<SessionResponse> activeSessions(String userId) {
        return refreshTokenService.activeSessions(userId).stream()
                .map(token -> new SessionResponse(
                        // The family ID, not the token hash: it identifies the session
                        // without exposing anything usable as a credential.
                        token.getFamilyId(),
                        token.getUserAgent(),
                        token.getIpAddress(),
                        token.getIssuedAt(),
                        token.getExpiresAt()))
                .toList();
    }

    /** Identity view for service-to-service consumers (fraud, notifications, analytics). */
    @Transactional(readOnly = true)
    public InternalUserView internalView(String userId) {
        return InternalUserView.from(requireUser(userId));
    }

    // ------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public PageResponse<UserProfileResponse> searchUsers(String query, Pageable pageable) {
        String normalised = query == null || query.isBlank() ? null : query.trim();
        var users = normalised == null
                ? userRepository.findAll(pageable)
                : userRepository.search(normalised, pageable);
        return PageResponse.of(users, UserProfileResponse::from);
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public void grantRole(String actorUserId, String targetUserId, String role, RequestContext context) {
        UserAccount account = requireUser(targetUserId);
        account.grantRole(role.toUpperCase());
        userRepository.save(account);

        log.info("Admin {} granted role {} to user {}", actorUserId, role, targetUserId);
        auditService.record(AuditLog.EventType.ROLE_GRANTED, AuditLog.Outcome.SUCCESS,
                actorUserId, targetUserId, "Granted role " + role, context,
                Map.of("role", role.toUpperCase()));
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public void revokeRole(String actorUserId, String targetUserId, String role, RequestContext context) {
        UserAccount account = requireUser(targetUserId);
        account.revokeRole(role.toUpperCase());
        userRepository.save(account);

        auditService.record(AuditLog.EventType.ROLE_REVOKED, AuditLog.Outcome.SUCCESS,
                actorUserId, targetUserId, "Revoked role " + role, context,
                Map.of("role", role.toUpperCase()));
    }

    /**
     * Disables an account and terminates its sessions.
     *
     * <p>Both steps are required: leaving sessions alive would let a compromised
     * account continue transacting after being disabled.
     */
    @Transactional
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public void disableUser(String actorUserId, String targetUserId, String reason, RequestContext context) {
        UserAccount account = requireUser(targetUserId);
        account.disable();
        userRepository.save(account);

        int revoked = refreshTokenService.revokeAllSessions(targetUserId, "Account disabled: " + reason);

        log.warn("Admin {} disabled user {} ({}); revoked {} session(s)",
                actorUserId, targetUserId, reason, revoked);
        auditService.record(AuditLog.EventType.ACCOUNT_DISABLED, AuditLog.Outcome.SUCCESS,
                actorUserId, targetUserId, "Account disabled: " + reason, context,
                Map.of("reason", reason, "sessionsRevoked", revoked));
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public void enableUser(String actorUserId, String targetUserId, RequestContext context) {
        UserAccount account = requireUser(targetUserId);
        account.enable();
        userRepository.save(account);

        auditService.record(AuditLog.EventType.ACCOUNT_UNLOCKED, AuditLog.Outcome.SUCCESS,
                actorUserId, targetUserId, "Account re-enabled", context, Map.of());
    }

    /**
     * Sweeps expired lockouts.
     *
     * <p>Cosmetic only: {@code UserAccount.isCurrentlyLocked()} already compares
     * {@code lockedUntil} to the clock on every login, so a lock expires on time
     * whether or not this runs. This just keeps the {@code status} column honest for
     * anyone reading the table or an admin dashboard.
     */
    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void unlockExpiredAccounts() {
        int unlocked = userRepository.unlockExpiredLocks(Instant.now());
        if (unlocked > 0) {
            log.debug("Cleared {} expired account lock(s)", unlocked);
        }
    }

    private UserAccount requireUser(String userId) {
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> NotFoundException.of("User", userId));
    }
}
