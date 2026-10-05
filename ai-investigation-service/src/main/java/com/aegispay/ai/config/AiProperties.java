package com.aegispay.ai.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Retrieval and generation settings.
 *
 * <p>The one thing deliberately <em>not</em> configurable is the recommendation vocabulary. It is a
 * compiled enum plus a database CHECK, because it is the field a prompt injection would target and a
 * list that could be extended by an environment variable would be an actionable value away from a
 * config change nobody reviewed.
 */
@Validated
@ConfigurationProperties(prefix = "aegis.ai")
public class AiProperties {

    private final Retrieval retrieval = new Retrieval();
    private final Generation generation = new Generation();
    private final KnowledgeBase knowledgeBase = new KnowledgeBase();

    /**
     * How policy is retrieved for an investigation.
     */
    public static class Retrieval {

        /**
         * How many chunks to consider from vector search.
         *
         * <p>Deliberately small. Every retrieved chunk consumes prompt budget, and a long tail of weak
         * matches does more harm than good: an LLM given eight loosely-relevant policies will find a
         * way to cite all eight, and a narrative that cites everything has cited nothing.
         */
        @Min(1)
        @Max(20)
        private int topK = 5;

        /**
         * Minimum cosine similarity for a chunk to count as evidence.
         *
         * <p><strong>The most consequential number in this service.</strong> An approximate
         * nearest-neighbour search always returns its k nearest neighbours however far away they are, so
         * without a floor a payment with no relevant policy retrieves the five least-irrelevant
         * documents in the corpus and the narrative cites them as though they mattered. The floor is
         * what makes "no policy matched" an expressible outcome.
         *
         * <p>0.28 is tuned for the default lexical embedder, where a genuinely on-topic passage scores
         * roughly 0.35-0.7 and an unrelated one below 0.15. It would need retuning for a semantic
         * embedder, whose similarity distribution is much narrower and higher — which is another reason
         * the embedder id is recorded on every investigation.
         *
         * <p>Raising it makes reports honestly say they found nothing more often. Lowering it produces
         * confident citations of irrelevant policy, which is strictly worse than a report that admits
         * it has no grounding.
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double minimumSimilarity = 0.28;

        /**
         * Whether policies declared against a fired rule are always included.
         *
         * <p>True by default, and it is what makes retrieval reliable rather than merely plausible.
         * Vector search finds policy that <em>resembles</em> the payment; the author-declared
         * {@code related_rule_ids} mapping finds the policy that <em>defines</em> the rule which
         * actually fired. Depending on similarity alone to surface that would be trusting an embedder
         * to have learned that {@code RULE_BUST_OUT_PATTERN} means the same thing as a paragraph about
         * accounts emptying themselves.
         */
        private boolean alwaysIncludeRulePolicies = true;

        /**
         * Prompt budget for retrieved policy, in approximate tokens.
         *
         * <p>Separate from the model's context limit on purpose: the evidence pack, the instructions and
         * the expected output all need room too, and a retrieval set that filled the window would push
         * the instructions out — which is how a model starts ignoring the output format it was told to
         * use.
         */
        @Min(200)
        @Max(20_000)
        private int contextTokenBudget = 2_000;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getMinimumSimilarity() {
            return minimumSimilarity;
        }

        public void setMinimumSimilarity(double minimumSimilarity) {
            this.minimumSimilarity = minimumSimilarity;
        }

        public boolean isAlwaysIncludeRulePolicies() {
            return alwaysIncludeRulePolicies;
        }

        public void setAlwaysIncludeRulePolicies(boolean alwaysIncludeRulePolicies) {
            this.alwaysIncludeRulePolicies = alwaysIncludeRulePolicies;
        }

        public int getContextTokenBudget() {
            return contextTokenBudget;
        }

        public void setContextTokenBudget(int contextTokenBudget) {
            this.contextTokenBudget = contextTokenBudget;
        }
    }

    /**
     * How the narrative is produced.
     */
    public static class Generation {

        /**
         * Which generator to use: {@code local}, {@code openai} or {@code anthropic}.
         *
         * <p>{@code local} by default, so the platform runs end to end with no API key and no outbound
         * network access. That is not a placeholder — the offline composer writes a real report from the
         * evidence and the retrieved policy. What it does not do is interpret beyond them, and every
         * report it writes is flagged {@code deterministicFallback} so an analyst knows which they are
         * reading.
         */
        @NotNull
        private String provider = "local";

        /** Model name for a hosted provider. Ignored by the local composer. */
        private String model = "gpt-4o-mini";

        private String apiKey = "";

        /** Overridable so an OpenAI-compatible gateway or a local server can be used instead. */
        private String apiBaseUrl = "";

        /**
         * Hard ceiling on one generation call.
         *
         * <p>Generous compared to the rest of the platform, because this is <em>not</em> on the payment's
         * critical path: an investigation happens after fraud-service has already decided, so a slow
         * model delays an analyst's reading rather than a customer's payment. Still bounded, because a
         * hung call would otherwise hold a consumer thread until the socket gave up.
         */
        @NotNull
        private Duration timeout = Duration.ofSeconds(20);

