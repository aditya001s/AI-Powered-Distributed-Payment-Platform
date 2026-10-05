package com.aegispay.notification.service;

import com.aegispay.notification.domain.DeliveryAttempt;
import com.aegispay.notification.domain.Notification;
import com.aegispay.notification.domain.NotificationDelivery;
import com.aegispay.notification.repository.DeliveryAttemptRepository;
import com.aegispay.notification.repository.NotificationRepository;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guarantees that cannot be asserted by exercising the code.
 *
 * <h2>Why a test that reads annotations</h2>
 *
 * <p>Because the failures it catches are invisible at runtime. A {@code @Transactional} method invoked as
 * {@code this.method()} runs <strong>untransacted</strong> and says nothing about it — Spring's advice lives
 * in a proxy, and a self-call never reaches it. That trap has already cost this project twice: auth-service's
 * refresh-token reuse detection revoked nothing, and the knowledge-base seeder wrote outside a transaction.
 * Both passed every behavioural test.
 *
 * <p>The other half is the opposite mistake. This service makes network calls on the delivery path, and a
 * transaction spanning one would pin a database connection for the length of an SMTP timeout. Under a
 * provider outage the pool would empty and the service would become unable to record the very failures it was
 * discovering — the worst possible moment to lose the ability to write.
 */
class NotificationTransactionBoundaryTest {

    // ------------------------------------------------------------------------
    // where transactions must not be
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("orchestration holds no transaction")
    class OrchestrationIsNotTransactional {

        /**
         * {@link NotificationDeliveryService} calls transports. A transaction here would hold a connection
         * across an SMTP timeout, and during an outage the pool would empty just as the service needed to
         * record failures.
         */
        @Test
        @DisplayName("NotificationDeliveryService declares no transaction anywhere")
        void deliveryServiceIsNotTransactional() {
            assertNoTransactional(NotificationDeliveryService.class,
                    "it calls transports; a transaction would pin a database connection across an "
                            + "SMTP timeout and empty the pool during the outage it is reporting");
        }

        /**
         * The worker claims a batch and then makes a series of transport calls. Wrapping the whole sweep would
         * hold the claim lock — and the connection — for the duration, so adding instances would make the
         * backlog drain no faster.
         */
        @Test
        @DisplayName("DeliveryRetryWorker declares no transaction anywhere")
        void retryWorkerIsNotTransactional() {
            assertNoTransactional(DeliveryRetryWorker.class,
                    "a sweep makes many transport calls; one transaction around them would hold the "
                            + "claim lock and the connection for the whole batch");
        }

        private static void assertNoTransactional(Class<?> type, String because) {
            assertThat(type.getAnnotation(Transactional.class))
                    .as("%s must not be @Transactional at class level: %s", type.getSimpleName(),
                            because)
                    .isNull();

            List<String> annotated = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Transactional.class))
                    .map(Method::getName)
                    .toList();

            assertThat(annotated)
                    .as("%s must declare no @Transactional method: %s", type.getSimpleName(), because)
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // where they must be
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("writes are transactional")
    class WritesAreTransactional {

        /**
         * Every public write on the mutation service. This is the bean that exists purely so the annotations
         * take effect through a proxy — if one method lost its annotation, the write would still appear to
         * work and would simply stop being atomic with its neighbours.
         */
        @Test
        @DisplayName("every public method on NotificationMutationService is transactional")
        void mutationServiceIsTransactional() {
            List<String> unannotated = Arrays.stream(
                            NotificationMutationService.class.getDeclaredMethods())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .filter(method -> !method.isAnnotationPresent(Transactional.class))
                    .map(Method::getName)
                    .toList();

            assertThat(unannotated)
                    .as("a write outside a transaction commits partially and reports success")
                    .isEmpty();
        }

        /**
         * The per-attempt recorders run in their own transaction.
         *
         * <p>Load-bearing, not decorative. The caller is looping over a claimed batch, and one delivery's
         * failure must not roll back the successes already recorded — fifty deliveries are fifty independent
         * outcomes, not one all-or-nothing block.
         *
         * <p>{@code beginAttempt} is the most important of them: if the process dies mid-send the count must
         * already be committed, or a delivery that crashes the JVM on every try would be retried forever with
         * each restart looking like a first attempt.
         */
        @Test
        @DisplayName("attempt recorders commit independently of the batch")
        void attemptRecordersRequireNewTransactions() {
            Set<String> mustBeIndependent = Set.of("beginAttempt", "recordSuccess",
                    "recordRetryableFailure", "recordDeadLetter");

            for (String name : mustBeIndependent) {
                Transactional annotation = methodNamed(NotificationMutationService.class, name)
                        .getAnnotation(Transactional.class);

                assertThat(annotation).as("%s must be @Transactional", name).isNotNull();
                assertThat(annotation.propagation())
                        .as("""
                                %s must use REQUIRES_NEW: one delivery's failure must not roll back the \
                                outcomes already recorded for the rest of the batch""", name)
                        .isEqualTo(Propagation.REQUIRES_NEW);
            }
        }

        /**
         * Read paths are read-only, which is not decoration: it lets Hibernate skip dirty checking and, more
         * usefully, makes an accidental write on a query path fail rather than commit.
         */
        @Test
        @DisplayName("every query on NotificationQueryService is read-only")
        void queriesAreReadOnly() {
            List<String> writable = Arrays.stream(
                            NotificationQueryService.class.getDeclaredMethods())
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .filter(method -> {
                        Transactional annotation = method.getAnnotation(Transactional.class);
                        return annotation == null || !annotation.readOnly();
                    })
                    .map(Method::getName)
                    .toList();

            assertThat(writable)
                    .as("a query path that can write is a query path that will, eventually")
                    .isEmpty();
        }

