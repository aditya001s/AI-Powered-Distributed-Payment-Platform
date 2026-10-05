package com.aegispay.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the payment state machine.
 *
 * <p>The transition table is the safety net that stops a payment being captured
 * twice, settled after rejection, or refunded before it completed. Encoding it in the
 * enum makes an illegal transition unrepresentable, and these tests pin the table
 * itself so a careless edit is caught.
 */
class PaymentStatusTest {

    @Test
    @DisplayName("follows the happy path through to COMPLETED")
    void happyPathIsAllowed() {
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.FUNDS_RESERVED)).isTrue();
        assertThat(PaymentStatus.FUNDS_RESERVED.canTransitionTo(PaymentStatus.RISK_ASSESSED)).isTrue();
        assertThat(PaymentStatus.RISK_ASSESSED.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CAPTURED)).isTrue();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("allows a fraud hold to be released or refused by an analyst")
    void holdCanBeResolvedEitherWay() {
        assertThat(PaymentStatus.RISK_ASSESSED.canTransitionTo(PaymentStatus.ON_HOLD)).isTrue();
        // An analyst releasing the hold sends it on to be charged...
        assertThat(PaymentStatus.ON_HOLD.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
        // ...or refuses it outright.
        assertThat(PaymentStatus.ON_HOLD.canTransitionTo(PaymentStatus.REJECTED)).isTrue();
    }

    /**
     * Double capture is the highest-consequence illegal transition: it would charge
     * the customer twice for one payment.
     */
    @Test
    @DisplayName("forbids capturing an already-captured payment")
    void forbidsDoubleCapture() {
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
        assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
        assertThat(PaymentStatus.COMPLETED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("forbids settling a rejected or failed payment")
    void forbidsSettlingAfterRefusal() {
        assertThat(PaymentStatus.REJECTED.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
        assertThat(PaymentStatus.REJECTED.canTransitionTo(PaymentStatus.COMPLETED)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("forbids skipping the risk assessment")
    void forbidsSkippingRiskAssessment() {
        // Reserving funds must not lead straight to a provider charge: every payment
        // is scored before any money moves.
        assertThat(PaymentStatus.FUNDS_RESERVED.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("only allows a refund once money has actually moved")
    void refundRequiresCompletion() {
        assertThat(PaymentStatus.COMPLETED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
        assertThat(PaymentStatus.COMPLETED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();
        // A partial refund can be topped up to a full one.
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();

        assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
        assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
    }

    @Test
    @DisplayName("classifies terminal states")
    void identifiesTerminalStates() {
        assertThat(PaymentStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(PaymentStatus.REJECTED.isTerminal()).isTrue();
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
        assertThat(PaymentStatus.REFUNDED.isTerminal()).isTrue();

        assertThat(PaymentStatus.CREATED.isTerminal()).isFalse();
        assertThat(PaymentStatus.ON_HOLD.isTerminal()).isFalse();
        // Deliberately not terminal: more of the amount can still be refunded.
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("classifies refundable states")
    void identifiesRefundableStates() {
        assertThat(PaymentStatus.COMPLETED.isRefundable()).isTrue();
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.isRefundable()).isTrue();
        assertThat(PaymentStatus.AUTHORIZED.isRefundable()).isFalse();
        assertThat(PaymentStatus.FAILED.isRefundable()).isFalse();
    }

    /**
     * Self-transitions are forbidden almost everywhere, because every handler is
     * reachable more than once (Kafka delivers at least once) and a self-transition
     * would let a replay re-run the side effects of a step that already ran.
     *
     * <p><strong>{@code PARTIALLY_REFUNDED} is the one deliberate exception.</strong>
     * A payment can legitimately be partially refunded several times (₹500 today,
     * ₹300 next week) and the status is unchanged each time. Safety for that operation
     * does not come from the state machine at all — it comes from the refund's
     * idempotency key plus the invariant that cumulative refunds cannot exceed the
     * original amount. Forbidding the self-transition here would force the refund path
     * to special-case "do not call transitionTo", which is exactly the kind of
     * scattered conditional this enum exists to eliminate.
     */
    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = "PARTIALLY_REFUNDED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("no state permits a transition to itself, except a repeat partial refund")
    void noSelfTransitions(PaymentStatus status) {
        assertThat(status.canTransitionTo(status))
                .as("%s must not transition to itself", status)
                .isFalse();
    }

    @Test
    @DisplayName("a second partial refund is allowed and keeps the same status")
    void repeatPartialRefundIsAllowed() {
        assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED))
                .as("multiple partial refunds are a real business case")
                .isTrue();
    }

    @Test
    @DisplayName("dead-end states have no outgoing transitions")
    void deadEndStatesAreClosed() {
        assertThat(PaymentStatus.REJECTED.allowedTransitions()).isEmpty();
        assertThat(PaymentStatus.FAILED.allowedTransitions()).isEmpty();
        assertThat(PaymentStatus.REFUNDED.allowedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("every non-terminal state can reach a failure state")
    void everyInFlightStateCanFail() {
        // A saga must always be able to give up and compensate. A non-terminal state
        // with no failure exit would strand payments permanently in flight.
        Set<PaymentStatus> inFlight = Set.of(
                PaymentStatus.CREATED,
                PaymentStatus.FUNDS_RESERVED,
                PaymentStatus.RISK_ASSESSED,
                PaymentStatus.ON_HOLD,
                PaymentStatus.AUTHORIZED,
                PaymentStatus.CAPTURED);

        for (PaymentStatus status : inFlight) {
            assertThat(status.allowedTransitions())
                    .as("%s must have an exit to FAILED or REJECTED", status)
                    .containsAnyOf(PaymentStatus.FAILED, PaymentStatus.REJECTED);
        }
    }
}
