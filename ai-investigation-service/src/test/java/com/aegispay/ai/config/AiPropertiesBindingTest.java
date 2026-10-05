package com.aegispay.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the real {@code application.yml} to {@link AiProperties}.
 *
 * <p><strong>Why this test exists.</strong> A misspelled configuration key neither fails to compile
 * nor fails to bind — Spring leaves the field at its default and says nothing. In this service the
 * symptom would be particularly quiet: if {@code minimum-similarity} failed to bind, retrieval would
 * still return chunks, the narrative would still cite them, and the reports would still read well.
 * They would simply be citing whatever happened to be least irrelevant. There is no error, no
 * exception and no log line — just investigations that are subtly ungrounded.
 *
 * <p>Ordinarily the context starting would catch a structural mistake. It cannot here: the
 * integration tests that boot a context need Docker, which is unavailable in this environment, so
 * without this test the entire {@code aegis.ai.*} tree ships unverified. This runs in milliseconds
 * against no infrastructure.
 *
 * <p>It reads the shipped file rather than an inline fixture. A fixture would prove that binding
 * works in general, which was never in doubt; the question is whether <em>this</em> file's keys match
 * <em>these</em> field names.
 */
class AiPropertiesBindingTest {

    private static final String PREFIX = "aegis.ai";

    private final AiProperties properties = bindShippedConfiguration();

    // ------------------------------------------------------------------------
    // retrieval
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("retrieval")
    class Retrieval {

        @Test
        @DisplayName("binds every retrieval key from the shipped configuration")
        void bindsRetrieval() {
            AiProperties.Retrieval retrieval = properties.getRetrieval();

            assertThat(retrieval.getTopK()).as("aegis.ai.retrieval.top-k").isEqualTo(5);
            assertThat(retrieval.getMinimumSimilarity())
                    .as("aegis.ai.retrieval.minimum-similarity")
                    .isEqualTo(0.28);
            assertThat(retrieval.isAlwaysIncludeRulePolicies())
                    .as("aegis.ai.retrieval.always-include-rule-policies")
                    .isTrue();
            assertThat(retrieval.getContextTokenBudget())
                    .as("aegis.ai.retrieval.context-token-budget")
                    .isEqualTo(2_000);
        }

        /**
         * <strong>The assertion that matters most in this class.</strong> A similarity floor of zero
         * is not a lenient setting, it is the absence of a control: an approximate
         * nearest-neighbour search returns its k nearest neighbours however distant they are, so at
         * zero every investigation retrieves five documents and none of them can be rejected as
         * irrelevant. "No policy matched" stops being an expressible outcome, and a report about a
         * payment the corpus says nothing about becomes indistinguishable from a grounded one.
         */
        @Test
        @DisplayName("keeps a non-zero similarity floor, so 'no policy matched' stays expressible")
        void floorIsNonZero() {
            assertThat(properties.getRetrieval().getMinimumSimilarity())
                    .as("""
                            at zero, retrieval can never return nothing: the five least-irrelevant \
                            documents in the corpus are cited as though they were on point, and an \
                            ungrounded investigation reads exactly like a grounded one""")
                    .isGreaterThan(0.0);
        }

        /**
         * A floor near 1.0 is the opposite failure and just as quiet: retrieval returns nothing for
         * every payment, every report honestly says it found no policy, and the service looks
         * healthy while contributing nothing. Cosine similarity on the lexical embedder rarely
         * exceeds 0.7 even for an on-topic passage.
         */
        @Test
        @DisplayName("keeps the floor reachable by a genuinely relevant passage")
        void floorIsReachable() {
            assertThat(properties.getRetrieval().getMinimumSimilarity())
                    .as("""
                            an unreachable floor makes every investigation report 'no relevant \
                            policy' while the service reports no error at all""")
                    .isLessThan(0.7);
        }

