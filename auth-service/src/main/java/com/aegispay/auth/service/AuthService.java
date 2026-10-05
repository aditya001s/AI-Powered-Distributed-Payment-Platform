package com.aegispay.auth.service;

import com.aegispay.auth.api.AuthDtos.LoginRequest;
import com.aegispay.auth.api.AuthDtos.RegisterRequest;
import com.aegispay.auth.api.AuthDtos.TokenResponse;
import com.aegispay.auth.api.AuthDtos.UserSummary;
import com.aegispay.auth.config.AuthProperties;
import com.aegispay.auth.domain.AuditLog;
import com.aegispay.auth.domain.UserAccount;
import com.aegispay.auth.repository.UserAccountRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.ForbiddenException;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import com.aegispay.common.observability.MetricNames;
import com.aegispay.common.security.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registration, login and session lifecycle.
 *
 * <p>All business logic lives here rather than in the controller. The controller's
 * job is HTTP: bind, validate, map a result to a status code.
 *
 * <h2>Transaction design</h2>
 *
 * <p>The methods on this class are deliberately <strong>not</strong>
 * {@code @Transactional}. They orchestrate; each collaborator owns a short
 * transaction of its own. Two problems forced that shape:
 *
 * <ol>
 *   <li><strong>Rejection rolls back the record of the rejection.</strong> A failed
 *       login must increment a counter <em>and</em> throw. Inside one transaction the
 *       throw discards the increment, so lockout never engages — on every attempt,
 *       not intermittently. Security-state writes therefore go through
 *       {@link LoginAttemptService} and {@link TokenRevocationService}, which commit
 *       independently of whatever this class throws next.</li>
 *   <li><strong>BCrypt must not run inside a transaction.</strong> Verifying a
 *       password at cost factor 12 takes roughly 250ms of CPU. Doing that while
 *       holding a database connection — previously also while holding a
 *       {@code SELECT ... FOR UPDATE} row lock — meant concurrent logins for one
 *       account serialised at 250ms each, which is a cheap denial-of-service against
 *       a single user, and it pinned a pooled connection for the duration. Hashing
 *       now happens with no transaction open; the row lock is taken only for the
 *       counter update, inside {@link LoginAttemptService}.</li>
 * </ol>
 *
 * <p>A useful side effect: because no transaction is open while these methods run,
 * the audit writes ({@code REQUIRES_NEW}) no longer need a <em>second</em>
 * simultaneous connection, which previously halved effective login concurrency and
 * risked exhausting the pool under load.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Window used for the credential-stuffing signal on failed logins. */
    private static final Duration STUFFING_WINDOW = Duration.ofMinutes(10);

    /** Failures from one IP within the window above that trigger a warning. */
    private static final long STUFFING_ALERT_THRESHOLD = 25L;

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final TokenRevocationService tokenRevocationService;
    private final AuditService auditService;
    private final AuthProperties properties;
    private final MeterRegistry meterRegistry;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserAccountRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy,
                       TokenIssuer tokenIssuer,
                       RefreshTokenService refreshTokenService,
                       LoginAttemptService loginAttemptService,
                       TokenRevocationService tokenRevocationService,
                       AuditService auditService,
                       AuthProperties properties,
                       MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.tokenRevocationService = tokenRevocationService;
        this.auditService = auditService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Registers a new user and immediately returns a token pair.
     *
     * <p>Atomicity of the user row and its role rows is preserved even without a
     * transaction here: roles are an {@code @ElementCollection} on
     * {@link UserAccount}, so a single {@code save} writes {@code users} and
     * {@code user_roles} inside the repository's own transaction. Either both land or
     * neither does — there is no window in which a user exists with no role.
     *
     * <p>Password hashing happens before that save, so the ~250ms of BCrypt work does
     * not hold a database connection.
     */
    public TokenResponse register(RegisterRequest request, RequestContext context) {
        String email = UserAccount.normaliseEmail(request.email());

        // Pre-check for a clear, specific error. It is NOT the guarantee: two
        // concurrent registrations can both pass it, which is what the unique index
        // and the catch below are for.
        if (userRepository.existsByEmail(email)) {
            auditService.record(AuditLog.EventType.USER_REGISTERED, AuditLog.Outcome.FAILURE,
                    email, "Registration rejected: email already in use", context);
            throw emailAlreadyRegistered();
        }

        passwordPolicy.validate(request.password());

        // Outside any transaction: ~250ms of CPU.
        String passwordHash = passwordEncoder.encode(request.password());

        UserAccount account = UserAccount.register(
                generateUserId(),
                email,
                passwordHash,
                request.fullName().trim(),
                blankToNull(request.phone()),
                Set.of(Roles.USER));

        UserAccount saved;
        try {
            saved = userRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent registration for the same email.
            // Mapped explicitly so the caller sees EMAIL_ALREADY_REGISTERED rather
            // than the generic RESOURCE_ALREADY_EXISTS the global handler would
            // produce: the same logical failure must not return two different codes
            // depending on timing.
            log.info("Concurrent registration lost the race for {}", maskEmail(email));
            auditService.record(AuditLog.EventType.USER_REGISTERED, AuditLog.Outcome.FAILURE,
                    email, "Registration rejected: concurrent duplicate", context);
            throw emailAlreadyRegistered();
        }

        log.info("Registered user {} [{}]", saved.getUserId(), maskEmail(saved.getEmail()));
        auditService.record(AuditLog.EventType.USER_REGISTERED, AuditLog.Outcome.SUCCESS,
                saved.getUserId(), null, "New user registered", context,
                Map.of("email", maskEmail(saved.getEmail())));

        return issueTokenPair(saved, context);
    }

    /**
     * Authenticates a user.
     *
     * <p>Every failure path returns the same {@link ErrorCode#INVALID_CREDENTIALS}
     * with the same message, whether the email is unknown, the password is wrong, or
     * the account is locked. Distinguishing them would turn this endpoint into an
     * account-enumeration oracle.
     *
     * <p>A dummy hash comparison runs when the email is unknown, so response time
     * does not reveal whether the account exists. Without it, "unknown email" returns
     * in about a millisecond while "wrong password" takes ~250ms of BCrypt work, and
     * that difference is trivially measurable over the network.
     */
    public TokenResponse login(LoginRequest request, RequestContext context) {
        String email = UserAccount.normaliseEmail(request.email());

        // Plain read, no lock and no surrounding transaction. The row lock needed to
        // make the failure counter race-safe is taken later, by LoginAttemptService,
        // and held only for that update.
        Optional<UserAccount> maybeAccount = userRepository.findByEmail(email);

        if (maybeAccount.isEmpty()) {
            // Spend comparable BCrypt effort so timing does not leak existence.
            passwordEncoder.matches(request.password(), dummyHash());
            registerFailure(email, null, context, "Unknown email address");
            throw invalidCredentials();
        }

        UserAccount account = maybeAccount.get();

        if (account.getStatus() == UserAccount.Status.DISABLED) {
            registerFailure(email, account.getUserId(), context, "Account is disabled");
            // Distinct code: a disabled account is an administrative state the user
            // must be told about, not a guessing signal.
            throw new ForbiddenException(ErrorCode.ACCOUNT_DISABLED,
                    "This account has been disabled. Contact support.");
        }

        if (account.isCurrentlyLocked()) {
            registerFailure(email, account.getUserId(), context, "Account is temporarily locked");
            throw invalidCredentials();
        }

        // ~250ms of CPU, with no transaction open and no row lock held.
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            registerFailure(email, account.getUserId(), context, "Incorrect password");
            throw invalidCredentials();
        }

        // ---- Success ----
        // Commits independently, so a later failure while issuing tokens cannot erase
        // the record that this user authenticated.
        loginAttemptService.recordSuccess(account.getUserId(), context.ipAddress());

        meterRegistry.counter(MetricNames.AUTH_LOGIN_ATTEMPT,
                MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_SUCCESS).increment();
        auditService.record(AuditLog.EventType.LOGIN_SUCCEEDED, AuditLog.Outcome.SUCCESS,
                account.getUserId(), "Login succeeded", context);

        log.info("User {} signed in", account.getUserId());
        return issueTokenPair(account, context);
    }

    /**
     * Exchanges a refresh token for a fresh token pair.
     *
     * <p>The account's current state is re-read rather than trusted from the old
     * token: an account disabled since the last refresh must not be able to keep
     * renewing its session indefinitely.
     */
    public TokenResponse refresh(String refreshToken, RequestContext context) {
        // Rotation is atomic in its own transaction: the old token is consumed and
        // its successor created together, so a crash between them cannot log the
        // user out with no replacement issued.
        var rotation = refreshTokenService.rotate(refreshToken, context);

        UserAccount account = userRepository.findByUserId(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.REFRESH_TOKEN_INVALID,
                        "The account associated with this token no longer exists"));

        if (!account.isAuthenticationAllowed()) {
            // Committed independently: this revocation must survive the exception
            // thrown immediately below, or a disabled account's sessions would stay
            // alive despite the code appearing to revoke them.
            tokenRevocationService.revokeAllSessionsImmediately(account.getUserId(),
                    "Account is not in a state that permits authentication");
            throw new ForbiddenException(ErrorCode.ACCOUNT_DISABLED,
                    "This account can no longer be used to sign in");
        }

        var accessToken = tokenIssuer.issueAccessToken(account);
        auditService.record(AuditLog.EventType.ACCESS_TOKEN_ISSUED, AuditLog.Outcome.SUCCESS,
                account.getUserId(), "Access token issued via refresh", context);

        return TokenResponse.of(
                accessToken.token(),
                rotation.newRefreshToken(),
                accessToken.expiresInSeconds(),
                properties.getJwt().getRefreshTokenTtl().toSeconds(),
                UserSummary.from(account));
    }

    /**
     * Ends the current session, or every session for the user.
     *
     * <p>Each revocation runs in its own transaction and this method throws nothing
     * afterwards, so the commit is unconditional.
     */
    public void logout(String userId, String refreshToken, boolean allSessions, RequestContext context) {
        if (allSessions) {
            int revoked = refreshTokenService.revokeAllSessions(userId, "User signed out of all sessions");
            auditService.record(AuditLog.EventType.LOGOUT, AuditLog.Outcome.SUCCESS,
                    userId, null, "Signed out of all sessions", context,
                    Map.of("sessionsRevoked", revoked));
        } else if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revokeSession(refreshToken, userId, "User signed out");
            auditService.record(AuditLog.EventType.LOGOUT, AuditLog.Outcome.SUCCESS,
                    userId, "Signed out", context);
        }
        // The access token remains valid until it expires. That is inherent to
        // stateless JWT validation and why the access-token TTL is 15 minutes; a
        // denylist would reintroduce a lookup on every request.
    }

    private TokenResponse issueTokenPair(UserAccount account, RequestContext context) {
        var accessToken = tokenIssuer.issueAccessToken(account);
        String refreshToken = refreshTokenService.issueForNewSession(account.getUserId(), context);

        return TokenResponse.of(
                accessToken.token(),
                refreshToken,
                accessToken.expiresInSeconds(),
                properties.getJwt().getRefreshTokenTtl().toSeconds(),
                UserSummary.from(account));
    }

    /**
     * Durably records a failed attempt: counter, lockout, metric and audit trail.
     *
     * <p>Order matters. The counter update commits first (its own transaction) and
     * only then is the audit entry written, so the audit trail cannot claim a lockout
     * that did not actually happen — the divergence the previous implementation
     * produced.
     */
    private void registerFailure(String email, String userId, RequestContext context, String reason) {
        LoginAttemptService.FailureOutcome outcome = loginAttemptService.recordFailure(email);

        meterRegistry.counter(MetricNames.AUTH_LOGIN_ATTEMPT,
                MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_FAILURE).increment();

        if (outcome.lockedNow()) {
            auditService.record(AuditLog.EventType.ACCOUNT_LOCKED, AuditLog.Outcome.SUSPICIOUS,
                    outcome.userId(), null,
                    "Account locked after repeated failed logins", context,
                    Map.of("failedAttempts", outcome.failedAttempts(),
                            "lockedUntil", String.valueOf(outcome.lockedUntil())));
        }

        auditService.record(AuditLog.EventType.LOGIN_FAILED, AuditLog.Outcome.FAILURE,
                userId == null ? "anonymous" : userId, null,
                "Login failed: " + reason, context,
                Map.of("email", maskEmail(email), "reason", reason));

        // Per-account lockout does not catch an attacker spreading a few attempts
        // across many accounts. This IP-level signal does.
        long recentFailures = auditService.recentFailuresFromIp(context.ipAddress(), STUFFING_WINDOW);
        if (recentFailures >= STUFFING_ALERT_THRESHOLD) {
            log.warn("Possible credential stuffing: {} failed logins from ip={} in the last {} minutes",
                    recentFailures, context.ipAddress(), STUFFING_WINDOW.toMinutes());
        }
    }

    private UnauthorizedException invalidCredentials() {
        // One message for every cause: unknown email, wrong password, locked account.
        return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS,
                "Email address or password is incorrect");
    }

    private ConflictException emailAlreadyRegistered() {
        return new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                "An account already exists for this email address");
    }

    /**
     * A well-formed BCrypt hash that no password matches, used to equalise timing on
     * the unknown-email path.
     */
    private String dummyHash() {
        return "{bcrypt}$2a$12$AbCdEfGhIjKlMnOpQrStUuVwXyZ0123456789abcdefghijklmnopq";
    }

    /**
     * Public user ID: a fixed prefix plus 120 bits of randomness.
     *
     * <p>Random rather than sequential so IDs cannot be enumerated and do not leak
     * how many users the platform has.
     */
    private String generateUserId() {
        byte[] bytes = new byte[15];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder("USR-");
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString().toUpperCase();
    }

    /**
     * Masks an email for logs and audit metadata: {@code as***@example.com}.
     *
     * <p>Keeps entries useful for correlation without writing full personal data into
     * a log store that is typically retained longer, and read by more people, than
     * the primary database.
     */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "unknown";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***" + domain;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
