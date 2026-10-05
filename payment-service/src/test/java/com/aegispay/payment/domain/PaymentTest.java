package com.aegispay.payment.domain;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.exception.DomainExceptions.IllegalStateTransitionException;
import com.aegispay.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the {@link Payment} aggregate.
 *
 * <p>Focused on the two things that protect money: the guarded state machine, which makes
 * double capture and settle-after-refusal unrepresentable, and the refund cap, which makes
 * over-refunding impossible.
 *
 * <p>The state machine matters most because Kafka delivers at least once, so every handler
 * will eventually see a duplicate event. Rejecting the illegal transition is what turns
 * at-least-once delivery into effectively-once state change without a separate
 * de-duplication table.
 */
class PaymentTest {

    private static final String INR = "INR";

    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = newPayment("2000.00");
    }

    private Payment newPayment(String amount) {
        return Payment.create(
                "PAY-TEST0001", "TXN0001", "idem-key-1",
                "USR-PAYER", "WAL-SOURCE", "WAL-DEST", "USR-PAYEE",
                Money.of(amount, INR), PaymentType.P2P_TRANSFER, "Dinner split",
                new RiskSignals("dev-1", "fp-1", "203.0.113.7", "curl/8.4.0",
                        "IN", "Mumbai", 19.076, 72.8777, Channel.MOBILE_ANDROID, null, null));
    }

    /** Advances a payment to COMPLETED through every legal step. */
    private void completePayment(Payment target) {
        target.transitionTo(PaymentStatus.FUNDS_RESERVED);
        target.transitionTo(PaymentStatus.RISK_ASSESSED);
        target.transitionTo(PaymentStatus.AUTHORIZED);
        target.transitionTo(PaymentStatus.CAPTURED);
        target.transitionTo(PaymentStatus.COMPLETED);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("starts in CREATED with no risk or provider outcome")
        void startsInCreated() {
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
            assertThat(payment.getRiskLevel()).isNull();
            assertThat(payment.getFraudDecision()).isNull();
            assertThat(payment.getProviderId()).isNull();
            assertThat(payment.getCompletedAt()).isNull();
            assertThat(payment.refunded().isZero()).isTrue();
        }

        @Test
        @DisplayName("preserves the risk context it was created with")
        void preservesRiskContext() {
            RiskSignals signals = payment.riskSignals();

            assertThat(signals.deviceFingerprint()).isEqualTo("fp-1");
            assertThat(signals.ipAddress()).isEqualTo("203.0.113.7");
            assertThat(signals.countryCode()).isEqualTo("IN");
            assertThat(signals.channel()).isEqualTo(Channel.MOBILE_ANDROID);
            assertThat(signals.hasGeoLocation()).isTrue();
            assertThat(signals.hasDevice()).isTrue();
        }

        @Test
        @DisplayName("treats absent risk context as UNKNOWN rather than failing")
        void toleratesMissingRiskContext() {
            Payment bare = Payment.create("PAY-2", "TXN2", "k2",
                    "USR-A", "WAL-A", "WAL-B", "USR-B",
                    Money.of("10.00", INR), null, null, null);

            // Absence is itself a fraud signal, so it is recorded rather than rejected.
            assertThat(bare.riskSignals().channel()).isEqualTo(Channel.UNKNOWN);
            assertThat(bare.riskSignals().hasDevice()).isFalse();
            assertThat(bare.getPaymentType()).isEqualTo(PaymentType.P2P_TRANSFER);
        }
    }

    @Nested
    @DisplayName("the state machine")
    class StateMachine {

        @Test
        @DisplayName("follows the happy path to COMPLETED and stamps completedAt")
        void happyPath() {
            completePayment(payment);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(payment.isTerminal()).isTrue();
            // Stamped by the aggregate, not the caller, so the column cannot disagree
            // with the status. A CHECK constraint enforces the same rule.
            assertThat(payment.getCompletedAt()).isNotNull();
        }

        /**
         * The highest-consequence illegal transition: it would charge the customer twice for
         * one payment.
         */
        @Test
        @DisplayName("refuses to capture an already-captured payment")
        void refusesDoubleCapture() {
            payment.transitionTo(PaymentStatus.FUNDS_RESERVED);
            payment.transitionTo(PaymentStatus.RISK_ASSESSED);
            payment.transitionTo(PaymentStatus.AUTHORIZED);
            payment.transitionTo(PaymentStatus.CAPTURED);

            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> payment.transitionTo(PaymentStatus.CAPTURED))
                    .satisfies(ex -> {
                        assertThat(ex.details()).containsEntry("fromState", "CAPTURED");
                        assertThat(ex.details()).containsEntry("toState", "CAPTURED");
                    });

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        }

        @Test
        @DisplayName("refuses to settle a rejected or failed payment")
        void refusesSettlementAfterRefusal() {
            Payment rejected = newPayment("100.00");
            rejected.transitionTo(PaymentStatus.REJECTED);

            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> rejected.transitionTo(PaymentStatus.AUTHORIZED));
            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> rejected.transitionTo(PaymentStatus.COMPLETED));

            Payment failed = newPayment("100.00");
            failed.transitionTo(PaymentStatus.FUNDS_RESERVED);
            failed.transitionTo(PaymentStatus.FAILED);

            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> failed.transitionTo(PaymentStatus.CAPTURED));
        }

        @Test
        @DisplayName("refuses to skip the risk assessment")
        void refusesSkippingRiskAssessment() {
            payment.transitionTo(PaymentStatus.FUNDS_RESERVED);

            // Every payment is scored before any money moves.
            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> payment.transitionTo(PaymentStatus.AUTHORIZED));
        }

        @Test
        @DisplayName("can always fail from any in-flight state")
        void canAlwaysFail() {
            // A saga must always be able to give up and compensate, wherever it got to.
            for (PaymentStatus reached : new PaymentStatus[]{
                    PaymentStatus.CREATED, PaymentStatus.FUNDS_RESERVED,
                    PaymentStatus.RISK_ASSESSED, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED}) {

                Payment fresh = newPayment("50.00");
                advanceTo(fresh, reached);
                assertThat(fresh.tryTransitionTo(PaymentStatus.FAILED))
                        .as("must be able to fail from %s", reached)
                        .isTrue();
            }
        }

        /**
         * The duplicate-delivery path. A replayed event should be quietly ignored rather than
         * raised as an error and retried into a dead-letter topic.
         */
        @Test
        @DisplayName("tryTransitionTo reports an illegal move instead of throwing")
        void tryTransitionIsNonThrowing() {
            completePayment(payment);

            assertThat(payment.tryTransitionTo(PaymentStatus.CAPTURED)).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        }

        @Test
        @DisplayName("supports the fraud hold path in both directions")
        void supportsHoldPath() {
            Payment released = newPayment("100.00");
            advanceTo(released, PaymentStatus.RISK_ASSESSED);
            released.transitionTo(PaymentStatus.ON_HOLD);
            // An analyst releases the hold and the payment proceeds.
            released.transitionTo(PaymentStatus.AUTHORIZED);
            assertThat(released.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

            Payment refused = newPayment("100.00");
            advanceTo(refused, PaymentStatus.RISK_ASSESSED);
            refused.transitionTo(PaymentStatus.ON_HOLD);
            // Or refuses it.
            refused.transitionTo(PaymentStatus.REJECTED);
            assertThat(refused.isTerminal()).isTrue();
        }

        private void advanceTo(Payment target, PaymentStatus destination) {
            if (destination == PaymentStatus.CREATED) {
                return;
            }
            target.transitionTo(PaymentStatus.FUNDS_RESERVED);
            if (destination == PaymentStatus.FUNDS_RESERVED) {
                return;
            }
            target.transitionTo(PaymentStatus.RISK_ASSESSED);
            if (destination == PaymentStatus.RISK_ASSESSED) {
                return;
            }
            target.transitionTo(PaymentStatus.AUTHORIZED);
            if (destination == PaymentStatus.AUTHORIZED) {
                return;
            }
            target.transitionTo(PaymentStatus.CAPTURED);
        }
    }

    /**
     * The forward walk the saga outcome listeners depend on.
     *
     * <p>Since Phase 5 the saga lives in payment-orchestrator and announces its progress on four
     * separate topics. Kafka orders records within one partition of one topic and gives no ordering
     * across topics, so {@code payment-completed} can genuinely arrive before
     * {@code payment-approved}. {@code advanceTo} exists for exactly that: it applies the state an
     * event asserts by taking every intervening hop, and it refuses to move backwards.
     */
    @Nested
    @DisplayName("advancing to a state the saga reports")
    class Advancing {

        @Test
        @DisplayName("walks every intervening state, so a late-arriving event still lands correctly")
        void walksIntermediateStates() {
            // Straight from CREATED to COMPLETED: this is payment-completed arriving without
            // payment-approved having been seen first.
            assertThat(payment.advanceTo(PaymentStatus.COMPLETED)).isTrue();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(payment.getCompletedAt())
                    .as("stamped by the transition, so the column cannot disagree with the status")
                    .isNotNull();
        }

        @Test
        @DisplayName("stops exactly at the requested state")
        void stopsAtTheTarget() {
            assertThat(payment.advanceTo(PaymentStatus.AUTHORIZED)).isTrue();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(payment.getCompletedAt()).isNull();
        }

        /**
         * The de-duplication that makes at-least-once delivery safe without a side table.
         *
         * <p>A redelivered {@code payment-completed} must change nothing, because the handler emits
         * customer notifications and a second pass would tell the payee their money arrived twice.
         */
        @Test
        @DisplayName("does nothing when the payment is already at the target")
        void refusesToRepeatItself() {
            payment.advanceTo(PaymentStatus.COMPLETED);

            assertThat(payment.advanceTo(PaymentStatus.COMPLETED)).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        }

        @Test
        @DisplayName("never moves backwards")
        void refusesToGoBackwards() {
            payment.advanceTo(PaymentStatus.CAPTURED);

            assertThat(payment.advanceTo(PaymentStatus.AUTHORIZED)).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        }

        /**
         * A payment that has left the forward path cannot be dragged back onto it.
         *
         * <p>This is the case that would be a real incident: a stale {@code payment-approved} for a
         * payment that already failed must not resurrect it, because the saga has by then released
         * the reservation and voided the authorisation.
         */
        @Test
        @DisplayName("refuses to advance a payment that already failed")
        void refusesToReviveAFailedPayment() {
            payment.transitionTo(PaymentStatus.FAILED);

            assertThat(payment.advanceTo(PaymentStatus.AUTHORIZED)).isFalse();
            assertThat(payment.advanceTo(PaymentStatus.COMPLETED)).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        /**
         * {@code ON_HOLD} is a legal state but not a forward one.
         *
         * <p>It means a human has to decide, so nothing may walk through or past it automatically.
         * Deriving the walk from the transition graph rather than naming the path explicitly would
         * have allowed exactly that.
         */
        @Test
        @DisplayName("will not route through ON_HOLD, which needs a human decision")
        void refusesToAdvanceThroughAHold() {
            assertThat(payment.advanceTo(PaymentStatus.ON_HOLD)).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        }
    }

    @Nested
    @DisplayName("refunds")
    class Refunds {

        @BeforeEach
        void complete() {
            completePayment(payment);
        }

        @Test
        @DisplayName("a full refund moves the payment to REFUNDED")
        void fullRefund() {
            PaymentStatus result = payment.applyRefund(Money.of("2000.00", INR));

            assertThat(result).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.refunded()).isEqualTo(Money.of("2000.00", INR));
            assertThat(payment.refundableRemaining().isZero()).isTrue();
        }

        @Test
        @DisplayName("a partial refund moves the payment to PARTIALLY_REFUNDED")
        void partialRefund() {
            PaymentStatus result = payment.applyRefund(Money.of("500.00", INR));

            assertThat(result).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(payment.refunded()).isEqualTo(Money.of("500.00", INR));
            assertThat(payment.refundableRemaining()).isEqualTo(Money.of("1500.00", INR));
        }

        /**
         * Repeated partial refunds are a real business case, which is why
         * {@code PARTIALLY_REFUNDED} is the one state permitted to transition to itself.
         * Safety comes from the cumulative cap, not from the state machine.
         */
        @Test
        @DisplayName("accumulates several partial refunds up to the full amount")
        void accumulatesPartialRefunds() {
            payment.applyRefund(Money.of("500.00", INR));
            payment.applyRefund(Money.of("300.00", INR));
            assertThat(payment.refunded()).isEqualTo(Money.of("800.00", INR));
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

            // The final instalment tips it to fully refunded.
            PaymentStatus result = payment.applyRefund(Money.of("1200.00", INR));
            assertThat(result).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.refundableRemaining().isZero()).isTrue();
        }

        /**
         * Over-refunding is a direct loss. Checked here and backed by a CHECK constraint,
         * because refunds are frequently driven by support tooling rather than this path.
         */
        @Test
        @DisplayName("refuses to refund more than was paid")
        void refusesOverRefund() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> payment.applyRefund(Money.of("2000.01", INR)))
                    .withMessageContaining("would exceed the payment amount");

            payment.applyRefund(Money.of("1800.00", INR));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> payment.applyRefund(Money.of("200.01", INR)));

            assertThat(payment.refunded()).isEqualTo(Money.of("1800.00", INR));
        }

        @Test
        @DisplayName("rejects a non-positive refund")
        void rejectsNonPositiveRefund() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> payment.applyRefund(Money.zero(INR)));
        }

        @Test
        @DisplayName("refuses to refund a payment whose money never moved")
        void refusesRefundBeforeCompletion() {
            Payment inFlight = newPayment("100.00");
            inFlight.transitionTo(PaymentStatus.FUNDS_RESERVED);

            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> inFlight.applyRefund(Money.of("50.00", INR)));

            Payment failed = newPayment("100.00");
            failed.transitionTo(PaymentStatus.FAILED);

            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> failed.applyRefund(Money.of("50.00", INR)));
        }

        @Test
        @DisplayName("refuses to refund twice in full")
        void refusesSecondFullRefund() {
            payment.applyRefund(Money.of("2000.00", INR));

            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> payment.applyRefund(Money.of("2000.00", INR)));
        }
    }

    @Nested
    @DisplayName("outcome recording")
    class OutcomeRecording {

        @Test
        @DisplayName("records the risk assessment without changing status")
        void riskAssessmentDoesNotChangeStatus() {
            payment.recordRiskAssessment(RiskLevel.HIGH, 0.94d, FraudDecision.HOLD);

            assertThat(payment.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(payment.getFraudProbability()).isEqualTo(0.94d);
            assertThat(payment.getFraudDecision()).isEqualTo(FraudDecision.HOLD);
            // Recording a verdict and acting on it are separate decisions.
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        }

        @Test
        @DisplayName("records the provider outcome and its fee")
        void recordsProviderAndFee() {
            payment.recordProvider("PROVIDER_A", "prov-ref-123", Money.of("5.00", INR));

            assertThat(payment.getProviderId()).isEqualTo("PROVIDER_A");
            assertThat(payment.getProviderReference()).isEqualTo("prov-ref-123");
            assertThat(payment.fee()).isEqualTo(Money.of("5.00", INR));
        }

        @Test
        @DisplayName("truncates an oversized failure reason to fit its column")
        void truncatesLongFailureReason() {
            payment.recordFailure("PROVIDER_ERROR", "x".repeat(900));

            assertThat(payment.getFailureReason()).hasSize(512).endsWith("...");
        }
    }
}