        /**
         * Vector search alone would be trusting the embedder to have learned that a machine token
         * like {@code RULE_BUST_OUT_PATTERN} means the same thing as a paragraph about accounts
         * emptying themselves. The declared mapping is what makes retrieval reliable rather than
         * merely plausible, so it defaults on.
         */
        @Test
        @DisplayName("includes author-declared rule policies by default")
        void declaredMappingIsOn() {
            assertThat(properties.getRetrieval().isAlwaysIncludeRulePolicies())
                    .as("""
                            with this off, the policy that DEFINES the rule which actually fired is \
                            only retrieved if the embedder happens to score it highly""")
                    .isTrue();
        }

        /**
         * Retrieval must not be able to consume the whole prompt. The evidence pack, the
         * instructions and the expected output all need room; a retrieval set that filled the window
         * would push the instructions out, which is how a model starts ignoring the output format it
         * was told to use.
         */
        @Test
        @DisplayName("leaves prompt budget for the instructions and the evidence")
        void budgetLeavesRoom() {
            assertThat(properties.getRetrieval().getContextTokenBudget())
                    .as("policy budget must leave room for instructions, evidence and output")
                    .isLessThanOrEqualTo(8_000);
            assertThat(properties.getRetrieval().getContextTokenBudget())
                    .as("a budget below one chunk means retrieval is silently disabled")
                    .isGreaterThan(properties.getKnowledgeBase().getChunkMaxTokens());
        }
    }

    // ------------------------------------------------------------------------
    // generation
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("generation")
    class Generation {

        @Test
        @DisplayName("binds every generation key from the shipped configuration")
        void bindsGeneration() {
            AiProperties.Generation generation = properties.getGeneration();

            assertThat(generation.getProvider()).as("aegis.ai.generation.provider").isEqualTo("local");
            assertThat(generation.getModel()).as("aegis.ai.generation.model").isEqualTo("gpt-4o-mini");
            assertThat(generation.getTimeout())
                    .as("aegis.ai.generation.timeout")
                    .isEqualTo(Duration.ofSeconds(20));
            assertThat(generation.getMaxAttempts()).as("aegis.ai.generation.max-attempts").isEqualTo(2);
            assertThat(generation.getMaxOutputTokens())
                    .as("aegis.ai.generation.max-output-tokens")
                    .isEqualTo(800);
            assertThat(generation.getTemperature())
                    .as("aegis.ai.generation.temperature")
                    .isEqualTo(0.1);
            assertThat(generation.isFallbackToLocal())
                    .as("aegis.ai.generation.fallback-to-local")
                    .isTrue();
            assertThat(generation.getMaxUntrustedFieldLength())
                    .as("aegis.ai.generation.max-untrusted-field-length")
                    .isEqualTo(240);
        }

        /**
         * No credential may be baked into a shipped file. Asserted rather than assumed because a
         * committed key is the single most expensive mistake available in this module, and it is
         * invisible in review once the file is long enough.
         */
        @Test
        @DisplayName("ships no API key and no hardcoded provider endpoint")
        void shipsNoSecret() {
            assertThat(properties.getGeneration().getApiKey())
                    .as("an API key committed to application.yml is a leaked credential the moment "
                            + "the repository is cloned")
                    .isEmpty();
            assertThat(properties.getGeneration().getApiBaseUrl())
                    .as("aegis.ai.generation.api-base-url — empty means the provider's own default")
                    .isEmpty();
        }

        /**
         * The platform has to run for someone who has no API key and no outbound network access.
         * Defaulting to a hosted provider would mean an unconfigured deployment fails every
         * generation and falls back on every single investigation — technically working, but the
         * fallback path would be the only path anyone ever exercised.
         */
        @Test
        @DisplayName("defaults to the offline composer, so an unconfigured deployment still works")
        void defaultsToLocal() {
            assertThat(properties.getGeneration().getProvider())
                    .as("""
                            with no key configured, a hosted default would fail generation on every \
                            investigation and reach the composer by the error path instead of the \
                            intended one""")
                    .isEqualTo("local");
        }

        /**
         * Temperature near zero: this is evidence summarisation, not a creative task, and two
         * analysts opening the same investigation should read the same words. A high temperature
         * here would produce variation that looks like new analysis.
         */
        @Test
        @DisplayName("keeps sampling near-deterministic")
        void temperatureIsLow() {
            assertThat(properties.getGeneration().getTemperature())
                    .as("variation between two runs over identical evidence reads like new "
                            + "analysis rather than the same analysis reworded")
                    .isLessThanOrEqualTo(0.3);
        }

