package com.aegispay.ai.generation;

import com.aegispay.ai.domain.InvestigationCitation;
import com.aegispay.ai.rag.RetrievedPolicy;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks a generator's citations against what was actually retrieved.
 *
 * <h2>Why this is possible at all</h2>
 *
 * <p>Hallucination is usually hard to detect because there is no reference to check against. Here there
 * is: the set of documents placed in the prompt is known exactly, and a citation naming anything outside
 * it was invented. Not "probably wrong" — invented, with certainty, because the model was never shown
 * such a document and this platform's corpus is closed.
 *
 * <p>That makes this the strongest verification in the service, and the reason the RAG design is worth
 * having beyond retrieval quality: grounding is checkable.
 *
 * <h2>Why an invented citation is recorded rather than dropped</h2>
 *
 * <p>Dropping it is the obvious move — it is worthless as evidence. It would also destroy the only trace
 * of the generator having invented evidence, which is the most important thing this service can tell
 * anyone about the model it is using. So it is persisted with {@code hallucinated} set, zero similarity,
 * and no claim to authority, and it drives a counter an operator can alert on.
 *
 * <p>The specific invented id matters too. A generator that reliably cites {@code POLICY-AML} is telling
 * you the corpus is missing a document somebody expects to exist, which is actionable in a way a bare
 * count is not.
 *
 * <h2>Why validation is separate from materialising rows</h2>
 *
 * <p>A citation row references the investigation by its public id, and that id is only assigned when the
 * report is inserted. So the check has to happen before the write — the hallucination count is stored
 * <em>on</em> the report, and discovering it afterwards would need a second update to an append-only row —
 * while the rows can only be built during it.
 *
 * <p>Splitting the two also keeps the metric honest. Validating twice to get the count and then the rows
 * would count every hallucination twice, which is the kind of defect that makes an alert threshold
 * meaningless.
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    private final MeterRegistry meterRegistry;

    public CitationValidator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** A citation that resolved to a passage actually put in front of the generator. */
    private record Verified(String documentId, String chunkId, int documentVersion,
                            double similarity, boolean citedInReport) {
    }

    /**
     * The outcome of validating one report's citations, before an investigation id exists.
     *
     * <p>Immutable and id-free, so it can be computed once before the write and materialised into rows
     * during it.
     */
    public static final class Result {

        private final List<Verified> verified;
        private final List<String> invented;

        private Result(List<Verified> verified, List<String> invented) {
            this.verified = List.copyOf(verified);
            this.invented = List.copyOf(invented);
        }

        /** How many citations were invented. Non-zero belongs in front of a reader. */
        public int hallucinated() {
            return invented.size();
        }

        public boolean isClean() {
            return invented.isEmpty();
        }

        /** The ids the generator invented, for logging and for the corpus-gap signal. */
        public List<String> inventedDocumentIds() {
            return invented;
        }

        /**
         * Builds the rows for a persisted investigation.
         *
         * <p>Grounded citations first in retrieval order, then the fabrications, so a reader scanning the
         * list sees the usable evidence before the noise.
         */
        public List<InvestigationCitation> citationsFor(String investigationId) {
            List<InvestigationCitation> rows = new ArrayList<>(verified.size() + invented.size());
            int rank = 1;

            for (Verified citation : verified) {
                rows.add(InvestigationCitation.grounded(investigationId, citation.documentId(),
                        citation.chunkId(), citation.documentVersion(), citation.similarity(),
                        rank++, citation.citedInReport()));
            }
            for (String fabrication : invented) {
                rows.add(InvestigationCitation.hallucinated(investigationId, fabrication, rank++));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * Validates the claimed citations.
     *
     * <p>Every retrieved document is recorded, not only the ones the generator named. Two reasons. A
     * reader needs to know what the generator was <em>shown</em> in order to judge what it chose to use —
     * a report that ignored the policy defining the rule that fired is worth a second look. And the
     * corpus-coverage query wants to know which documents are being retrieved, which is a different
     * question from which are being cited.
     *
     * <p>Called exactly once per investigation, before the write. The metric is incremented here, so
     * calling it twice would double-count.
     *
     * @param claimed   document ids the generator said it used
     * @param retrieved the passages actually put in front of it
     */
    public Result validate(String paymentId, List<String> claimed, List<RetrievedPolicy> retrieved) {
        Map<String, RetrievedPolicy> available = new LinkedHashMap<>();
        for (RetrievedPolicy policy : retrieved) {
            // Normalised for lookup only. A model that returns "policy-bust-out" for "POLICY-BUST-OUT"
            // has cited the right document in the wrong case, and treating that as a hallucination would
            // be a false accusation against the generator over a formatting difference.
            available.putIfAbsent(normalise(policy.documentId()), policy);
        }

        List<Verified> verified = new ArrayList<>();
        List<String> invented = new ArrayList<>();

        // De-duplicated as they are consumed. A model repeating a citation is common, and two rows for one
        // document would violate the unique constraint on (investigation_id, document_id, chunk_id) — so
        // the report would fail to persist over a formatting quirk. It would also suggest two independent
        // sources agreed when it is one document counted twice.
        java.util.Set<String> seenClaims = new java.util.HashSet<>();

        for (String claim : claimed) {
            if (!seenClaims.add(normalise(claim))) {
                continue;
            }
            RetrievedPolicy match = available.get(normalise(claim));
            if (match == null) {
                invented.add(claim);
                continue;
            }
            verified.add(new Verified(match.documentId(), match.chunkId(),
                    match.documentVersion(), match.similarity(), true));
        }

        // Retrieved but not cited. Recorded so a reader can see what the generator declined to use, which
        // is otherwise invisible: a report that ignored the most authoritative document available looks
        // identical to one where nothing relevant was found.
        for (RetrievedPolicy policy : retrieved) {
            boolean alreadyRecorded = verified.stream()
                    .anyMatch(citation -> citation.documentId().equals(policy.documentId()));
            if (!alreadyRecorded) {
                verified.add(new Verified(policy.documentId(), policy.chunkId(),
                        policy.documentVersion(), policy.similarity(), false));
            }
        }

        for (String fabrication : invented) {
            meterRegistry.counter(MetricNames.AI_HALLUCINATED_CITATION,
                    "documentId", safeTag(fabrication)).increment();
        }
        if (!invented.isEmpty()) {
            log.error("""
                            The generator cited {} document(s) for payment {} that were never \
                            retrieved: {}. These are recorded as hallucinated and must not be relied \
                            on. A generator that invents a citation is producing something that looks \
                            like evidence and is not.""",
                    invented.size(), paymentId, invented);
        }
        return new Result(verified, invented);
    }

    private static String normalise(String documentId) {
        return documentId == null ? "" : documentId.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Caps a metric tag value.
     *
     * <p>The tag is a document id the generator invented, so it is effectively model-controlled and could
     * be arbitrarily long or high-cardinality. An unbounded tag from an untrusted source is how a metrics
     * backend gets a cardinality explosion, so it is truncated and non-identifier characters are dropped.
     */
    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_.-]", "");
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        return cleaned.length() <= 48 ? cleaned : cleaned.substring(0, 48);
    }
}
