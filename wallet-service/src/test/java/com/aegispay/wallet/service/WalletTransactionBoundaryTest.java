package com.aegispay.wallet.service;

import com.aegispay.common.idempotency.IdempotencyRecordRepository;
import com.aegispay.wallet.api.InternalWalletController;
import com.aegispay.wallet.api.WalletController;
import com.aegispay.wallet.repository.WalletHoldRepository;
import com.aegispay.wallet.repository.WalletRepository;
import com.aegispay.wallet.repository.WalletTransactionRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on wallet-service's transaction and locking boundaries.
 *
 * <p>Two classes of bug are pinned here, both of which are invisible to a unit test with
 * mocked repositories and only surface as corrupted balances under real concurrency:
 *
 * <ol>
 *   <li><strong>Self-invocation.</strong> Spring applies transaction advice through a proxy,
 *       so a {@code @Transactional} method called as {@code this.method()} runs
 *       <em>untransacted</em>. An earlier draft of {@link WalletService} had exactly that —
 *       {@code @Transactional protected} methods invoked from a non-transactional public
 *       method — which would have written balance changes with no transaction around them.
 *       The fix was to move them behind a real bean boundary, and this test keeps them
 *       there.</li>
 *   <li><strong>Missing row locks.</strong> Every balance mutation is a read-modify-write.
 *       Without {@code SELECT ... FOR UPDATE}, two concurrent withdrawals both read ₹100,
 *       both approve ₹80, and the customer spends ₹160 they never had.</li>
 * </ol>
 *
 * <p>Runs in milliseconds with no database, so it guards every build rather than only the
 * ones where Docker is available.
 */
class WalletTransactionBoundaryTest {

    /**
     * {@link WalletMutationService} exists purely to be a proxied bean boundary for the
     * transactional writes. If it were merged back into {@link WalletService}, the
     * annotations on its methods would stop taking effect.
     */
    @Test
    @DisplayName("balance mutations live on a bean separate from the orchestrator")
    void mutationsAreOnTheirOwnBean() {
        assertThat(WalletMutationService.class)
                .as("merging these would make every @Transactional here a no-op, because "
                        + "WalletService calls them and is itself non-transactional")
                .isNotEqualTo(WalletService.class);
    }

    @ParameterizedTest
    @DisplayName("every balance mutation is transactional")
    @ValueSource(strings = {"deposit", "withdraw", "reserve", "captureAndTransfer",
            "release", "releaseExpired", "freeze", "unfreeze"})
    void mutationsAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(WalletMutationService.class, methodName);
        assertThat(methods).as("expected WalletMutationService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("WalletMutationService.%s mutates a balance and must be transactional",
                            methodName)
                    .isNotNull();
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .as("WalletMutationService.%s must be public, or Spring's proxy cannot "
                            + "advise it and the transaction would be silently absent", methodName)
                    .isTrue();
        }
    }

    /**
     * The orchestrator wraps writes in idempotency, whose claim must commit <em>before</em>
     * the balance changes so a concurrent duplicate can be detected. A transaction spanning
     * both would make the claim invisible to other transactions and let a duplicate through.
     */
    @ParameterizedTest
    @DisplayName("idempotent write orchestration is not transactional")
    @ValueSource(strings = {"deposit", "withdraw"})
    void orchestrationIsNotTransactional(String methodName) {
        for (Method method : methodsNamed(WalletService.class, methodName)) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("WalletService.%s must stay non-transactional: the idempotency claim "
                            + "has to commit before the balance moves", methodName)
                    .isNull();
        }
    }

    /**
     * The lock that actually prevents lost updates. Without it the CHECK constraints would
     * still stop a negative balance, but only by failing the transaction — the read-modify-write
     * race would be a routine occurrence rather than an impossibility.
     */
    @Test
    @DisplayName("wallets are loaded FOR UPDATE when they are about to be modified")
    void mutatingReadsTakeARowLock() {
        for (String finder : List.of("findByWalletIdForUpdate", "findByUserAndCurrencyForUpdate")) {
            Method method = methodsNamed(WalletRepository.class, finder).getFirst();
            Lock lock = method.getAnnotation(Lock.class);

            assertThat(lock)
                    .as("%s must take a pessimistic write lock, or two concurrent debits can "
                            + "both read the same balance and both succeed", finder)
                    .isNotNull();
            assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }
    }

    /**
     * A bounded lock timeout. Waiting indefinitely behind a stuck transaction would hold a
     * request thread until the connection died; failing fast lets the client retry.
     */
    @Test
    @DisplayName("locking reads declare a timeout rather than waiting forever")
    void lockingReadsHaveATimeout() {
        Method method = methodsNamed(WalletRepository.class, "findByWalletIdForUpdate").getFirst();
        var hints = method.getAnnotation(org.springframework.data.jpa.repository.QueryHints.class);

        assertThat(hints).isNotNull();
        assertThat(Arrays.stream(hints.value())
                .anyMatch(h -> h.name().equals("jakarta.persistence.lock.timeout")))
                .as("an unbounded lock wait pins a request thread behind a stuck transaction")
                .isTrue();
    }

    /**
     * A JPQL bulk statement bypasses the persistence context, so pending entity changes must
     * be flushed first and stale entities cleared afterwards. Same rule the auth service
     * needed after its refresh-token revocations were being silently overwritten.
     */
    @ParameterizedTest
    @DisplayName("every @Modifying query flushes and clears")
    @ValueSource(classes = {WalletRepository.class, WalletHoldRepository.class,
            WalletTransactionRepository.class, IdempotencyRecordRepository.class})
    void modifyingQueriesFlushAndClear(Class<?> repositoryType) {
        for (Method method : repositoryType.getDeclaredMethods()) {
            Modifying modifying = method.getAnnotation(Modifying.class);
            if (modifying == null) {
                continue;
            }
            assertThat(modifying.flushAutomatically())
                    .as("%s.%s", repositoryType.getSimpleName(), method.getName())
                    .isTrue();
            assertThat(modifying.clearAutomatically())
                    .as("%s.%s", repositoryType.getSimpleName(), method.getName())
                    .isTrue();
        }
    }

    @ParameterizedTest
    @DisplayName("controllers declare no transactions")
    @ValueSource(classes = {WalletController.class, InternalWalletController.class})
    void controllersAreNotTransactional(Class<?> controllerType) {
        assertThat(controllerType.getAnnotation(Transactional.class)).isNull();
        for (Method method : controllerType.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("%s.%s", controllerType.getSimpleName(), method.getName())
                    .isNull();
        }
    }

    private List<Method> methodsNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .toList();
    }
}
