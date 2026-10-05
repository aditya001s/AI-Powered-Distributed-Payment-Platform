package com.aegispay.ai.service;

import com.aegispay.ai.api.InvestigationController;
import com.aegispay.ai.domain.Investigation;
import com.aegispay.ai.domain.InvestigationCitation;
import com.aegispay.ai.domain.InvestigationReview;
import com.aegispay.ai.generation.LocalReportComposer;
import com.aegispay.ai.rag.KnowledgeBaseSeeder;
import com.aegispay.ai.rag.PolicyRetriever;
import com.aegispay.ai.rag.PolicyWriter;
import com.aegispay.ai.repository.InvestigationCitationRepository;
import com.aegispay.ai.repository.InvestigationReviewRepository;
import com.aegispay.common.security.Roles;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on ai-investigation-service's transaction, authorisation and authority boundaries.
 *
 * <p>Everything asserted here is invisible to a unit test with mocked collaborators, and each would fail in a
 * way that looks like something else:
 *
 * <ol>
 *   <li><strong>A transactional generation path.</strong> Generating a report can be a twenty-second call to a
 *       language model. A transaction spanning it would pin a database connection across the network until the
 *       pool emptied.</li>
 *   <li><strong>Self-invocation.</strong> Spring applies transaction advice through a proxy, so a
 *       {@code @Transactional} method called as {@code this.method()} runs untransacted. The auth service hit
 *       this in Phase 2, where a private self-called method meant refresh-token revocation revoked
 *       nothing.</li>
 *   <li><strong>A mutable report.</strong> An editable narrative would allow the most tempting and least
 *       defensible change available: bringing the record of what the AI said into line with what turned out
 *       to be true.</li>
 *   <li><strong>An unguarded console.</strong> A narrative describes another customer's payment, device and
 *       location in plain prose, and prose is a worse leak than structured data because it is immediately
 *       intelligible.</li>
 *   <li><strong>The authority boundary.</strong> Enforced by <em>absence</em> — no wallet client, no ledger
 *       client, no endpoint that acts. A test is the only thing that stops one being added.</li>
 * </ol>
 *
 * <p>Runs in milliseconds with no infrastructure, so it guards every build rather than only the ones where
 * Docker is available.
 */
class AiTransactionBoundaryTest {

    // ------------------------------------------------- the authority boundary

    /**
     * <strong>The most important assertion in this service.</strong> An investigation recommends; a person
     * decides. That is enforced by the service holding no credential any internal write API would accept — so
     * the guarantee is broken the moment somebody adds a client, not when they add a handler that calls one.
     *
     * <p>Asserted by scanning every type in the service for a field or method that looks like an outbound
     * platform call, because a reviewer reading a new class will not necessarily be thinking about this.
     */
    @Test
    @DisplayName("holds no client for any service that can move money")
    void holdsNoWriteCapableClient() {
        List<Class<?>> types = List.of(
                InvestigationService.class,
                InvestigationMutationService.class,
                InvestigationQueryService.class,
                InvestigationController.class,
                com.aegispay.ai.api.AiDtos.class);

        for (Class<?> type : types) {
            for (Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getSimpleName();
                assertThat(fieldType)
                        .as("""
                                %s.%s looks like a client for another service. This service must hold \
                                NO capability to move money, refuse a payment or restrict an account: \
                                that is what makes the authority boundary a guarantee rather than a \
                                naming convention.""", type.getSimpleName(), field.getName())
                        .doesNotContain("WalletClient")
                        .doesNotContain("LedgerClient")
                        .doesNotContain("PaymentClient")
                        .doesNotContain("FraudClient");
            }
        }
    }

