package com.aegispay.ai.generation;

import com.aegispay.ai.domain.Recommendation;
import com.aegispay.ai.rag.RetrievedPolicy;
import com.aegispay.common.domain.RiskLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes an investigation without a language model.
 *
 * <h2>What this is honestly called</h2>
 *
 * <p>A <strong>composer</strong>, not a model. It assembles a report from the evidence and the retrieved
 * policy using written rules about how to describe them. It does not paraphrase, does not infer, and
 * cannot say anything that is not derivable from its inputs.
 *
 * <p>Calling it an "offline LLM" or a "local model" would be the easiest lie in this codebase to tell:
 * the output is readable prose with findings and a recommendation, so it looks like model output. Every
 * report it writes is flagged {@code deterministicFallback}, and that flag reaches the API and the
 * analyst.
 *
 * <h2>Why it is the default rather than a fallback</h2>
 *
 * <p>The platform must run with no API keys, which is reason enough. But it is also the more defensible
 * generator for this task, and that is worth stating plainly rather than treating the LLM path as
 * obviously better:
 *
 * <ul>
 *   <li><strong>It cannot hallucinate.</strong> It has no source of facts other than its arguments, so
 *       every sentence traces to a value or a retrieved passage by construction. The hallucination
 *       counter exists for the LLM path.</li>
 *   <li><strong>It is reproducible.</strong> The same evidence produces the same words on every machine
 *       and after every restart. Hosted models are not deterministic even at temperature zero, so two
 *       analysts can read materially different reports on the same payment.</li>
 *   <li><strong>Its recommendation is a stated rule, not a judgement.</strong> Which means it can be
 *       reviewed, argued with, and changed on purpose.</li>
 * </ul>
 *
 * <p>What it genuinely does worse: it cannot weigh findings against each other, cannot notice that two
 * signals have a common innocent explanation, and cannot write about a pattern nobody encoded. Those are
 * real limits and they are why the LLM seam exists.
 *
 * <h2>The recommendation rules</h2>
 *
 * <p>Deliberately conservative and stated in code rather than learned. The floor comes from the risk
 * level, because fraud-service already made that decision with more information than this service has,
 * and it is then raised — never lowered — by specific findings. A composer that could recommend less
 * caution than the verdict it is explaining would be arguing with the control that stopped the payment.
 */
public class LocalReportComposer implements ReportGenerator {

    /** Bumped when the wording or the recommendation rules change, so a shift is attributable. */
    private static final String VERSION = "1";

    @Override
    public String id() {
        return "local-composer/" + VERSION;
    }

    @Override
    public boolean isDeterministic() {
        return true;
    }

    @Override
    public GeneratedReport generate(EvidencePack evidence, List<RetrievedPolicy> policies) {
        List<RetrievedPolicy> retrieved = policies == null ? List.of() : policies;

        List<String> findings = buildFindings(evidence, retrieved);
        Recommendation recommendation = decide(evidence, retrieved);

        return new GeneratedReport(
                buildNarrative(evidence, retrieved, findings, recommendation),
                findings,
                recommendation,
                buildRationale(evidence, retrieved, recommendation),
                confidenceFor(evidence, retrieved),
                // Only documents actually retrieved. A composer that cited anything else would be
                // inventing, which is the failure mode it exists to be incapable of.
                retrieved.stream().map(RetrievedPolicy::documentId).distinct().toList(),
                id(),
                true);
    }

    // ---------------------------------------------------------------- findings

