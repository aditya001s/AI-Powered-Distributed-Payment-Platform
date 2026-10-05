package com.aegispay.ai.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The vector text encoding, and the two guards around it.
 *
 * <p><strong>Why a whole test class for string formatting.</strong> Because this is the seam where a
 * float array becomes something Postgres parses, and every way it can go wrong is silent. pgvector
 * accepts {@code [1.0,2.0,3.0]}; it does not report "your locale used a comma as a decimal separator",
 * it reports a dimension mismatch, or worse, parses a different vector than the one that was intended.
 * The SQL around it cannot be tested without Docker, so this encoding is the part that can be pinned
 * down here — and it is the part that would break on a machine configured differently from the one
 * that wrote it.
 */
class VectorStoreTest {

    // ------------------------------------------------------------------------
    // encoding
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("vector literal")
    class Literal {

        @Test
        @DisplayName("writes the bracketed, comma-separated form pgvector parses")
        void writesPgvectorForm() {
            assertThat(VectorStore.toVectorLiteral(new float[] {1f, 2f, 3f}))
                    .isEqualTo("[1.0,2.0,3.0]");
        }

        @Test
        @DisplayName("writes a single-element vector without a separator")
        void writesSingleElement() {
            assertThat(VectorStore.toVectorLiteral(new float[] {0.5f})).isEqualTo("[0.5]");
        }

        /**
         * Embeddings are L2-normalised and signed, so roughly half of every vector is negative. A
         * formatter that dropped or mangled the sign would turn a passage into its own opposite and
         * still produce a parseable literal.
         */
        @Test
        @DisplayName("keeps negative components, which are half of every normalised vector")
        void keepsNegatives() {
            assertThat(VectorStore.toVectorLiteral(new float[] {-0.25f, 0.25f}))
                    .isEqualTo("[-0.25,0.25]");
        }

        /**
         * <strong>The reason this is not {@code String.format}.</strong> In a comma-decimal locale
         * {@code %f} renders 1.0 as {@code 1,0}, so a two-element vector becomes the four-element
         * literal {@code [1,0,2,0]} — and pgvector would either reject it as a dimension mismatch or,
         * for an unlucky length, silently store a completely different vector. The bug would appear only
         * on machines whose default locale differs from the developer's, which is the worst possible
         * distribution for it.
         */
        @Test
        @DisplayName("encodes identically under a comma-decimal locale")
        void isLocaleIndependent() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                assertThat(VectorStore.toVectorLiteral(new float[] {1.5f, -2.25f}))
                        .as("""
                                a comma decimal separator turns one component into two and changes \
                                the vector's dimension""")
                        .isEqualTo("[1.5,-2.25]")
                        .doesNotContain("1,5");
            } finally {
                Locale.setDefault(original);
            }
        }

        /**
         * A fixed number of decimal places would quantise the vector, and two chunks whose embeddings
         * differ only in the discarded digits would collapse into one retrieval result — distinct
         * passages becoming indistinguishable at exactly the point where the search has to tell them
         * apart.
         */
        @Test
        @DisplayName("preserves full float precision, so near-identical chunks stay distinct")
        void preservesPrecision() {
            String first = VectorStore.toVectorLiteral(new float[] {0.12345678f});
            String second = VectorStore.toVectorLiteral(new float[] {0.12345679f});

            assertThat(first)
                    .as("rounding here silently merges passages the search has to distinguish")
                    .isNotEqualTo(second);
        }

        /** The literal has to survive the round trip, or the stored vector is not the computed one. */
        @Test
        @DisplayName("round-trips back to the same components")
        void roundTrips() {
            float[] vector = {0.1f, -0.9876543f, 0f, 1f, -1f, 3.4028235E38f, 1.4E-45f};

            String literal = VectorStore.toVectorLiteral(vector);
            String[] parts = literal.substring(1, literal.length() - 1).split(",");

            assertThat(parts).hasSameSizeAs(vector);
            for (int i = 0; i < vector.length; i++) {
                assertThat(Float.parseFloat(parts[i]))
                        .as("component " + i)
                        .isEqualTo(vector[i]);
            }
        }

        /**
         * A NaN or infinite component makes every distance involving the vector NaN, and a NaN distance
         * does not sort — the chunk appears at an arbitrary position in every result rather than being
         * excluded from any of them. Failing at the encoder is the last place this is still cheap to
         * detect.
         */
        @ParameterizedTest
        @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
        @DisplayName("refuses a non-finite component rather than poisoning every distance")
        void refusesNonFinite(float bad) {
            assertThatThrownBy(() -> VectorStore.toVectorLiteral(new float[] {0.1f, bad, 0.2f}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-finite")
                    .hasMessageContaining("index 1");
        }

        /**
         * Documents the boundary rather than endorsing it: {@code []} is not a valid pgvector value, so
         * an empty array must never reach here. {@link VectorStore#storeEmbedding} is what stops it, and
         * that is asserted separately below.
         */
        @Test
        @DisplayName("produces an empty literal for an empty array, which callers must prevent")
        void emptyArrayIsCallersProblem() {
            assertThat(VectorStore.toVectorLiteral(new float[0]))
                    .as("pgvector rejects []; storeEmbedding is the guard that keeps it from getting "
                            + "this far")
                    .isEqualTo("[]");
        }
    }

    // ------------------------------------------------------------------------
    // guards that do not need a database
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("guards")
    class Guards {

        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final VectorStore store = new VectorStore(jdbc);

        /**
         * A missing vector must fail at the write, not at the read. Storing nothing would leave a chunk
         * with metadata and a NULL embedding: invisible to retrieval, present in every count, and
         * silently shrinking the corpus. The seeder does repair these, but only because they are
         * reachable by a crash — never by a caller passing nothing.
         */
        @Test
        @DisplayName("refuses to store a null or empty vector")
        void refusesEmptyEmbedding() {
            assertThatThrownBy(() -> store.storeEmbedding("chunk-1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chunk-1");
            assertThatThrownBy(() -> store.storeEmbedding("chunk-1", new float[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no vector");

            verifyNoInteractions(jdbc);
        }

        /**
         * An absent query vector returns no matches rather than querying for one. It cannot be a
         * successful search of nothing — that would be indistinguishable from a corpus with no relevant
         * policy — and it must not become a SQL error either, because the caller's next step is to
         * compose an honestly ungrounded report.
         */
        @Test
        @DisplayName("returns no matches for an absent query vector, without touching the database")
        void emptyQueryReturnsNothing() {
            assertThat(store.search(null, "hashing-384/v1", 0.28, 5)).isEmpty();
            assertThat(store.search(new float[0], "hashing-384/v1", 0.28, 5)).isEmpty();

            verifyNoInteractions(jdbc);
        }

        /**
         * The match record carries the floor test rather than leaving each caller to write
         * {@code >=} themselves. Boundary included: a passage exactly at the floor is evidence, because
         * the floor is the definition of "enough" and excluding the boundary would make the configured
         * number mean something slightly different from what it says.
         */
        @Test
        @DisplayName("treats a match exactly at the floor as evidence")
        void floorIsInclusive() {
            VectorStore.Match atFloor = match(0.28);

            assertThat(atFloor.clears(0.28)).isTrue();
            assertThat(match(0.2799).clears(0.28)).isFalse();
            assertThat(match(0.9).clears(0.28)).isTrue();
        }

        private static VectorStore.Match match(double similarity) {
            return new VectorStore.Match("chunk-1", "POLICY-BUST-OUT", "Bust-out", 1,
                    "ATTACK_PATTERN", 0, "body", 40, similarity, 1);
        }
    }
}
