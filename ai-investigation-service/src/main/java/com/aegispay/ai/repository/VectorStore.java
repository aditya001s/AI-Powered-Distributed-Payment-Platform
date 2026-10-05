package com.aegispay.ai.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The only place that speaks pgvector.
 *
 * <p><strong>Why native SQL rather than JPA.</strong> Two reasons, and neither is avoidable. A
 * {@code vector(384)} column has no Hibernate type, so mapping it needs a third-party dialect
 * contribution — and {@code ddl-auto=validate} would then be validating a type it does not really
 * understand. More decisively, the only operation that matters on a vector is nearest-neighbour search
 * with {@code <=>}, which has no JPA expression at all. A mapped field would be a field nothing could
 * use.
 *
 * <p>So the metadata is JPA-mapped in {@code PolicyChunk} and the vector lives here. Hibernate never
 * sees the column, which is why {@code validate} passes.
 *
 * <h2>Cosine distance, and the conversion to similarity</h2>
 *
 * <p>pgvector's {@code <=>} returns cosine <em>distance</em> in {@code [0,2]}: 0 is identical, 1 is
 * orthogonal, 2 is opposite. Every threshold in this service is expressed as cosine
 * <em>similarity</em> in {@code [-1,1]}, because that is the number people reason about. The
 * conversion is {@code similarity = 1 - distance}, applied in SQL so the ORDER BY and the filter agree
 * — computing it in Java would mean ordering by one quantity and filtering by another.
 *
 * <h2>Why the similarity floor is applied in the query</h2>
 *
 * <p>An ANN search always returns its {@code k} nearest neighbours, however far away they are. Without
 * a floor, a payment with no relevant policy retrieves the five least-irrelevant documents in the
 * corpus and the narrative cites them as though they mattered. The floor is what makes "nothing
 * matched" an expressible outcome, and an investigation that says so is far more useful than one that
 * pads itself with the closest available text.
 */
