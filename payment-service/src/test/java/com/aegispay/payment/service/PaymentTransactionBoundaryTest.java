package com.aegispay.payment.service;

import com.aegispay.common.idempotency.IdempotencyRecordRepository;
import com.aegispay.payment.api.PaymentController;
import com.aegispay.payment.repository.PaymentRepository;
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
 * Structural guards on payment-service's transaction boundaries.
 *
 * <p>The rule this protects is the one that is easiest to break by accident: <strong>no
 * transaction may span a network call</strong>. Accepting a payment resolves two wallets over HTTP
 * and a refund reserves and captures over HTTP, and a transaction wrapped around any of them would
 * hold a database connection open across the network. Under load the pool empties and the service
 * stalls waiting on a downstream it cannot hurry — an outage caused entirely by transaction scope.
 *
 * <p>So {@link PaymentService} orchestrates without a transaction, and every state change
 * happens in a short transaction inside {@link PaymentMutationService}. Both halves are
 * asserted here, because either one drifting reintroduces the problem.
 *
 * <p>The same rule covers the saga outcome listeners. Their handlers do a Redis de-duplication
 * lookup before calling into the mutation service, and a transaction opened around the handler would
 * hold a connection across that too — while also making a Kafka retry re-run the de-duplication
 * inside a transaction that has already partly committed.
 */
class PaymentTransactionBoundaryTest {

    /**
     * {@link PaymentMutationService} exists to be a proxied bean boundary. Merged back into
     * {@link PaymentService}, its {@code @Transactional} annotations would silently stop
     * applying, because self-invocation bypasses the proxy.
     */
    @Test
    @DisplayName("state writes live on a bean separate from the orchestrator")
    void writesAreOnTheirOwnBean() {
        assertThat(PaymentMutationService.class).isNotEqualTo(PaymentService.class);
    }

    /**
     * These methods make HTTP calls to wallet-service. A transaction here would hold a
     * connection across the network for the duration of the whole payment.
     */
    @ParameterizedTest
    @DisplayName("orchestration that makes network calls is not transactional")
    @ValueSource(strings = {"initiate", "refund"})
    void orchestrationIsNotTransactional(String methodName) {
        List<Method> methods = methodsNamed(PaymentService.class, methodName);
        assertThat(methods).as("expected PaymentService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("PaymentService.%s calls wallet-service over HTTP; a transaction here "
                            + "would pin a database connection across the network", methodName)
                    .isNull();
        }
    }

    @Test
    @DisplayName("PaymentService has no class-level transaction either")
    void noClassLevelTransaction() {
        assertThat(PaymentService.class.getAnnotation(Transactional.class))
                .as("a class-level @Transactional would wrap the HTTP calls too")
                .isNull();
    }

    @ParameterizedTest
    @DisplayName("every state change is transactional, so it commits with its outbox event")
    @ValueSource(strings = {"createPayment", "applyApproved", "applyCompleted", "applyFailed",
            "applyRejected", "applyRefund"})
    void stateChangesAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(PaymentMutationService.class, methodName);
        assertThat(methods).as("expected PaymentMutationService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            // The state row and its outbox event must land in ONE commit. Without the
            // transaction, a crash between them either loses the event (nobody learns the
            // payment completed) or publishes one for a payment that rolled back.
            assertThat(method.getAnnotation(Transactional.class))
                    .as("PaymentMutationService.%s must be transactional so the state change "
                            + "and its outbox event are atomic", methodName)
                    .isNotNull();
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .as("PaymentMutationService.%s must be public for the proxy to advise it",
                            methodName)
                    .isTrue();
        }
    }

    /**
     * A status change is a read-modify-write, so it needs the same protection as a balance.
     * The state machine alone cannot help: it only rejects transitions that are illegal
     * <em>from the state it was shown</em>, so two readers of {@code AUTHORIZED} would both
     * find {@code CAPTURED} legal.
     */
    @Test
    @DisplayName("payments are loaded FOR UPDATE before a state transition")
    void transitionsTakeARowLock() {
        Method method = methodsNamed(PaymentRepository.class, "findByPaymentIdForUpdate").getFirst();
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock)
                .as("without the lock, a saga step and a duplicate event delivery could both "
                        + "read AUTHORIZED and both apply the transition to CAPTURED")
                .isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @ParameterizedTest
    @DisplayName("every @Modifying query flushes and clears")
    @ValueSource(classes = {PaymentRepository.class, IdempotencyRecordRepository.class})
    void modifyingQueriesFlushAndClear(Class<?> repositoryType) {
        for (Method method : repositoryType.getDeclaredMethods()) {
            Modifying modifying = method.getAnnotation(Modifying.class);
            if (modifying == null) {
                continue;
            }
            assertThat(modifying.flushAutomatically())
                    .as("%s.%s", repositoryType.getSimpleName(), method.getName()).isTrue();
            assertThat(modifying.clearAutomatically())
                    .as("%s.%s", repositoryType.getSimpleName(), method.getName()).isTrue();
        }
    }

    /**
     * Kafka handlers stay outside the transaction.
     *
     * <p>Each one does a Redis de-duplication lookup and then calls a transactional mutation. A
     * transaction opened at the handler would span the Redis round trip, and — worse — a container
     * retry would re-enter a handler whose earlier partial work had already committed on a different
     * boundary from the one it thought it was on.
     */
    @Test
    @DisplayName("saga outcome handlers are not transactional")
    void sagaListenersAreNotTransactional() {
        Class<?> listener = com.aegispay.payment.messaging.SagaOutcomeListener.class;

        assertThat(listener.getAnnotation(Transactional.class))
                .as("a class-level @Transactional would wrap the de-duplication lookup too")
                .isNull();

        for (Method method : listener.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("SagaOutcomeListener.%s", method.getName())
                    .isNull();
        }
    }

    /**
     * Every saga outcome must have a handler, and every handler must be a real listener.
     *
     * <p>Since Phase 5 this service learns what happened to a payment only from these four events.
     * A missing handler would leave payments sitting at {@code CREATED} forever with the money
     * already moved — and it would do so silently, because nothing else in the system depends on
     * this service consuming them.
     */
    @Test
    @DisplayName("all four saga outcome topics are consumed")
    void everySagaOutcomeIsConsumed() {
        List<String> topics = Arrays.stream(
                        com.aegispay.payment.messaging.SagaOutcomeListener.class
                                .getDeclaredMethods())
                .map(method -> method.getAnnotation(
                        org.springframework.kafka.annotation.KafkaListener.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(annotation -> Arrays.stream(annotation.topics()))
                .toList();

        assertThat(topics).containsExactlyInAnyOrder(
                com.aegispay.common.events.KafkaTopics.PAYMENT_APPROVED,
                com.aegispay.common.events.KafkaTopics.PAYMENT_COMPLETED,
                com.aegispay.common.events.KafkaTopics.PAYMENT_FAILED,
                com.aegispay.common.events.KafkaTopics.PAYMENT_REJECTED);
    }

    @Test
    @DisplayName("the controller declares no transactions")
    void controllerIsNotTransactional() {
        assertThat(PaymentController.class.getAnnotation(Transactional.class)).isNull();
        for (Method method : PaymentController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("PaymentController.%s", method.getName())
                    .isNull();
        }
    }

    private List<Method> methodsNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .toList();
    }
}