    /**
     * The evidence, restated as checkable statements.
     *
     * <p>Each line names its source — a rule id, a feature value, a policy document — so a reader can go
     * and verify it. A finding with no traceable source would be exactly the "fluency without grounding"
     * that the evidence standard forbids.
     */
    private static List<String> buildFindings(EvidencePack evidence, List<RetrievedPolicy> policies) {
        List<String> findings = new ArrayList<>();

        if (evidence.degradedVerdict()) {
            // First, because it changes how every other line should be read.
            findings.add("The fraud model was unreachable for this payment, so the verdict came from "
                    + "the deterministic rules alone. No model score exists; the stored probability of "
                    + "0.0 means unknown rather than low.");
        }

        for (String ruleId : evidence.ruleHits()) {
            String policyReference = policies.stream()
                    .filter(policy -> policy.isAuthoritative())
                    .filter(policy -> policy.content() != null)
                    .map(RetrievedPolicy::documentId)
                    .findFirst()
                    .map(documentId -> " See " + documentId + ".")
                    .orElse("");
            findings.add("Deterministic rule " + ruleId + " matched this payment." + policyReference);
        }

        if (!evidence.degradedVerdict() && !evidence.modelReasons().isEmpty()) {
            evidence.modelReasons().forEach(reason ->
                    findings.add("Model contribution: " + reason));
        }

        // Device and location, stated as what the data says rather than as an interpretation.
        if (!evidence.hasDeviceFingerprint()) {
            findings.add("No device fingerprint was supplied for this payment. This is normal for the "
                    + "API channel and must not be read as an unrecognised device.");
        }
        if (!evidence.hasGeoLocation()) {
            findings.add("No coordinates were available, so no distance-based finding is possible for "
                    + "this payment.");
        }
        if (evidence.countryCode() != null) {
            findings.add("The payment originated from country " + evidence.countryCode()
                    + ". Whether that is unusual is a question about this account's own history, "
                    + "recorded in the feature snapshot.");
        }

        if (!evidence.hasSubstantiveEvidence()) {
            findings.add("The evidence pack contains no rule hits, no model reasons and no feature "
                    + "values, so there is very little here to assess.");
        }
        if (policies.isEmpty()) {
            findings.add("No fraud policy matched this payment above the retrieval threshold, so "
                    + "nothing in this report is supported by written policy.");
        }
        return List.copyOf(findings);
    }

    // ------------------------------------------------------------- narrative

    private static String buildNarrative(EvidencePack evidence, List<RetrievedPolicy> policies,
                                         List<String> findings, Recommendation recommendation) {
        StringBuilder narrative = new StringBuilder();

        narrative.append("## Summary\n\n");
        narrative.append("Payment ").append(evidence.paymentId())
                .append(" for ").append(evidence.amount())
                .append(" was referred for investigation. ")
                .append(evidence.verdictSummary())
                .append("\n\n");

        narrative.append("This report was assembled by the offline deterministic composer, not by a "
                + "language model. It restates the available evidence and the fraud policy retrieved "
                + "for it, and does not interpret beyond them.\n\n");

        narrative.append("## What the evidence shows\n\n");
        if (findings.isEmpty()) {
            narrative.append("Nothing in the evidence pack is actionable.\n\n");
        } else {
            findings.forEach(finding -> narrative.append("- ").append(finding).append('\n'));
            narrative.append('\n');
        }

        narrative.append("## Applicable policy\n\n");
        if (policies.isEmpty()) {
            narrative.append("No fraud policy was retrieved for this payment above the similarity "
                    + "threshold. Nothing below is supported by written policy, and this report should "
                    + "be read as a restatement of the raw evidence only.\n\n");
        } else {
            for (RetrievedPolicy policy : policies) {
                narrative.append("**").append(policy.citationLabel()).append(" — ")
                        .append(policy.documentTitle()).append("**");
                narrative.append(policy.isAuthoritative()
                        ? " (declared as explaining a rule that fired)"
                        : " (retrieved by similarity, %.2f)".formatted(policy.similarity()));
                narrative.append("\n\n> ")
                        .append(policy.content().replace("\n", "\n> "))
                        .append("\n\n");
            }
        }

        narrative.append("## Feature values scored\n\n");
        List<String> featureLines = evidence.featureLines();
        if (featureLines.isEmpty()) {
            narrative.append("No feature snapshot was supplied with this payment.\n\n");
        } else {
            featureLines.forEach(line -> narrative.append("- ").append(line).append('\n'));
            narrative.append("\nA value of \"unknown\" means no data was available, which is not the "
                    + "same as a value of zero. Window aggregates cover the period before this payment "
                    + "and exclude it.\n\n");
        }

        appendUntrustedSection(narrative, evidence);

        narrative.append("## Recommendation\n\n");
        narrative.append("**").append(recommendation.name()).append("**. ")
                .append("This is advice to an analyst. Nothing in this platform acts on it: releasing "
                        + "or refusing this payment requires a decision recorded against a person.\n");

        return narrative.toString();
    }

