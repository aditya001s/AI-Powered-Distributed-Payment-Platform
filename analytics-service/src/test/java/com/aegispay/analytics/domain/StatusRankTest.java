package com.aegispay.analytics.domain;

import com.aegispay.common.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The out-of-order guard, tested directly.
 *
 * <p>This class is four lines of logic protecting the read model from a failure with no symptom. If a late
 * {@code payment-created} were allowed to overwrite {@code COMPLETED}, the dashboard would report a settled
 * payment as in-flight — permanently, because every event for that payment has already been consumed and
 * nothing is left to correct it. No exception, no log line, no dead letter. Just a payment that appears
 * stuck and a success rate that is quietly wrong.
 *
 * <p>Which is why the ordering is asserted exhaustively rather than by example: the enum will gain values,
 * and the test that catches a mis-ranked new status is the one that iterates over all of them.
 */
class StatusRankTest {

    // ------------------------------------------------------------------------
    // totality
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("every status has a rank")
    class Totality {

        /**
         * A new {@code PaymentStatus} added to the shared enum must be ranked here too. The switch is
         * exhaustive so it would fail to compile — this asserts the runtime half, that no value throws.
         */
        @ParameterizedTest
        @EnumSource(PaymentStatus.class)
        @DisplayName("ranks without throwing")
        void ranksEveryStatus(PaymentStatus status) {
            assertThat(StatusRank.of(status))
                    .as("%s must have a rank, or the projection cannot decide whether to apply it", status)
                    .isPositive();
        }