    /**
     * No handler may act on a recommendation. Releasing a held payment is an analyst decision recorded in
     * fraud-service against their token; a handler here that appeared to do it would create a second,
     * ambiguous channel through which something might seem to have been authorised.
     */
    @Test
    @DisplayName("exposes no endpoint that acts on a recommendation")
    void exposesNoActingEndpoint() {
        List<String> handlers = Arrays.stream(InvestigationController.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(handlers)
                .as("an investigation recommends; a person decides, and records that decision "
                        + "elsewhere")
                .doesNotContain("release", "reject", "approve", "block", "freeze", "hold",
                        "unhold", "capture", "refund", "restrict", "close", "disable");
    }

    /**
     * An analyst's disagreement is recorded beside the machine's verdict, never as an edit to it. Both have to
     * survive: a dispute six months later needs to see what the engine said <em>and</em> what the human made
     * of it.
     */
    @Test
    @DisplayName("exposes no endpoint that edits a report")
    void exposesNoEditEndpoint() {
        List<String> handlers = Arrays.stream(InvestigationController.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(handlers)
                .doesNotContain("update", "edit", "amend", "revise", "delete", "setRecommendation");
    }

    // ------------------------------------------------------- bean boundaries

    /**
     * The load-bearing separation. {@link InvestigationMutationService} exists to be a proxied bean boundary;
     * merging it into the orchestration would make every {@code @Transactional} on it a no-op, because the
     * orchestration calls it and is itself deliberately non-transactional.
     */
    @Test
    @DisplayName("the mutation service is a bean separate from the generation orchestration")
    void mutationsAreTheirOwnBean() {
        assertThat(InvestigationMutationService.class)
                .as("merging these would silently drop every transaction on the write path")
                .isNotEqualTo(InvestigationService.class);
    }

    @Test
    @DisplayName("reads are a bean separate from writes")
    void queriesAreTheirOwnBean() {
        assertThat(InvestigationQueryService.class).isNotEqualTo(InvestigationMutationService.class);
    }

    /**
     * Same trap, in the seeding path. {@link KnowledgeBaseSeeder} loops over documents computing an embedding
     * for each — an HTTP call with a hosted embedder — so it must not be transactional, which means the
     * per-document write has to be reachable through a proxy for its annotation to take effect at all.
     */
    @Test
    @DisplayName("the policy writer is a bean separate from the seeder")
    void policyWriterIsItsOwnBean() {
        assertThat(PolicyWriter.class)
                .as("a self-invoked @Transactional would leave the corpus writing untransacted, and "
                        + "orphaned rows behind on any failure")
                .isNotEqualTo(KnowledgeBaseSeeder.class);
    }

    // ---------------------------------------------------------- transactions

    /**
     * <strong>The most important transaction assertion.</strong> Generation calls a language model with a
     * twenty-second budget. A transaction here would hold a database connection open across that call; under
     * load the pool empties and the service stalls on a dependency it cannot hurry.
     */
    @Test
    @DisplayName("the generation path holds no transaction, because it calls a language model")
    void generationIsNotTransactional() {
        assertThat(InvestigationService.class.getAnnotation(Transactional.class))
                .as("a class-level @Transactional would span the model HTTP call")
                .isNull();

        for (Method method : InvestigationService.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("InvestigationService.%s must not be transactional", method.getName())
                    .isNull();
        }
    }

    @Test
    @DisplayName("the seeder holds no transaction, because it computes embeddings")
    void seederIsNotTransactional() {
        assertThat(KnowledgeBaseSeeder.class.getAnnotation(Transactional.class)).isNull();

        for (Method method : KnowledgeBaseSeeder.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("KnowledgeBaseSeeder.%s must not be transactional: with a hosted embedder "
                            + "each document costs an HTTP call", method.getName())
                    .isNull();
        }
    }

    @ParameterizedTest
    @DisplayName("every investigation write is transactional and public")
    @ValueSource(strings = {"record", "review"})
    void writesAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(InvestigationMutationService.class, methodName);
        assertThat(methods).as("expected InvestigationMutationService.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("InvestigationMutationService.%s writes investigation state and must be "
                            + "transactional", methodName)
                    .isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("InvestigationMutationService.%s must be public, or Spring's proxy cannot "
                            + "advise it and the transaction would be silently absent", methodName)
                    .isTrue();
        }
    }

    @ParameterizedTest
    @DisplayName("every corpus write is transactional and public")
    @ValueSource(strings = {"write", "repairVector"})
    void policyWritesAreTransactional(String methodName) {
        List<Method> methods = methodsNamed(PolicyWriter.class, methodName);
        assertThat(methods).as("expected PolicyWriter.%s", methodName).isNotEmpty();

        for (Method method : methods) {
            assertThat(method.getAnnotation(Transactional.class)).isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
        }
    }

    /**
     * Reads share one snapshot and cannot write. {@code readOnly} puts the JDBC connection in read-only mode,
     * so a mapping mistake that tried to flush a dirty investigation fails at the database rather than
     * attempting an UPDATE the trigger would then refuse — and a read-only violation names the mistake far
     * more clearly than a trigger message.
     */
    @Test
    @DisplayName("reads are marked read-only at the class level")
    void readsAreReadOnly() {
        Transactional classLevel = InvestigationQueryService.class.getAnnotation(Transactional.class);

        assertThat(classLevel)
                .as("InvestigationQueryService must be transactional so its reads share one snapshot")
                .isNotNull();
        assertThat(classLevel.readOnly()).isTrue();
    }

