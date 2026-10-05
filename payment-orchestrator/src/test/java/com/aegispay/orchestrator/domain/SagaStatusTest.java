package com.aegispay.orchestrator.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the saga state machine.
 *
 * <p>The transition table is the only thing standing between the orchestrator and the two failures
 * that would cost real money: capturing a payment twice, and compensating one whose funds have
 * already moved. Both are prevented by omission — the edge simply does not exist — so these tests
 * assert the <em>absence</em> of transitions as carefully as the presence of them. An accidentally
 * added edge is exactly the kind of change that looks harmless in review.
 */
class SagaStatusTest {

    @Nested
    @DisplayName("the forward path")
    class ForwardPath {

        /**
         * The order is load-bearing, not stylistic.
         *
         * <p>Reserve before authorise, so a provider is never asked to charge money the customer does
         * not have. Post to the ledger before capturing, so money never moves that has not been
         * recorded.
         */
        @Test
        @DisplayName("runs reserve, assess, authorise, post, capture, complete")
        void followsTheIntendedOrder() {
            SagaStatus status = SagaStatus.STARTED;

            for (SagaStatus next : new SagaStatus[]{
                    SagaStatus.FUNDS_RESERVED,
                    SagaStatus.RISK_ASSESSED,
                    SagaStatus.AUTHORIZED,
                    SagaStatus.LEDGER_POSTED,
                    SagaStatus.CAPTURED,
                    SagaStatus.COMPLETED}) {

                assertThat(status.canTransitionTo(next))
                        .as("%s must be able to advance to %s", status, next)
                        .isTrue();
                status = next;
            }
            assertThat(status.isTerminal()).isTrue();
        }

        /**
         * No step may be skipped, and this is the pair that matters most.
         *
         * <p>{@code AUTHORIZED -> CAPTURED} would move a customer's money with nothing in the books
         * saying why. The ledger posting sits between them precisely so that a crash leaves the books
         * ahead of the wallets — detectable by reconciliation and undoable by a reversal — rather than
         * behind them.
         */
        @Test
        @DisplayName("cannot capture without posting to the ledger first")
        void cannotSkipTheLedger() {
            assertThat(SagaStatus.AUTHORIZED.canTransitionTo(SagaStatus.CAPTURED))
                    .as("capturing before posting would move money with no record of why")
                    .isFalse();
        }