        /**
         * A null status is a programming error rather than bad data: {@link PaymentFact} seeds a row with
         * a status supplied by the listener, so a null here means a listener passed nothing.
         */
        @Test
        @DisplayName("refuses a null status rather than defaulting it")
        void rejectsNull() {
            assertThatThrownBy(() -> StatusRank.of(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("status");
        }
    }

    // ------------------------------------------------------------------------
    // ordering
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the lifecycle only moves forward")
    class Ordering {

        /**
         * The in-flight sequence, in the order the orchestrator actually walks it. Asserted pairwise so a
         * failure names the two states that got out of order rather than reporting a list mismatch.
         */
        @Test
        @DisplayName("in-flight statuses rank in lifecycle order")
        void inFlightOrder() {
            List<PaymentStatus> lifecycle = List.of(
                    PaymentStatus.CREATED,
                    PaymentStatus.FUNDS_RESERVED,
                    PaymentStatus.RISK_ASSESSED,
                    PaymentStatus.ON_HOLD,
                    PaymentStatus.AUTHORIZED,
                    PaymentStatus.CAPTURED);

            for (int index = 1; index < lifecycle.size(); index++) {
                PaymentStatus earlier = lifecycle.get(index - 1);
                PaymentStatus later = lifecycle.get(index);

                assertThat(StatusRank.of(later))
                        .as("%s must outrank %s", later, earlier)
                        .isGreaterThan(StatusRank.of(earlier));
                assertThat(StatusRank.advances(earlier, later))
                        .as("advancing from %s to %s must be allowed", earlier, later)
                        .isTrue();
                assertThat(StatusRank.advances(later, earlier))
                        .as("""
                                going back from %s to %s must be refused: that is a late event on another \
                                topic overwriting newer state""", later, earlier)
                        .isFalse();
            }
        }

        /**
         * <strong>The reason the whole class exists.</strong> A completion applied before its creation is
         * the ordinary case when replaying topics into a fresh read model, not a rare one.
         */
        @Test
        @DisplayName("a late payment-created cannot drag a completed payment back to CREATED")
        void lateCreationCannotRegress() {
            assertThat(StatusRank.advances(PaymentStatus.COMPLETED, PaymentStatus.CREATED))
                    .as("""
                            a settled payment reported as in-flight forever, with every event already \
                            consumed and nothing left to correct it""")
                    .isFalse();
        }

        @Test
        @DisplayName("any status may be applied to a row that has none")
        void nullCurrentAcceptsAnything() {
            for (PaymentStatus status : PaymentStatus.values()) {
                assertThat(StatusRank.advances(null, status))
                        .as("a fresh row must accept %s, since any listener can arrive first", status)
                        .isTrue();
            }
        }

        /** A null candidate is nothing to apply, so it must not clear an existing status. */
        @Test
        @DisplayName("a null candidate never replaces a known status")
        void nullCandidateNeverAdvances() {
            assertThat(StatusRank.advances(PaymentStatus.CAPTURED, null)).isFalse();
            assertThat(StatusRank.advances(null, null)).isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // the terminal three
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the three endings are mutually exclusive")
    class TerminalOutcomes {

        private static final Set<PaymentStatus> ENDINGS =
                EnumSet.of(PaymentStatus.COMPLETED, PaymentStatus.REJECTED, PaymentStatus.FAILED);

        /**
         * Equal ranks, deliberately. Combined with the strictly-greater test in
         * {@link StatusRank#advances}, this is what makes the first ending to arrive the one that sticks.
         */
        @Test
        @DisplayName("COMPLETED, REJECTED and FAILED share one rank")
        void endingsShareARank() {
            assertThat(ENDINGS.stream().map(StatusRank::of).distinct().toList())
                    .as("""
                            a payment reaches exactly one ending; equal ranks plus a strictly-greater test \
                            is what stops a second terminal event rewriting the outcome""")
                    .hasSize(1);
        }

        /**
         * The consequence, stated as behaviour: no ending can replace another. A redelivered or
         * duplicated terminal event cannot change what the platform recorded.
         */
        @Test
        @DisplayName("no ending can replace another")
        void endingsCannotReplaceEachOther() {
            for (PaymentStatus current : ENDINGS) {
                for (PaymentStatus candidate : ENDINGS) {
                    assertThat(StatusRank.advances(current, candidate))
                            .as("""
                                    %s must not become %s: a second terminal event is a redelivery or a \
                                    producer defect, and in both cases changing the outcome is wrong""",
                                    current, candidate)
                            .isFalse();
                }
            }
        }

        /**
         * Refunds are the exception, and they must be. A refund legitimately follows a settled payment, so
         * it has to pass the same guard that blocks a second ending.
         */
        @Test
        @DisplayName("a refund still applies to a completed payment")
        void refundsOutrankCompletion() {
            assertThat(StatusRank.advances(PaymentStatus.COMPLETED, PaymentStatus.PARTIALLY_REFUNDED))
                    .as("a partial refund follows settlement and must be recorded")
                    .isTrue();
            assertThat(StatusRank.advances(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED))
                    .as("a partial refund can later become full")
                    .isTrue();
            assertThat(StatusRank.advances(PaymentStatus.REFUNDED, PaymentStatus.PARTIALLY_REFUNDED))
                    .as("a fully refunded payment must not regress to partially refunded")
                    .isFalse();
        }

        /**
         * A refund must not resurrect a payment that never settled. A rejected payment reserved no funds to
         * return, so a refund event naming one is a producer defect, and applying it would report money
         * moving that never moved.
         */
        @Test
        @DisplayName("a refund outranks rejection and failure too, so the guard is rank-based only")
        void refundRanksAboveEveryEnding() {
            for (PaymentStatus ending : ENDINGS) {
                assertThat(StatusRank.of(PaymentStatus.REFUNDED))
                        .as("REFUNDED must outrank %s for the single-comparison guard to work", ending)
                        .isGreaterThan(StatusRank.of(ending));
            }
        }
    }

    // ------------------------------------------------------------------------
    // numbering
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("rank values")
    class Numbering {

        /**
         * Ranks are stored in {@code payment_facts.status_rank} as a {@code smallint}. A rank beyond that
         * range would not fail on insert — it would wrap, and the guard would start allowing regressions.
         */
        @Test
        @DisplayName("every rank fits a smallint, because it is persisted as one")
        void ranksFitSmallint() {
            for (PaymentStatus status : PaymentStatus.values()) {
                assertThat(StatusRank.of(status))
                        .as("%s is written to a smallint column", status)
                        .isLessThanOrEqualTo(Short.MAX_VALUE);
            }
        }

        /**
         * Spacing exists so a state can be inserted between two existing ones without renumbering — which
         * would be a migration over every row rather than a code change.
         */
        @Test
        @DisplayName("leaves room between adjacent ranks for a state added later")
        void ranksAreSpaced() {
            assertThat(StatusRank.of(PaymentStatus.FUNDS_RESERVED) - StatusRank.of(PaymentStatus.CREATED))
                    .as("a rank gap of one would force a renumbering migration to insert a state")
                    .isGreaterThan(1);
        }
    }
}