        /**
         * The untrusted-field cap is an injection defence. A generous cap gives an attacker room for
         * a full instruction block; the closed recommendation vocabulary means they still cannot
         * produce an action, but they can derail the narrative and burn the context window.
         */
        @Test
        @DisplayName("caps untrusted text tightly enough to deny room for an instruction block")
        void untrustedCapIsTight() {
            assertThat(properties.getGeneration().getMaxUntrustedFieldLength())
                    .as("a payment description is the obvious carrier for 'ignore previous "
                            + "instructions'; length is the cheapest thing to take away")
                    .isLessThanOrEqualTo(512);
        }

        /**
         * Retries are safe here — generation has no side effect at the provider beyond cost, and the
         * investigation is persisted once — but they are not free, and the composer is an acceptable
         * answer. A large retry count would multiply the bill and delay the fallback that was always
         * going to happen.
         */
        @Test
        @DisplayName("retries sparingly, because the fallback is an acceptable outcome")
        void retriesAreBounded() {
            assertThat(properties.getGeneration().getMaxAttempts()).isBetween(1, 3);
        }

        /**
         * A generation timeout generous by platform standards is correct precisely because an
         * investigation is off the payment's critical path. It still has to be bounded: an unbounded
         * call holds a Kafka consumer thread until the socket gives up, and that thread is shared
         * with every other investigation waiting behind it.
         */
        @Test
        @DisplayName("bounds a single generation call")
        void timeoutIsBounded() {
            assertThat(properties.getGeneration().getTimeout())
                    .as("an unbounded call holds the consumer thread and stalls every "
                            + "investigation queued behind it")
                    .isLessThanOrEqualTo(Duration.ofSeconds(60))
                    .isGreaterThan(Duration.ZERO);
        }
    }

    // ------------------------------------------------------------------------
    // knowledge base
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("knowledge base")
    class KnowledgeBaseBinding {

        @Test
        @DisplayName("binds every knowledge-base key from the shipped configuration")
        void bindsKnowledgeBase() {
            AiProperties.KnowledgeBase kb = properties.getKnowledgeBase();

            assertThat(kb.isSeedOnStartup()).as("aegis.ai.knowledge-base.seed-on-startup").isTrue();
            assertThat(kb.isUpgradeExistingDocuments())
                    .as("aegis.ai.knowledge-base.upgrade-existing-documents")
                    .isTrue();
            assertThat(kb.getChunkTargetTokens())
                    .as("aegis.ai.knowledge-base.chunk-target-tokens")
                    .isEqualTo(180);
            assertThat(kb.getChunkMaxTokens())
                    .as("aegis.ai.knowledge-base.chunk-max-tokens")
                    .isEqualTo(300);
            assertThat(kb.getChunkOverlapTokens())
                    .as("aegis.ai.knowledge-base.chunk-overlap-tokens")
                    .isEqualTo(30);
        }

        /**
         * A target above the maximum would make every chunk oversized and force a split on every
         * paragraph; an overlap at or above the target would make consecutive chunks near-duplicates
         * and, at the limit, never advance. Each value is individually legal, so no validation
         * annotation can catch the combination.
         */
        @Test
        @DisplayName("keeps overlap < target < maximum")
        void chunkSizesAreOrdered() {
            AiProperties.KnowledgeBase kb = properties.getKnowledgeBase();

            assertThat(kb.getChunkOverlapTokens())
                    .as("overlap at or above the target makes consecutive chunks near-duplicates "
                            + "and can stop the window advancing")
                    .isLessThan(kb.getChunkTargetTokens());
            assertThat(kb.getChunkTargetTokens())
                    .as("a target above the maximum forces a split on every paragraph")
                    .isLessThanOrEqualTo(kb.getChunkMaxTokens());
        }

        /**
         * Zero overlap makes every paragraph boundary a place where a policy sentence becomes
         * unreachable by the query that should have found it.
         */
        @Test
        @DisplayName("overlaps chunks, so a claim spanning a boundary stays retrievable")
        void overlapIsNonZero() {
            assertThat(properties.getKnowledgeBase().getChunkOverlapTokens()).isPositive();
        }

