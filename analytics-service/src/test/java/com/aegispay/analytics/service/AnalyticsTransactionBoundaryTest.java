package com.aegispay.analytics.service;

import com.aegispay.analytics.api.AnalyticsController;
import com.aegispay.analytics.domain.PaymentFact;
import com.aegispay.analytics.messaging.FraudCheckedListener;
import com.aegispay.analytics.messaging.PaymentLifecycleListener;
import com.aegispay.analytics.repository.PaymentFactRepository;
import com.aegispay.analytics.repository.StreamStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guarantees that cannot be asserted by exercising the code.
 *
 * <h2>Why a test that reads annotations</h2>
 *
 * <p>Because the failures it catches are invisible at runtime. A {@code @Transactional} method invoked as
 * {@code this.method()} runs <strong>untransacted</strong> and says nothing about it — Spring's advice lives
 * in a proxy and a self-call never reaches it. That trap has already cost this project twice.
 *
 * <p>Here it would be quieter still. This service only ever writes a projection, so an untransacted apply
 * would commit the payment row and lose the stream high-water mark, or the reverse: the freshness figure
 * would advance for an event whose data was never stored, and the dashboard would confidently report
 * up-to-date numbers that are missing a payment.
 *
 * <h2>The other half: this service must stay read-only to the outside world</h2>
 *
 * <p>analytics-service has no write endpoint and no service client, on purpose. Approving or blocking a
 * payment goes browser → fraud-service directly, so the decision is attributed to the analyst's own token in
 * the service that owns the append-only trail. A write endpoint added here later would insert a reporting
 * service into the chain of custody of a decision it played no part in — and that is a design property worth
 * failing a build over rather than trusting to review.
 */
class AnalyticsTransactionBoundaryTest {

    // ------------------------------------------------------------------------
    // where transactions must be
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the projection writer is transactional")
    class ProjectionIsTransactional {

        /**
         * {@link PaymentProjectionService#apply} writes two rows: the payment fact and the stream
         * high-water mark. They have to commit together or the freshness figure lies about what was stored.
         */
        @Test
        @DisplayName("apply is transactional")
        void applyIsTransactional() {
            assertThat(methodNamed(PaymentProjectionService.class, "apply")
                    .getAnnotation(Transactional.class))
                    .as("""
                            apply writes the payment fact and the stream high-water mark; committing one \
                            without the other means reporting fresh data that is missing a payment""")
                    .isNotNull();
        }

        /**
         * Every public method on the writer, not just the one. A method that lost its annotation would still
         * appear to work — it would simply stop being atomic with its neighbours.
         */
        @Test
        @DisplayName("every public method on the projection service is transactional")
        void everyPublicMethodIsTransactional() {
            List<String> unannotated = publicMethods(PaymentProjectionService.class)
                    .filter(method -> !method.isAnnotationPresent(Transactional.class))
                    .map(Method::getName)
                    .toList();

            assertThat(unannotated)
                    .as("a projection write outside a transaction commits partially and reports success")
                    .isEmpty();
        }

