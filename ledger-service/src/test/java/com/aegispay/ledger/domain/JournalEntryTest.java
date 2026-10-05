package com.aegispay.ledger.domain;

import com.aegispay.ledger.domain.JournalEntry.PostingType;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link JournalEntry}, including the structural guarantees that make it immutable.
 *
 * <p>The immutability assertions are unusual for a unit test but they are the cheapest place
 * to catch the regression that matters: someone adding a setter or a {@code @Version} column
 * to make "just one" field updatable. The database triggers would still refuse the UPDATE, so
 * the symptom would be a runtime failure in production rather than a compile error — which is
 * exactly the kind of thing worth pinning in a test that runs on every build.
 */
class JournalEntryTest {

    private static final String KEY = "LTX-PAY-PAY-0001";
    private static final String INR = "INR";

    private static JournalEntry entry(PostingType type, String reverses) {
        return JournalEntry.of(KEY, type, INR, 200_000L, "PAY-0001", "TXN0001",
                "narrative", reverses, "payment-service", "corr-1", Instant.now());
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("records both business time and system time")
        void recordsBothTimestamps() {
            Instant occurred = Instant.now().minusSeconds(600);
            JournalEntry created = JournalEntry.of(KEY, PostingType.TRANSFER, INR, 200_000L,
                    "PAY-0001", "TXN0001", "narrative", null, "payment-service", "corr-1",
                    occurred);

            assertThat(created.getOccurredAt()).isEqualTo(occurred);
            assertThat(created.getRecordedAt())
                    .as("system time must be when we recorded it, not when it happened: a "
                            + "redelivered event booked later still occurred when it occurred")
                    .isAfter(occurred);
        }

        @Test
        @DisplayName("defaults business time to now when it is not supplied")
        void defaultsOccurredAt() {
            JournalEntry created = JournalEntry.of(KEY, PostingType.TRANSFER, INR, 1L,
                    null, null, null, null, "test", null, null);

            assertThat(created.getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("normalises the currency to upper case")
        void normalisesCurrency() {
            JournalEntry created = JournalEntry.of(KEY, PostingType.TRANSFER, "inr", 1L,
                    null, null, null, null, "test", null, Instant.now());

            assertThat(created.getCurrency()).isEqualTo("INR");
        }

        @Test
        @DisplayName("requires an idempotency key")
        void requiresIdempotencyKey() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> JournalEntry.of(null, PostingType.TRANSFER, INR, 1L,
                            null, null, null, null, "test", null, Instant.now()))
                    .withMessageContaining("idempotency key");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> JournalEntry.of("  ", PostingType.TRANSFER, INR, 1L,
                            null, null, null, null, "test", null, Instant.now()));
        }

        @ParameterizedTest
        @DisplayName("refuses a non-positive total")
        @ValueSource(longs = {0L, -1L, -200_000L})
        void refusesNonPositiveTotal(long total) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> JournalEntry.of(KEY, PostingType.TRANSFER, INR, total,
                            null, null, null, null, "test", null, Instant.now()))
                    .withMessageContaining("positive amount");
        }
    }

    @Nested
    @DisplayName("reversal rules")
    class ReversalRules {

        @Test
        @DisplayName("a REVERSAL must name the posting it reverses")
        void reversalNeedsATarget() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> entry(PostingType.REVERSAL, null))
                    .withMessageContaining("must name the posting it reverses");
        }

        @Test
        @DisplayName("nothing may reverse itself")
        void nothingReversesItself() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> entry(PostingType.REVERSAL, KEY))
                    .withMessageContaining("cannot reverse itself");
        }

        /**
         * A refund points at the transfer it partially undoes without being a reversal. The
         * distinction is load-bearing: the schema permits only one REVERSAL per posting, so
         * typing a partial refund as one would wrongly block every subsequent refund.
         */
        @Test
        @DisplayName("a REFUND may reference a posting without being a reversal of it")
        void refundReferencesWithoutReversing() {
            JournalEntry refund = JournalEntry.of("LTX-RFD-RFD-0001", PostingType.REFUND, INR,
                    50_000L, "PAY-0001", "TXN0001", "partial refund", KEY, "payment-service",
                    null, Instant.now());

            assertThat(refund.isReversal()).isFalse();
            assertThat(refund.reversesSomething())
                    .as("the link exists so the chain is walkable, even though it is not a reversal")
                    .isTrue();
            assertThat(refund.getReversesLedgerTransactionId()).isEqualTo(KEY);
        }

        @Test
        @DisplayName("a plain transfer reverses nothing")
        void transferReversesNothing() {
            JournalEntry transfer = entry(PostingType.TRANSFER, null);

            assertThat(transfer.isReversal()).isFalse();
            assertThat(transfer.reversesSomething()).isFalse();
        }
    }

    /**
     * The journal is append-only. These assertions pin the shape that makes that true in the
     * mapping layer, alongside the database triggers that enforce it at the storage layer.
     */
    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("has no optimistic-locking version column")
        void hasNoVersionColumn() {
            boolean hasVersion = Arrays.stream(JournalEntry.class.getDeclaredFields())
                    .anyMatch(field -> field.isAnnotationPresent(Version.class));

            assertThat(hasVersion)
                    .as("a @Version column exists to make UPDATEs safe, and this row is never "
                            + "updated; having one would advertise the opposite of the contract")
                    .isFalse();
        }

        @Test
        @DisplayName("every mapped column except the generated id is non-updatable")
        void allColumnsAreNonUpdatable() {
            for (Field field : JournalEntry.class.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null) {
                    continue;
                }
                assertThat(column.updatable())
                        .as("JournalEntry.%s must be updatable = false; the journal is "
                                + "append-only and a correction is a new reversing entry",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("exposes no setters")
        void exposesNoSetters() {
            assertThat(Arrays.stream(JournalEntry.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .toList())
                    .as("a setter on a journal entry is a way to rewrite history")
                    .isEmpty();
        }

        @Test
        @DisplayName("has no updatedAt field")
        void hasNoUpdatedAt() {
            assertThat(Arrays.stream(JournalEntry.class.getDeclaredFields())
                    .map(Field::getName)
                    .filter(name -> name.toLowerCase().contains("updated"))
                    .toList())
                    .as("an updatedAt column would imply the row can change")
                    .isEmpty();
        }

        /**
         * Deliberately absent. Back-filling "what reversed me" would require an UPDATE, so the
         * relationship is stored only on the reversing entry and queried in that direction.
         */
        @Test
        @DisplayName("has no reversedBy field, because setting one would require an update")
        void hasNoReversedByField() {
            assertThat(Arrays.stream(JournalEntry.class.getDeclaredFields())
                    .map(Field::getName)
                    .filter(name -> name.equalsIgnoreCase("reversedBy")
                            || name.equalsIgnoreCase("reversedByLedgerTransactionId"))
                    .toList())
                    .isEmpty();
        }
    }
}
