package com.aegispay.ai.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a policy document into retrieval passages.
 *
 * <h2>Why chunking is not just splitting</h2>
 *
 * <p>A chunk is what gets embedded, retrieved and shown to an analyst as a citation, so it has to be
 * three things at once: small enough that its vector represents one idea rather than an average of
 * several, large enough to make sense when read alone, and cut at a boundary the author intended.
 *
 * <p>Fixed-size splitting fails the third. Cutting at character 500 lands mid-sentence, and the
 * resulting chunk is both a worse vector — half of it is about the next topic — and useless as a
 * citation, because an analyst opens it and reads a fragment. So this splits on <strong>paragraph
 * boundaries first</strong>, and only falls back to sentence boundaries when a single paragraph is too
 * large for the window.
 *
 * <h2>Why chunks overlap</h2>
 *
 * <p>A claim that spans a paragraph break belongs to both paragraphs, and whichever one it is assigned
 * to, the other loses it. Overlapping consecutive chunks by a sentence or two means a statement near a
 * boundary is retrievable from either side. The cost is a slightly larger corpus and the occasional
 * duplicated sentence in a prompt; the alternative is a policy sentence that no query can reach.
 *
 * <p>Deliberately deterministic and stateless: the same document always chunks identically, so a
 * citation to {@code POLICY-BUST-OUT#2} means the same passage on every machine and after every
 * restart. A chunker whose output depended on anything else would make old citations point at
 * different text.
 */
public final class TextChunker {

    /**
     * Target passage size, in approximate tokens.
     *
     * <p>Sized so several chunks fit in a prompt alongside the evidence pack. Too small and each vector
     * represents a fragment with no context; too large and one retrieved chunk crowds out the others,
     * so the narrative ends up grounded in one policy rather than the two or three that apply.
     */
    private final int targetTokens;

    /** Hard ceiling. A paragraph above this is split at sentence boundaries. */
    private final int maxTokens;

    /** How much of the previous chunk's tail to repeat, in approximate tokens. */
    private final int overlapTokens;

    public TextChunker(int targetTokens, int maxTokens, int overlapTokens) {
        if (targetTokens < 20) {
            throw new IllegalArgumentException(
                    "a target of " + targetTokens + " tokens is too small to be a meaningful passage");
        }
        if (maxTokens < targetTokens) {
            throw new IllegalArgumentException(
                    "the ceiling (" + maxTokens + ") must not be below the target (" + targetTokens + ")");
        }
        if (overlapTokens < 0 || overlapTokens >= targetTokens) {
            // Overlap at or above the target would make each chunk mostly a copy of the previous one,
            // so retrieval would return the same text several times and the prompt budget would be
            // spent on repetition.
            throw new IllegalArgumentException(
                    "overlap (" + overlapTokens + ") must be non-negative and below the target ("
                            + targetTokens + ")");
        }
        this.targetTokens = targetTokens;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    /** One passage, with the token count measured at split time so nothing has to re-measure it. */
    public record Chunk(int ordinal, String content, int approximateTokens) {
    }

    /**
     * Splits a document body into passages.
     *
     * @return at least one chunk for any non-blank body, in document order
     */
    public List<Chunk> chunk(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }

        List<String> units = paragraphs(body);
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        int ordinal = 0;
        String carriedOverlap = "";

        for (String unit : units) {
            int unitTokens = approximateTokens(unit);

            // A single paragraph over the ceiling cannot be emitted whole. Split it at sentence
            // boundaries rather than mid-word: a chunk that begins mid-sentence is unusable as a
            // citation even when its vector happens to be a good match.
            if (unitTokens > maxTokens) {
                if (currentTokens > 0) {
                    chunks.add(new Chunk(ordinal++, current.toString().trim(), currentTokens));
                    carriedOverlap = tailFor(current.toString());
                    current.setLength(0);
                    currentTokens = 0;
                }
                for (String sentenceGroup : splitLongParagraph(unit)) {
                    String withOverlap = carriedOverlap.isEmpty()
                            ? sentenceGroup
                            : carriedOverlap + " " + sentenceGroup;
                    chunks.add(new Chunk(ordinal++, withOverlap.trim(),
                            approximateTokens(withOverlap)));
                    carriedOverlap = tailFor(sentenceGroup);
                }
                continue;
            }

            // Adding this paragraph would overshoot the target: close the current chunk first.
            if (currentTokens > 0 && currentTokens + unitTokens > targetTokens) {
                chunks.add(new Chunk(ordinal++, current.toString().trim(), currentTokens));
                carriedOverlap = tailFor(current.toString());
                current.setLength(0);
                currentTokens = 0;
            }

            if (current.isEmpty() && !carriedOverlap.isEmpty()) {
                current.append(carriedOverlap).append("\n\n");
                currentTokens += approximateTokens(carriedOverlap);
                carriedOverlap = "";
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(unit);
            currentTokens += unitTokens;
        }

        if (currentTokens > 0) {
            chunks.add(new Chunk(ordinal, current.toString().trim(), currentTokens));
        }
        return List.copyOf(chunks);
    }

    /**
     * Splits on blank lines, the author's own topic boundary.
     *
     * <p>Markdown headings are kept attached to the paragraph that follows them rather than becoming
     * their own unit. A heading alone embeds to almost nothing useful — four words with no content — and
     * as a citation it tells an analyst the name of a section they cannot read.
     */
    private static List<String> paragraphs(String body) {
        String[] raw = body.trim().split("\\n\\s*\\n");
        List<String> units = new ArrayList<>();
        String pendingHeading = null;

        for (String block : raw) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isHeadingOnly(trimmed)) {
                pendingHeading = pendingHeading == null
                        ? trimmed
                        : pendingHeading + "\n" + trimmed;
                continue;
            }
            units.add(pendingHeading == null ? trimmed : pendingHeading + "\n" + trimmed);
            pendingHeading = null;
        }

        // A document ending in a heading with nothing under it: keep it rather than silently dropping
        // authored text, even though it is a poor chunk.
        if (pendingHeading != null) {
            units.add(pendingHeading);
        }
        return units;
    }

    private static boolean isHeadingOnly(String block) {
        if (block.contains("\n")) {
            return false;
        }
        String trimmed = block.trim();
        return trimmed.startsWith("#") || (trimmed.length() < 80 && !trimmed.endsWith("."));
    }

    /**
     * Splits an over-long paragraph into groups of whole sentences.
     *
     * <p>Sentence detection is deliberately simple — a full stop, question mark or exclamation followed
     * by whitespace and a capital. It mis-splits on "e.g." and on abbreviations, and that is an accepted
     * cost: the alternative is a sentence-boundary model, and the failure mode here is a slightly
     * awkward chunk boundary rather than a wrong answer. A policy corpus is authored text, so the case
     * is rare.
     */
    private List<String> splitLongParagraph(String paragraph) {
        String[] sentences = paragraph.split("(?<=[.!?])\\s+(?=[A-Z\"'(])");
        List<String> groups = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        int groupTokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = approximateTokens(sentence);

            if (groupTokens > 0 && groupTokens + sentenceTokens > targetTokens) {
                groups.add(group.toString().trim());
                group.setLength(0);
                groupTokens = 0;
            }
            if (!group.isEmpty()) {
                group.append(' ');
            }
            group.append(sentence.trim());
            groupTokens += sentenceTokens;

            // A single sentence longer than the ceiling. Emitted whole rather than cut: an unreadable
            // fragment is worse than an oversized chunk, and the prompt budget clips at the chunk
            // level anyway.
            if (groupTokens > maxTokens) {
                groups.add(group.toString().trim());
                group.setLength(0);
                groupTokens = 0;
            }
        }
        if (groupTokens > 0) {
            groups.add(group.toString().trim());
        }
        return groups;
    }

    /** The last sentences of a chunk, up to the overlap budget, for prepending to the next one. */
    private String tailFor(String chunk) {
        if (overlapTokens == 0) {
            return "";
        }
        String[] sentences = chunk.trim().split("(?<=[.!?])\\s+");
        StringBuilder tail = new StringBuilder();
        int tokens = 0;

        for (int i = sentences.length - 1; i >= 0; i--) {
            int sentenceTokens = approximateTokens(sentences[i]);
            if (tokens + sentenceTokens > overlapTokens && tokens > 0) {
                break;
            }
            tail.insert(0, sentences[i] + " ");
            tokens += sentenceTokens;
            if (tokens >= overlapTokens) {
                break;
            }
        }
        return tail.toString().trim();
    }

    /**
     * Approximate token count.
     *
     * <p>Whitespace-delimited words scaled by 1.3, which is roughly the word-to-subword-token ratio for
     * English prose in the common BPE vocabularies. Deliberately approximate: the exact count depends
     * on the tokeniser of whichever model is generating, this service supports several, and the number
     * is used only to keep a prompt inside a budget. Being 20% out costs some unused headroom; being
     * exact would mean bundling a tokeniser per provider.
     *
     * <p>Always at least 1 for non-blank text, because the schema requires a positive token count and a
     * one-word chunk is still a chunk.
     */
    public static int approximateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int words = text.trim().split("\\s+").length;
        return Math.max(1, (int) Math.ceil(words * 1.3));
    }
}
