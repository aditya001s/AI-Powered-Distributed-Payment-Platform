package com.aegispay.ai.rag;

/**
 * Turns text into a vector for similarity search.
 *
 * <h2>Why the identifier is part of the contract</h2>
 *
 * <p>{@link #id()} is stored on every chunk and every investigation, and retrieval filters on it.
 * That is not bookkeeping. A vector is comparable only to vectors produced by the same embedder, and
 * comparing across embedders does not fail — it returns confident nonsense, with cosine similarities
 * that look entirely reasonable and mean nothing. Nothing throws, no row is malformed, and retrieval
 * quietly stops working.
 *
 * <p>So the id must change whenever the output changes. Not the class name, not a version anyone has to
 * remember to bump: anything that alters a vector for the same input — the dimension, the tokenisation,
 * the weighting, the hash seed — is a different embedder and must say so. After a change, retrieval
 * returns nothing until the corpus is re-embedded, which is loud and correct.
 *
 * <h2>Why this is an interface</h2>
 *
 * <p>The default implementation runs offline with no model file and no network, because the platform has
 * to work with no API keys configured. That is a real constraint, and it has a real cost in retrieval
 * quality — see {@link HashingEmbedder}, which is explicit about being lexical rather than semantic.
 * The seam exists so a deployment with a hosted embedding endpoint can swap in a genuinely semantic
 * model without touching the schema, the retriever or the prompt: same 384 dimensions, different id,
 * re-embed the corpus.
 */
public interface Embedder {

    /**
     * Stable identifier for this embedder's output.
     *
     * <p>Must change whenever the vectors change. Stored on every chunk and used as a retrieval filter.
     */
    String id();

    /** Vector dimension. Must match the {@code vector(n)} column, or every insert fails. */
    int dimensions();

    /**
     * Embeds text.
     *
     * <p>Implementations must return an L2-normalised vector, so cosine similarity is a dot product and
     * a long document does not out-score a short one purely by having more of everything.
     *
     * @return a vector of exactly {@link #dimensions()} finite floats; never null
     */
    float[] embed(String text);

    /**
     * Whether this embedder captures meaning beyond word overlap.
     *
     * <p>Exposed because it changes how a retrieval result should be read, and therefore how the
     * service should describe its own confidence. A lexical embedder that returns nothing has not
     * established that no relevant policy exists — only that none shares vocabulary with the query. A
     * report built on lexical retrieval should not claim to have searched semantically, so this flag
     * reaches the API rather than staying an implementation detail.
     */
    default boolean isSemantic() {
        return false;
    }
}