    /**
     * Renders customer-controlled text, labelled as such.
     *
     * <p>Included because it is genuinely useful evidence — a payment description often explains the
     * whole case — and fenced because it is the one part of the report a customer chose the wording of.
     * An analyst reading text here should treat it as a claim, not a fact, in exactly the way the prompt
     * builder makes a language model treat it.
     */
    private static void appendUntrustedSection(StringBuilder narrative, EvidencePack evidence) {
        EvidencePack.Untrusted untrusted = evidence.untrusted();
        if (untrusted.isEmpty()) {
            return;
        }
        narrative.append("## Customer-supplied text (untrusted)\n\n");
        narrative.append("The following was provided by whoever initiated the payment. It is quoted "
                + "verbatim and is not evidence of anything except what they chose to type.\n\n");

        if (untrusted.paymentDescription() != null) {
            narrative.append("- Description: `").append(untrusted.paymentDescription()).append("`\n");
        }
        if (untrusted.merchantCategory() != null) {
            narrative.append("- Merchant category: `").append(untrusted.merchantCategory()).append("`\n");
        }
        if (untrusted.userAgent() != null) {
            narrative.append("- User agent: `").append(untrusted.userAgent()).append("`\n");
        }
        narrative.append('\n');
    }

    // ----------------------------------------------------------- the decision

    /**
     * Chooses a recommendation from stated rules.
     *
     * <p>Starts at the floor the risk level implies and escalates only. fraud-service reached that
     * verdict with the full feature vector and the rule engine; a composer recommending less caution
     * than the verdict it is explaining would be arguing with the control that stopped the payment, on
     * strictly less information.
     */
    private static Recommendation decide(EvidencePack evidence, List<RetrievedPolicy> policies) {
        Recommendation recommendation = floorFor(evidence.riskLevel());

        // Almost nothing to go on. Reported as such rather than dressed up: an investigation whose
        // honest answer is "there is not enough here" is more useful than one that manufactures a
        // concern, and INSUFFICIENT_EVIDENCE is a real answer.
        if (!evidence.hasSubstantiveEvidence()) {
            recommendation = recommendation.orMoreCautious(Recommendation.INSUFFICIENT_EVIDENCE);
        }

        // A verdict reached without the model is a verdict with a missing half. Neither "the model
        // found nothing" nor "the model found something" is available, so verification is the
        // proportionate advice regardless of how the rules landed.
        if (evidence.degradedVerdict()) {
            recommendation = recommendation.orMoreCautious(
                    Recommendation.REQUEST_CUSTOMER_VERIFICATION);
        }

        // Confirmed prior fraud is the one finding that reaches the most severe recommendation, and it
        // does so because a human already confirmed it. No probability gets here.
        if (evidence.ruleHits().contains("RULE_CONFIRMED_FRAUD_HISTORY")) {
            recommendation = recommendation.orMoreCautious(
                    Recommendation.RECOMMEND_REJECT_AND_REVIEW_ACCOUNT);
        }

        // Several independent rules firing together is a different picture from one, and it is the case
        // where weighing the findings against each other matters most — which is precisely what this
        // composer cannot do. Escalating is the honest response to its own limitation.
        long escalatingRules = evidence.ruleHits().size();
        if (escalatingRules >= 3) {
            recommendation = recommendation.orMoreCautious(Recommendation.ESCALATE_TO_SENIOR_ANALYST);
        }

        // No written policy behind the report. The recommendation is not softened for this — the rules
        // still fired — but a reader is told, and the confidence is reduced.
        if (policies.isEmpty() && recommendation.severity()
                < Recommendation.REQUEST_CUSTOMER_VERIFICATION.severity()) {
            recommendation = recommendation.orMoreCautious(Recommendation.MONITOR_ACCOUNT);
        }
        return recommendation;
    }