        /**
         * Seeding off by default would produce the worst failure this service has available: an
         * empty corpus answers every investigation with a readable, ungrounded report and logs
         * nothing wrong.
         */
        @Test
        @DisplayName("seeds the corpus at startup by default")
        void seedsByDefault() {
            assertThat(properties.getKnowledgeBase().isSeedOnStartup())
                    .as("an empty corpus produces readable reports with no grounding and no error")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------------
    // the parts of the shipped file that are not AiProperties
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("shipped configuration")
    class ShippedConfiguration {

        private final StandardEnvironment environment = shippedEnvironment();

        /**
         * <strong>The authority boundary, asserted where it is actually enforced.</strong>
         * {@code AiTransactionBoundaryTest} proves no client field exists in the source; this proves
         * the service holds no credential that any internal write API would accept, which is what
         * makes the absence of those fields a guarantee rather than a habit. Flipping this key to
         * {@code true} is a one-line change that would silently hand the service the ability to
         * authenticate against wallet, ledger and payment.
         */
        @Test
        @DisplayName("disables service-to-service credentials entirely")
        void holdsNoServiceCredentials() {
            assertThat(environment.getProperty("aegis.service-auth.enabled"))
                    .as("""
                            an investigation recommends and a person decides; with no credential the \
                            service cannot move money or restrict an account even if a future \
                            handler tried to""")
                    .isEqualTo("false");
        }

        /**
         * The completed report and the event announcing it must be one commit. Without the outbox the
         * service would persist an investigation and then publish, and a crash between the two leaves
         * a report nobody is told about — or worse, an event for a report that was rolled back.
         */
        @Test
        @DisplayName("enables the outbox, so the report and its event are one commit")
        void outboxIsEnabled() {
            assertThat(environment.getProperty("aegis.outbox.enabled")).isEqualTo("true");
        }

        /**
         * Flyway owns the schema. {@code validate} turns a mapping/schema mismatch into a startup
         * failure; {@code update} would let Hibernate quietly ALTER a live database, and this schema
         * contains append-only triggers and a {@code vector} column Hibernate does not understand.
         */
        @Test
        @DisplayName("never lets Hibernate touch the schema")
        void hibernateDoesNotOwnTheSchema() {
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                    .as("the schema has append-only triggers and a vector column Hibernate cannot "
                            + "model; letting it ALTER anything is how those get dropped")
                    .isEqualTo("validate");
        }

        /**
         * The recommendation vocabulary is deliberately not configurable — it is a compiled enum plus
         * a database CHECK. A configurable list would put the one field a prompt injection targets an
         * environment variable away from being extended, and an extended vocabulary is how a
         * recommendation acquires a value some consumer treats as a command.
         */
        @Test
        @DisplayName("exposes no configuration key for the recommendation vocabulary")
        void vocabularyIsNotConfigurable() {
            String yaml = readShippedYaml().toLowerCase();

            assertThat(yaml)
                    .as("""
                            the closed vocabulary is the real injection defence: the worst a \
                            successful injection achieves is a value already on the list. A \
                            configurable list gives that back""")
                    .doesNotContain("recommendation-vocabulary")
                    .doesNotContain("allowed-recommendations")
                    .doesNotContain("recommendations:");
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Loads {@code application.yml} and binds the {@code aegis.ai} subtree.
     *
     * <p>Environment placeholders resolve to their defaults, which is what an unconfigured deployment
     * gets — and therefore the values worth asserting.
     */
    private static AiProperties bindShippedConfiguration() {
        AiProperties bound = new AiProperties();
        Binder.get(shippedEnvironment()).bind(PREFIX, Bindable.ofInstance(bound));
        return bound;
    }

    private static StandardEnvironment shippedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"));
            sources.forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (IOException ex) {
            throw new AssertionError("could not read ai-investigation-service's application.yml", ex);
        }
        return environment;
    }

    private static String readShippedYaml() {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError("could not read ai-investigation-service's application.yml", ex);
        }
    }
}
