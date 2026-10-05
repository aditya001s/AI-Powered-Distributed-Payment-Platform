package com.aegispay.ai.rag;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The default embedder: signed feature hashing over words, word pairs and character n-grams.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p><strong>This is lexical retrieval expressed as vectors. It is not semantic.</strong> It will match
 * "dormant account reactivation" to a policy paragraph containing those words, and it will
 * <em>not</em> match "an account that has not been used for months" to the same paragraph. There is no
 * trained model here and no notion of meaning — only overlap in surface form. Saying otherwise would be
 * the easiest lie in this codebase to tell and the hardest for a reader to catch, because the retrieval
 * results look identical either way until you test a paraphrase.
 *
 * <h2>Why lexical is nevertheless the right default here</h2>
 *
 * <p>Not a compromise forced by the absence of a model, though that is also true. Three reasons:
 *
 * <ol>
 *   <li><strong>Both sides share a controlled vocabulary.</strong> A retrieval query in this service is
 *       built from rule ids ({@code RULE_BUST_OUT_PATTERN}), feature names ({@code amountSum24hMinor})
 *       and risk-level terms, and the policy corpus is authored to explain exactly those tokens. The
 *       query and the documents are written in the same language on purpose, so vocabulary overlap is
 *       the signal rather than a proxy for it. A semantic model's main advantage — robustness to
 *       paraphrase — buys least precisely where the terms are fixed.</li>
 *   <li><strong>Determinism is worth a lot here.</strong> The same text produces the same vector on
 *       every machine, forever, with no model file to pin and no endpoint to be deprecated. A citation
 *       written today is reproducible in five years, which is the property an audit of an automated
 *       decision actually asks for.</li>
 *   <li><strong>The strongest signal does not come from the vectors at all.</strong>
 *       {@code PolicyRetriever} also looks policies up by {@code related_rule_ids}, an explicit
 *       author-declared mapping from rule to policy. Vector search widens the net; the declared mapping
 *       is what guarantees the policy defining the rule that fired is present.</li>
 * </ol>
 *
 * <p>Where this genuinely falls short — an analyst asking a free-text question in their own words —
 * {@link Embedder#isSemantic()} returns false and that reaches the API, so nothing claims to have
 * understood a question it merely pattern-matched.
 *
 * <h2>The three feature families, and why all three</h2>
 *
 * <ul>
 *   <li><strong>Word unigrams</strong> carry the topic.</li>
 *   <li><strong>Word bigrams</strong> carry the little of word order that matters: "new device" and
 *       "device fingerprint" are different ideas that share a word, and unigrams alone cannot tell
 *       them apart.</li>
 *   <li><strong>Character 4-grams</strong> handle morphology and typos without a stemmer.
 *       "chargeback" and "chargebacks" share every 4-gram but one, so they match strongly; a stemmer
 *       would do this better for English and worse for identifiers like {@code amountSum24hMinor},
 *       where the substring overlap is exactly what should match.</li>
 * </ul>
 *
 * <h2>Why the hashing is signed</h2>
 *
 * <p>Feature hashing collides: 384 buckets cannot hold a vocabulary of thousands without two features
 * landing in the same slot. With unsigned hashing every collision <em>adds</em>, so errors accumulate in
 * one direction and unrelated documents drift towards each other. Taking one bit of the hash as a sign
 * makes collisions cancel in expectation, which is the standard result and the reason this technique is
 * usable at 384 dimensions at all.
 *
 * <h2>Why there is no IDF</h2>
 *
 * <p>Inverse document frequency would improve ranking — it is what stops "the" and "payment" dominating
 * every vector. It is left out because it makes the embedder <em>stateful</em>: the vector for a chunk
 * would depend on the rest of the corpus at the time it was embedded, so adding a policy would silently
 * change the meaning of every existing vector, and a re-seed would produce different numbers for
 * unchanged text.
 *
 * <p>That trade is taken deliberately. A stopword list plus sublinear term-frequency scaling recovers
 * most of what IDF was for, and statelessness buys the reproducibility above. If ranking quality ever
 * matters more than reproducibility, the honest fix is a real embedding model, not a stateful hash.
 */
public final class HashingEmbedder implements Embedder {

    /**
     * Matches the {@code vector(384)} column and the conventional sentence-embedding size, so a hosted
     * model can be dropped in without a schema migration.
     */
    public static final int DIMENSIONS = 384;

    /**
     * Bumped whenever anything changes the output.
     *
     * <p>The dimension, the tokenisation, the n-gram sizes, the weighting, the stopword list and the
     * hash seeds are all part of this. Changing any of them without bumping this would leave the corpus
     * holding vectors that are silently no longer comparable to new queries.
     */
    private static final String VERSION = "1";

    /** Separate seeds per feature family, so the same string in two roles does not collide with itself. */
    private static final int SEED_UNIGRAM = 0x9E3779B9;
    private static final int SEED_BIGRAM = 0x85EBCA6B;
    private static final int SEED_CHARGRAM = 0xC2B2AE35;

    private static final int CHAR_GRAM_SIZE = 4;

    /**
     * Weights per family.
     *
     * <p>Unigrams dominate because they carry the topic. Character n-grams are damped hard: a passage
     * generates far more of them than words, so at equal weight they would swamp the vector and two
     * documents would look similar for sharing common English letter sequences.
     */
    private static final float WEIGHT_UNIGRAM = 1.0f;
    private static final float WEIGHT_BIGRAM = 0.6f;
    private static final float WEIGHT_CHARGRAM = 0.25f;

    /**
     * Words too common to carry signal.
     *
     * <p>Small and English-only on purpose. An aggressive list would strip terms that are generic in
     * ordinary prose and meaningful here — "account", "payment", "risk" all appear in the stopword lists
     * shipped with search libraries, and removing them from a fraud-policy corpus would delete the
     * subject matter.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "for", "from",
            "had", "has", "have", "if", "in", "into", "is", "it", "its", "may", "must", "not", "of",
            "on", "or", "should", "than", "that", "the", "their", "then", "there", "these", "this",
            "to", "was", "were", "when", "which", "will", "with", "would");

    @Override
    public String id() {
        return "hashing-" + DIMENSIONS + "/v" + VERSION;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /**
     * Lexical, by construction. Reported honestly so callers can describe their own confidence
     * accurately rather than implying a semantic search happened.
     */
    @Override
    public boolean isSemantic() {
        return false;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            // A zero vector. Cosine distance against it is undefined, which pgvector reports as NaN, so
            // a blank query must never reach the store — the retriever checks for this rather than
            // letting a NaN distance sort a chunk to a random position in every result.
            return vector;
        }

        String normalised = normalise(text);
        String[] tokens = normalised.split("\\s+");

        // Counted first, then scaled sublinearly. A term appearing twenty times in a long policy is not
        // twenty times as indicative as one appearing once; raw counts would let a single repeated word
        // dominate the whole vector.
        Map<String, Integer> unigrams = new HashMap<>();
        Map<String, Integer> bigrams = new HashMap<>();

        String previous = null;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (!STOPWORDS.contains(token)) {
                unigrams.merge(token, 1, Integer::sum);
            }
            if (previous != null) {
                // Bigrams keep stopwords: "not present" and "present" mean opposite things, and
                // dropping the negation is exactly the mistake that makes a retrieval result cite a
                // policy which says the reverse of what the narrative claims.
                bigrams.merge(previous + "_" + token, 1, Integer::sum);
            }
            previous = token;
        }

        unigrams.forEach((term, count) ->
                add(vector, term, SEED_UNIGRAM, WEIGHT_UNIGRAM * sublinear(count)));
        bigrams.forEach((term, count) ->
                add(vector, term, SEED_BIGRAM, WEIGHT_BIGRAM * sublinear(count)));

        // Character n-grams are taken over distinct values rather than counted. They are dense enough
        // that frequency weighting on top of the family weight would let letter-sequence statistics,
        // rather than content, decide similarity.
        for (String gram : characterGrams(normalised)) {
            add(vector, gram, SEED_CHARGRAM, WEIGHT_CHARGRAM);
        }

        normalise(vector);
        return vector;
    }

    // --------------------------------------------------------------- internals

    /**
     * Lowercases and reduces to letters, digits and single spaces.
     *
     * <p>Underscores become spaces, which is what makes {@code RULE_BUST_OUT_PATTERN} match a policy
     * about "bust out" patterns: without it the whole identifier would be one opaque token matching
     * only an exact repetition of itself, and the rule-to-policy association would depend entirely on
     * the declared mapping.
     *
     * <p>Digits are kept. They are meaningful in this vocabulary — {@code paymentCount24h} and
     * {@code paymentCount1h} are different features, and stripping the digits would make them the same
     * token.
     */
    private static String normalise(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        boolean lastWasSpace = true;

        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
                lastWasSpace = false;
            } else if (!lastWasSpace) {
                builder.append(' ');
                lastWasSpace = true;
            }
        }
        return builder.toString().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Distinct character n-grams across the whole normalised text.
     *
     * <p>Taken across word boundaries deliberately: the space is part of the gram, so "new device"
     * contributes {@code "w de"}, which encodes a little adjacency information that word-level features
     * would need a bigram to express.
     */
    private static Set<String> characterGrams(String normalised) {
        Set<String> grams = new HashSet<>();
        if (normalised.length() < CHAR_GRAM_SIZE) {
            // Too short to produce any. Returned empty rather than padded: padding would make every
            // short string share a gram with every other, so all short queries would look alike.
            return grams;
        }
        for (int i = 0; i + CHAR_GRAM_SIZE <= normalised.length(); i++) {
            grams.add(normalised.substring(i, i + CHAR_GRAM_SIZE));
        }
        return grams;
    }

    /**
     * Sublinear term-frequency scaling: {@code 1 + ln(count)}.
     *
     * <p>The standard TF damping. A term appearing ten times is more indicative than one appearing once
     * but nowhere near ten times as much, and with raw counts a single repeated word in a long policy
     * would dominate its vector and pull it towards every query mentioning that word.
     */
    private static float sublinear(int count) {
        return (float) (1.0 + Math.log(count));
    }

    /**
     * Adds a weighted feature to its hashed bucket, with the sign taken from the hash.
     *
     * <p>The sign is what makes 384 buckets workable. Collisions are inevitable at this width, and with
     * unsigned hashing they all add — errors accumulate in one direction and unrelated documents drift
     * towards each other. Deriving the sign from an independent bit of the same hash makes colliding
     * contributions cancel in expectation, which is the result that makes feature hashing usable rather
     * than merely compact.
     */
    private static void add(float[] vector, String feature, int seed, float weight) {
        int hash = hash(feature, seed);
        int bucket = Math.floorMod(hash, DIMENSIONS);
        // The top bit, which is independent of the low bits that chose the bucket. Using the same bits
        // for both would correlate the sign with the bucket and defeat the cancellation.
        float sign = (hash >>> 31) == 0 ? 1.0f : -1.0f;
        vector[bucket] += sign * weight;
    }

    /**
     * FNV-1a, seeded.
     *
     * <p>Chosen over {@link String#hashCode()} because that has known clustering on short similar
     * strings — exactly what a character-n-gram feature set is made of — and clustered features would
     * collide far more than the uniform assumption behind signed hashing allows. Not a cryptographic
     * hash, and it does not need to be: nothing here depends on being hard to invert, only on spreading
     * evenly. Implemented inline rather than pulled from a library so the vector values are pinned by
     * this file and cannot change under a dependency upgrade, which would silently invalidate the
     * stored corpus.
     */
    private static int hash(String feature, int seed) {
        int hash = seed;
        for (byte b : feature.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        // A final avalanche step, so features differing in one trailing byte land far apart.
        hash ^= (hash >>> 16);
        hash *= 0x7FEB352D;
        hash ^= (hash >>> 15);
        return hash;
    }

    /**
     * Scales the vector to unit length, in place.
     *
     * <p>Required by the {@link Embedder} contract so cosine similarity is a dot product, and so a long
     * policy document does not out-score a short one purely by containing more of everything. A
     * zero-length vector is left alone rather than divided by zero.
     */
    private static void normalise(float[] vector) {
        double sumOfSquares = 0.0;
        for (float value : vector) {
            sumOfSquares += (double) value * value;
        }
        if (sumOfSquares <= 0.0) {
            return;
        }
        float inverse = (float) (1.0 / Math.sqrt(sumOfSquares));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inverse;
        }
    }
}
