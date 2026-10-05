package com.aegispay.ai.rag;

import com.aegispay.ai.rag.PolicyDocumentLoader.LoadedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The policy corpus, and the rules for reading it.
 *
 * <p>Two things are being tested here and they fail in opposite ways. The parser tests check that a
 * malformed policy is <em>rejected</em>, because the alternative — a policy that loads with a missing
 * field — produces a citation an analyst cannot follow. The corpus tests check that the shipped
 * documents are actually well-formed, because they are content rather than code and nothing else
 * compiles them.
 *
 * <p>The corpus assertions matter more than they look. A policy with a misspelled
 * {@code relatedRuleIds} entry loads without complaint, seeds without complaint and is simply never
 * retrieved by the declared mapping — and because vector search still runs, retrieval keeps returning
 * <em>something</em>. The failure mode is investigations that quietly cite less relevant policy than
 * they should, which no runtime check would ever surface.
 */
class PolicyDocumentLoaderTest {

    private final PolicyDocumentLoader loader = new PolicyDocumentLoader();

    /**
     * Every rule id the fraud service's engine can emit.
     *
     * <p>Duplicated from {@code RuleEngine} on purpose: this module does not depend on fraud-service
     * and should not start now — the coupling between them is an event contract, not a classpath.
     *
     * <p>The limitation is worth being honest about. Because the list is a copy, this test cannot
     * notice a <em>new</em> rule added in fraud-service with no policy written for it. What it does
     * catch is the far likelier mistake: an id in the corpus that has drifted from the one the engine
     * actually emits, whether by typo, by rename, or by writing the constant's Java name
     * ({@code RULE_BUST_OUT}) instead of its value ({@code RULE_BUST_OUT_PATTERN}).
     */
    private static final Set<String> ENGINE_RULE_IDS = Set.of(
            "RULE_CONFIRMED_FRAUD_HISTORY",
            "RULE_BUST_OUT_PATTERN",
            "RULE_CARD_TESTING_VELOCITY",
            "RULE_IMPOSSIBLE_TRAVEL",
            "RULE_NEW_DEVICE_HIGH_VALUE",
            "RULE_DORMANT_ACCOUNT_LARGE_PAYMENT",
            "RULE_UNFAMILIAR_CASH_OUT_CORRIDOR",
            "RULE_FIRST_PAYMENT_UNUSUALLY_LARGE");

    // ------------------------------------------------------------------------
    // the shipped corpus
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("shipped corpus")
    class ShippedCorpus {

        private final List<LoadedPolicy> policies = loader.loadAll();

        @Test
        @DisplayName("loads every bundled policy")
        void loadsEveryPolicy() {
            assertThat(policies)
                    .as("a policy that fails to load is a policy that cannot be cited, and the "
                            + "absence shows up only as an investigation that never mentions it")
                    .isNotEmpty();
            assertThat(policies).extracting(LoadedPolicy::documentId)
                    .contains("POLICY-BUST-OUT", "POLICY-CARD-TESTING", "POLICY-IMPOSSIBLE-TRAVEL",
                            "POLICY-ACCOUNT-TAKEOVER", "POLICY-MULE-CASHOUT",
                            "POLICY-CONFIRMED-FRAUD-HISTORY", "POLICY-NEW-ACCOUNT",
                            "POLICY-HOLD-HANDLING", "POLICY-DEGRADED-VERDICT",
                            "POLICY-EVIDENCE-STANDARD", "POLICY-AUTHORITY-BOUNDARY");
        }

        /**
         * Filesystem enumeration order differs between machines, and between a jar and an exploded
         * directory. An unstable order would make the seeder's log and any corpus assertion differ
         * between environments for no reason at all.
         */
        @Test
        @DisplayName("returns policies in a stable order")
        void orderIsStable() {
            assertThat(policies).isSortedAccordingTo(Comparator.comparing(LoadedPolicy::documentId));
        }