        @Test
        @DisplayName("cannot authorise without reserving and assessing first")
        void cannotSkipToAuthorisation() {
            assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.AUTHORIZED)).isFalse();
            assertThat(SagaStatus.FUNDS_RESERVED.canTransitionTo(SagaStatus.AUTHORIZED))
                    .as("a charge must not be sent to a provider before a risk verdict exists")
                    .isFalse();
        }

        @Test
        @DisplayName("cannot complete before the funds are captured")
        void cannotCompleteEarly() {
            for (SagaStatus status : EnumSet.of(SagaStatus.STARTED, SagaStatus.FUNDS_RESERVED,
                    SagaStatus.RISK_ASSESSED, SagaStatus.AUTHORIZED, SagaStatus.LEDGER_POSTED)) {
                assertThat(status.canTransitionTo(SagaStatus.COMPLETED))
                        .as("%s must not be able to declare success", status)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("compensation")
    class Compensation {

        /**
         * Compensation is available from every state that has a side effect to undo, and from no
         * other.
         *
         * <p>{@code STARTED} is excluded because nothing external has happened yet — entering
         * compensation there would be a no-op that nonetheless persists a state implying an incident.
         */
        @ParameterizedTest
        @DisplayName("is reachable from every state that has something to undo")
        @EnumSource(value = SagaStatus.class, names = {"FUNDS_RESERVED", "RISK_ASSESSED",
                "AUTHORIZED", "LEDGER_POSTED"})
        void reachableWhileEffectsExist(SagaStatus status) {
            assertThat(status.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();
        }

        @Test
        @DisplayName("is not reachable before anything external has happened")
        void notReachableFromStarted() {
            assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.COMPENSATING))
                    .as("nothing has been touched yet, so there is nothing to compensate")
                    .isFalse();
        }

        /**
         * <strong>The most important omission in the table.</strong>
         *
         * <p>Once the funds are captured the money has genuinely moved. Releasing the reservation at
         * that point would hand back money that was legitimately taken, so the only correct direction
         * is forward: finish recording the outcome. Failing forward is not a compromise here, it is
         * the only option that does not destroy value.
         */
        @Test
        @DisplayName("is unreachable once the money has moved")
        void unreachableAfterCapture() {
            assertThat(SagaStatus.CAPTURED.canTransitionTo(SagaStatus.COMPENSATING))
                    .as("undoing a capture would refund money that was correctly taken")
                    .isFalse();
            assertThat(SagaStatus.CAPTURED.allowedTransitions())
                    .as("record the outcome, do not reverse it")
                    .containsExactlyInAnyOrder(SagaStatus.COMPLETED, SagaStatus.FAILED);
        }

        /**
         * {@code COMPENSATING} is persisted rather than transient, which is what lets the recovery
         * sweep tell "died before compensating" from "died halfway through it". Those need different
         * handling, so they must be different states.
         */
        @Test
        @DisplayName("only ends in a terminal outcome")
        void endsTerminally() {
            assertThat(SagaStatus.COMPENSATING.allowedTransitions())
                    .containsExactlyInAnyOrder(SagaStatus.FAILED, SagaStatus.REJECTED);
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminal {

        @Test
        @DisplayName("are exactly completed, failed and rejected")
        void terminalSet() {
            for (SagaStatus status : SagaStatus.values()) {
                boolean expected = Set.of(SagaStatus.COMPLETED, SagaStatus.FAILED,
                        SagaStatus.REJECTED).contains(status);
                assertThat(status.isTerminal()).as("%s", status).isEqualTo(expected);
            }
        }

        /**
         * A terminal state with an outgoing edge would let a redelivered event resurrect a settled
         * payment — charging a customer again for a payment already refused, or reversing one already
         * completed.
         */
        @ParameterizedTest
        @DisplayName("have no way out")
        @EnumSource(value = SagaStatus.class, names = {"COMPLETED", "FAILED", "REJECTED"})
        void noOutgoingTransitions(SagaStatus status) {
            assertThat(status.allowedTransitions()).isEmpty();
        }

        /**
         * A rejection is a decision and a failure is a fault, so they are reachable from different
         * places. {@code REJECTED} is unavailable once a provider has authorised the charge: at that
         * point something exists at the rail, and calling the payment "refused" would misdescribe it.
         */
        @Test
        @DisplayName("rejection is unavailable once a provider has authorised the charge")
        void rejectionStopsAtAuthorisation() {
            assertThat(SagaStatus.AUTHORIZED.canTransitionTo(SagaStatus.REJECTED)).isFalse();
            assertThat(SagaStatus.LEDGER_POSTED.canTransitionTo(SagaStatus.REJECTED)).isFalse();
            assertThat(SagaStatus.RISK_ASSESSED.canTransitionTo(SagaStatus.REJECTED))
                    .as("every provider declining is a rejection, and that happens here")
                    .isTrue();
        }

        /**
         * Failure must always be reachable from a live saga.
         *
         * <p>Otherwise a saga could reach a state it can neither advance from nor abandon, and would
         * sit in flight forever holding a customer's funds.
         */
        @ParameterizedTest
        @DisplayName("failure is reachable from every non-terminal state")
        @EnumSource(value = SagaStatus.class, names = {"COMPLETED", "FAILED", "REJECTED"},
                mode = EnumSource.Mode.EXCLUDE)
        void failureIsAlwaysAvailable(SagaStatus status) {
            assertThat(status.canTransitionTo(SagaStatus.FAILED))
                    .as("%s must be abandonable, or a saga could stall permanently", status)
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("external effects")
    class ExternalEffects {

        /**
         * Used by the compensator as a cheap first check. It is deliberately conservative: from
         * {@code FUNDS_RESERVED} onwards it says "yes" even when the specific handles turn out to be
         * empty, because the alternative — assuming nothing happened — strands customer money
         * whenever the assumption is wrong.
         */
        @Test
        @DisplayName("are assumed from the first state after STARTED onwards")
        void effectsBeginAfterStart() {
            assertThat(SagaStatus.STARTED.hasExternalEffects()).isFalse();

            for (SagaStatus status : SagaStatus.values()) {
                if (status != SagaStatus.STARTED) {
                    assertThat(status.hasExternalEffects())
                            .as("%s must be treated as possibly having touched something", status)
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("the held-for-review state")
    class HeldForReview {

        /**
         * A HOLD verdict has to be reachable from the risk step, and only from there.
         *
         * <p>Reachable later would mean a payment could be parked after a provider had already been
         * charged, and the charge would sit at the rail for however long the review took.
         */
        @Test
        @DisplayName("is reachable only from the risk step")
        void reachableOnlyFromRiskStep() {
            assertThat(SagaStatus.FUNDS_RESERVED.canTransitionTo(SagaStatus.RISK_HELD)).isTrue();

            for (SagaStatus status : EnumSet.complementOf(EnumSet.of(SagaStatus.FUNDS_RESERVED,
                    SagaStatus.RISK_HELD))) {
                assertThat(status.canTransitionTo(SagaStatus.RISK_HELD))
                        .as("%s must not be able to park a payment for review", status)
                        .isFalse();
            }
        }

        /**
         * A release rejoins the normal path rather than jumping ahead on it.
         *
         * <p>{@code RISK_HELD -> AUTHORIZED} would skip the authorisation step's own bookkeeping and
         * give the released payment a different route through the saga than an unheld one. One path,
         * two entry points.
         */
        @Test
        @DisplayName("releases back onto the normal path, not past it")
        void releasesToRiskAssessed() {
            assertThat(SagaStatus.RISK_HELD.canTransitionTo(SagaStatus.RISK_ASSESSED)).isTrue();
            assertThat(SagaStatus.RISK_HELD.canTransitionTo(SagaStatus.AUTHORIZED))
                    .as("a released payment must take the same route as one never held")
                    .isFalse();
            assertThat(SagaStatus.RISK_HELD.canTransitionTo(SagaStatus.CAPTURED)).isFalse();
        }

        /** An analyst's refusal has to be expressible, with the reservation released first. */
        @Test
        @DisplayName("can be refused, compensating on the way out")
        void canBeRefused() {
            assertThat(SagaStatus.RISK_HELD.canTransitionTo(SagaStatus.COMPENSATING))
                    .as("refusing a held payment must release the reservation")
                    .isTrue();
            assertThat(SagaStatus.RISK_HELD.canTransitionTo(SagaStatus.REJECTED)).isTrue();
        }

        /**
         * <strong>The distinction the recovery sweep depends on.</strong>
         *
         * <p>A parked saga has more to do but cannot do it alone. If it were reported as ordinary
         * in-flight work the sweep would "recover" every held payment on every pass, incrementing its
         * attempt count until a payment behaving exactly as designed looked like a chronic failure —
         * and nothing would fail loudly enough for anyone to notice.
         */
        @Test
        @DisplayName("is parked but not terminal, and nothing else is parked")
        void isParkedAndNotTerminal() {
            assertThat(SagaStatus.RISK_HELD.isParked()).isTrue();
            assertThat(SagaStatus.RISK_HELD.isTerminal())
                    .as("a held payment may still complete, so it is not an outcome")
                    .isFalse();

            for (SagaStatus status : EnumSet.complementOf(EnumSet.of(SagaStatus.RISK_HELD))) {
                assertThat(status.isParked())
                        .as("%s must be driveable; only a held payment waits on a person", status)
                        .isFalse();
            }
        }

        /** The customer's funds are reserved throughout, so a failure must be able to release them. */
        @Test
        @DisplayName("counts as having external effects")
        void hasExternalEffects() {
            assertThat(SagaStatus.RISK_HELD.hasExternalEffects())
                    .as("the reservation is still outstanding while the payment waits")
                    .isTrue();
        }
    }

    /**
     * Every state must be reachable from {@code STARTED}.
     *
     * <p>An unreachable state is dead code in the most dangerous place: it looks like a handled case,
     * and a reviewer will assume the path exists.
     */
    @Test
    @DisplayName("every state is reachable from STARTED")
    void everyStateIsReachable() {
        Set<SagaStatus> reached = EnumSet.of(SagaStatus.STARTED);
        boolean grew = true;

        while (grew) {
            grew = false;
            for (SagaStatus status : EnumSet.copyOf(reached)) {
                for (SagaStatus next : status.allowedTransitions()) {
                    grew |= reached.add(next);
                }
            }
        }
        assertThat(reached).containsExactlyInAnyOrder(SagaStatus.values());
    }
}