@Repository
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    private final JdbcTemplate jdbc;

    public VectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One retrieved passage, with everything a citation needs.
     *
     * @param similarity cosine similarity in {@code [-1,1]}, already converted from pgvector's distance
     * @param rank       1-based position in the result, so a citation can record how strong a match it was
     */
    public record Match(
            String chunkId,
            String documentId,
            String documentTitle,
            int documentVersion,
            String category,
            int ordinal,
            String content,
            int contentTokens,
            double similarity,
            int rank
    ) {
        /** Whether this match is strong enough to present as supporting evidence. */
        public boolean clears(double floor) {
            return similarity >= floor;
        }
    }

    /**
     * Writes a chunk's vector.
     *
     * <p>An upsert on the chunk's primary key, so re-seeding an unchanged corpus is idempotent rather
     * than a duplicate-key failure at every startup. The row's metadata is inserted by JPA first; this
     * fills in the column JPA cannot see.
     *
     * <p>The vector is passed as a string literal cast to {@code vector}, which is pgvector's own text
     * format. A binary bind would be marginally faster and would need the pgvector JDBC extension;
     * this runs once per chunk at seed time, so the dependency is not worth it.
     */
    @Transactional
    public void storeEmbedding(String chunkId, float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("chunk " + chunkId + " has no vector to store");
        }
        jdbc.update("UPDATE policy_chunks SET embedding = ?::vector WHERE chunk_id = ?",
                toVectorLiteral(embedding), chunkId);
    }

    /**
     * Finds the nearest passages to a query vector.
     *
     * <p>Filtered by {@code embedder}, and that filter is load-bearing rather than tidy. A vector is
     * comparable only to vectors produced by the same embedder; comparing across embedders does not
     * error, it returns confident nonsense. Filtering here means an embedder change makes retrieval
     * return nothing until the corpus is rebuilt — loud, and correct.
     *
     * <p>The floor is applied in SQL, so a caller cannot forget it. It is also applied <em>after</em>
     * the ANN ordering rather than as a pre-filter, because pgvector's HNSW index cannot use a
     * predicate on a computed distance: asking for it as a WHERE clause would fall back to a
     * sequential scan over the whole corpus.
     *
     * @param floor minimum cosine similarity. A passage below it is not evidence, and returning it
     *              would let a narrative cite the least-irrelevant document in the corpus.
     * @param limit how many to consider before the floor is applied
     */
    @Transactional(readOnly = true)
    public List<Match> search(float[] queryVector, String embedder, double floor, int limit) {
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        String literal = toVectorLiteral(queryVector);

        // The subquery does the ANN ordering so the index is used; the outer WHERE applies the floor.
        // Inverted, the floor would become a predicate on a computed value and the planner would stop
        // using the HNSW index entirely.
        List<Match> matches = jdbc.query("""
                        SELECT chunk_id, document_id, document_title, document_version, category,
                               ordinal, content, content_tokens, similarity
                        FROM (
                            SELECT c.chunk_id,
                                   c.document_id,
                                   d.title   AS document_title,
                                   d.version AS document_version,
                                   d.category,
                                   c.ordinal,
                                   c.content,
                                   c.content_tokens,
                                   1 - (c.embedding <=> ?::vector) AS similarity
                            FROM policy_chunks c
                            JOIN policy_documents d ON d.document_id = c.document_id
                            WHERE c.embedder = ?
                            ORDER BY c.embedding <=> ?::vector
                            LIMIT ?
                        ) ranked
                        WHERE similarity >= ?
                        ORDER BY similarity DESC
                        """,
                (rs, rowNum) -> new Match(
                        rs.getString("chunk_id"),
                        rs.getString("document_id"),
                        rs.getString("document_title"),
                        rs.getInt("document_version"),
                        rs.getString("category"),
                        rs.getInt("ordinal"),
                        rs.getString("content"),
                        rs.getInt("content_tokens"),
                        rs.getDouble("similarity"),
                        rowNum + 1),
                literal, embedder, literal, limit, floor);

        if (matches.isEmpty()) {
            log.debug("No policy chunk cleared the similarity floor of {} for embedder {}",
                    floor, embedder);
        }
        return matches;
    }

    /**
     * Chunks that have metadata but no vector.
     *
     * <p>Reachable if the process died between the JPA insert and the vector update, which are separate
     * statements in the same transaction but not the same statement. A chunk with no vector is invisible
     * to retrieval and silently reduces the corpus, so the seeder checks for it on every startup rather
     * than trusting that the last one finished.
     */
    @Transactional(readOnly = true)
    public List<String> findChunksMissingEmbedding(String embedder) {
        return jdbc.queryForList(
                "SELECT chunk_id FROM policy_chunks WHERE embedder = ? AND embedding IS NULL",
                String.class, embedder);
    }

    /**
     * Formats a vector the way pgvector parses it: {@code [1.0,2.0,3.0]}.
     *
     * <p>{@code Float.toString} rather than a formatted decimal, deliberately. A fixed number of
     * decimal places would quantise the vector, and two chunks whose embeddings differ only in the
     * discarded digits would become identical — collapsing distinct passages into one retrieval result.
     * Locale-independent by construction, which a {@code String.format("%f")} would not be: in a
     * comma-decimal locale that produces {@code [1,0,2,0]}, a 4-element vector where a 2-element one was
     * intended, and pgvector would reject or misread it.
     */
    static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 12 + 2);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            float value = vector[i];
            if (!Float.isFinite(value)) {
                // NaN or infinity would make every distance involving this vector NaN, and a NaN
                // distance sorts unpredictably: the chunk would appear at a random position in every
                // result rather than being excluded.
                throw new IllegalArgumentException(
                        "vector contains a non-finite value at index " + i + ": " + value);
            }
            builder.append(Float.toString(value));
        }
        builder.append(']');
        return builder.toString();
    }
}