        /**
         * The id is the filename, so it appears in the repository listing, cannot be duplicated by
         * accident, and survives an edit to the front matter. Since it is what every citation records,
         * the file and the citation cannot drift apart.
         */
        @Test
        @DisplayName("derives a unique document id from each filename")
        void idsAreUnique() {
            assertThat(policies).extracting(LoadedPolicy::documentId).doesNotHaveDuplicates();
            assertThat(policies).allSatisfy(policy ->
                    assertThat(policy.documentId()).startsWith("POLICY-").doesNotContain(".md"));
        }

        /**
         * Every field a citation displays has to be present. A blank title renders as an empty line in
         * an analyst's evidence list, and a missing source removes the only pointer back to the
         * authority the narrative is leaning on.
         */
        @Test
        @DisplayName("gives every policy the metadata a citation displays")
        void metadataIsComplete() {
            assertThat(policies).allSatisfy(policy -> {
                assertThat(policy.title()).as("title of " + policy.documentId()).isNotBlank();
                assertThat(policy.category()).as("category of " + policy.documentId()).isNotBlank();
                assertThat(policy.source()).as("source of " + policy.documentId()).isNotBlank();
                assertThat(policy.version()).as("version of " + policy.documentId())
                        .isGreaterThanOrEqualTo(1);
                assertThat(policy.body()).as("body of " + policy.documentId()).isNotBlank();
            });
        }

        /**
         * A body short enough to fit in a sentence is not policy, it is a heading. It would still be
         * retrievable and cite-able, which is worse than not existing: it looks like evidence.
         */
        @Test
        @DisplayName("gives every policy a body substantial enough to be evidence")
        void bodiesAreSubstantial() {
            assertThat(policies).allSatisfy(policy ->
                    assertThat(policy.body().length())
                            .as(policy.documentId() + " is too short to be evidence; a cite-able "
                                    + "stub looks like grounding without providing any")
                            .isGreaterThan(400));
        }

        /**
         * <strong>The assertion this class exists for.</strong> The declared mapping is what makes
         * retrieval reliable rather than merely plausible, and it is matched by exact string equality.
         * An id that does not match the one the engine emits fails silently and forever.
         */
        @Test
        @DisplayName("covers every rule the fraud engine can fire with at least one policy")
        void everyRuleHasAPolicy() {
            Set<String> declared = policies.stream()
                    .flatMap(policy -> policy.relatedRuleIds().stream())
                    .collect(Collectors.toSet());

            assertThat(declared)
                    .as("""
                            a rule with no declared policy is retrievable only if the embedder happens \
                            to score its prose highly against a machine token""")
                    .containsAll(ENGINE_RULE_IDS);
        }

        /**
         * The other direction, and the one that catches typos. An id in the corpus that the engine
         * never emits is dead configuration: it matches nothing, reports nothing, and reads in review
         * as though the mapping were covered.
         */
        @Test
        @DisplayName("declares no rule id the fraud engine cannot emit")
        void declaresNoUnknownRule() {
            Set<String> declared = policies.stream()
                    .flatMap(policy -> policy.relatedRuleIds().stream())
                    .collect(Collectors.toSet());

            assertThat(declared)
                    .as("""
                            an id the engine never emits matches nothing and reports nothing, but \
                            reads in review as though that rule's policy were wired up""")
                    .isSubsetOf(ENGINE_RULE_IDS);
        }

