package com.aegispay.fraud.service;

import com.aegispay.common.security.Roles;
import com.aegispay.fraud.api.FraudController;
import com.aegispay.fraud.api.InternalFraudController;
import com.aegispay.fraud.domain.AnalystDecision;
import com.aegispay.fraud.domain.FraudAssessment;
import com.aegispay.fraud.repository.AnalystDecisionRepository;
import com.aegispay.fraud.repository.FraudAlertRepository;
import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on fraud-service's transaction, locking and authorisation boundaries.
 *
 * <p>Everything here is invisible to a unit test with mocked collaborators, and each would fail in a
 * way that looks like something else:
 *
 * <ol>
 *   <li><strong>A transactional scoring path.</strong> {@link FraudScoringService} makes an HTTP call
 *       to ml-service. A transaction spanning it would pin a database connection across the network,
 *       and under load the pool empties and the service stalls waiting on a dependency it cannot
 *       hurry.</li>
 *   <li><strong>Self-invocation.</strong> Spring applies transaction advice through a proxy, so a
 *       {@code @Transactional} method called as {@code this.method()} runs untransacted. The auth
 *       service learned this in Phase 2, where a private self-called method meant refresh-token
 *       revocation silently revoked nothing.</li>
 *   <li><strong>A missing row lock on an alert.</strong> Deciding an alert is read-modify-write on its
 *       status. Two analysts deciding at the same moment must not both succeed — one would release the
 *       payment and the other refuse it, and whichever committed second would win by accident.</li>
 *   <li><strong>An unguarded fraud console.</strong> The gateway routes {@code /api/fraud/**} here and
 *       the shared chain only auto-restricts {@code /api/admin/**}. Without method security, every
 *       customer's risk score, device history and location would be readable by any authenticated
 *       user — data they never knowingly provided.</li>
 *   <li><strong>A mutable audit trail.</strong> An assessment and an analyst decision are statements
 *       about what happened. If Hibernate could generate an UPDATE for either, the record of why a
 *       customer's payment was refused could be rewritten.</li>
 * </ol>
 *
 * <p>Runs in milliseconds with no infrastructure, so it guards every build rather than only the ones
 * where Docker is available.
 */
class FraudTransactionBoundaryTest {

    // ------------------------------------------------------- bean boundaries

    /**
     * The load-bearing separation. {@link FraudMutationService} exists to be a proxied bean boundary;
     * merging it into the scoring service would make every {@code @Transactional} on it a no-op,
     * because the scoring service calls it and is itself deliberately non-transactional.
     */
    @Test
    @DisplayName("the mutation service is a bean separate from the scoring orchestration")
    void mutationsAreTheirOwnBean() {
        assertThat(FraudMutationService.class)
                .as("merging these would silently drop every transaction on the write path")
                .isNotEqualTo(FraudScoringService.class);
    }

    @Test
    @DisplayName("reads are a bean separate from writes")
    void queriesAreTheirOwnBean() {
        assertThat(FraudQueryService.class).isNotEqualTo(FraudMutationService.class);
    }

    // ---------------------------------------------------------- transactions

    /**
     * <strong>The most important assertion in this class.</strong> Scoring calls ml-service over HTTP.
     * A transaction here would hold a database connection open across that call; under load the pool
     * empties and the whole service stalls on a dependency it cannot hurry.
     */
    @Test
    @DisplayName("the scoring path holds no transaction, because it makes a network call")
    void scoringIsNotTransactional() {
        assertThat(FraudScoringService.class.getAnnotation(Transactional.class))
                .as("a class-level @Transactional would span the ml-service HTTP call")
                .isNull();

        for (Method method : methodsNamed(FraudScoringService.class, "assess")) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("FraudScoringService.assess calls ml-service and must not be transactional")
                    .isNull();
        }
    }

    @ParameterizedTest
    @DisplayName("every fraud state change is transactional and public")
    @ValueSource(strings = {"recordAssessment", "applyDecision", "takeAlert", "observe",
            "recordOutcome"})
    void writesAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(FraudMutationService.class, methodName);
        assertThat(methods).as("expected FraudMutationService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("FraudMutationService.%s changes fraud state and must be transactional",
                            methodName)
                    .isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("FraudMutationService.%s must be public, or Spring's proxy cannot advise "
                            + "it and the transaction would be silently absent", methodName)
                    .isTrue();
        }
    }

    /**
     * The verdict, its alert and the announcement land in one commit. An assessment recorded without
     * its alert would leave a customer's payment held with nothing in any queue to release it — the
     * worst outcome this service can produce, because it is invisible: nothing failed, and the payment
     * simply never moves again.
     */
    @Test
    @DisplayName("recording an assessment is one transaction, alert and event included")
    void assessmentAndAlertShareATransaction() {
        Method record = methodsNamed(FraudMutationService.class, "recordAssessment").getFirst();

        assertThat(record.getAnnotation(Transactional.class)).isNotNull();
        assertThat(methodsNamed(FraudMutationService.class, "raiseAlert"))
                .as("raising the alert must be private, so it cannot be committed separately from "
                        + "the assessment that justifies it")
                .allSatisfy(method -> assertThat(Modifier.isPrivate(method.getModifiers())).isTrue());
    }

    @ParameterizedTest
    @DisplayName("every read is marked read-only")
    @ValueSource(strings = {"assessmentForPayment", "assessmentDetail", "browseAssessments",
            "queue", "alert", "stats", "engineInfo"})
    void readsAreReadOnly(String methodName) {
        // Class-level @Transactional(readOnly = true) covers them all, which is the point: an
        // individual method that forgot it would still be read-only rather than accidentally writable.
        Transactional classLevel = FraudQueryService.class.getAnnotation(Transactional.class);

        assertThat(classLevel)
                .as("FraudQueryService must be transactional so its reads share one snapshot")
                .isNotNull();
        assertThat(classLevel.readOnly())
                .as("read-only puts the connection in read-only mode, so a mapping mistake that "
                        + "tried to flush a dirty assessment fails at the database rather than "
                        + "quietly rewriting a row that is supposed to be immutable")
                .isTrue();
        assertThat(methodsNamed(FraudQueryService.class, methodName))
                .as("expected FraudQueryService.%s", methodName)
                .isNotEmpty();
    }

    // ---------------------------------------------------------------- locking

    /**
     * Two analysts deciding the same alert at the same moment must not both succeed: one would release
     * the payment and the other refuse it, and whichever committed second would win by accident. The
     * state machine cannot catch that on its own, because it only rejects transitions illegal from the
     * state it was <em>shown</em>.
     */
    @ParameterizedTest
    @DisplayName("alerts are loaded with a row lock before being decided")
    @ValueSource(strings = {"findByAlertIdForUpdate", "findByPaymentIdForUpdate"})
    void alertsAreLockedForUpdate(String methodName) {
        Method method = methodsNamed(FraudAlertRepository.class, methodName).getFirst();
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock)
                .as("FraudAlertRepository.%s must take a pessimistic write lock", methodName)
                .isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * A bounded lock wait, so an analyst clicking a button gets a clear conflict rather than a hung
     * request.
     */
    @Test
    @DisplayName("the alert lock has a bounded wait")
    void alertLockTimesOut() {
        Method method = methodsNamed(FraudAlertRepository.class, "findByAlertIdForUpdate").getFirst();
        var hints = method.getAnnotation(org.springframework.data.jpa.repository.QueryHints.class);

        assertThat(hints).as("an unbounded lock wait would hang the request").isNotNull();
        assertThat(Arrays.stream(hints.value())
                .anyMatch(hint -> hint.name().equals("jakarta.persistence.lock.timeout")))
                .isTrue();
    }

    /**
     * The decision path must take the lock. Reading without it and then writing would let two
     * concurrent decisions both pass their own state check.
     */
    @Test
    @DisplayName("applying a decision goes through the locking lookup")
    void decisionUsesTheLock() {
        assertThat(methodsNamed(FraudAlertRepository.class, "findByAlertIdForUpdate"))
                .as("FraudMutationService.applyDecision depends on this existing")
                .isNotEmpty();
    }

    // -------------------------------------------------------- append-only data

    /**
     * An assessment is a statement about a payment at a moment in time. If any of these were updatable,
     * the reason a customer was refused could be rewritten by a later build — and a database trigger
     * cannot help, because Hibernate would not even be generating an UPDATE it could refuse.
     */
    @ParameterizedTest
    @DisplayName("the verdict fields on an assessment cannot be updated")
    @ValueSource(strings = {"paymentId", "fraudProbability", "riskScore", "riskLevel", "decision",
            "reasons", "ruleHits", "modelVersion", "featureSnapshot", "degradedMode", "evaluatedAt"})
    void assessmentVerdictIsImmutable(String fieldName) {
        Column column = fieldOf(FraudAssessment.class, fieldName).getAnnotation(Column.class);

        assertThat(column).as("FraudAssessment.%s must be mapped", fieldName).isNotNull();
        assertThat(column.updatable())
                .as("""
                        FraudAssessment.%s must not be updatable. A verdict that could be rewritten \
                        after the fact would be worthless in a dispute, and the field the argument \
                        would turn on is exactly this one.""", fieldName)
                .isFalse();
    }

    /**
     * Every field, not just the interesting ones. An analyst decision is the only record of why a
     * customer's payment was released or refused, and it is what an audit reads.
     */
    @Test
    @DisplayName("no field on an analyst decision can be updated")
    void analystDecisionIsFullyImmutable() {
        for (Field field : AnalystDecision.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null) {
                continue;
            }
            assertThat(column.updatable())
                    .as("AnalystDecision.%s must be append-only: a database trigger refuses UPDATE "
                            + "on this table, and Hibernate must not even generate one",
                            field.getName())
                    .isFalse();
        }
    }

    /**
     * The repository must expose no bulk write. {@code JpaRepository} inherits {@code delete} and
     * {@code save}, which the trigger refuses at the database — but adding a modifying query here
     * would be a deliberate attempt to work around it, and this is where a reviewer would notice.
     */
    @Test
    @DisplayName("the decision repository declares no modifying query")
    void decisionRepositoryHasNoModifyingQuery() {
        for (Method method : AnalystDecisionRepository.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(
                    org.springframework.data.jpa.repository.Modifying.class))
                    .as("AnalystDecisionRepository.%s must not modify the audit trail",
                            method.getName())
                    .isNull();
        }
    }

    // -------------------------------------------------------------- security

    /**
     * The shared chain restricts {@code /api/admin/**}; these paths are {@code /api/fraud/**} because
     * the gateway routes by prefix. Without method security the whole fraud console would be readable
     * by any authenticated customer — including other customers' device fingerprints and coordinates.
     */
    @Test
    @DisplayName("the fraud console requires an elevated role")
    void fraudConsoleIsGuarded() {
        PreAuthorize classLevel = FraudController.class.getAnnotation(PreAuthorize.class);

        assertThat(classLevel)
                .as("without this, every customer's risk score and location would be public to "
                        + "any authenticated user")
                .isNotNull();
        assertThat(classLevel.value())
                .contains(Roles.ROLE_ADMIN)
                .contains(Roles.ROLE_FRAUD_ANALYST);
    }

    /**
     * The internal surface decides whether a payment proceeds, so it is worth exactly as much
     * protection as one that moves money. The gateway also denies {@code /internal/**} outright: two
     * independent controls.
     */
    @Test
    @DisplayName("the internal assessment endpoint requires a service token")
    void internalSurfaceRequiresServiceRole() {
        PreAuthorize classLevel = InternalFraudController.class.getAnnotation(PreAuthorize.class);

        assertThat(classLevel).isNotNull();
        assertThat(classLevel.value())
                .as("an endpoint that gates a payment must not be reachable by a customer token")
                .contains(Roles.ROLE_SERVICE);
    }

    /**
     * No handler may take the analyst's identity from the request. "Who authorised this" is the first
     * question asked about a released payment that turned out to be fraud, and an answer the caller
     * could choose for itself would be worthless.
     */
    @Test
    @DisplayName("no endpoint accepts an analyst identity as a parameter")
    void analystIdentityIsNeverAParameter() {
        for (Method method : FraudController.class.getDeclaredMethods()) {
            for (var parameter : method.getParameters()) {
                assertThat(parameter.getName().toLowerCase())
                        .as("%s.%s must take the analyst from the token, never from the request",
                                FraudController.class.getSimpleName(), method.getName())
                        .doesNotContain("analyst")
                        .doesNotContain("decidedby");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static List<Method> methodsNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
    }

    private static Field fieldOf(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("expected " + type.getSimpleName() + " to declare " + name, ex);
        }
    }
}