        private static Method methodNamed(Class<?> type, String name) {
            return Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no method " + name + " on " + type.getSimpleName()));
        }
    }

    // ------------------------------------------------------------------------
    // immutability
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("append-only entities")
    class AppendOnlyEntities {

        /**
         * The database refuses UPDATE on {@code notifications} by trigger. {@code updatable = false} means
         * Hibernate never generates one to be refused — without it, a stray setter would produce a runtime
         * trigger exception inside an API request rather than being impossible.
         */
        @Test
        @DisplayName("every column on Notification is non-updatable")
        void notificationColumnsAreNotUpdatable() {
            assertAllColumnsNonUpdatable(Notification.class);
        }

        @Test
        @DisplayName("every column on DeliveryAttempt is non-updatable")
        void attemptColumnsAreNotUpdatable() {
            assertAllColumnsNonUpdatable(DeliveryAttempt.class);
        }

        /**
         * The three fields on a delivery that record what happened, as opposed to what is happening. A
         * customer changing their email next week must not rewrite where last week's message went, and
         * re-wording a template next month must not change what this customer read.
         */
        @Test
        @DisplayName("what was actually sent cannot be rewritten")
        void sentContentIsImmutable() {
            for (String field : List.of("destination", "renderedSubject", "renderedBody")) {
                Column column = fieldNamed(NotificationDelivery.class, field)
                        .getAnnotation(Column.class);

                assertThat(column.updatable())
                        .as("""
                                NotificationDelivery.%s must not be updatable: it is the record of what \
                                happened, and 'we sent it to that address' is a claim made in a dispute""",
                                field)
                        .isFalse();
            }
        }

        /**
         * The mutable fields must stay mutable. A delivery is the work queue, so a copy-paste of
         * {@code updatable = false} onto the status would silently stop every retry from being recorded —
         * the row would be claimed, attempted, and left exactly as it was.
         */
        @Test
        @DisplayName("retry state stays mutable, because the delivery is the work queue")
        void retryStateIsMutable() {
            for (String field : List.of("status", "attemptCount", "nextAttemptAt", "lastError",
                    "sentAt", "readAt", "updatedAt")) {
                Column column = fieldNamed(NotificationDelivery.class, field)
                        .getAnnotation(Column.class);

                assertThat(column.updatable())
                        .as("""
                                NotificationDelivery.%s must remain updatable, or a claimed delivery would \
                                be attempted and left unchanged""", field)
                        .isTrue();
            }
        }

        /**
         * The optimistic-lock column guards the one race {@code FOR UPDATE SKIP LOCKED} does not: the worker
         * holding a row while an operator replays it through the admin API. Without it the later write would
         * silently win, and a replay could reset an attempt count that had just been incremented.
         */
        @Test
        @DisplayName("NotificationDelivery carries an optimistic lock")
        void deliveryIsVersioned() {
            assertThat(fieldNamed(NotificationDelivery.class, "version")
                    .isAnnotationPresent(jakarta.persistence.Version.class))
                    .as("""
                            SKIP LOCKED stops two workers taking one row; this stops a worker and an \
                            operator's replay overwriting each other""")
                    .isTrue();
        }

        /**
         * No setters on the immutable aggregates. {@code updatable = false} stops Hibernate writing a change;
         * the absence of a setter stops the change being made at all, which is a better place to fail.
         */
        @Test
        @DisplayName("the immutable entities expose no setters")
        void immutableEntitiesHaveNoSetters() {
            for (Class<?> type : List.of(Notification.class, DeliveryAttempt.class)) {
                List<String> setters = Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> method.getName().startsWith("set"))
                        .map(Method::getName)
                        .toList();

                assertThat(setters)
                        .as("%s records something that already happened", type.getSimpleName())
                        .isEmpty();
            }
        }

        private static void assertAllColumnsNonUpdatable(Class<?> type) {
            List<String> updatable = Arrays.stream(type.getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(Column.class))
                    .filter(field -> field.getAnnotation(Column.class).updatable())
                    .map(Field::getName)
                    .toList();

            assertThat(updatable)
                    .as("""
                            %s is append-only at the database. A column Hibernate believes it may update \
                            turns that guarantee into a runtime trigger exception inside a request.""",
                            type.getSimpleName())
                    .isEmpty();
        }

        private static Field fieldNamed(Class<?> type, String name) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ex) {
                throw new AssertionError("no field " + name + " on " + type.getSimpleName(), ex);
            }
        }
    }

    // ------------------------------------------------------------------------
    // repositories
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("repositories")
    class Repositories {

        /**
         * The attempt history is append-only at the database, so a modifying query would fail at runtime.
         * Declaring none means it fails at compile time instead — and stops somebody adding a "tidy up old
         * attempts" query that would delete the evidence of why a delivery died.
         */
        @Test
        @DisplayName("the attempt repository declares no modifying query")
        void attemptRepositoryIsReadOnly() {
            List<String> modifying = Arrays.stream(DeliveryAttemptRepository.class.getMethods())
                    .filter(method -> method.isAnnotationPresent(Modifying.class))
                    .map(Method::getName)
                    .toList();

            assertThat(modifying)
                    .as("""
                            the attempt trail is the only record of why a delivery failed, and replaying \
                            one overwrites the delivery's own last error""")
                    .isEmpty();
        }

        @Test
        @DisplayName("the notification repository declares no modifying query")
        void notificationRepositoryIsReadOnly() {
            List<String> modifying = Arrays.stream(NotificationRepository.class.getMethods())
                    .filter(method -> method.isAnnotationPresent(Modifying.class))
                    .map(Method::getName)
                    .toList();

            assertThat(modifying)
                    .as("a notification records something a customer was already told")
                    .isEmpty();
        }
    }
}