        /**
         * Not every policy explains a rule. The procedural and evidence-standard documents describe how
         * an investigation must be written and how a held payment is handled, which is exactly the
         * guidance that applies when no rule fired at all — and the case where the declared mapping
         * contributes nothing and vector search is all there is.
         */
        @Test
        @DisplayName("includes policy that applies when no rule fired")
        void includesRuleIndependentPolicy() {
            assertThat(policies)
                    .filteredOn(policy -> policy.relatedRuleIds().isEmpty())
                    .as("""
                            an investigation of a model-only verdict has no rule to look up, so the \
                            procedural policies are the only grounding available""")
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // front-matter parsing
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("front matter")
    class FrontMatter {

        private static final String VALID = """
                ---
                title: Bust-out pattern
                category: ATTACK_PATTERN
                relatedRuleIds: RULE_BUST_OUT_PATTERN
                version: 3
                source: Fraud Operations Manual, section 4.2
                ---

                An established account that suddenly empties itself is a bust-out.
                """;

        @Test
        @DisplayName("parses every declared field")
        void parsesFields() {
            LoadedPolicy policy = PolicyDocumentLoader.parse("POLICY-BUST-OUT", VALID);

            assertThat(policy.documentId()).isEqualTo("POLICY-BUST-OUT");
            assertThat(policy.title()).isEqualTo("Bust-out pattern");
            assertThat(policy.category()).isEqualTo("ATTACK_PATTERN");
            assertThat(policy.relatedRuleIds()).containsExactly("RULE_BUST_OUT_PATTERN");
            assertThat(policy.version()).isEqualTo(3);
            assertThat(policy.source()).isEqualTo("Fraud Operations Manual, section 4.2");
            assertThat(policy.body())
                    .isEqualTo("An established account that suddenly empties itself is a bust-out.");
        }

        /** A colon inside a value is normal in a citation source, so only the first one separates. */
        @Test
        @DisplayName("keeps colons inside a value")
        void keepsColonsInValues() {
            LoadedPolicy policy = PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Holds: what they cost
                    version: 1
                    ---

                    A hold has a cost, and the cost falls on a customer who is usually legitimate.
                    """);

            assertThat(policy.title()).isEqualTo("Holds: what they cost");
        }

        /**
         * The repository is checked out on Windows as often as not, and a CRLF file whose front matter
         * failed to parse would fail on one developer's machine and not another's.
         */
        @Test
        @DisplayName("reads a file with Windows line endings")
        void handlesCrlf() {
            LoadedPolicy policy = PolicyDocumentLoader.parse("POLICY-X",
                    VALID.replace("\n", "\r\n"));

            assertThat(policy.title()).isEqualTo("Bust-out pattern");
            assertThat(policy.body()).doesNotContain("\r");
        }

        @Test
        @DisplayName("tolerates leading blank lines before the front matter")
        void toleratesLeadingBlankLines() {
            assertThat(PolicyDocumentLoader.parse("POLICY-X", "\n\n" + VALID).version()).isEqualTo(3);
        }

        @Test
        @DisplayName("ignores comment lines in the front matter")
        void ignoresComments() {
            LoadedPolicy policy = PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    # reviewed by fraud ops, 2026-01
                    title: Card testing
                    version: 1
                    ---

                    A burst of failures against many payees is the shape of credential testing.
                    """);

            assertThat(policy.title()).isEqualTo("Card testing");
        }

        /**
         * A horizontal rule inside the body is ordinary markdown. Treating it as the end of the front
         * matter would truncate the policy at its first section break and lose most of the text — while
         * still loading successfully.
         */
        @Test
        @DisplayName("keeps a horizontal rule in the body")
        void keepsHorizontalRuleInBody() {
            LoadedPolicy policy = PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Card testing
                    version: 1
                    ---

                    First section, which explains the pattern.

                    ---

                    Second section, which explains what to do about it.
                    """);

            assertThat(policy.body())
                    .as("truncating at the first body rule would drop most of the policy while "
                            + "still loading successfully")
                    .contains("First section")
                    .contains("Second section");
        }

        // ------------------------------------------------------------ defaults

        /**
         * Category and source have defaults; title and version do not. The split is deliberate: a
         * missing category degrades grouping, while a missing version would make an edited policy look
         * as though every past investigation had cited today's text.
         */
        @Test
        @DisplayName("defaults category and source, which only degrade presentation")
        void defaultsOptionalFields() {
            LoadedPolicy policy = PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Something
                    version: 1
                    ---

                    A body long enough to be a real policy body rather than a heading.
                    """);

            assertThat(policy.category()).isEqualTo("GENERAL");
            assertThat(policy.source()).isEqualTo("AegisPay fraud policy");
        }

