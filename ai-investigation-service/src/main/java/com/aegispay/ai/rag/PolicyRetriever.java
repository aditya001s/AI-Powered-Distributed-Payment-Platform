package com.aegispay.ai.rag;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.PolicyDocument;
import com.aegispay.ai.repository.PolicyChunkRepository;
import com.aegispay.ai.repository.PolicyDocumentRepository;
import com.aegispay.ai.repository.VectorStore;
import com.aegispay.common.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the policy an investigation is allowed to reason from.
 *
 * <h2>Two routes, and why both</h2>
 *
 * <p><strong>Declared rule mappings</strong> come first. When {@code RULE_BUST_OUT_PATTERN} fired, the
 * policy that explains bust-out is not merely relevant — it is the document a reviewer would expect to
 * see cited, and its presence must not depend on an embedder having learned that a machine token relates
 * to a paragraph of prose. The association is written down in {@code related_rule_ids} and looked up
 * exactly.
 *
 * <p><strong>Vector search</strong> comes second, and widens the net. It finds policy that is relevant to
 * the payment's <em>shape</em> — a large amount, a new device, an unfamiliar country — where no specific
 * rule fired, which is precisely the case a model-only verdict produces. It guarantees nothing, which is
 * why it is not the only route.
 *
 * <p>Ordering matters: declared matches are ranked ahead of searched ones regardless of similarity, so
 * the prompt budget is spent on the authoritative document before the merely similar one.
 *
 * <h2>Why the similarity floor is not negotiable</h2>
 *
 * <p>An approximate nearest-neighbour search always returns its {@code k} nearest neighbours, however far
 * away they are. Without a floor, a payment with no relevant policy retrieves the five least-irrelevant
 * documents in the corpus, and a narrative built on them cites policy that has nothing to do with the
 * case. The floor is what makes <em>"no policy matched"</em> an expressible outcome — and an
 * investigation that says so is far more useful than one padded with the closest available text.
 *
 * <h2>Read-only, and transactional for a reason</h2>
 *
 * <p>The declared lookup and the vector search must see one consistent corpus. Between them, a seeder on
 * another instance could be mid-re-embed, and a retrieval set assembled across that boundary would mix
 * two versions of a policy — citing one version's text under the other's version number.
 */
@Service
public class PolicyRetriever {

    private static final Logger log = LoggerFactory.getLogger(PolicyRetriever.class);

    private final PolicyDocumentRepository documents;
    private final PolicyChunkRepository chunks;
    private final VectorStore vectors;
    private final Embedder embedder;
    private final AiProperties properties;
    private final MeterRegistry meterRegistry;

