package com.aegispay.auth.service;

import com.aegispay.auth.api.AuthDtos.LoginRequest;
import com.aegispay.auth.api.AuthDtos.RegisterRequest;
import com.aegispay.auth.config.AuthProperties;
import com.aegispay.auth.domain.AuditLog;
import com.aegispay.auth.domain.UserAccount;
import com.aegispay.auth.repository.UserAccountRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.ForbiddenException;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import com.aegispay.common.security.Roles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AuthService} login and registration.
 *
 * <p>These exist primarily as <strong>regression cover for three
 * transaction-boundary defects</strong> found in review, where security state was
 * written and then discarded by rollback:
 *
 * <ul>
 *   <li>the failed-login counter never advanced, so lockout never engaged;</li>
 *   <li>refresh-token reuse detection revoked nothing;</li>
 *   <li>a disabled account's sessions were never revoked on refresh.</li>
 * </ul>
 *
 * <p>Each fix works by delegating the durable write to a collaborator that owns an
 * independent transaction. These tests assert the <em>delegation</em>; that the
 * collaborator's transaction actually survives a rollback is asserted structurally by
 * {@link TransactionBoundaryTest} and behaviourally by the Testcontainers suite.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceLoginTest {

    private static final String EMAIL = "asha.verma@example.com";
    private static final String PASSWORD = "correct-horse-Battery-9!";
    private static final String HASH = "{bcrypt}$2a$12$realhashvaluethatwouldbestoredinthedatabase00000000";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private TokenRevocationService tokenRevocationService;
    @Mock
    private AuditService auditService;

    private AuthService authService;
    private RequestContext context;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                new PasswordPolicy(properties),   // real policy: validation genuinely runs
                tokenIssuer,
                refreshTokenService,
                loginAttemptService,
                tokenRevocationService,
                auditService,
                properties,
                new SimpleMeterRegistry());
        context = new RequestContext("203.0.113.7", "Mozilla/5.0");
    }

    // ------------------------------------------------------------------- login

    @Nested
    @DisplayName("login")
    class Login {

        /**
         * The core lockout regression. Previously the counter was incremented on the
         * account entity and saved inside {@code login}'s own transaction, then
         * discarded when the method threw. Now it must be delegated to
         * {@link LoginAttemptService}, whose transaction commits independently.
         */
        @Test
        @DisplayName("records the failure durably when the password is wrong")
        void recordsFailureOnWrongPassword() {
            UserAccount account = activeAccount();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(passwordEncoder.matches("wrong-password", HASH)).thenReturn(false);
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(failure(1, false));

            assertThatExceptionOfType(UnauthorizedException.class)
                    .isThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong-password"), context))
                    .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

            // The durable write must have been delegated, exactly once.
            verify(loginAttemptService, times(1)).recordFailure(EMAIL);
            // And login must NOT mutate + save the account itself, which is what
            // made the increment vulnerable to rollback in the first place.
            verify(userRepository, never()).save(any(UserAccount.class));
        }

        @Test
        @DisplayName("emits a lockout audit entry only on the attempt that crosses the threshold")
        void auditsLockoutOnceWhenThresholdCrossed() {
            UserAccount account = activeAccount();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(passwordEncoder.matches(anyString(), eq(HASH))).thenReturn(false);
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(failure(6, true));

            assertThatExceptionOfType(UnauthorizedException.class)
                    .isThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong"), context));

            verify(auditService).record(eq(AuditLog.EventType.ACCOUNT_LOCKED),
                    eq(AuditLog.Outcome.SUSPICIOUS), anyString(), any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("does not emit a lockout audit entry when the threshold is not reached")
        void doesNotAuditLockoutBelowThreshold() {
            UserAccount account = activeAccount();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(passwordEncoder.matches(anyString(), eq(HASH))).thenReturn(false);
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(failure(2, false));

            assertThatExceptionOfType(UnauthorizedException.class)
                    .isThrownBy(() -> authService.login(new LoginRequest(EMAIL, "wrong"), context));

            verify(auditService, never()).record(eq(AuditLog.EventType.ACCOUNT_LOCKED),
                    any(), anyString(), any(), anyString(), any(), any());
        }

        /**
         * Timing equalisation. Without the dummy comparison an unknown email returns
         * in about a millisecond while a wrong password costs ~250ms of BCrypt, and
         * that difference is measurable over the network — turning the endpoint into
         * an account-existence oracle.
         */
        @Test
        @DisplayName("spends BCrypt effort even when the email is unknown")
        void equalisesTimingForUnknownEmail() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(
                    new LoginAttemptService.FailureOutcome(false, null, 0, null, false));

            assertThatExceptionOfType(UnauthorizedException.class)
                    .isThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD), context))
                    .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

            // A hash comparison happened despite there being no account.
            verify(passwordEncoder).matches(eq(PASSWORD), anyString());
        }

        @Test
        @DisplayName("returns the same error for an unknown email and a wrong password")
        void doesNotLeakAccountExistence() {
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(
                    new LoginAttemptService.FailureOutcome(false, null, 0, null, false));

            UnauthorizedException unknownEmail = catchUnauthorized(EMAIL, PASSWORD);

            UserAccount account = activeAccount();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(passwordEncoder.matches(anyString(), eq(HASH))).thenReturn(false);
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(failure(1, false));

            UnauthorizedException wrongPassword = catchUnauthorized(EMAIL, "nope");

            assertThat(unknownEmail.errorCode()).isEqualTo(wrongPassword.errorCode());
            assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
        }

        @Test
        @DisplayName("reports a locked account as invalid credentials, not as locked")
        void lockedAccountIsIndistinguishable() {
            UserAccount account = lockedAccount();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(failure(6, false));

            assertThatExceptionOfType(UnauthorizedException.class)
                    .isThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD), context))
                    .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));

            // Short-circuits before hashing: a locked account must not be a free
            // oracle for verifying whether a guessed password is correct.
            verify(passwordEncoder, never()).matches(anyString(), eq(HASH));
            verify(loginAttemptService).recordFailure(EMAIL);
        }

        @Test
        @DisplayName("reports a disabled account distinctly, because it is an admin state")
        void disabledAccountGetsItsOwnCode() {
            UserAccount account = activeAccount();
            account.disable();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(loginAttemptService.recordFailure(EMAIL)).thenReturn(failure(1, false));

            assertThatExceptionOfType(ForbiddenException.class)
                    .isThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD), context))
                    .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DISABLED));
        }

        @Test
        @DisplayName("clears throttling state and issues tokens on success")
        void issuesTokensOnSuccess() {
            UserAccount account = activeAccount();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            stubTokenIssuance();

            var response = authService.login(new LoginRequest(EMAIL, PASSWORD), context);

            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(response.refreshToken()).isEqualTo("refresh-token");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.user().email()).isEqualTo(EMAIL);

            verify(loginAttemptService).recordSuccess(account.getUserId(), "203.0.113.7");
            verify(loginAttemptService, never()).recordFailure(anyString());
        }

        /**
         * The success state must be committed before tokens are minted, so that a
         * failure during token issuance cannot erase the record that the user
         * authenticated.
         */
        @Test
        @DisplayName("commits the successful-login record before issuing tokens")
        void recordsSuccessBeforeIssuingTokens() {
            UserAccount account = activeAccount();
            when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            stubTokenIssuance();

            authService.login(new LoginRequest(EMAIL, PASSWORD), context);

            InOrder order = inOrder(loginAttemptService, tokenIssuer);
            order.verify(loginAttemptService).recordSuccess(anyString(), anyString());
            order.verify(tokenIssuer).issueAccessToken(any());
        }
    }

    // ---------------------------------------------------------------- register

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("rejects an email that is already registered")
        void rejectsDuplicateEmail() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> authService.register(registerRequest(), context))
                    .satisfies(ex -> assertThat(ex.errorCode())
                            .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED));

            verify(userRepository, never()).save(any(UserAccount.class));
        }

        /**
         * The pre-check is not the guarantee: two concurrent registrations can both
         * pass it and one will hit the unique index. That must surface as the same
         * {@code EMAIL_ALREADY_REGISTERED} code, not the generic
         * {@code RESOURCE_ALREADY_EXISTS} the global handler would otherwise produce —
         * one logical failure must not return two codes depending on timing.
         */
        @Test
        @DisplayName("maps a lost concurrent-registration race to EMAIL_ALREADY_REGISTERED")
        void mapsUniqueViolationToTheSameCode() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
            when(userRepository.save(any(UserAccount.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_users_email"));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> authService.register(registerRequest(), context))
                    .satisfies(ex -> assertThat(ex.errorCode())
                            .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED));
        }

        @Test
        @DisplayName("enforces the password policy before touching the database")
        void enforcesPasswordPolicyFirst() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

            assertThatExceptionOfType(com.aegispay.common.exception.DomainExceptions.BadRequestException.class)
                    .isThrownBy(() -> authService.register(
                            new RegisterRequest(EMAIL, "weak", "Asha Verma", ""), context));

            verify(passwordEncoder, never()).encode(anyString());
            verify(userRepository, never()).save(any(UserAccount.class));
        }

        /**
         * Hashing must happen before the save, so the ~250ms of BCrypt work does not
         * hold a database connection.
         */
        @Test
        @DisplayName("hashes the password before persisting")
        void hashesBeforePersisting() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
            when(userRepository.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
            stubTokenIssuance();

            authService.register(registerRequest(), context);

            InOrder order = inOrder(passwordEncoder, userRepository);
            order.verify(passwordEncoder).encode(PASSWORD);
            order.verify(userRepository).save(any(UserAccount.class));
        }

        @Test
        @DisplayName("stores only a hash, never the password, and grants USER")
        void storesHashAndDefaultRole() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
            when(userRepository.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
            stubTokenIssuance();

            authService.register(registerRequest(), context);

            ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
            verify(userRepository).save(captor.capture());
            UserAccount saved = captor.getValue();

            assertThat(saved.getPasswordHash()).isEqualTo(HASH).isNotEqualTo(PASSWORD);
            assertThat(saved.getPasswordHash()).doesNotContain(PASSWORD);
            assertThat(saved.getRoles()).containsExactly(Roles.USER);
            assertThat(saved.getEmail()).isEqualTo(EMAIL);
            assertThat(saved.getUserId()).startsWith("USR-");
        }

        @Test
        @DisplayName("normalises the email to lowercase")
        void normalisesEmail() {
            when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode(PASSWORD)).thenReturn(HASH);
            when(userRepository.save(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
            stubTokenIssuance();

            authService.register(
                    new RegisterRequest("  ASHA.Verma@Example.COM  ", PASSWORD, "Asha Verma", ""), context);

            verify(userRepository).existsByEmail(EMAIL);
        }
    }

    // ----------------------------------------------------------------- helpers

    private UnauthorizedException catchUnauthorized(String email, String password) {
        try {
            authService.login(new LoginRequest(email, password), context);
            throw new AssertionError("expected UnauthorizedException");
        } catch (UnauthorizedException ex) {
            return ex;
        }
    }

    private RegisterRequest registerRequest() {
        return new RegisterRequest(EMAIL, PASSWORD, "Asha Verma", "");
    }

    private void stubTokenIssuance() {
        lenient().when(tokenIssuer.issueAccessToken(any())).thenReturn(
                new TokenIssuer.IssuedToken("access-token", "jti-1",
                        Instant.now().plusSeconds(900), 900L));
        lenient().when(refreshTokenService.issueForNewSession(anyString(), any()))
                .thenReturn("refresh-token");
    }

    private UserAccount activeAccount() {
        return UserAccount.register("USR-ABC123", EMAIL, HASH, "Asha Verma", null, Set.of(Roles.USER));
    }

    /** An account already locked, produced by driving the real domain rule. */
    private UserAccount lockedAccount() {
        UserAccount account = activeAccount();
        account.recordFailedLogin(1, Duration.ofMinutes(15), Duration.ofMinutes(30));
        assertThat(account.isCurrentlyLocked()).isTrue();
        return account;
    }

    private LoginAttemptService.FailureOutcome failure(int attempts, boolean lockedNow) {
        return new LoginAttemptService.FailureOutcome(
                true, "USR-ABC123", attempts,
                lockedNow ? Instant.now().plusSeconds(900) : null, lockedNow);
    }
}
