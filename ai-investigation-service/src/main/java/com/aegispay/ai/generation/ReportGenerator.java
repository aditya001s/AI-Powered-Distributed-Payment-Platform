package com.aegispay.ai.generation;

import com.aegispay.ai.rag.RetrievedPolicy;

import java.util.List;

/**
 * Turns an evidence pack and its retrieved policy into a narrative.
 *
 * <h2>Why this is an interface with a working offline default</h2>
 *
 * <p>The platform has to run end to end with no API keys, so there must be an implementation that needs
 * no network. That is not a stub: {@code LocalReportComposer} writes a real report from the evidence and
 * the retrieved policy, and reports it as deterministic so nobody mistakes its restraint for a model's
 * analysis.
 *
 * <p>The seam also isolates the one genuinely dangerous part of this service. An HTTP generator sends
 * customer-derived text to a third party and parses an untrusted response; the offline one does neither.
 * Keeping them behind one interface means the caller's validation, citation checking and recommendation
 * parsing apply identically to both, rather than being a property only the LLM path happens to have.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <ul>
 *   <li>Return a recommendation from the closed vocabulary, or fail. Never invent one.</li>
 *   <li>Never return a narrative that is not derived from the arguments. An implementation has no other
 *       source of facts, and anything else is invention.</li>
 *   <li>Throw rather than return a degraded report on failure, so the caller decides whether to fall
 *       back. An implementation that quietly substituted its own fallback would hide which generator
 *       actually wrote the text.</li>
 * </ul>
 */
public interface ReportGenerator {

    /**
     * Identifies this generator and its build, e.g. {@code local-composer/1}.
     *
     * <p>Stored on every investigation. Must change when the output changes, so a shift in the quality
     * of advice can be attributed to a version rather than to the world.
     */
    String id();

    /** Whether this writes without a language model. Reaches the analyst, so it must be truthful. */
    boolean isDeterministic();

    /**
     * Whether this generator is currently usable.
     *
     * <p>Checked before use so a misconfigured hosted provider — no API key, no base URL — is caught at
     * startup rather than producing a failed generation on the first real investigation.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Writes the report.
     *
     * @param evidence the facts, with customer-controlled text already isolated and capped
     * @param policies the passages retrieved for this payment, possibly empty. An implementation must
     *                 treat an empty list as grounds for saying it found no supporting policy, never as
     *                 licence to supply one from memory.
     * @throws GenerationException when no report could be produced
     */
    GeneratedReport generate(EvidencePack evidence, List<RetrievedPolicy> policies);

    /** A generator could not produce a report. */
    class GenerationException extends RuntimeException {

        public GenerationException(String message) {
            super(message);
        }

        public GenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
