package com.aegispay.auth.service;

import com.aegispay.auth.api.AuthController;
import com.aegispay.auth.api.JwksController;
import com.aegispay.auth.api.UserController;
import com.aegispay.auth.repository.AuditLogRepository;
import com.aegispay.auth.repository.RefreshTokenRepository;
import com.aegispay.auth.repository.ServiceClientRepository;
import com.aegispay.auth.repository.UserAccountRepository;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import com.aegispay.common.outbox.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on the transaction boundaries of the authentication flows.
 *
 * <p><strong>Why assert annotations rather than behaviour.</strong> Three defects
 * found in review all had the same shape: a security-relevant write enlisted in a
 * transaction that was then rolled back by the rejection it was recording. That class
 * of bug is invisible in a unit test with mocked repositories (the mock records the
 * call regardless) and only shows up against a real database — which needs Docker,
 * and therefore does not run on every developer's machine or on a build without a
 * container runtime.
 *
 * <p>These tests close that gap. They run in milliseconds with no infrastructure and
 * fail immediately if someone reintroduces the original shape — for instance by adding
 * {@code @Transactional} back onto {@code AuthService.login}, which would silently
 * disable account lockout again with no test going red anywhere else.
 *
 * <p>They complement rather than replace the Testcontainers suite, which asserts the
 * actual committed outcome.
 */
class TransactionBoundaryTest {

    // -------------------------------------------------- orchestration is untransacted

    /**
     * {@code AuthService} orchestrates; its collaborators own the transactions.
     *
     * <p>If a transaction were opened here it would span BCrypt verification (~250ms
     * of CPU holding a pooled connection) and, worse, it would roll back the
     * failed-login counter written by {@link LoginAttemptService} the moment the
     * method threw — the exact defect this shape exists to prevent.
     */
    @Test
    @DisplayName("AuthService is not transactional at class level")
    void authServiceHasNoClassLevelTransaction() {
        assertThat(AuthService.class.getAnnotation(Transactional.class))
                .as("A class-level @Transactional on AuthService would roll back the "
                        + "failed-login counter whenever login() throws, silently disabling lockout")
                .isNull();
    }

