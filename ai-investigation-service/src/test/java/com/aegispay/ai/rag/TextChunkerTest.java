package com.aegispay.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for splitting a policy into retrieval passages.
 *
 * <p>A chunk has to be three things at once: a good vector, a readable citation, and a cut the author
 * intended. The tests below are mostly about the third, because it is the one a naive implementation gets
 * wrong — and a chunk cut mid-sentence is both a worse vector and useless as a citation, since an analyst
 * opens it and reads a fragment.
 */
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(180, 300, 30);

    @Nested
    @DisplayName("splitting")
    class Splitting {

        @Test
        @DisplayName("returns nothing for empty input")
        void emptyInput() {
            assertThat(chunker.chunk(null)).isEmpty();
            assertThat(chunker.chunk("")).isEmpty();
            assertThat(chunker.chunk("   \n\n  ")).isEmpty();
        }

        @Test
        @DisplayName("keeps a short document as one chunk")
        void shortDocumentStaysWhole() {
            List<TextChunker.Chunk> chunks = chunker.chunk(
                    "A dormant account waking up with a large payment is worth a review.");

            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().ordinal()).isZero();
            assertThat(chunks.getFirst().approximateTokens()).isPositive();
        }

        /**
         * Blank lines are the author's own topic boundary, so splitting there produces chunks that each cover
         * one idea. Fixed-size splitting would cut mid-paragraph and average two topics into one vector.
         */
        @Test
        @DisplayName("splits on paragraph boundaries once the target is exceeded")
        void splitsOnParagraphs() {
            String body = paragraph("alpha", 120) + "\n\n" + paragraph("beta", 120);

            List<TextChunker.Chunk> chunks = chunker.chunk(body);

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks.getFirst().content()).contains("alpha");
            assertThat(chunks.getLast().content()).contains("beta");
        }

        @Test
        @DisplayName("numbers chunks consecutively from zero")
        void ordinalsAreConsecutive() {
            List<TextChunker.Chunk> chunks = chunker.chunk(
                    paragraph("one", 120) + "\n\n" + paragraph("two", 120) + "\n\n"
                            + paragraph("three", 120));

            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).ordinal())
                        .as("a gap would make a citation to POLICY-X#2 ambiguous")
                        .isEqualTo(i);
            }
        }

        /**
         * A heading alone embeds to almost nothing useful — four words with no content — and as a citation it
         * tells an analyst the name of a section they cannot read.
         */
        @Test
        @DisplayName("attaches a heading to the paragraph it introduces")
        void headingStaysWithItsContent() {
            List<TextChunker.Chunk> chunks = chunker.chunk("""
                    # Bust-out pattern

                    An established account sends its largest ever payment to a new counterparty.
                    """);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.getFirst().content())
                    .contains("Bust-out pattern")
                    .contains("largest ever payment");
        }

        @Test
        @DisplayName("keeps a trailing heading rather than dropping authored text")
        void trailingHeadingSurvives() {
            List<TextChunker.Chunk> chunks = chunker.chunk("""
                    Some content that stands alone as a paragraph.

                    ## A heading with nothing under it
                    """);

            assertThat(String.join(" ", chunks.stream().map(TextChunker.Chunk::content).toList()))
                    .as("silently discarding authored text is worse than a poor chunk")
                    .contains("A heading with nothing under it");
        }

        /**
         * A chunk beginning mid-sentence is unusable as a citation even when its vector happens to be a good
         * match, so an over-long paragraph is cut at sentence boundaries instead.
         */
        @Test
        @DisplayName("splits an over-long paragraph at sentence boundaries")
        void splitsLongParagraphAtSentences() {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 40; i++) {
                body.append("Sentence number ").append(i)
                        .append(" describes a distinct aspect of the fraud policy in question. ");
            }

            List<TextChunker.Chunk> chunks = chunker.chunk(body.toString());

            assertThat(chunks).hasSizeGreaterThan(1);
            for (TextChunker.Chunk chunk : chunks) {
                assertThat(chunk.content().trim())
                        .as("a chunk starting mid-sentence cannot be read as a citation")
                        .matches("(?s)^[A-Z\"'(].*");
            }
        }
    }

    @Nested
    @DisplayName("overlap")
    class Overlap {

        /**
         * A claim spanning a paragraph break belongs to both paragraphs. Without overlap, whichever one it is
         * assigned to, the other loses it — and a policy sentence becomes unreachable by the query that
         * should have found it.
         */
        @Test
        @DisplayName("repeats the tail of the previous chunk")
        void repeatsTail() {
            String distinctive = "The conjunction is what makes the rule usable.";
            String body = paragraph("alpha", 110) + " " + distinctive + "\n\n"
                    + paragraph("beta", 130);

            List<TextChunker.Chunk> chunks = chunker.chunk(body);

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks.get(1).content())
                    .as("a statement near a boundary must be retrievable from either side")
                    .contains(distinctive);
        }

        @Test
        @DisplayName("produces no overlap when configured to zero")
        void zeroOverlap() {
            TextChunker noOverlap = new TextChunker(180, 300, 0);
            String distinctive = "This sentence marks the end of the first passage.";

            List<TextChunker.Chunk> chunks = noOverlap.chunk(
                    paragraph("alpha", 110) + " " + distinctive + "\n\n" + paragraph("beta", 130));

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks.get(1).content()).doesNotContain(distinctive);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("refuses a target too small to be a meaningful passage")
        void refusesTinyTarget() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TextChunker(5, 300, 0));
        }

        @Test
        @DisplayName("refuses a ceiling below the target")
        void refusesInvertedCeiling() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TextChunker(200, 100, 0));
        }

        /**
         * Overlap at or above the target would make each chunk mostly a copy of the previous one, so retrieval
         * would return the same text several times and the prompt budget would be spent on repetition.
         */
        @Test
        @DisplayName("refuses overlap at or above the target")
        void refusesExcessiveOverlap() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TextChunker(180, 300, 180));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TextChunker(180, 300, 400));
        }
    }

    @Nested
    @DisplayName("token counting")
    class Tokens {

        @Test
        @DisplayName("counts nothing for blank text")
        void blankIsZero() {
            assertThat(TextChunker.approximateTokens(null)).isZero();
            assertThat(TextChunker.approximateTokens("  ")).isZero();
        }

        /**
         * The schema requires a positive token count, and a one-word chunk is still a chunk. A count of zero
         * would fail the CHECK constraint at flush time with a far less useful message.
         */
        @Test
        @DisplayName("counts at least one token for any real text")
        void neverZeroForRealText() {
            assertThat(TextChunker.approximateTokens("a")).isPositive();
        }

        /**
         * Scaled by 1.3, roughly the word-to-subword ratio for English prose. Deliberately approximate: the
         * exact count depends on the tokeniser of whichever model is generating, and being 20% out costs
         * unused headroom rather than a truncated prompt.
         */
        @Test
        @DisplayName("scales above the raw word count")
        void scalesAboveWordCount() {
            assertThat(TextChunker.approximateTokens("one two three four five"))
                    .isGreaterThanOrEqualTo(5)
                    .isLessThanOrEqualTo(9);
        }
    }

    /** Builds a paragraph of roughly the requested word count, with a distinctive marker word. */
    private static String paragraph(String marker, int words) {
        StringBuilder builder = new StringBuilder(marker).append(' ');
        for (int i = 1; i < words; i++) {
            builder.append("word").append(i).append(' ');
        }
        return builder.toString().trim() + ".";
    }
}
