package com.aegispay.ai.generation;

import com.aegispay.ai.domain.InvestigationCitation;
import com.aegispay.ai.rag.RetrievedPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for citation validation — the hallucination detector.
 *
 * <p>Hallucination is usually hard to detect because there is no reference to check against. Here there is:
 * the set of documents placed in the prompt is known exactly, and a citation naming anything outside it was
 * invented with certainty, not merely probably. That makes this the strongest verification in the service and
 * the reason the RAG design is worth having beyond retrieval quality.
 */
class CitationValidatorTest {

    private static final String INVESTIGATION = "INV-TEST-1";

    private SimpleMeterRegistry meterRegistry;
    private CitationValidator validator;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        validator = new CitationValidator(meterRegistry);
    }

    @Nested
    @DisplayName("a clean report")
    class Clean {

        @Test
        @DisplayName("records a cited document as verifiable")
        void recordsVerifiedCitation() {
            CitationValidator.Result result = validator.validate("PAY-1",
                    List.of("POLICY-BUST-OUT"),
                    List.of(policy("POLICY-BUST-OUT", 3, 0.61)));

            assertThat(result.isClean()).isTrue();
            assertThat(result.hallucinated()).isZero();

            List<InvestigationCitation> rows = result.citationsFor(INVESTIGATION);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getDocumentId()).isEqualTo("POLICY-BUST-OUT");
            assertThat(rows.getFirst().isVerifiable()).isTrue();
            assertThat(rows.getFirst().isCitedInReport()).isTrue();
            assertThat(rows.getFirst().getSimilarity()).isEqualTo(0.61);
            assertThat(rows.getFirst().getDocumentVersion())
                    .as("the version read is what makes a citation resolve to the text the "
                            + "generator actually saw")
                    .isEqualTo(3);
        }

        /**
         * A model that returns lower case has cited the right document in the wrong case. Treating that as a
         * hallucination would be a false accusation over a formatting difference.
         */
        @Test
        @DisplayName("matches a citation whose case differs")
        void caseInsensitive() {
            CitationValidator.Result result = validator.validate("PAY-1",
                    List.of("policy-bust-out", "  POLICY-HOLD-HANDLING  "),
                    List.of(policy("POLICY-BUST-OUT", 1, 0.6),
                            policy("POLICY-HOLD-HANDLING", 1, 0.4)));

            assertThat(result.isClean()).isTrue();
            assertThat(result.citationsFor(INVESTIGATION)).hasSize(2);
        }

        /**
         * A report that ignored the most authoritative document available looks identical to one where nothing
         * relevant was found, unless what was retrieved is recorded too.
         */
        @Test
        @DisplayName("records a retrieved document the generator did not cite")
        void recordsUncitedRetrieval() {
            CitationValidator.Result result = validator.validate("PAY-1",
                    List.of("POLICY-BUST-OUT"),
                    List.of(policy("POLICY-BUST-OUT", 1, 0.6),
                            policy("POLICY-HOLD-HANDLING", 1, 0.31)));

            assertThat(result.isClean()).isTrue();
            List<InvestigationCitation> rows = result.citationsFor(INVESTIGATION);
            assertThat(rows)
                    .as("what the generator was SHOWN is needed to judge what it chose to use")
                    .hasSize(2);
            assertThat(rows).filteredOn(InvestigationCitation::isCitedInReport)
                    .extracting(InvestigationCitation::getDocumentId)
                    .containsExactly("POLICY-BUST-OUT");
            assertThat(rows).filteredOn(citation -> !citation.isCitedInReport())
                    .extracting(InvestigationCitation::getDocumentId)
                    .containsExactly("POLICY-HOLD-HANDLING");
        }

        @Test
        @DisplayName("ranks cited documents before uncited ones")
        void citedComeFirst() {
            List<InvestigationCitation> rows = validator.validate("PAY-1",
                            List.of("POLICY-HOLD-HANDLING"),
                            List.of(policy("POLICY-BUST-OUT", 1, 0.6),
                                    policy("POLICY-HOLD-HANDLING", 1, 0.31)))
                    .citationsFor(INVESTIGATION);

            assertThat(rows.getFirst().getDocumentId()).isEqualTo("POLICY-HOLD-HANDLING");
            assertThat(rows.getFirst().getRank()).isEqualTo(1);
        }

        @Test
        @DisplayName("de-duplicates a document cited twice")
        void deduplicates() {
            CitationValidator.Result result = validator.validate("PAY-1",
                    List.of("POLICY-BUST-OUT", "POLICY-BUST-OUT"),
                    List.of(policy("POLICY-BUST-OUT", 1, 0.6)));

            assertThat(result.citationsFor(INVESTIGATION))
                    .as("a duplicated citation would suggest two independent sources agreed, when "
                            + "it is one document counted twice")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("an invented citation")
    class Hallucination {

        /**
         * <strong>The detection this class exists for.</strong> The corpus is closed and the retrieval set is
         * known, so a citation outside it was invented — with certainty.
         */
        @Test
        @DisplayName("is detected and counted")
        void detected() {
            CitationValidator.Result result = validator.validate("PAY-1",
                    List.of("POLICY-BUST-OUT", "POLICY-AML-SCREENING"),
                    List.of(policy("POLICY-BUST-OUT", 1, 0.6)));

            assertThat(result.isClean()).isFalse();
            assertThat(result.hallucinated()).isEqualTo(1);
            assertThat(result.inventedDocumentIds()).containsExactly("POLICY-AML-SCREENING");
        }

        /**
         * Dropping it is the obvious move and would destroy the only trace of the generator having invented
         * evidence — which is the most important thing this service can report about the model it is using.
         */
        @Test
        @DisplayName("is recorded rather than discarded")
        void recorded() {
            List<InvestigationCitation> rows = validator.validate("PAY-1",
                            List.of("POLICY-AML-SCREENING"),
                            List.of(policy("POLICY-BUST-OUT", 1, 0.6)))
                    .citationsFor(INVESTIGATION);

            InvestigationCitation invented = rows.stream()
                    .filter(InvestigationCitation::isHallucinated)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the fabrication was not recorded"));

            assertThat(invented.getDocumentId()).isEqualTo("POLICY-AML-SCREENING");
            assertThat(invented.isCitedInReport()).isTrue();
            assertThat(invented.getSimilarity())
                    .as("nothing was compared, so any other value would let an invented citation "
                            + "look ranked")
                    .isZero();
            assertThat(invented.getDocumentVersion())
                    .as("no document was read")
                    .isZero();
            assertThat(invented.getChunkId()).isNull();
        }

        @Test
        @DisplayName("increments a metric tagged with the invented id")
        void incrementsMetric() {
            validator.validate("PAY-1", List.of("POLICY-AML-SCREENING"), List.of());

            assertThat(meterRegistry.get("aegis.ai.citation.hallucinated")
                    .tag("documentId", "POLICY-AML-SCREENING")
                    .counter().count())
                    .as("the specific invented id is actionable: a generator reliably citing "
                            + "POLICY-AML is saying the corpus is missing a document")
                    .isEqualTo(1.0);
        }

        /**
         * The tag value is effectively model-controlled, so it could be arbitrarily long or high-cardinality —
         * which is how a metrics backend gets a cardinality explosion.
         */
        @Test
        @DisplayName("sanitises a hostile document id before using it as a metric tag")
        void sanitisesMetricTag() {
            validator.validate("PAY-1",
                    List.of("POLICY-" + "X".repeat(500) + " \n\t{weird}"), List.of());

            assertThat(meterRegistry.find("aegis.ai.citation.hallucinated").counters())
                    .allSatisfy(counter -> assertThat(counter.getId().getTag("documentId"))
                            .hasSizeLessThanOrEqualTo(48)
                            .matches("[A-Za-z0-9_.-]+"));
        }

        /**
         * The worst case: a report citing only invented sources looks fully grounded to anyone reading the
         * narrative.
         */
        @Test
        @DisplayName("counts every fabrication when nothing was retrieved at all")
        void allInvented() {
            CitationValidator.Result result = validator.validate("PAY-1",
                    List.of("POLICY-A", "POLICY-B", "POLICY-C"), List.of());

            assertThat(result.hallucinated()).isEqualTo(3);
            assertThat(result.citationsFor(INVESTIGATION))
                    .hasSize(3)
                    .allMatch(InvestigationCitation::isHallucinated);
        }
    }

    @Nested
    @DisplayName("edge cases")
    class Edges {

        @Test
        @DisplayName("is clean when nothing was cited and nothing retrieved")
        void emptyIsClean() {
            CitationValidator.Result result = validator.validate("PAY-1", List.of(), List.of());

            assertThat(result.isClean()).isTrue();
            assertThat(result.citationsFor(INVESTIGATION)).isEmpty();
        }

        @Test
        @DisplayName("assigns consecutive ranks across verified and invented citations")
        void ranksAreConsecutive() {
            List<InvestigationCitation> rows = validator.validate("PAY-1",
                            List.of("POLICY-BUST-OUT", "POLICY-INVENTED"),
                            List.of(policy("POLICY-BUST-OUT", 1, 0.6),
                                    policy("POLICY-HOLD-HANDLING", 1, 0.3)))
                    .citationsFor(INVESTIGATION);

            assertThat(rows).hasSize(3);
            for (int i = 0; i < rows.size(); i++) {
                assertThat(rows.get(i).getRank())
                        .as("a duplicate rank would violate the unique constraint on the citation "
                                + "table")
                        .isEqualTo(i + 1);
            }
        }

        /**
         * The same result must be materialisable against whichever id the insert assigns, because validation
         * happens before the write and the id only exists during it.
         */
        @Test
        @DisplayName("materialises the same result against any investigation id")
        void materialisesForAnyId() {
            CitationValidator.Result result = validator.validate("PAY-1",
                    List.of("POLICY-BUST-OUT"), List.of(policy("POLICY-BUST-OUT", 1, 0.6)));

            assertThat(result.citationsFor("INV-A").getFirst().getInvestigationId())
                    .isEqualTo("INV-A");
            assertThat(result.citationsFor("INV-B").getFirst().getInvestigationId())
                    .isEqualTo("INV-B");
        }
    }

    private static RetrievedPolicy policy(String documentId, int version, double similarity) {
        return new RetrievedPolicy(documentId, documentId + " title", version, "ATTACK_PATTERN",
                documentId + "#0@e", 0, "A policy passage.", 20, similarity, 1,
                RetrievedPolicy.Source.VECTOR_SEARCH);
    }
}