    @Test
    @DisplayName("retrieval is read-only, so a search cannot mutate the corpus")
    void retrievalIsReadOnly() {
        Method retrieve = methodsNamed(PolicyRetriever.class, "retrieve").getFirst();
        Transactional transactional = retrieve.getAnnotation(Transactional.class);

        assertThat(transactional)
                .as("the declared lookup and the vector search must see one consistent corpus, or a "
                        + "retrieval set assembled across a re-embed would mix two versions of a "
                        + "policy")
                .isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    @DisplayName("the controller declares no transactions")
    void controllerIsNotTransactional() {
        assertThat(InvestigationController.class.getAnnotation(Transactional.class)).isNull();

        for (Method method : InvestigationController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Transactional.class))
                    .as("InvestigationController.%s", method.getName())
                    .isNull();
        }
    }

    // -------------------------------------------------------- append-only data

    /**
     * A report is a statement about a payment at a moment in time, generated from a specific evidence pack by a
     * specific model build. If any field were updatable, the record of what the AI said could be quietly
     * brought into line with what turned out to be true — and a database trigger cannot help, because
     * Hibernate would not even be generating an UPDATE for it to refuse.
     */
    @Test
    @DisplayName("no field on an investigation can be updated")
    void investigationIsFullyImmutable() {
        assertEveryColumnImmutable(Investigation.class);
    }

    @Test
    @DisplayName("no field on a citation can be updated")
    void citationIsFullyImmutable() {
        assertEveryColumnImmutable(InvestigationCitation.class);
    }

    @Test
    @DisplayName("no field on a review can be updated")
    void reviewIsFullyImmutable() {
        assertEveryColumnImmutable(InvestigationReview.class);
    }

    private static void assertEveryColumnImmutable(Class<?> entity) {
        for (Field field : entity.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null) {
                continue;
            }
            assertThat(column.updatable())
                    .as("""
                            %s.%s must not be updatable. A trigger refuses UPDATE on this table, and \
                            Hibernate must not even generate one — the alternative is a constraint \
                            error at flush time instead of a compile-time guarantee.""",
                            entity.getSimpleName(), field.getName())
                    .isFalse();
        }
    }

    /**
     * The repositories must declare no bulk write. {@code JpaRepository} inherits {@code delete} and
     * {@code save}, which the triggers refuse at the database — but adding a modifying query here would be a
     * deliberate attempt to work around them, and this is where a reviewer would notice.
     */
    @ParameterizedTest
    @DisplayName("the append-only repositories declare no modifying query")
    @ValueSource(classes = {InvestigationCitationRepository.class,
            InvestigationReviewRepository.class})
    void appendOnlyRepositoriesHaveNoModifyingQuery(Class<?> repository) {
        for (Method method : repository.getDeclaredMethods()) {
            assertThat(method.getAnnotation(
                    org.springframework.data.jpa.repository.Modifying.class))
                    .as("%s.%s must not modify the audit trail",
                            repository.getSimpleName(), method.getName())
                    .isNull();
        }
    }

    // -------------------------------------------------------------- security

    /**
     * The shared chain restricts {@code /api/admin/**}; these paths are {@code /api/investigations/**} because
     * the gateway routes by prefix. Without method security, a narrative describing another customer's payment,
     * device and location in plain prose would be readable by any authenticated user — and prose is a worse
     * leak than structured data, because it is immediately intelligible.
     */
    @Test
    @DisplayName("the investigation console requires an elevated role")
    void consoleIsGuarded() {
        PreAuthorize classLevel = InvestigationController.class.getAnnotation(PreAuthorize.class);

        assertThat(classLevel)
                .as("without this, a plain-prose description of another customer's payment would be "
                        + "readable by any authenticated user")
                .isNotNull();
        assertThat(classLevel.value())
                .contains(Roles.ROLE_ADMIN)
                .contains(Roles.ROLE_FRAUD_ANALYST);
    }

    /**
     * No handler may take the analyst's identity from the request. "Who signed off on this" is the first
     * question asked when a recommendation was followed and turned out to be wrong, and an answer the caller
     * could choose for itself would be worthless.
     */
    @Test
    @DisplayName("no endpoint accepts an analyst identity as a parameter")
    void analystIdentityIsNeverAParameter() {
        for (Method method : InvestigationController.class.getDeclaredMethods()) {
            for (var parameter : method.getParameters()) {
                assertThat(parameter.getName().toLowerCase(java.util.Locale.ROOT))
                        .as("%s must take the analyst from the token, never from the request",
                                method.getName())
                        .doesNotContain("analyst")
                        .doesNotContain("reviewedby")
                        .doesNotContain("requestedby");
            }
        }
    }

    // ------------------------------------------------------- generator seams

    /**
     * The fallback must always be available, and it must be its own bean. Resolving it by re-reading
     * configuration at failure time would mean a misconfiguration could leave the fallback path broken too —
     * exactly when it is needed.
     */
    @Test
    @DisplayName("the offline composer is injected as its own dependency, not resolved on failure")
    void fallbackIsInjected() {
        boolean hasComposerField = Arrays.stream(InvestigationService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(LocalReportComposer.class));

        assertThat(hasComposerField)
                .as("the fallback has to be wired in, or a misconfiguration could break the path "
                        + "that exists to survive one")
                .isTrue();
    }

    /**
     * The composer must be incapable of claiming to be a model. The aggregate refuses a deterministic report
     * whose generator id does not start with {@code local}, so the two have to agree — and this is what makes
     * {@code deterministicFallback} a trustworthy signal to an analyst rather than a hint.
     */
    @Test
    @DisplayName("the offline composer identifies itself as local and deterministic")
    void composerIsHonest() {
        LocalReportComposer composer = new LocalReportComposer();

        assertThat(composer.isDeterministic()).isTrue();
        assertThat(composer.id()).startsWith("local");
    }

    // ---------------------------------------------------------------- helpers

    private static List<Method> methodsNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
    }
}