    @ParameterizedTest
    @DisplayName("AuthService entry points are not transactional")
    @ValueSource(strings = {"register", "login", "refresh", "logout"})
    void authServiceMethodsAreNotTransactional(String methodName) {
        List<Method> methods = methodsNamed(AuthService.class, methodName);
        assertThat(methods)
                .as("expected to find AuthService.%s", methodName)
                .isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("AuthService.%s must stay non-transactional: it throws to reject "
                            + "requests, and a surrounding transaction would discard the "
                            + "security state written just before the throw", methodName)
                    .isNull();
        }
    }

    /**
     * {@code changePassword} performs up to three BCrypt operations (~750ms). A
     * transaction here would hold a connection for that entire duration.
     */
    @Test
    @DisplayName("UserService.changePassword is not transactional")
    void changePasswordIsNotTransactional() {
        Method method = methodsNamed(UserService.class, "changePassword").getFirst();
        assertThat(method.getAnnotation(Transactional.class))
                .as("changePassword runs up to three BCrypt operations; a transaction "
                        + "would pin a database connection for ~750ms of pure CPU")
                .isNull();
    }

    // ------------------------------------------- security writes commit independently

    @ParameterizedTest
    @DisplayName("LoginAttemptService commits independently of the caller")
    @ValueSource(strings = {"recordFailure", "recordSuccess"})
    void loginAttemptWritesRequireNewTransaction(String methodName) {
        assertRequiresNew(LoginAttemptService.class, methodName,
                "the caller throws immediately afterwards to reject the login, so a "
                        + "joined transaction would roll this write back and lockout would never engage");
    }

    @ParameterizedTest
    @DisplayName("TokenRevocationService commits independently of the caller")
    @ValueSource(strings = {"revokeFamilyImmediately", "revokeAllSessionsImmediately"})
    void revocationsRequireNewTransaction(String methodName) {
        assertRequiresNew(TokenRevocationService.class, methodName,
                "reuse detection and disabled-account refresh both revoke and then throw; "
                        + "a joined transaction would leave the compromised tokens ACTIVE");
    }

    /**
     * These two must be on a bean separate from their callers. Spring applies
     * transaction advice through a proxy, so a self-invocation bypasses it entirely and
     * the {@code REQUIRES_NEW} would be silently ignored — which is precisely how the
     * original reuse-detection bug arose, from a private method called on {@code this}.
     */
    @Test
    @DisplayName("independent-transaction helpers live on their own beans, so the proxy applies")
    void independentWritersAreSeparateBeans() {
        assertThat(LoginAttemptService.class).isNotEqualTo(AuthService.class);
        assertThat(TokenRevocationService.class)
                .isNotEqualTo(RefreshTokenService.class)
                .isNotEqualTo(AuthService.class);

        // And they must be public so the proxy can advise them.
        assertRequiresNewMethodsArePublic(LoginAttemptService.class);
        assertRequiresNewMethodsArePublic(TokenRevocationService.class);
    }

    // --------------------------------------------------- rotation stays atomic

    /**
     * The one place a transaction is genuinely required: consuming the presented token
     * and inserting its successor must be atomic, or a crash between them logs the user
     * out with no replacement token issued.
     */
    @Test
    @DisplayName("refresh-token rotation is transactional")
    void rotationIsTransactional() {
        Method rotate = methodsNamed(RefreshTokenService.class, "rotate").getFirst();
        Transactional annotation = rotate.getAnnotation(Transactional.class);

        assertThat(annotation)
                .as("consuming the old token and creating its successor must be atomic")
                .isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(annotation.noRollbackFor())
                .as("reuse revocation occurs while this transaction owns the token row lock; "
                        + "the 401 must commit that revocation rather than roll it back")
                .containsExactly(UnauthorizedException.class);
    }

    // ------------------------------------------------- bulk updates are safe

    /**
     * A JPQL bulk {@code UPDATE}/{@code DELETE} bypasses the persistence context. Both
     * flags are needed: {@code flushAutomatically} so pending entity changes reach the
     * database first (otherwise a later flush can overwrite the rows the bulk statement
     * just modified), and {@code clearAutomatically} so already-loaded entities do not
     * keep serving stale pre-update state for the rest of the transaction.
     */
    @ParameterizedTest
    @DisplayName("every @Modifying query flushes and clears the persistence context")
    @ValueSource(classes = {
            UserAccountRepository.class,
            RefreshTokenRepository.class,
            AuditLogRepository.class,
            ServiceClientRepository.class,
            OutboxEventRepository.class
    })
    void modifyingQueriesFlushAndClear(Class<?> repositoryType) {
        List<Method> modifying = java.util.Arrays.stream(repositoryType.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Modifying.class))
                .toList();

        for (Method method : modifying) {
            Modifying annotation = method.getAnnotation(Modifying.class);

            assertThat(annotation.flushAutomatically())
                    .as("%s.%s: without flushAutomatically, pending entity changes have not "
                                    + "reached the database and a later flush can overwrite the rows "
                                    + "this bulk statement modified",
                            repositoryType.getSimpleName(), method.getName())
                    .isTrue();

            assertThat(annotation.clearAutomatically())
                    .as("%s.%s: without clearAutomatically, entities already loaded keep stale "
                                    + "pre-update state for the rest of the transaction",
                            repositoryType.getSimpleName(), method.getName())
                    .isTrue();
        }
    }

    // ---------------------------------------------- controllers stay transaction-free

    /**
     * A transaction opened at the controller would span response serialisation and,
     * for these endpoints, BCrypt work — reintroducing the long-transaction problem one
     * layer up.
     */
    @ParameterizedTest
    @DisplayName("controllers declare no transactions")
    @ValueSource(classes = {AuthController.class, UserController.class, JwksController.class})
    void controllersAreNotTransactional(Class<?> controllerType) {
        assertThat(controllerType.getAnnotation(Transactional.class))
                .as("%s must not be transactional", controllerType.getSimpleName())
                .isNull();

        for (Method method : controllerType.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("%s.%s must not be transactional",
                            controllerType.getSimpleName(), method.getName())
                    .isNull();
        }
    }

    // ----------------------------------------------------------------- helpers

    private void assertRequiresNew(Class<?> type, String methodName, String why) {
        List<Method> methods = methodsNamed(type, methodName);
        assertThat(methods).as("expected to find %s.%s", type.getSimpleName(), methodName).isNotEmpty();

        for (Method method : methods) {
            Transactional annotation = method.getAnnotation(Transactional.class);
            assertThat(annotation)
                    .as("%s.%s must be @Transactional: %s", type.getSimpleName(), methodName, why)
                    .isNotNull();
            assertThat(annotation.propagation())
                    .as("%s.%s must use REQUIRES_NEW: %s", type.getSimpleName(), methodName, why)
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    private void assertRequiresNewMethodsArePublic(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            Transactional annotation = method.getAnnotation(Transactional.class);
            if (annotation != null && annotation.propagation() == Propagation.REQUIRES_NEW) {
                assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                        .as("%s.%s carries REQUIRES_NEW but is not public, so Spring's proxy "
                                        + "cannot advise it and the propagation would be ignored",
                                type.getSimpleName(), method.getName())
                        .isTrue();
            }
        }
    }

    private List<Method> methodsNamed(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .toList();
    }
}