    /**
     * The least cautious recommendation consistent with a risk level.
     *
     * <p>{@code CRITICAL} floors at escalation rather than at a recommended refusal. A CRITICAL verdict
     * is reachable only from the confirmed-fraud rule, which is handled explicitly above; anything else
     * arriving here as CRITICAL is a state this composer does not understand, and the right response to
     * not understanding something is a senior analyst rather than the most severe advice available.
     */
    private static Recommendation floorFor(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> Recommendation.NO_ACTION_REQUIRED;
            case MEDIUM -> Recommendation.MONITOR_ACCOUNT;
            case HIGH -> Recommendation.HOLD_AND_REQUEST_VERIFICATION;
            case CRITICAL -> Recommendation.ESCALATE_TO_SENIOR_ANALYST;
        };
    }

    private static String buildRationale(EvidencePack evidence, List<RetrievedPolicy> policies,
                                         Recommendation recommendation) {
        StringBuilder rationale = new StringBuilder();
        rationale.append(recommendation.name()).append(" follows from ");

        List<String> because = new ArrayList<>();
        because.add("a %s risk verdict".formatted(evidence.riskLevel()));

        if (!evidence.ruleHits().isEmpty()) {
            because.add("%d deterministic rule(s) matching (%s)".formatted(
                    evidence.ruleHits().size(), String.join(", ", evidence.ruleHits())));
        }
        if (evidence.degradedVerdict()) {
            because.add("the absence of a model score, which leaves the assessment incomplete");
        }
        if (!evidence.hasSubstantiveEvidence()) {
            because.add("an evidence pack with nothing substantive in it");
        }
        if (policies.isEmpty()) {
            because.add("no written policy matching above the retrieval threshold");
        } else {
            because.add("policy %s".formatted(policies.stream()
                    .map(RetrievedPolicy::documentId).distinct().toList()));
        }

        rationale.append(String.join("; ", because)).append(". ");
        rationale.append("The recommendation is never lower than the risk verdict implies, because "
                + "fraud-service reached that verdict with the full feature vector and the rule "
                + "engine. This composer only raises it.");

        return rationale.toString();
    }

    /**
     * Confidence in the report, not in the payment being fraudulent.
     *
     * <p>An honest self-assessment of how much this composer had to work with. It rises with grounded
     * policy and concrete rule hits, and falls hard when the model score is missing or nothing was
     * retrieved. Deliberately never reaches 1.0: a composer that cannot weigh evidence should not claim
     * certainty about its own conclusions.
     */
    private static double confidenceFor(EvidencePack evidence, List<RetrievedPolicy> policies) {
        double confidence = 0.35;

        if (!evidence.ruleHits().isEmpty()) {
            // Deterministic findings are facts about the payment, so they are the strongest thing
            // available to a composer that cannot interpret.
            confidence += Math.min(0.25, 0.10 * evidence.ruleHits().size());
        }
        if (!policies.isEmpty()) {
            confidence += 0.15;
            if (policies.stream().anyMatch(RetrievedPolicy::isAuthoritative)) {
                // A declared rule-to-policy mapping is an exact match rather than a resemblance.
                confidence += 0.10;
            }
        }
        if (!evidence.featureSnapshot().isEmpty()) {
            confidence += 0.05;
        }
        if (evidence.degradedVerdict()) {
            confidence -= 0.20;
        }
        if (!evidence.hasSubstantiveEvidence()) {
            confidence -= 0.20;
        }
        return Math.clamp(confidence, 0.05, 0.85);
    }
}
