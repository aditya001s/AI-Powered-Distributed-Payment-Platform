package com.aegispay.auth;

import com.aegispay.auth.api.AuthDtos.ChangePasswordRequest;
import com.aegispay.auth.api.AuthDtos.LoginRequest;
import com.aegispay.auth.api.AuthDtos.RefreshRequest;
import com.aegispay.auth.api.AuthDtos.RegisterRequest;
import com.aegispay.auth.api.AuthDtos.TokenResponse;
import com.aegispay.auth.api.AuthDtos.UserProfileResponse;
import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.security.Roles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end authentication and authorisation against a real PostgreSQL database.
 *
 * <p>These are the tests that actually prove the three transaction-boundary fixes.
 * Each defect produced code that <em>looked</em> correct and whose unit tests passed —
 * the write was issued and then silently rolled back. The HTTP response was identical
 * before and after the fix, so the critical tests here read the tables directly rather
 * than trusting the response body.
 */
class AuthFlowIT extends AbstractAuthIntegrationTest {

    private static final String EMAIL = "asha.verma@example.com";
    private static final String PASSWORD = "correct-horse-Battery-9!";
    private static final String FULL_NAME = "Asha Verma";

    // ========================================================== registration

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("creates the user and its role atomically and returns a token pair")
        void registersUserWithRoleAtomically() {
            Response<TokenResponse> response = post("/api/auth/register", registerRequest(),
                    TokenResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
            TokenResponse body = response.body();
            assertThat(body).isNotNull();
            assertThat(body.accessToken()).isNotBlank();
            assertThat(body.refreshToken()).isNotBlank();
            assertThat(body.tokenType()).isEqualTo("Bearer");
            assertThat(body.user().roles()).containsExactly(Roles.USER);

            // Both rows committed: there is no window where a user exists with no role.
            assertThat(countUsers(EMAIL)).isEqualTo(1);
            assertThat(countRolesFor(EMAIL)).isEqualTo(1);
        }

        @Test
        @DisplayName("never stores the password in recoverable form")
        void storesOnlyAHash() {
            post("/api/auth/register", registerRequest(), TokenResponse.class);

            String hash = jdbc.queryForObject(
                    "SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL);

            assertThat(hash).isNotNull()
                    .startsWith("{bcrypt}$2")
                    .doesNotContain(PASSWORD);
        }

        @Test
        @DisplayName("rejects a duplicate email with EMAIL_ALREADY_REGISTERED")
        void rejectsDuplicateEmail() {
            post("/api/auth/register", registerRequest(), TokenResponse.class);

            Response<ApiError> second = post("/api/auth/register", registerRequest(), ApiError.class);

            assertThat(second.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(second.body()).isNotNull();
            assertThat(second.body().code()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED.name());
            assertThat(countUsers(EMAIL)).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects a password that fails the policy and writes nothing")
        void rejectsWeakPasswordWithoutPartialWrite() {
            Response<ApiError> response = post("/api/auth/register",
                    new RegisterRequest(EMAIL, "weak", FULL_NAME, ""), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            // No partial user or role rows survived the rejection.
            assertThat(countUsers(EMAIL)).isZero();
            assertThat(countRolesFor(EMAIL)).isZero();
        }

        /**
         * The pre-check in {@code register} cannot prevent this; the unique index does.
         * Exactly one request must win, and every loser must report the same code — not
         * the generic {@code RESOURCE_ALREADY_EXISTS} that the global handler would
         * otherwise produce for a raw constraint violation.
         */
        @Test
        @DisplayName("concurrent registrations for one email produce exactly one user")
        void concurrentRegistrationCreatesExactlyOneUser() throws Exception {
            int attempts = 8;
            List<Callable<HttpStatus>> tasks = IntStream.range(0, attempts)
                    .<Callable<HttpStatus>>mapToObj(i ->
                            () -> postStatus("/api/auth/register", registerRequest()))
                    .toList();

            List<HttpStatus> statuses;
            try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
                statuses = pool.invokeAll(tasks).stream()
                        .map(AuthFlowIT::resolve)
                        .toList();
            }

            assertThat(statuses.stream().filter(s -> s == HttpStatus.CREATED).count())
                    .as("exactly one concurrent registration may succeed")
                    .isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == HttpStatus.CONFLICT).count())
                    .as("every loser must report a conflict, never a 500")
                    .isEqualTo(attempts - 1);

            assertThat(countUsers(EMAIL)).isEqualTo(1);
            assertThat(countRolesFor(EMAIL)).isEqualTo(1);
        }
    }

    // ================================================================== login

    @Nested
    @DisplayName("login and lockout")
    class Login {

        @Test
        @DisplayName("authenticates with correct credentials and clears the failure counter")
        void authenticatesAndClearsCounter() {
            register();
            failLogin("wrong-password");
            assertThat(failedLoginAttempts(EMAIL)).isEqualTo(1);

            Response<TokenResponse> success = post("/api/auth/login",
                    new LoginRequest(EMAIL, PASSWORD), TokenResponse.class);

            assertThat(success.status()).isEqualTo(HttpStatus.OK);
            assertThat(failedLoginAttempts(EMAIL))
                    .as("a successful login must clear the counter")
                    .isZero();
        }

        /**
         * <strong>Regression test for the lockout defect.</strong> The counter was
         * previously incremented on the account entity and saved inside {@code login}'s
         * own transaction, then discarded when the method threw — so it stayed at 0
         * forever and the account never locked. Asserting the committed column is the
         * only way to catch that; the 401 looked identical either way.
         */
        @Test
        @DisplayName("failed attempts are committed, not rolled back by the rejection")
        void failedAttemptsAreCommitted() {
            register();
            assertThat(failedLoginAttempts(EMAIL)).isZero();

            failLogin("wrong-1");
            assertThat(failedLoginAttempts(EMAIL))
                    .as("the increment must survive the 401 that reports the failure")
                    .isEqualTo(1);

            failLogin("wrong-2");
            assertThat(failedLoginAttempts(EMAIL)).isEqualTo(2);
        }

        @Test
        @DisplayName("locks the account once the threshold is reached")
        void locksAccountAtThreshold() {
            register();

            // Threshold is 3 in the test configuration.
            failLogin("wrong-1");
            failLogin("wrong-2");
            assertThat(isLocked(EMAIL)).isFalse();

            failLogin("wrong-3");

            assertThat(isLocked(EMAIL))
                    .as("the third failure must commit a lock")
                    .isTrue();
            assertThat(accountStatus(EMAIL)).isEqualTo("LOCKED");
            assertThat(countAuditEvents("ACCOUNT_LOCKED")).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses the correct password while locked, without revealing the lock")
        void lockedAccountRejectsEvenCorrectPassword() {
            register();
            failLogin("wrong-1");
            failLogin("wrong-2");
            failLogin("wrong-3");
            assertThat(isLocked(EMAIL)).isTrue();

            Response<ApiError> response = post("/api/auth/login",
                    new LoginRequest(EMAIL, PASSWORD), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code())
                    .as("a locked account must be indistinguishable from a wrong password")
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
        }

        @Test
        @DisplayName("returns an identical response for an unknown email and a wrong password")
        void doesNotLeakAccountExistence() {
            register();

            Response<ApiError> unknown = post("/api/auth/login",
                    new LoginRequest("nobody@example.com", PASSWORD), ApiError.class);
            Response<ApiError> wrongPassword = post("/api/auth/login",
                    new LoginRequest(EMAIL, "definitely-wrong"), ApiError.class);

            assertThat(unknown.status()).isEqualTo(wrongPassword.status());
            assertThat(unknown.body()).isNotNull();
            assertThat(wrongPassword.body()).isNotNull();
            assertThat(unknown.body().code()).isEqualTo(wrongPassword.body().code());
            assertThat(unknown.body().message()).isEqualTo(wrongPassword.body().message());
        }

        @Test
        @DisplayName("records every failed login in the audit trail")
        void auditsFailedLogins() {
            register();
            failLogin("wrong-1");
            failLogin("wrong-2");

            assertThat(countAuditEvents("LOGIN_FAILED")).isEqualTo(2);
        }
    }

    // ================================================== protected resources

    @Nested
    @DisplayName("protected endpoints")
    class ProtectedEndpoints {

        @Test
        @DisplayName("accepts a valid access token and returns the caller's own profile")
        void acceptsValidToken() {
            TokenResponse tokens = register();

            Response<UserProfileResponse> response = get("/api/users/me",
                    tokens.accessToken(), UserProfileResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().email()).isEqualTo(EMAIL);
            assertThat(response.body().userId()).isEqualTo(tokens.user().userId());
            assertThat(response.body().roles()).containsExactly(Roles.USER);
        }

        @Test
        @DisplayName("rejects a missing token")
        void rejectsMissingToken() {
            assertThat(get("/api/users/me", ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("rejects a malformed or forged token")
        void rejectsForgedToken() {
            assertThat(get("/api/users/me", "not.a.real.jwt", ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * Role enforcement. A freshly registered account holds only {@code USER}, so the
         * admin endpoint must refuse with 403 (authenticated but not permitted) rather
         * than 401.
         */
        @Test
        @DisplayName("denies an admin endpoint to a plain USER with 403")
        void deniesAdminEndpointToUser() {
            TokenResponse tokens = register();

            Response<ApiError> response = get("/api/admin/users", tokens.accessToken(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
        }

        /**
         * Roles are carried in the token, so a role granted afterwards only takes effect
         * once a new token is issued. Asserting both halves confirms the claim is
         * genuinely what drives the decision.
         */
        @Test
        @DisplayName("permits the admin endpoint once ADMIN is present in a newly issued token")
        void permitsAdminEndpointAfterRoleGrant() {
            register();
            grantRole(EMAIL, Roles.ADMIN);

            TokenResponse elevated = post("/api/auth/login",
                    new LoginRequest(EMAIL, PASSWORD), TokenResponse.class).body();
            assertThat(elevated).isNotNull();
            assertThat(elevated.user().roles()).contains(Roles.ADMIN);

            assertThat(get("/api/admin/users", elevated.accessToken(), String.class).status())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("denies an internal endpoint to a user token")
        void deniesInternalEndpointToUserToken() {
            TokenResponse tokens = register();

            Response<ApiError> response = get("/internal/users/" + tokens.user().userId(),
                    tokens.accessToken(), ApiError.class);

            assertThat(response.status())
                    .as("/internal/** requires ROLE_SERVICE, which a human token never carries")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ====================================================== refresh rotation

    @Nested
    @DisplayName("refresh rotation and reuse detection")
    class RefreshRotation {

        @Test
        @DisplayName("rotates the refresh token and consumes the old one")
        void rotatesRefreshToken() {
            TokenResponse initial = register();
            String userId = initial.user().userId();

            Response<TokenResponse> refreshed = post("/api/auth/refresh",
                    new RefreshRequest(initial.refreshToken()), TokenResponse.class);

            assertThat(refreshed.status()).isEqualTo(HttpStatus.OK);
            assertThat(refreshed.body()).isNotNull();
            assertThat(refreshed.body().refreshToken())
                    .as("rotation must hand back a different secret")
                    .isNotEqualTo(initial.refreshToken());

            assertThat(countTokensInState(userId, "CONSUMED")).isEqualTo(1);
            assertThat(countTokensInState(userId, "ACTIVE")).isEqualTo(1);
        }

        @Test
        @DisplayName("the rotated token continues to work")
        void rotatedTokenStillWorks() {
            TokenResponse initial = register();
            TokenResponse rotated = refresh(initial.refreshToken());

            assertThat(post("/api/auth/refresh",
                    new RefreshRequest(rotated.refreshToken()), TokenResponse.class).status())
                    .isEqualTo(HttpStatus.OK);
        }

        /**
         * <strong>Regression test for the reuse-detection defect.</strong> The family
         * revocation was previously issued inside {@code rotate}'s transaction and rolled
         * back by the 401, so the compromised family stayed ACTIVE while the metric and
         * audit trail both reported that the theft had been contained.
         *
         * <p>The HTTP status was 401 before and after the fix. Only the committed token
         * states distinguish them.
         */
        @Test
        @DisplayName("replaying a consumed token revokes the whole family, and it commits")
        void replayRevokesFamilyDurably() {
            TokenResponse initial = register();
            String userId = initial.user().userId();

            TokenResponse rotated = refresh(initial.refreshToken());
            assertThat(countTokensInState(userId, "ACTIVE")).isEqualTo(1);

            // Replay the already-consumed token.
            Response<ApiError> replay = post("/api/auth/refresh",
                    new RefreshRequest(initial.refreshToken()), ApiError.class);

            assertThat(replay.status()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // The assertion that actually matters.
            assertThat(countTokensInState(userId, "ACTIVE"))
                    .as("reuse detection must leave no ACTIVE token in the family; "
                            + "a value of 1 means the revocation was rolled back")
                    .isZero();
            assertThat(countTokensInState(userId, "REVOKED")).isPositive();
            assertThat(countAuditEvents("REFRESH_TOKEN_REUSE_DETECTED")).isEqualTo(1);

            // The token from the legitimate rotation is dead too. That is the intended
            // consequence: the user must sign in again.
            assertThat(post("/api/auth/refresh",
                    new RefreshRequest(rotated.refreshToken()), ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("concurrent reuse of one active token cannot mint two active successors")
        void concurrentRotationIsSerialized() throws Exception {
            TokenResponse initial = register();
            String userId = initial.user().userId();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Callable<HttpStatus> rotate = () -> {
                ready.countDown();
                start.await();
                return post("/api/auth/refresh", new RefreshRequest(initial.refreshToken()),
                        String.class).status();
            };

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<HttpStatus> first = executor.submit(rotate);
                Future<HttpStatus> second = executor.submit(rotate);
                ready.await();
                start.countDown();

                assertThat(List.of(resolve(first), resolve(second)))
                        .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
            }

            assertThat(countTokensInState(userId, "CONSUMED"))
                    .as("family revocation upgrades the consumed row to REVOKED")
                    .isZero();
            assertThat(countTokensInState(userId, "ACTIVE"))
                    .as("the losing concurrent refresh is a replay and revokes the issued family")
                    .isZero();
            assertThat(countTokensInState(userId, "REVOKED")).isEqualTo(2);
            assertThat(countAuditEvents("REFRESH_TOKEN_REUSE_DETECTED")).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects an unrecognised refresh token")
        void rejectsUnknownRefreshToken() {
            register();

            assertThat(post("/api/auth/refresh",
                    new RefreshRequest("completely-made-up-token"), ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // =============================================================== logout

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("revokes the session and the revocation commits")
        void logoutRevokesSession() {
            TokenResponse tokens = register();
            String userId = tokens.user().userId();

            Response<Void> response = post("/api/auth/logout",
                    Map.of("refreshToken", tokens.refreshToken(), "allSessions", false),
                    tokens.accessToken(), Void.class);

            assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(countTokensInState(userId, "ACTIVE")).isZero();
            assertThat(countTokensInState(userId, "REVOKED")).isEqualTo(1);

            assertThat(post("/api/auth/refresh",
                    new RefreshRequest(tokens.refreshToken()), ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("revokes every session when asked")
        void logoutAllSessions() {
            register();
            // Three further logins => three more families.
            TokenResponse a = loginOk();
            TokenResponse b = loginOk();
            TokenResponse c = loginOk();
            String userId = c.user().userId();

            assertThat(countTokensInState(userId, "ACTIVE")).isEqualTo(4);

            post("/api/auth/logout", Map.of("allSessions", true), c.accessToken(), Void.class);

            assertThat(countTokensInState(userId, "ACTIVE")).isZero();
            assertThat(post("/api/auth/refresh",
                    new RefreshRequest(a.refreshToken()), ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(post("/api/auth/refresh",
                    new RefreshRequest(b.refreshToken()), ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ====================================================== password change

    @Nested
    @DisplayName("password change")
    class PasswordChange {

        @Test
        @DisplayName("changes the password and revokes every existing session")
        void changePasswordRevokesSessions() {
            TokenResponse tokens = register();
            String userId = tokens.user().userId();
            String newPassword = "an0ther-Str0ng-Passphrase!";

            Response<Void> response = post("/api/users/me/change-password",
                    new ChangePasswordRequest(PASSWORD, newPassword), tokens.accessToken(), Void.class);

            assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);

            // A password change exists because credentials may be compromised; leaving
            // sessions alive would defeat the point.
            assertThat(countTokensInState(userId, "ACTIVE")).isZero();

            assertThat(post("/api/auth/login",
                    new LoginRequest(EMAIL, PASSWORD), ApiError.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(post("/api/auth/login",
                    new LoginRequest(EMAIL, newPassword), TokenResponse.class).status())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("requires the current password and changes nothing on failure")
        void requiresCurrentPassword() {
            TokenResponse tokens = register();
            String userId = tokens.user().userId();

            Response<ApiError> response = post("/api/users/me/change-password",
                    new ChangePasswordRequest("not-the-current-one", "Brand-New-Passw0rd!"),
                    tokens.accessToken(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            // Sessions untouched and the original password still works.
            assertThat(countTokensInState(userId, "ACTIVE")).isEqualTo(1);
            assertThat(post("/api/auth/login",
                    new LoginRequest(EMAIL, PASSWORD), TokenResponse.class).status())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ================================================================= JWKS

    @Nested
    @DisplayName("JWKS")
    class Jwks {

        @Test
        @DisplayName("publishes public key material and no private key")
        void publishesPublicKeysOnly() {
            Response<String> response = get("/.well-known/jwks.json", String.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            String body = response.body();
            assertThat(body).isNotNull();

            // Public RSA parameters: modulus and exponent.
            assertThat(body).contains("\"kty\":\"RSA\"").contains("\"n\":").contains("\"e\":");

            // Private parameters must never appear: d is the private exponent, p/q the
            // primes, dp/dq/qi the CRT values. Any of them would leak the signing key.
            assertThat(body)
                    .as("JWKS must never expose private key parameters")
                    .doesNotContain("\"d\":")
                    .doesNotContain("\"p\":")
                    .doesNotContain("\"q\":")
                    .doesNotContain("\"dp\":")
                    .doesNotContain("\"dq\":")
                    .doesNotContain("\"qi\":");
        }

        @Test
        @DisplayName("is reachable without authentication")
        void isPublic() {
            // Every other service fetches this to validate tokens; requiring a token to
            // fetch the key that validates tokens would be circular.
            assertThat(get("/.well-known/jwks.json", String.class).status()).isEqualTo(HttpStatus.OK);
        }
    }

    // ---------------------------------------------------------------- helpers

    private RegisterRequest registerRequest() {
        return new RegisterRequest(EMAIL, PASSWORD, FULL_NAME, "");
    }

    private TokenResponse register() {
        Response<TokenResponse> response = post("/api/auth/register", registerRequest(),
                TokenResponse.class);
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body()).isNotNull();
        return response.body();
    }

    private TokenResponse loginOk() {
        Response<TokenResponse> response = post("/api/auth/login",
                new LoginRequest(EMAIL, PASSWORD), TokenResponse.class);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.body()).isNotNull();
        return response.body();
    }

    /** Attempts a login expected to fail, discarding the error body. */
    private void failLogin(String password) {
        post("/api/auth/login", new LoginRequest(EMAIL, password), ApiError.class);
    }

    private TokenResponse refresh(String refreshToken) {
        Response<TokenResponse> response = post("/api/auth/refresh",
                new RefreshRequest(refreshToken), TokenResponse.class);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.body()).isNotNull();
        return response.body();
    }

    private static <T> T resolve(Future<T> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new IllegalStateException("concurrent request failed", ex);
        }
    }
}