        /**
         * Attempts, including the first.
         *
         * <p>Safe to retry: generation has no side effect at the provider beyond cost, and the
         * investigation is only persisted once. Kept low because a retry doubles the bill and the
         * fallback composer is a perfectly acceptable answer.
         */
        @Min(1)
        @Max(3)
        private int maxAttempts = 2;

        /**
         * Cap on generated length.
         *
         * <p>Also a cost control, but mainly a quality one: a model given unlimited room to write about a
         * thin evidence pack fills the space, and the padding reads exactly like the grounded part.
         */
        @Min(128)
        @Max(4_000)
        private int maxOutputTokens = 800;

        /**
         * Sampling temperature.
         *
         * <p>Near zero on purpose. This is an evidence-summarisation task, not a creative one, and two
         * analysts reading the same investigation should see the same words. It cannot be made fully
         * reproducible — hosted models are not deterministic even at temperature 0 — which is one more
         * reason the offline composer exists.
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double temperature = 0.1;

        /**
         * Whether a failed hosted generation falls back to the offline composer.
         *
         * <p>True by default. An investigation is advisory, so a degraded report an analyst can still
         * read beats no report at all — and the alternative, dead-lettering the request, means the
         * payment quietly never gets investigated. The fallback is always labelled, so nobody mistakes
         * the composer's restrained prose for a model's analysis.
         */
        private boolean fallbackToLocal = true;

        /**
         * Maximum length of any single customer-controlled field placed in a prompt.
         *
         * <p>An injection defence, and a blunt one on purpose. A payment description is the obvious
         * vector for {@code "ignore previous instructions"}, and while the closed recommendation
         * vocabulary means a successful injection cannot produce an action, it can still waste the
         * context window and derail the narrative. Truncating hard costs a little fidelity on genuinely
         * long descriptions and removes the room an instruction needs.
         */
        @Min(32)
        @Max(2_000)
        private int maxUntrustedFieldLength = 240;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public boolean isFallbackToLocal() {
            return fallbackToLocal;
        }

        public void setFallbackToLocal(boolean fallbackToLocal) {
            this.fallbackToLocal = fallbackToLocal;
        }

        public int getMaxUntrustedFieldLength() {
            return maxUntrustedFieldLength;
        }

        public void setMaxUntrustedFieldLength(int maxUntrustedFieldLength) {
            this.maxUntrustedFieldLength = maxUntrustedFieldLength;
        }
    }

    /**
     * How the policy corpus is loaded and chunked.
     */
    public static class KnowledgeBase {

        /**
         * Whether the bundled policies are seeded at startup.
         *
         * <p>True by default: a service that came up with an empty corpus would answer every
         * investigation ungrounded and report no error, which is the worst kind of broken. Turned off
         * only where the corpus is managed externally.
         */
        private boolean seedOnStartup = true;

        /**
         * Whether an existing document is overwritten when the bundled version is newer.
         *
         * <p>True, so editing a bundled policy and redeploying actually takes effect. Compares the
         * version number rather than the text: a body-based comparison would re-chunk and re-embed the
         * whole corpus on every whitespace change, and the version is what a citation records anyway.
         */
        private boolean upgradeExistingDocuments = true;

        /** Target chunk size, in approximate tokens. */
        @Min(40)
        @Max(1_000)
        private int chunkTargetTokens = 180;

        /** Hard ceiling before a paragraph is split at sentence boundaries. */
        @Min(60)
        @Max(2_000)
        private int chunkMaxTokens = 300;

        /**
         * Overlap between consecutive chunks, in approximate tokens.
         *
         * <p>Non-zero so a claim spanning a paragraph break is retrievable from either side. Zero
         * overlap means every boundary is a place where a policy sentence becomes unreachable by the
         * query that should have found it.
         */
        @Min(0)
        @Max(200)
        private int chunkOverlapTokens = 30;

        public boolean isSeedOnStartup() {
            return seedOnStartup;
        }

        public void setSeedOnStartup(boolean seedOnStartup) {
            this.seedOnStartup = seedOnStartup;
        }

        public boolean isUpgradeExistingDocuments() {
            return upgradeExistingDocuments;
        }

        public void setUpgradeExistingDocuments(boolean upgradeExistingDocuments) {
            this.upgradeExistingDocuments = upgradeExistingDocuments;
        }

        public int getChunkTargetTokens() {
            return chunkTargetTokens;
        }

        public void setChunkTargetTokens(int chunkTargetTokens) {
            this.chunkTargetTokens = chunkTargetTokens;
        }

        public int getChunkMaxTokens() {
            return chunkMaxTokens;
        }

        public void setChunkMaxTokens(int chunkMaxTokens) {
            this.chunkMaxTokens = chunkMaxTokens;
        }

        public int getChunkOverlapTokens() {
            return chunkOverlapTokens;
        }

        public void setChunkOverlapTokens(int chunkOverlapTokens) {
            this.chunkOverlapTokens = chunkOverlapTokens;
        }
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Generation getGeneration() {
        return generation;
    }

    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }
}