        /**
         * The read on the writer is marked read-only. Not decoration: it lets Hibernate skip dirty checking,
         * and more usefully it makes an accidental write on a query path fail rather than commit.
         */
        @Test
        @DisplayName("newestEventApplied is read-only")
        void freshnessReadIsReadOnly() {
            Transactional annotation = methodNamed(PaymentProjectionService.class, "newestEventApplied")
                    .getAnnotation(Transactional.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.readOnly())
                    .as("a query path that can write is a query path that will, eventually")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("every query is read-only")
    class QueriesAreReadOnly {

        /**
         * All six endpoints are served from here. A writable transaction on a reporting query is how a read
         * model acquires a write it was never supposed to have — and this service's whole claim is that it is
         * derived data that can be dropped and rebuilt.
         */
        @Test
        @DisplayName("every public method on AnalyticsQueryService is @Transactional(readOnly = true)")
        void queriesAreReadOnly() {
            List<String> writable = publicMethods(AnalyticsQueryService.class)
                    .filter(method -> {
                        Transactional annotation = method.getAnnotation(Transactional.class);
                        return annotation == null || !annotation.readOnly();
                    })
                    .map(Method::getName)
                    .toList();

            assertThat(writable)
                    .as("""
                            this service reports; it does not decide. A writable query path is the first \
                            step towards it becoming the only place a fact exists""")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // no write surface
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the service exposes no way to change anything")
    class NoWriteSurface {

        /**
         * <strong>The design assertion.</strong> No mutating HTTP method anywhere on the controller.
         *
         * <p>Approve and block are deliberately not proxied through here. Adding a POST would put a
         * reporting service into the audit trail of an analyst's decision, and the identity recorded against
         * that decision would be one hop removed from the person who made it.
         */
        @Test
        @DisplayName("AnalyticsController declares no POST, PUT, PATCH or DELETE")
        void controllerIsReadOnly() {
            List<String> mutating = Arrays.stream(AnalyticsController.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(PostMapping.class)
                            || method.isAnnotationPresent(PutMapping.class)
                            || method.isAnnotationPresent(PatchMapping.class)
                            || method.isAnnotationPresent(DeleteMapping.class))
                    .map(Method::getName)
                    .toList();

            assertThat(mutating)
                    .as("""
                            an analyst's decision belongs in fraud-service's append-only trail, attributed \
                            to their own token. Proxying it through here adds a hop to the chain of custody \
                            for a decision this service played no part in""")
                    .isEmpty();
        }

        /**
         * The payment repository is a read surface plus the projection's own save. A modifying query would be
         * a way to rewrite history in the read model without an event — which would break the property that
         * the table can be dropped and rebuilt from the topics and come back identical.
         */
        @Test
        @DisplayName("the fact repository declares no modifying query")
        void factRepositoryDeclaresNoModifyingQuery() {
            assertNoModifyingQuery(PaymentFactRepository.class,
                    "every row must be reproducible by replaying the topics; a hand-written UPDATE is not");
        }

        @Test
        @DisplayName("the stream state repository declares no modifying query")
        void streamRepositoryDeclaresNoModifyingQuery() {
            assertNoModifyingQuery(StreamStateRepository.class,
                    "the high-water mark is advanced by observing events, never set directly");
        }

        private static void assertNoModifyingQuery(Class<?> repository, String because) {
            List<String> modifying = Arrays.stream(repository.getMethods())
                    .filter(method -> method.isAnnotationPresent(Modifying.class))
                    .map(Method::getName)
                    .toList();

            assertThat(modifying).as("%s: %s", repository.getSimpleName(), because).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // listeners
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("listeners delegate rather than transact")
    class ListenersDelegate {

        /**
         * The listeners must not carry their own transaction.
         *
         * <p>Two reasons, and the second is the one that bites. A transaction opened in the listener would
         * already be active when {@link PaymentProjectionService#insert} hits the unique-constraint
         * violation on a concurrent insert — and once a {@code DataIntegrityViolationException} has marked
         * that outer transaction rollback-only, the re-read that recovers from the race cannot commit. The
         * expected path would become an unrecoverable failure.
         *
         * <p>The other reason is ordinary: two transaction boundaries around one write is one more than
         * necessary, and the inner one is where the rule belongs.
         */
        @Test
        @DisplayName("no listener declares a transaction of its own")
        void listenersAreNotTransactional() {
            for (Class<?> listener : List.of(FraudCheckedListener.class,
                    PaymentLifecycleListener.class)) {

                assertThat(listener.getAnnotation(Transactional.class))
                        .as("%s must not be @Transactional at class level", listener.getSimpleName())
                        .isNull();

                List<String> annotated = Arrays.stream(listener.getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Transactional.class))
                        .map(Method::getName)
                        .toList();

                assertThat(annotated)
                        .as("""
                                %s must declare no transaction: an outer transaction would already be \
                                rollback-only by the time the projection recovers from a concurrent insert""",
                                listener.getSimpleName())
                        .isEmpty();
            }
        }
    }

    // ------------------------------------------------------------------------
    // the entity is derived data
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the fact is a projection, not a record of truth")
    class FactIsDerived {

        /**
         * Every field is assembled from events, so the merge methods are the only way in. A public setter
         * would let a caller assign a status directly and bypass the {@code StatusRank} guard — which is the
         * one rule standing between an out-of-order event and a permanently wrong row.
         */
        @Test
        @DisplayName("PaymentFact exposes no setters")
        void factHasNoSetters() {
            List<String> setters = Arrays.stream(PaymentFact.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> method.getName().startsWith("set"))
                    .map(Method::getName)
                    .toList();

            assertThat(setters)
                    .as("""
                            a setter for status would bypass the rank guard, and a late payment-created \
                            would report a settled payment as in-flight for good""")
                    .isEmpty();
        }

        /**
         * The payment id is the natural key the whole projection is addressed by, and it is what the unique
         * constraint protects. If Hibernate believed it could update the column, a merge could move a row
         * onto another payment's id.
         */
        @Test
        @DisplayName("the payment id and first-seen time cannot be updated")
        void identityColumnsAreNotUpdatable() {
            for (String field : List.of("paymentId", "firstSeenAt")) {
                jakarta.persistence.Column column = fieldNamed(PaymentFact.class, field)
                        .getAnnotation(jakarta.persistence.Column.class);

                assertThat(column.updatable())
                        .as("PaymentFact.%s identifies the row and must not be rewritten", field)
                        .isFalse();
            }
        }

        /**
         * Everything else must stay updatable. A copy-pasted {@code updatable = false} onto the status would
         * silently stop the projection advancing: events would be consumed, the high-water mark would move,
         * and every payment would sit at whichever status happened to arrive first.
         */
        @Test
        @DisplayName("the projected fields stay updatable, because the row is rebuilt event by event")
        void projectedColumnsAreUpdatable() {
            for (String field : List.of("status", "statusRank", "riskLevel", "fraudDecision",
                    "completedAt", "createdAt", "lastEventAt", "degradedMode", "refundedMinor")) {
                jakarta.persistence.Column column = fieldNamed(PaymentFact.class, field)
                        .getAnnotation(jakarta.persistence.Column.class);

                assertThat(column.updatable())
                        .as("""
                                PaymentFact.%s must remain updatable, or events would be consumed and \
                                acknowledged while the row never changed""", field)
                        .isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static java.util.stream.Stream<Method> publicMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic());
    }

    private static Method methodNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no method " + name + " on " + type.getSimpleName()));
    }

    private static java.lang.reflect.Field fieldNamed(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("no field " + name + " on " + type.getSimpleName(), ex);
        }
    }
}
