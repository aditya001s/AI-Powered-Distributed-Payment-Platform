package com.aegispay.ai.rag;

/**
 * One policy passage selected as evidence for an investigation, with how it was found.
 *
 * @param source how this passage entered the retrieval set. Recorded because the two routes carry
 *               different authority: a {@link Source#DECLARED_RULE} passage is the policy that
 *               <em>defines</em> the rule which fired, whereas a {@link Source#VECTOR_SEARCH} passage
 *               merely resembles the payment. Collapsing them would make a strong citation and a weak
 *               one indistinguishable.
 * @param similarity cosine similarity, or 1.0 for a declared match where no vector was compared. A
 *                   declared match is exact by construction, so reporting a measured similarity for it
 *                   would invent a number.
 */
public record RetrievedPolicy(
        String documentId,
        String documentTitle,
        int documentVersion,
        String category,
        String chunkId,
        int ordinal,
        String content,
        int approximateTokens,
        double similarity,
        int rank,
        Source source
) {

    public enum Source {
        /**
         * The policy author declared this document as explaining a rule that fired.
         *
         * <p>The authoritative route. It does not depend on an embedder having learned that a machine
         * token like {@code RULE_BUST_OUT_PATTERN} relates to a paragraph about accounts emptying
         * themselves — the association is written down.
         */
        DECLARED_RULE,

        /** Nearest-neighbour search over the evidence. Widens the net; guarantees nothing. */
        VECTOR_SEARCH
    }

    /**
     * Whether this passage was found by an explicit author-declared mapping rather than by resemblance.
     *
     * <p>Reaches the prompt and the citation view, so a reader can tell "this is the policy for the rule
     * that fired" from "this policy looked similar".
     */
    public boolean isAuthoritative() {
        return source == Source.DECLARED_RULE;
    }

    /** A compact label for the prompt, so the model cites an id that resolves to a real document. */
    public String citationLabel() {
        return documentId + " v" + documentVersion;
    }
}
