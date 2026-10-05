package com.aegispay.orchestrator.service;

import com.aegispay.orchestrator.api.InternalOrchestrationController;
import com.aegispay.orchestrator.api.OrchestrationController;
import com.aegispay.orchestrator.messaging.PaymentCreatedListener;
import com.aegispay.orchestrator.provider.ProviderHealthRecorder;
import com.aegispay.orchestrator.repository.PaymentSagaRepository;
import com.aegispay.orchestrator.repository.ProviderStateRepository;
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
 * Structural guards on the orchestrator's transaction, locking and authorisation boundaries.
 *
 * <p>Everything asserted here is invisible at runtime until it is too late. A missing
 * {@code @Transactional} does not throw; it just means a state change and its outbox event stop being
 * atomic, and the symptom is a payment that completed without anyone being told — weeks later, under
 * load, once. A missing {@code REQUIRES_NEW} does not throw either; it means a step log vanishes
 * along with the transaction whose failure it was recording, which is the one record an incident
 * needs most.
 *
 * <p>These are reflection tests rather than behavioural ones on purpose. The properties are about the
 * <em>shape</em> of the code, and shape is what drifts during refactoring.
 */
class SagaTransactionBoundaryTest {

    // ------------------------------------------------- transaction boundaries

    /**
     * The orchestrator holds no transaction, and that is the single most important structural
     * property in this service.
     *
     * <p>Four of its six steps are network calls and one of them is allowed three seconds. A
     * transaction spanning them would pin a database connection across the network; under load the
     * pool empties and the service stalls waiting on a provider it cannot hurry. The outage would be
     * caused entirely by transaction scope, and it would look like a database problem.
     */
    @Test
    @DisplayName("SagaOrchestrator declares no transaction anywhere")
    void orchestratorIsNotTransactional() {
        assertThat(SagaOrchestrator.class.getAnnotation(Transactional.class))
                .as("a class-level @Transactional would wrap every provider call")
                .isNull();

        for (Method method : SagaOrchestrator.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("SagaOrchestrator.%s calls providers, wallets and the ledger over the "
                            + "network; a transaction here would hold a connection open across it",
                            method.getName())
                    .isNull();
        }
    }

    /**
     * The writer must be a separate bean, not a private method.
     *
     * <p>Spring's {@code @Transactional} works through a proxy, so a {@code this.method()} call from
     * inside {@link SagaOrchestrator} would bypass it entirely and run untransacted — silently. The
     * separate bean is what makes the annotation actually apply.
     */
    @Test
    @DisplayName("state writes live on a bean separate from the orchestrator")
    void writesAreOnTheirOwnBean() {
        assertThat(SagaMutationService.class).isNotEqualTo(SagaOrchestrator.class);
    }