    public PolicyRetriever(PolicyDocumentRepository documents,
                           PolicyChunkRepository chunks,
                           VectorStore vectors,
                           Embedder embedder,
                           AiProperties properties,
                           MeterRegistry meterRegistry) {
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
        this.embedder = embedder;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Retrieves policy for a payment.
     *
     * @param query    the text to search on: the evidence rendered as prose, so the vocabulary of the
     *                 query and the vocabulary of the corpus line up
     * @param ruleHits the fraud rules that fired, looked up exactly rather than searched
     * @return passages in the order they should appear in a prompt, possibly empty
     */
    @Transactional(readOnly = true)
    public List<RetrievedPolicy> retrieve(String query, List<String> ruleHits) {
        Timer.Sample sample = Timer.start(meterRegistry);
        AiProperties.Retrieval config = properties.getRetrieval();

        List<RetrievedPolicy> selected = new ArrayList<>();
        Set<String> seenDocuments = new LinkedHashSet<>();

        if (config.isAlwaysIncludeRulePolicies() && ruleHits != null) {
            selected.addAll(byDeclaredRule(ruleHits, seenDocuments));
        }
        selected.addAll(byVectorSearch(query, seenDocuments, config));

        List<RetrievedPolicy> withinBudget = applyTokenBudget(selected, config.getContextTokenBudget());

        sample.stop(meterRegistry.timer(MetricNames.AI_RETRIEVAL_LATENCY,
                "embedder", embedder.id()));
        meterRegistry.summary(MetricNames.AI_RETRIEVED_CHUNKS).record(withinBudget.size());

        if (withinBudget.isEmpty()) {
            // Not an error. It means the narrative must say it found no supporting policy, which is a
            // legitimate and useful thing for a report to say. A rising rate means the corpus has
            // drifted away from the traffic, which is why it has a counter of its own.
            meterRegistry.counter(MetricNames.AI_UNGROUNDED_INVESTIGATION).increment();
            log.info("No policy cleared the similarity floor of {} for rules {}; the investigation "
                            + "will state that it has no written grounding",
                    config.getMinimumSimilarity(), ruleHits);
        }
        return withinBudget;
    }

    /**
     * Policies whose author declared them as explaining a rule that fired.
     *
     * <p>The first chunk of each such document is taken rather than the whole thing. A policy is authored
     * with its definition up front, so chunk 0 is the part that answers "what is this rule", and pulling
     * every chunk of a long policy would consume the whole prompt budget on one document.
     */
    private List<RetrievedPolicy> byDeclaredRule(List<String> ruleHits, Set<String> seenDocuments) {
        List<RetrievedPolicy> found = new ArrayList<>();
        int rank = 1;

        for (String ruleId : ruleHits) {
            if (ruleId == null || ruleId.isBlank()) {
                continue;
            }
            for (PolicyDocument document : documents.findByRelatedRule(ruleId)) {
                if (!seenDocuments.add(document.getDocumentId())) {
                    // Two rules can share a policy. Cited once: a duplicated citation would suggest two
                    // independent sources agreed, when it is one document counted twice.
                    continue;
                }
                List<com.aegispay.ai.domain.PolicyChunk> documentChunks =
                        chunks.findByDocumentIdOrderByOrdinalAsc(document.getDocumentId());
                if (documentChunks.isEmpty()) {
                    // The document exists but was never chunked for the current embedder — a corpus
                    // mid-migration. Skipped rather than cited from the raw body, because a citation
                    // must point at something retrieval could have found.
                    log.warn("Policy {} explains rule {} but has no chunks for embedder {}; it will "
                                    + "not be cited until the corpus is re-embedded",
                            document.getDocumentId(), ruleId, embedder.id());
                    continue;
                }
                com.aegispay.ai.domain.PolicyChunk first = documentChunks.getFirst();
                found.add(new RetrievedPolicy(
                        document.getDocumentId(),
                        document.getTitle(),
                        document.getVersion(),
                        document.getCategory(),
                        first.getChunkId(),
                        first.getOrdinal(),
                        first.getContent(),
                        first.getContentTokens(),
                        // 1.0, not a measured value: nothing was compared. Reporting a similarity here
                        // would invent a number and make an exact match look like a close one.
                        1.0,
                        rank++,
                        RetrievedPolicy.Source.DECLARED_RULE));
            }
        }
        return found;
    }

    private List<RetrievedPolicy> byVectorSearch(String query, Set<String> seenDocuments,
                                                 AiProperties.Retrieval config) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        float[] queryVector = embedder.embed(query);
        if (isZero(queryVector)) {
            // Every term was a stopword, or the query was punctuation. Cosine distance against a zero
            // vector is undefined and pgvector reports NaN, which sorts unpredictably — so the chunk
            // would appear at a random position in every result rather than being excluded. Refused here.
            log.debug("Query produced a zero vector, so no similarity search is possible");
            return List.of();
        }

        // topK plus the documents already taken, so a declared policy that would also have matched by
        // similarity does not consume one of the k slots and silently narrow the search.
        int limit = config.getTopK() + seenDocuments.size();

        List<VectorStore.Match> matches = vectors.search(
                queryVector, embedder.id(), config.getMinimumSimilarity(), limit);

        List<RetrievedPolicy> found = new ArrayList<>();
        int rank = seenDocuments.size() + 1;

        for (VectorStore.Match match : matches) {
            if (found.size() >= config.getTopK()) {
                break;
            }
            if (!seenDocuments.add(match.documentId())) {
                continue;
            }
            found.add(new RetrievedPolicy(
                    match.documentId(),
                    match.documentTitle(),
                    match.documentVersion(),
                    match.category(),
                    match.chunkId(),
                    match.ordinal(),
                    match.content(),
                    match.contentTokens(),
                    match.similarity(),
                    rank++,
                    RetrievedPolicy.Source.VECTOR_SEARCH));
        }
        return found;
    }

    /**
     * Trims the retrieval set to fit the prompt budget.
     *
     * <p>Truncates whole passages rather than clipping the last one. A chunk cut mid-sentence is worse
     * than an absent chunk: it is still cited, still looks like evidence, and the part that mattered may
     * be the part that was removed.
     *
     * <p>Always keeps at least one passage, even if it alone exceeds the budget. A retrieval that found
     * something and then reported nothing because of an accounting limit would be indistinguishable from
     * a corpus with no relevant policy — and those two states call for completely different responses.
     */
    private List<RetrievedPolicy> applyTokenBudget(List<RetrievedPolicy> candidates, int budget) {
        List<RetrievedPolicy> kept = new ArrayList<>();
        int used = 0;

        for (RetrievedPolicy candidate : candidates) {
            int cost = candidate.approximateTokens();
            if (!kept.isEmpty() && used + cost > budget) {
                log.debug("Dropping policy {} from the retrieval set: {} tokens would exceed the "
                        + "{}-token budget", candidate.documentId(), cost, budget);
                continue;
            }
            kept.add(candidate);
            used += cost;
        }
        return List.copyOf(kept);
    }

    /** The embedder in use, recorded on every investigation so a retrieval set is reproducible. */
    public String embedderId() {
        return embedder.id();
    }

    /**
     * Whether the embedder in use captures meaning beyond word overlap.
     *
     * <p>Surfaced so a report can describe its own confidence honestly. With a lexical embedder, "no
     * policy matched" means no policy shared vocabulary with the query — <em>not</em> that no relevant
     * policy exists, which is what a semantic null result would suggest.
     */
    public boolean isSemanticRetrieval() {
        return embedder.isSemantic();
    }

    /**
     * How many chunks are usable by the current embedder.
     *
     * <p>Zero with a non-empty table means the corpus was embedded by a previous embedder and is
     * invisible to retrieval — correctly, because the vectors are not comparable, but silently. Exposed
     * so readiness can fail on it rather than a week of ungrounded reports being the first symptom.
     */
    public long usableChunkCount() {
        return chunks.countUsable(embedder.id());
    }

    private static boolean isZero(float[] vector) {
        for (float value : vector) {
            if (value != 0.0f) {
                return false;
            }
        }
        return true;
    }
}