        /** Legitimately empty: a procedural policy explains no specific rule. */
        @Test
        @DisplayName("accepts an absent or blank rule list")
        void allowsNoRuleIds() {
            LoadedPolicy absent = PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Evidence standard
                    version: 1
                    ---

                    An investigation may state what the evidence shows and no more than that.
                    """);
            LoadedPolicy blank = PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Evidence standard
                    relatedRuleIds:
                    version: 1
                    ---

                    An investigation may state what the evidence shows and no more than that.
                    """);

            assertThat(absent.relatedRuleIds()).isEmpty();
            assertThat(blank.relatedRuleIds()).isEmpty();
        }

        @Test
        @DisplayName("trims rule ids and drops empty entries")
        void normalisesRuleIds() {
            LoadedPolicy policy = PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Takeover
                    relatedRuleIds: RULE_A ,  RULE_B ,,
                    version: 1
                    ---

                    A device never seen before, sending an amount this account never sends.
                    """);

            assertThat(policy.relatedRuleIds())
                    .as("a trailing comma is an easy edit to make and would otherwise become an "
                            + "empty rule id that matches nothing")
                    .containsExactly("RULE_A", "RULE_B");
        }

        // ------------------------------------------------------------ rejection

        @Test
        @DisplayName("rejects a file with no front matter")
        void rejectsMissingFrontMatter() {
            assertThatThrownBy(() -> PolicyDocumentLoader.parse("POLICY-X",
                    "Just some prose with no metadata at all."))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("front matter");
        }

        @Test
        @DisplayName("rejects front matter that is never closed")
        void rejectsUnclosedFrontMatter() {
            assertThatThrownBy(() -> PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Unclosed
                    version: 1
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not closed");
        }

        /**
         * A title is what an analyst reads in the evidence list; without it the citation is an opaque
         * id. Defaulting it to the document id was considered and rejected — it would render as
         * {@code POLICY-BUST-OUT} where a sentence should be, and look intentional.
         */
        @Test
        @DisplayName("rejects a policy with no title")
        void rejectsMissingTitle() {
            assertThatThrownBy(() -> PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    category: ATTACK_PATTERN
                    version: 1
                    ---

                    A body that is present while the title is not.
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title");
        }

        /**
         * The version is what a citation records. Without one, editing a policy would make every past
         * investigation appear to have cited the current text — which is precisely the claim a dispute
         * would be testing.
         */
        @Test
        @DisplayName("rejects a policy with no version")
        void rejectsMissingVersion() {
            assertThatThrownBy(() -> PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Something
                    ---

                    A body that is present while the version is not.
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version");
        }

        @ParameterizedTest
        @ValueSource(strings = {"one", "1.0", "0", "-1", "v2"})
        @DisplayName("rejects a version that is not a positive integer")
        void rejectsBadVersion(String version) {
            assertThatThrownBy(() -> PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Something
                    version: %s
                    ---

                    A body that is present while the version is unusable.
                    """.formatted(version)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("version");
        }

        /**
         * An empty body is the worst of the malformed cases, which is why it is fatal rather than
         * skipped: the document would be retrievable and cite-able while saying nothing. A citation of
         * it looks exactly like grounding.
         */
        @Test
        @DisplayName("rejects a policy with front matter and no body")
        void rejectsEmptyBody() {
            assertThatThrownBy(() -> PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Something
                    version: 1
                    ---
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("body");
        }

        /**
         * The parser handles {@code key: value} and nothing else. Silently skipping a line it does not
         * understand would let an author add a nested field, see no error, and assume it took effect.
         */
        @Test
        @DisplayName("rejects a front-matter line that is not 'key: value'")
        void rejectsMalformedLine() {
            assertThatThrownBy(() -> PolicyDocumentLoader.parse("POLICY-X", """
                    ---
                    title: Something
                    version: 1
                    this line has no colon
                    ---

                    A body that is present while the front matter is not parseable.
                    """))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("key: value");
        }
    }
}
