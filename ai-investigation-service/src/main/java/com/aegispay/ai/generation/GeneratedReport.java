package com.aegispay.ai.generation;

import com.aegispay.ai.domain.Recommendation;

import java.util.List;

/**
 * What a generator produced, before its citations have been validated.
 *
 * <p><strong>{@code citedDocumentIds} is claimed, not verified.</strong> That distinction is the whole
 * point of this type existing separately from a persisted investigation. A generator can name any
 * document it likes, including one that was never retrieved and one that does not exist, and the
 * validation step compares this list against the retrieval set. Modelling the claim and the verified
 * fact as the same thing would make a hallucinated citation indistinguishable from a real one at the
 * only point where the difference is still detectable.
 *
 * @param confidence the generator's own estimate, in [0,1]. Uncalibrated by construction: there is no
 *                   ground truth for "was this narrative correct", so nothing could calibrate it
 *                   against. Useful for ordering a queue, not for a threshold.
 */
public record GeneratedReport(
        String narrative,
        List<String> findings,
        Recommendation recommendation,
        String recommendationRationale,
        double confidence,
        List<String> citedDocumentIds,

        /** Identifies the generator and its build, e.g. {@code local-composer/1} or {@code openai/gpt-4o-mini}. */
        String generatedBy,

        /** True when written by the offline composer rather than a language model. */
        boolean deterministic
) {

    public GeneratedReport {
        if (narrative == null || narrative.isBlank()) {
            throw new IllegalArgumentException("a generated report must have a narrative");
        }
        if (recommendation == null) {
            throw new IllegalArgumentException("a generated report must reach a recommendation");
        }
        if (recommendationRationale == null || recommendationRationale.isBlank()) {
            throw new IllegalArgumentException(
                    "a generated report must say why it recommends " + recommendation);
        }
        if (generatedBy == null || generatedBy.isBlank()) {
            // An unattributed report cannot be reproduced, and cannot be traced to a model build when a
            // pattern of bad advice shows up.
            throw new IllegalArgumentException("a generated report must name its generator");
        }
        if (deterministic && !generatedBy.startsWith("local")) {
            throw new IllegalArgumentException(
                    "a deterministic report must not claim a language model wrote it: " + generatedBy);
        }
        // Clamped rather than rejected. A model that returns 1.4 for confidence has misunderstood the
        // scale, which is a reason to distrust the number, not to discard an otherwise usable report.
        confidence = Math.clamp(Double.isFinite(confidence) ? confidence : 0.0, 0.0, 1.0);
        findings = findings == null ? List.of() : List.copyOf(findings);
        citedDocumentIds = citedDocumentIds == null ? List.of() : List.copyOf(citedDocumentIds);
    }
}