    @ParameterizedTest
    @DisplayName("every saga state change is transactional, so it commits with its outbox event")
    @ValueSource(strings = {"startSaga", "markFundsReserved", "markRiskAssessed", "markAuthorized",
            "markLedgerPosted", "markCaptured", "markCompleted", "markFailed", "markRejected",
            "beginCompensating", "clearHold", "clearAuthorization", "clearLedgerPosting"})
    void stateChangesAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(SagaMutationService.class, methodName);
        assertThat(methods).as("expected SagaMutationService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("SagaMutationService.%s must be transactional so the state change and "
                            + "its event are atomic", methodName)
                    .isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("SagaMutationService.%s must be public for the proxy to advise it",
                            methodName)
                    .isTrue();
        }
    }

    /**
     * <strong>The step log must survive the failure it is recording.</strong>
     *
     * <p>These three write the audit trail: a step was attempted, a step failed, a provider was
     * called and answered this way. If they joined the caller's transaction, a rollback would take the
     * record of the failure with it — and the saga would end up with no explanation of why it failed,
     * which is precisely the state nobody can debug.
     *
     * <p>{@link Propagation#REQUIRES_NEW} gives each of them its own committed transaction, so the
     * evidence outlives whatever went wrong.
     */
    @ParameterizedTest
    @DisplayName("audit writes use REQUIRES_NEW so they survive the failure they record")
    @ValueSource(strings = {"beginStep", "stepFailed", "recordProviderAttempt"})
    void auditWritesAreIndependent(String methodName) {
        List<Method> methods = methodsNamed(SagaMutationService.class, methodName);
        assertThat(methods).as("expected SagaMutationService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional)
                    .as("SagaMutationService.%s must be transactional", methodName)
                    .isNotNull();
            assertThat(transactional.propagation())
                    .as("SagaMutationService.%s joins the caller's transaction, so a rollback "
                            + "would erase the record of what went wrong", methodName)
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    @Test
    @DisplayName("provider probe writes run through a separate REQUIRES_NEW bean")
    void providerProbeWriterHasARealTransactionalBoundary() {
        Method record = methodsNamed(ProviderHealthRecorder.class, "record").getFirst();
        Transactional transactional = record.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(Modifier.isPublic(record.getModifiers())).isTrue();
    }

    /**
     * The recovery sweep is not transactional either.
     *
     * <p>It drives sagas, and driving a saga means making network calls. A transaction around the
     * sweep would hold one connection open for the whole batch, and a single stuck saga would block
     * every other one behind it.
     */
    @Test
    @DisplayName("the recovery sweep holds no transaction across the sagas it drives")
    void recoveryIsNotTransactional() {
        assertThat(SagaRecoveryService.class.getAnnotation(Transactional.class)).isNull();

        for (Method method : methodsNamed(SagaRecoveryService.class, "sweep")) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("sweep() re-drives sagas, which makes provider and wallet calls")
                    .isNull();
        }
    }

    /** Reads are marked read-only so the driver and Hibernate can skip dirty-checking. */
    @Test
    @DisplayName("query methods are read-only")
    void queriesAreReadOnly() {
        for (Method method : OrchestrationQueryService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional)
                    .as("OrchestrationQueryService.%s", method.getName())
                    .isNotNull();
            assertThat(transactional.readOnly())
                    .as("OrchestrationQueryService.%s never writes", method.getName())
                    .isTrue();
        }
    }

    /**
     * Kafka handlers stay outside the transaction.
     *
     * <p>Each does a Redis de-duplication lookup and then drives a whole saga. A transaction here
     * would span both, and a container retry would re-enter a handler whose earlier work had already
     * committed on a different boundary from the one it believed it was on.
     */
    @Test
    @DisplayName("the payment-created listener is not transactional")
    void listenerIsNotTransactional() {
        assertThat(PaymentCreatedListener.class.getAnnotation(Transactional.class)).isNull();

        for (Method method : PaymentCreatedListener.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("PaymentCreatedListener.%s", method.getName())
                    .isNull();
        }
    }

    // --------------------------------------------------------------- locking

    /**
     * A saga transition is a read-modify-write on the status column, so it needs the same protection
     * as a balance.
     *
     * <p>The state machine cannot help on its own: it only rejects transitions that are illegal
     * <em>from the state it was shown</em>. Two threads that both read {@code AUTHORIZED} would both
     * find {@code LEDGER_POSTED} legal and both apply it — one payment, two postings.
     */
    @Test
    @DisplayName("sagas are loaded FOR UPDATE before a transition")
    void transitionsTakeARowLock() {
        Method method = methodsNamed(PaymentSagaRepository.class, "findByPaymentIdForUpdate")
                .getFirst();
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock)
                .as("without the lock, a redelivered event and the recovery sweep could both "
                        + "advance the same saga")
                .isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * The kill switch is also read-modify-write: two operators acting at once must not interleave
     * into a state where one's reason is recorded against the other's decision.
     */
    @Test
    @DisplayName("provider state is loaded FOR UPDATE before the kill switch is flipped")
    void providerStateTakesARowLock() {
        Method method = methodsNamed(ProviderStateRepository.class, "findByIdForUpdate").getFirst();
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * Batch queries that lock more than one row must impose an order.
     *
     * <p>Two replicas sweeping concurrently and locking in different orders will deadlock. Ordering
     * by a stable column means they always contend on the same row first, so one simply waits.
     */
    @Test
    @DisplayName("the recovery sweep locks stalled sagas in a deterministic order")
    void batchLockingIsOrdered() {
        Method method = methodsNamed(PaymentSagaRepository.class, "findStalled").getFirst();
        org.springframework.data.jpa.repository.Query query =
                method.getAnnotation(org.springframework.data.jpa.repository.Query.class);

        assertThat(query).as("findStalled must be an explicit query").isNotNull();
        assertThat(query.value().toUpperCase())
                .as("two replicas locking in different orders would deadlock")
                .contains("ORDER BY");
    }

    // --------------------------------------------------------- authorisation

    /**
     * Method security is doing real work here rather than duplicating a chain rule.
     *
     * <p>The shared security chain auto-restricts {@code /api/admin/**}, but these paths are
     * {@code /api/orchestration/**} because the gateway routes to this service by prefix. Without
     * {@code @PreAuthorize}, a customer token could read every payment's saga — counterparties,
     * amounts and provider routing across the whole platform.
     */
    @Test
    @DisplayName("the operational API is restricted to staff")
    void operationalApiIsRestricted() {
        assertThat(OrchestrationController.class.getAnnotation(PreAuthorize.class))
                .as("without method security a customer token could read every payment's saga")
                .isNotNull();
    }

    /**
     * Anything that changes provider availability or drives the sweep is admin-only, separately from
     * the class-level rule that also admits fraud analysts. Reading a saga and disabling a payment
     * rail are not the same privilege.
     */
    @ParameterizedTest
    @DisplayName("provider and recovery actions carry their own admin-only rule")
    @ValueSource(strings = {"disable", "enable", "sweep"})
    void mutatingActionsAreAdminOnly(String methodName) {
        for (Method method : methodsNamed(OrchestrationController.class, methodName)) {
            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize)
                    .as("OrchestrationController.%s changes what the platform does", methodName)
                    .isNotNull();
            assertThat(preAuthorize.value())
                    .as("OrchestrationController.%s must not be open to read-only staff",
                            methodName)
                    .contains("ROLE_ADMIN");
        }
    }

    /**
     * The simulation surface is machine-only.
     *
     * <p>It can make a payment rail start failing on demand. The gateway has no route for
     * {@code /internal/**} and denies the path outright, and this is the second, independent control —
     * worth having even in a sandbox.
     */
    @Test
    @DisplayName("the simulation API requires a service token")
    void simulationApiRequiresAServiceToken() {
        PreAuthorize preAuthorize =
                InternalOrchestrationController.class.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize)
                .as("an endpoint that can break a payment rail must be protected twice")
                .isNotNull();
        assertThat(preAuthorize.value()).contains("ROLE_SERVICE");
    }

    /**
     * There is no endpoint that starts a payment, and no endpoint that nudges a stuck saga.
     *
     * <p>Orchestration is triggered by {@code payment-created} and by nothing else, so there is
     * exactly one way in and no second path to keep consistent with it. A saga advances only through
     * the orchestrator or the recovery sweep, both of which drive it through the state machine rather
     * than around it — an operator who could set a status by hand could park a payment in a state the
     * machine forbids.
     */
    @Test
    @DisplayName("no endpoint starts a payment or edits a saga's state")
    void thereIsNoBackDoorIntoTheSaga() {
        List<String> handlers = Arrays.stream(OrchestrationController.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(handlers)
                .as("orchestration must have exactly one trigger: the payment-created event")
                .doesNotContain("initiate", "orchestrate", "start", "retry", "advance",
                        "setStatus", "forceComplete", "forceFail");
    }

    @Test
    @DisplayName("the controllers declare no transactions")
    void controllersAreNotTransactional() {
        for (Class<?> controller : List.of(OrchestrationController.class,
                InternalOrchestrationController.class)) {
            assertThat(controller.getAnnotation(Transactional.class))
                    .as("%s", controller.getSimpleName())
                    .isNull();

            for (Method method : controller.getDeclaredMethods()) {
                assertThat(method.getAnnotation(Transactional.class))
                        .as("%s.%s", controller.getSimpleName(), method.getName())
                        .isNull();
            }
        }
    }

    // -------------------------------------------------- the held-review state

    /**
     * <strong>The recovery sweep must not touch a parked saga.</strong>
     *
     * <p>A payment held for fraud review stops deliberately and may sit for hours; its
     * {@code updatedAt} stops moving precisely because it is behaving as designed. Without this
     * exclusion the sweep would "recover" every held payment on every pass, incrementing its attempt
     * count until a healthy payment looked like a chronic failure — and nothing would fail loudly
     * enough for anyone to notice.
     *
     * <p>Asserted against the query text because there is no other way to check it without a database,
     * and the alternative is discovering it in production as a mysteriously rising attempt count.
     */
    @Test
    @DisplayName("the stalled-saga query excludes payments parked for review")
    void stalledQueryExcludesHeldPayments() {
        Method findStalled = methodsNamed(PaymentSagaRepository.class, "findStalled").getFirst();
        org.springframework.data.jpa.repository.Query query =
                findStalled.getAnnotation(org.springframework.data.jpa.repository.Query.class);

        assertThat(query).as("findStalled must declare its own JPQL").isNotNull();
        assertThat(query.value())
                .as("""
                        RISK_HELD must be excluded from the recovery sweep. A parked payment is \
                        waiting on a person, not abandoned, and recovering it every minute would \
                        make a healthy saga look like a failing one.""")
                .contains("RISK_HELD");
    }

    /**
     * The held queue is reported, never recovered. Its own query rather than a status filter, because
     * a broken decision consumer produces no errors and no failures — just a growing list of customers
     * whose funds are reserved, and this is the only place that shows up.
     */
    @Test
    @DisplayName("held payments have their own read query")
    void heldPaymentsAreReportable() {
        assertThat(methodsNamed(PaymentSagaRepository.class, "findHeldForReview"))
                .as("without this, nothing surfaces a stuck review queue")
                .isNotEmpty();
    }

    /**
     * Resuming a released payment writes saga state, so it has to be transactional and reachable
     * through the proxy. A self-invocation would leave the release uncommitted while the payment
     * carried on to charge a provider.
     */
    @ParameterizedTest
    @DisplayName("the review transitions are transactional and public")
    @ValueSource(strings = {"markRiskHeld", "markRiskReviewReleased", "markRiskReviewRefused"})
    void reviewTransitionsAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(SagaMutationService.class, methodName);
        assertThat(methods).as("expected SagaMutationService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("SagaMutationService.%s changes saga state and must be transactional",
                            methodName)
                    .isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("SagaMutationService.%s must be public or the proxy cannot advise it",
                            methodName)
                    .isTrue();
        }
    }

    /**
     * The decision listener is the only thing that unparks a payment, so it must exist and must be a
     * Kafka consumer rather than an HTTP handler. A synchronous call from fraud-service could fail
     * after the alert was resolved, leaving the payment parked forever with nothing to point at.
     */
    @Test
    @DisplayName("analyst decisions arrive as an event, not as an endpoint")
    void decisionsArriveAsEvents() {
        assertThat(Arrays.stream(
                com.aegispay.orchestrator.messaging.FraudDecisionListener.class
                        .getDeclaredMethods())
                .anyMatch(method -> method.getAnnotation(
                        org.springframework.kafka.annotation.KafkaListener.class) != null))
                .as("the resume path must be an event consumer, so the decision and the intent to "
                        + "resume can commit together in fraud-service's outbox")
                .isTrue();

        assertThat(Arrays.stream(OrchestrationController.class.getDeclaredMethods())
                .map(Method::getName))
                .as("there must be no HTTP back door that releases a held payment")
                .doesNotContain("release", "decide", "approve", "unhold");
    }

    private List<Method> methodsNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
    }
}
