package com.aegispay.ledger.service;

import com.aegispay.ledger.api.InternalLedgerController;
import com.aegispay.ledger.api.LedgerController;
import com.aegispay.ledger.repository.LedgerAccountRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on ledger-service's transaction, locking and authorisation boundaries.
 *
 * <p>Everything asserted here is invisible to a unit test with mocked repositories, and each
 * would fail in a way that looks like something else:
 *
 * <ol>
 *   <li><strong>Self-invocation.</strong> Spring applies transaction advice through a proxy, so
 *       a {@code @Transactional} method called as {@code this.method()} runs untransacted. The
 *       auth service learned this the hard way in Phase 2, where a private self-called method
 *       meant refresh-token revocation silently revoked nothing.</li>
 *   <li><strong>A transactional orchestrator.</strong> {@link LedgerService} must <em>not</em>
 *       be transactional: it recovers from a duplicate-key violation by re-reading, and in
 *       PostgreSQL a constraint violation aborts the whole transaction, so the re-read has to
 *       happen in a fresh one.</li>
 *   <li><strong>Missing row locks.</strong> Applying a posting is a read-modify-write on an
 *       account's running totals. Without {@code SELECT ... FOR UPDATE} an increment is lost
 *       and the cached balance drifts from the journal.</li>
 *   <li><strong>An unguarded journal.</strong> The gateway routes {@code /api/ledger/**} here,
 *       and the shared security chain only auto-restricts {@code /api/admin/**}. Without
 *       method security the entire journal would be readable by any authenticated customer.</li>
 * </ol>
 *
 * <p>Runs in milliseconds with no infrastructure, so it guards every build rather than only the
 * ones where Docker is available.
 */
class LedgerTransactionBoundaryTest {

    // ------------------------------------------------------- bean boundaries

    /**
     * {@link JournalWriter} exists to be a proxied bean boundary. Merging it into
     * {@link LedgerService} would make every {@code @Transactional} on it a no-op, because
     * {@code LedgerService} calls it and is itself deliberately non-transactional.
     */
    @Test
    @DisplayName("the journal writer is a bean separate from the orchestrator")
    void writerIsItsOwnBean() {
        assertThat(JournalWriter.class)
                .as("merging these would silently drop every transaction on the write path")
                .isNotEqualTo(LedgerService.class);
    }

    @Test
    @DisplayName("account provisioning is a bean separate from the writer")
    void provisionerIsItsOwnBean() {
        // REQUIRES_NEW is ignored on a self-invocation, so provisioning has to be reachable
        // through a proxy or its separate commit would not happen at all.
        assertThat(LedgerAccountProvisioner.class).isNotEqualTo(JournalWriter.class);
    }

    // ---------------------------------------------------------- transactions

    @ParameterizedTest
    @DisplayName("every journal write is transactional and public")
    @ValueSource(strings = {"append", "appendReversal"})
    void writesAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(JournalWriter.class, methodName);
        assertThat(methods).as("expected JournalWriter.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("JournalWriter.%s writes the journal and must be transactional", methodName)
                    .isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("JournalWriter.%s must be public, or Spring's proxy cannot advise it "
                            + "and the transaction would be silently absent", methodName)
                    .isTrue();
        }
    }

    /**
     * The load-bearing assertion of this class. {@code post} catches
     * {@code DataIntegrityViolationException} and re-reads the winning posting; that re-read
     * only works because no transaction is open when the violation aborts the writer's one.
     */
    @ParameterizedTest
    @DisplayName("posting orchestration is not transactional")
    @ValueSource(strings = {"post", "reverse"})
    void orchestrationIsNotTransactional(String methodName) {
        for (Method method : methodsNamed(LedgerService.class, methodName)) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("LedgerService.%s must stay non-transactional: it recovers from a "
                            + "duplicate-key violation by re-reading, which needs a fresh "
                            + "transaction because the violation aborts the failed one",
                            methodName)
                    .isNull();
        }
        assertThat(LedgerService.class.getAnnotation(Transactional.class))
                .as("a class-level @Transactional would defeat the same recovery path")
                .isNull();
    }

    /**
     * Provisioning must commit before the posting starts. Sharing the posting's transaction
     * would mean a collision over an empty account row aborts the whole posting.
     */
    @Test
    @DisplayName("account provisioning commits in its own transaction")
    void provisioningUsesRequiresNew() {
        for (String methodName : List.of("ensureWalletAccount", "requireSystemAccount")) {
            Method method = methodsNamed(LedgerAccountProvisioner.class, methodName).getFirst();
            Transactional transactional = method.getAnnotation(Transactional.class);

            assertThat(transactional)
                    .as("LedgerAccountProvisioner.%s must be transactional", methodName)
                    .isNotNull();
            assertThat(transactional.propagation())
                    .as("%s must use REQUIRES_NEW so a unique-constraint collision over a new "
                            + "account row cannot abort the posting that triggered it", methodName)
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    @ParameterizedTest
    @DisplayName("reconciliation never writes")
    @ValueSource(strings = {"trialBalance", "reconcileAccount", "reconcileLedger",
            "walletLiabilities"})
    void reconciliationIsReadOnly(String methodName) {
        List<Method> methods = methodsNamed(ReconciliationService.class, methodName);
        assertThat(methods).isNotEmpty();

        for (Method method : methods) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional)
                    .as("ReconciliationService.%s must declare a transaction", methodName)
                    .isNotNull();
            assertThat(transactional.readOnly())
                    .as("""
                            ReconciliationService.%s must be readOnly. A check that repaired \
                            what it found would destroy the evidence of how the discrepancy \
                            arose, and the cause matters far more than the symptom.""",
                            methodName)
                    .isTrue();
        }
    }

    @ParameterizedTest
    @DisplayName("journal queries are read-only transactions")
    @ValueSource(strings = {"getPostingBySequence", "getPostingsForPayment", "getAccount",
            "listAccounts", "accountStatement", "journal", "findReversalOf"})
    void queriesAreReadOnly(String methodName) {
        for (Method method : methodsNamed(LedgerService.class, methodName)) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional)
                    .as("LedgerService.%s should run in a read-only transaction", methodName)
                    .isNotNull();
            assertThat(transactional.readOnly()).isTrue();
        }
    }

    // --------------------------------------------------------------- locking

    @Test
    @DisplayName("accounts are loaded FOR UPDATE before being modified")
    void mutatingReadsTakeARowLock() {
        Method method = methodsNamed(LedgerAccountRepository.class, "findByAccountIdForUpdate")
                .getFirst();
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock)
                .as("without a pessimistic write lock, two concurrent postings both read the "
                        + "same totals and one increment is lost")
                .isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * The deadlock-avoidance rule. Two postings touching the same accounts in opposite orders
     * would each hold the row the other needs, and PostgreSQL would kill one. Sorting means
     * every caller acquires locks in the same sequence, so no cycle can form.
     */
    @Test
    @DisplayName("the writer sorts accounts before locking them")
    void locksAreAcquiredInSortedOrder() {
        assertThat(methodsNamed(JournalWriter.class, "lockAccountsInOrder"))
                .as("""
                        JournalWriter must funnel every lock acquisition through one method that \
                        sorts, rather than locking lazily in payload order. Renaming or inlining \
                        this method is how the sort quietly disappears.""")
                .isNotEmpty();
    }

    // --------------------------------------------------------- authorisation

    /**
     * {@code /api/ledger/**} does not match the shared chain's {@code /api/admin/**} rule, so
     * the annotation is the only thing standing between a customer token and every other
     * customer's movements.
     */
    @Test
    @DisplayName("the staff-facing ledger API is restricted by method security")
    void ledgerApiRequiresStaffRole() {
        PreAuthorize preAuthorize = LedgerController.class.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
                .as("""
                        LedgerController must carry a class-level @PreAuthorize. The gateway \
                        routes /api/ledger/** to this service and the shared security chain only \
                        auto-restricts /api/admin/**, so without this the whole journal is \
                        readable by any authenticated customer.""")
                .isNotNull();
        assertThat(preAuthorize.value())
                .contains("ROLE_ADMIN")
                .contains("ROLE_FRAUD_ANALYST");
    }

    @Test
    @DisplayName("the internal ledger API requires a service token")
    void internalApiRequiresServiceRole() {
        PreAuthorize preAuthorize =
                InternalLedgerController.class.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
                .as("posting changes the platform's book of record and must never be reachable "
                        + "with a human token")
                .isNotNull();
        assertThat(preAuthorize.value()).contains("ROLE_SERVICE");
    }

    /**
     * There is deliberately no write path on the staff API. A posting is a consequence of a
     * business event, never something typed in by hand; an operator correction goes through
     * {@code /internal/ledger} as an explicit, attributed adjustment.
     */
    @Test
    @DisplayName("the staff-facing ledger API is read-only")
    void ledgerApiIsReadOnly() {
        List<String> writeHandlers = Arrays.stream(LedgerController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.isAnnotationPresent(
                                org.springframework.web.bind.annotation.PostMapping.class)
                        || method.isAnnotationPresent(
                                org.springframework.web.bind.annotation.PutMapping.class)
                        || method.isAnnotationPresent(
                                org.springframework.web.bind.annotation.PatchMapping.class)
                        || method.isAnnotationPresent(
                                org.springframework.web.bind.annotation.DeleteMapping.class))
                .map(Method::getName)
                .toList();

        assertThat(writeHandlers)
                .as("the journal must not be writable by hand through the staff API")
                .isEmpty();
    }

    // ------------------------------------------------------------- internals

    private static List<Method> methodsNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
    }
}
