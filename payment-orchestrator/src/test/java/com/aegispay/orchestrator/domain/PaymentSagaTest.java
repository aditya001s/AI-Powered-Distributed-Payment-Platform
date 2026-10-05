package com.aegispay.orchestrator.domain;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.PaymentCreatedPayload;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.exception.DomainExceptions.IllegalStateTransitionException;
import com.aegispay.common.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the saga aggregate.
 *
 * <p>The theme throughout is the same one that makes a saga correct at all: <strong>the compensation
 * handles, not the status, are the record of what exists in the outside world.</strong> A crash
 * between a provider approving a charge and the status being written leaves a real authorisation
 * outstanding while the status still says {@code RISK_ASSESSED}. Anything that reasoned from the
 * status would miss it, and a real charge would sit at the provider forever.
 */
class PaymentSagaTest {

    private static final String PAYMENT_ID = "PAY-TEST0001";
    private static final String SOURCE_WALLET = "WAL-SOURCE";
    private static final String DEST_WALLET = "WAL-DEST";

    private PaymentSaga saga;

    @BeforeEach
    void setUp() {
        saga = PaymentSaga.start(createdPayload(Money.of("2000.00", "INR")), "corr-1");
    }

    @Nested
    @DisplayName("starting")
    class Starting {

        @Test
        @DisplayName("copies every fact the saga will need, so no step calls back to payment-service")
        void copiesTheFacts() {
            assertThat(saga.getSagaId()).startsWith("SAGA-");
            assertThat(saga.getPaymentId()).isEqualTo(PAYMENT_ID);
            assertThat(saga.getSourceWalletId()).isEqualTo(SOURCE_WALLET);
            assertThat(saga.getDestinationWalletId()).isEqualTo(DEST_WALLET);
            assertThat(saga.amount()).isEqualTo(Money.of("2000.00", "INR"));
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.STARTED);
            assertThat(saga.getCorrelationId()).isEqualTo("corr-1");
        }

        /**
         * Nothing external has happened yet, so a failure at this point needs no compensation. The
         * flag starts {@code true} for that reason — it means "nothing is outstanding", and defaulting
         * it to {@code false} would fill the operator's uncompensated queue with sagas that never
         * touched anything.
         */
        @Test
        @DisplayName("starts with nothing outstanding")
        void startsClean() {
            assertThat(saga.hasOutstandingHold()).isFalse();
            assertThat(saga.hasOutstandingAuthorization()).isFalse();
            assertThat(saga.hasLedgerPosting()).isFalse();
            assertThat(saga.isCompensationComplete()).isTrue();
            assertThat(saga.getAttemptCount()).isEqualTo(1);
        }

        /**
         * A self-transfer nets to nothing while still producing a provider call, a ledger entry, a
         * fee and two notifications. Refused here as well as in payment-service, because the saga can
         * be started by a redelivered event from any producer version.
         */
        @Test
        @DisplayName("refuses a payment from a wallet to itself")
        void refusesSelfTransfer() {
            PaymentCreatedPayload selfPay = new PaymentCreatedPayload(
                    PAYMENT_ID, "TXN0001", "key-1", "USR-PAYER",
                    SOURCE_WALLET, SOURCE_WALLET, "USR-PAYER",
                    Money.of("100.00", "INR"), PaymentType.P2P_TRANSFER, "oops",
                    signals(), Instant.now());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PaymentSaga.start(selfPay, "corr-1"))
                    .withMessageContaining("identical");
        }

        @Test
        @DisplayName("refuses a non-positive amount")
        void refusesNonPositiveAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PaymentSaga.start(
                            createdPayload(Money.ofMinor(0L, "INR")), "corr-1"));
        }

        @Test
        @DisplayName("refuses a payment with a missing wallet")
        void refusesMissingWallet() {
            PaymentCreatedPayload noDestination = new PaymentCreatedPayload(
                    PAYMENT_ID, "TXN0001", "key-1", "USR-PAYER",
                    SOURCE_WALLET, null, "USR-PAYEE",
                    Money.of("100.00", "INR"), PaymentType.P2P_TRANSFER, "nowhere",
                    signals(), Instant.now());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PaymentSaga.start(noDestination, "corr-1"));
        }
    }

    @Nested
    @DisplayName("compensation handles")
    class Handles {

        /**
         * <strong>The reason the handle is written before the transition.</strong>
         *
         * <p>{@code reservedFunds} sets {@code holdId} and only then advances the status. If the
         * process dies between the two, the row says {@code STARTED} but reports an outstanding hold —
         * which is exactly right, because the hold really does exist at wallet-service.
         */
        @Test
        @DisplayName("a hold is reported outstanding regardless of the status")
        void holdIsReportedFromTheHandle() {
            saga.reservedFunds("HLD-001");

            assertThat(saga.hasOutstandingHold()).isTrue();
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.FUNDS_RESERVED);

            saga.holdReleased();
            assertThat(saga.hasOutstandingHold())
                    .as("cleared only once the release is confirmed")
                    .isFalse();
        }

        @Test
        @DisplayName("an authorisation is reported outstanding until it is voided")
        void authorizationIsReportedFromTheHandle() {
            advanceToRiskAssessed();
            saga.authorized("SWIFTPAY", "SWIFTPAY-AUTH-1", "SWIFTPAY-REF-1",
                    Money.of("5.00", "INR"));

            assertThat(saga.hasOutstandingAuthorization()).isTrue();
            assertThat(saga.getProviderId()).isEqualTo("SWIFTPAY");
            assertThat(saga.fee()).isEqualTo(Money.of("5.00", "INR"));

            saga.authorizationVoided();
            assertThat(saga.hasOutstandingAuthorization()).isFalse();
            assertThat(saga.getProviderId())
                    .as("the provider stays recorded: which rail was tried is audit history, "
                            + "not an outstanding effect")
                    .isEqualTo("SWIFTPAY");
        }

        @Test
        @DisplayName("a ledger posting is reported outstanding until it is reversed")
        void ledgerPostingIsReportedFromTheHandle() {
            advanceToAuthorized();
            saga.ledgerPosted("LTX-PAY-" + PAYMENT_ID);

            assertThat(saga.hasLedgerPosting()).isTrue();

            saga.ledgerReversed();
            assertThat(saga.hasLedgerPosting()).isFalse();
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        /**
         * An authorisation with no provider could not be voided and its fee could not be booked
         * against any settlement account. Refused in the aggregate as well as by the schema, because
         * a NOT NULL violation at commit is a far worse diagnostic than this message.
         */
        @Test
        @DisplayName("refuses an authorisation that names no provider")
        void refusesUnattributedAuthorisation() {
            advanceToRiskAssessed();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> saga.authorized(null, "AUTH-1", "REF-1",
                            Money.of("5.00", "INR")))
                    .withMessageContaining("name the provider");
        }

        /**
         * A fee in another currency cannot be added to this payment's books without an explicit,
         * rate-stamped conversion — and there is nowhere here to stamp a rate. Refusing is the only
         * honest option: storing the number would silently treat cents as paise.
         */
        @Test
        @DisplayName("refuses a fee denominated in another currency")
        void refusesCrossCurrencyFee() {
            advanceToRiskAssessed();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> saga.authorized("NEXUSPAY", "AUTH-1", "REF-1",
                            Money.of("5.00", "USD")))
                    .withMessageContaining("USD");
        }

        /** A provider that charges nothing is legitimate, and must not be forced to send a zero. */
        @Test
        @DisplayName("accepts an authorisation with no fee")
        void acceptsNoFee() {
            advanceToRiskAssessed();
            saga.authorized("NEXUSPAY", "AUTH-1", "REF-1", null);

            assertThat(saga.fee()).isEqualTo(Money.ofMinor(0L, "INR"));
        }
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        /**
         * Re-asserting the state a saga is already in is what a redelivered event does, so it must be
         * a no-op rather than an error — an exception here would send a perfectly ordinary duplicate
         * to the dead-letter topic.
         */
        @Test
        @DisplayName("re-asserting the current state is a no-op, not an error")
        void selfTransitionIsIdempotent() {
            saga.transitionTo(SagaStatus.STARTED);
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.STARTED);
        }

        @Test
        @DisplayName("refuses a transition the state machine does not allow")
        void refusesIllegalTransition() {
            assertThatExceptionOfType(IllegalStateTransitionException.class)
                    .isThrownBy(() -> saga.transitionTo(SagaStatus.CAPTURED));
        }

        @Test
        @DisplayName("stamps a completion time when it reaches a terminal state")
        void stampsCompletion() {
            advanceToCaptured();
            assertThat(saga.getCompletedAt()).isNull();

            saga.completed();

            assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(saga.getCompletedAt()).isNotNull();
        }

        /**
         * <strong>A recorded success must never be overwritten by a later failure report.</strong>
         *
         * <p>This is reachable in practice: a recovery sweep can pick up a saga, find it already
         * complete, and a concurrent path can still be trying to record the failure it saw before the
         * completion committed. Silently keeping the first outcome is correct, because the money has
         * moved and the completion is the true one.
         */
        @Test
        @DisplayName("a late failure cannot overwrite a completed saga, or any part of it")
        void completionWins() {
            advanceToCaptured();
            saga.completed();

            boolean applied = saga.failed("PROVIDER_TIMEOUT", "late report",
                    SagaStep.Name.AUTHORIZE, false);

            assertThat(applied)
                    .as("reported to the caller so it does not announce a second outcome")
                    .isFalse();
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(saga.getFailureCode())
                    .as("a settled payment has no failure code")
                    .isNull();
            assertThat(saga.isCompensationComplete())
                    .as("flipping this would put a perfectly settled payment into the operator's "
                            + "\"money may be stranded\" queue")
                    .isTrue();
        }

        @Test
        @DisplayName("a late rejection cannot overwrite a recorded failure")
        void firstOutcomeWins() {
            saga.reservedFunds("HLD-001");
            saga.failed("UPSTREAM_UNAVAILABLE", "wallet timed out", SagaStep.Name.RESERVE_FUNDS,
                    true);

            assertThat(saga.rejected("DECLINED_BY_ALL_PROVIDERS", "late", false)).isFalse();
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.FAILED);
            assertThat(saga.getFailureCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        }

        /**
         * Compensation can be entered from several states and re-entered by the recovery sweep after
         * a crash, so it must not throw. A saga stuck in {@code COMPENSATING} still needs to be
         * driven to a terminal state, and an exception here would stop that.
         */
        @Test
        @DisplayName("entering compensation twice is safe")
        void compensationIsReentrant() {
            saga.reservedFunds("HLD-001");
            saga.beginCompensating();
            saga.beginCompensating();

            assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        }

        @Test
        @DisplayName("compensation is not entered when nothing has happened yet")
        void compensationSkippedFromStarted() {
            saga.beginCompensating();

            assertThat(saga.getStatus())
                    .as("STARTED has no COMPENSATING edge, and tryTransitionTo must not throw")
                    .isEqualTo(SagaStatus.STARTED);
        }
    }

    @Nested
    @DisplayName("provider attempt history")
    class ProviderAttempts {

        /**
         * More than one provider on a payment is the only visible sign that a fallback happened — a
         * payment that fell back still looks like an ordinary success everywhere else.
         */
        @Test
        @DisplayName("records providers in the order they were tried")
        void recordsInOrder() {
            saga.recordProviderAttempt("SWIFTPAY");
            saga.recordProviderAttempt("NEXUSPAY");

            assertThat(saga.providerAttemptList()).containsExactly("SWIFTPAY", "NEXUSPAY");
        }

        /** A retry against the same provider is one rail tried, not two. */
        @Test
        @DisplayName("does not record the same provider twice")
        void deduplicates() {
            saga.recordProviderAttempt("SWIFTPAY");
            saga.recordProviderAttempt("SWIFTPAY");

            assertThat(saga.providerAttemptList()).containsExactly("SWIFTPAY");
        }

        @Test
        @DisplayName("ignores a blank provider id")
        void ignoresBlank() {
            saga.recordProviderAttempt("  ");
            saga.recordProviderAttempt(null);

            assertThat(saga.providerAttemptList()).isEmpty();
        }
    }

    @Nested
    @DisplayName("after capture")
    class AfterCapture {

        /**
         * The check the compensator consults before doing anything.
         *
         * <p>Once this is true the money has moved, and undoing the reservation would refund money
         * that was correctly taken. The state machine forbids the transition as well; this is the
         * cheaper guard that stops the attempt being made.
         */
        @Test
        @DisplayName("reports the funds as moved, so compensation is refused")
        void reportsFundsCaptured() {
            advanceToAuthorized();
            saga.ledgerPosted("LTX-1");
            assertThat(saga.fundsCaptured()).isFalse();

            saga.capturedFunds();
            assertThat(saga.fundsCaptured()).isTrue();

            saga.completed();
            assertThat(saga.fundsCaptured()).isTrue();
        }
    }

    @Nested
    @DisplayName("recovery")
    class Recovery {

        /**
         * The counter is what makes a saga that keeps being picked up and never progresses visible.
         * Without it, a permanently stuck payment looks identical to one that has just started.
         */
        @Test
        @DisplayName("counts every re-entry by the sweep")
        void countsAttempts() {
            saga.recordRecoveryAttempt();
            saga.recordRecoveryAttempt();

            assertThat(saga.getAttemptCount()).isEqualTo(3);
        }
    }

    /**
     * A failure reason from a provider is outside our control and can be arbitrarily long.
     * Truncating beats failing the write that records why a payment failed — which would leave the
     * saga with no explanation at all.
     */
    @Test
    @DisplayName("truncates an over-long failure reason instead of failing the write")
    void truncatesFailureReason() {
        saga.reservedFunds("HLD-001");
        saga.failed("PROVIDER_ERROR", "x".repeat(2_000), SagaStep.Name.AUTHORIZE, true);

        assertThat(saga.getFailureReason()).hasSizeLessThanOrEqualTo(512);
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.FAILED);
    }

    @Nested
    @DisplayName("held for fraud review")
    class HeldForReview {

        @Test
        @DisplayName("parks the payment and records why, keeping the funds reserved")
        void parksWithTheVerdict() {
            saga.reservedFunds("HLD-001");
            saga.heldForReview(RiskLevel.HIGH, 0.87, FraudDecision.HOLD, "FRA-1", false);

            assertThat(saga.getStatus()).isEqualTo(SagaStatus.RISK_HELD);
            assertThat(saga.isParked()).isTrue();
            assertThat(saga.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(saga.getFraudDecision()).isEqualTo(FraudDecision.HOLD);
            assertThat(saga.getRiskAssessmentId()).isEqualTo("FRA-1");
            assertThat(saga.getRiskHeldAt()).isNotNull();
            assertThat(saga.hasOutstandingHold())
                    .as("the customer's funds stay reserved while they wait")
                    .isTrue();
        }

        /**
         * The verdict is recorded before the transition, so a crash between the two leaves a
         * recoverable saga rather than one parked with no record of why.
         */
        @Test
        @DisplayName("records a degraded verdict as such")
        void recordsDegradedVerdict() {
            saga.reservedFunds("HLD-001");
            saga.heldForReview(RiskLevel.HIGH, 0.0, FraudDecision.HOLD, null, true);

            assertThat(saga.isRiskDegraded())
                    .as("this service chose to act on a verdict reached without the model")
                    .isTrue();
            assertThat(saga.getRiskAssessmentId()).isNull();
        }

        /**
         * {@code riskHeldAt} is how long the customer has been waiting. Re-parking must not reset it,
         * or a payment held for a day would report as freshly held after any redelivery.
         */
        @Test
        @DisplayName("does not reset the wait when re-applied")
        void doesNotResetTheWait() {
            saga.reservedFunds("HLD-001");
            saga.heldForReview(RiskLevel.HIGH, 0.87, FraudDecision.HOLD, "FRA-1", false);
            Instant firstHeld = saga.getRiskHeldAt();

            saga.heldForReview(RiskLevel.HIGH, 0.87, FraudDecision.HOLD, "FRA-1", false);

            assertThat(saga.getRiskHeldAt()).isEqualTo(firstHeld);
        }

        @Test
        @DisplayName("reports how long the customer has been waiting")
        void reportsTheWait() {
            saga.reservedFunds("HLD-001");
            assertThat(saga.reviewWait()).isEmpty();

            saga.heldForReview(RiskLevel.HIGH, 0.87, FraudDecision.HOLD, "FRA-1", false);
            assertThat(saga.reviewWait()).isPresent();
        }

        /**
         * A release rejoins the normal path, and the machine's verdict survives it.
         *
         * <p>Rewriting {@code fraudDecision} to APPROVE would erase the fact that the engine stopped
         * this payment, which is exactly what somebody reviewing a released fraud needs to see.
         */
        @Test
        @DisplayName("a release resumes the payment without erasing the original verdict")
        void releaseKeepsTheVerdict() {
            saga.reservedFunds("HLD-001");
            saga.heldForReview(RiskLevel.HIGH, 0.87, FraudDecision.HOLD, "FRA-1", false);

            assertThat(saga.riskReviewReleased("USR-ANALYST", "Confirmed by phone")).isTrue();

            assertThat(saga.getStatus()).isEqualTo(SagaStatus.RISK_ASSESSED);
            assertThat(saga.isParked()).isFalse();
            assertThat(saga.getFraudDecision())
                    .as("the engine's verdict is a fact and is not rewritten by the override")
                    .isEqualTo(FraudDecision.HOLD);
            assertThat(saga.getRiskReviewedBy()).isEqualTo("USR-ANALYST");
            assertThat(saga.getRiskReviewReason()).isEqualTo("Confirmed by phone");
            assertThat(saga.getRiskReviewedAt()).isNotNull();
        }

        @Test
        @DisplayName("a refusal attributes the decision without settling the saga")
        void refusalOnlyAttributes() {
            saga.reservedFunds("HLD-001");
            saga.heldForReview(RiskLevel.HIGH, 0.87, FraudDecision.HOLD, "FRA-1", false);

            assertThat(saga.riskReviewRefused("USR-ANALYST", "Customer denies it")).isTrue();

            assertThat(saga.getStatus())
                    .as("the caller must still compensate before recording the outcome")
                    .isEqualTo(SagaStatus.RISK_HELD);
            assertThat(saga.getRiskReviewedBy()).isEqualTo("USR-ANALYST");
        }

        /**
         * Kafka delivers at least once, so a decision will be seen twice. The second must change
         * nothing rather than moving a payment that has already advanced.
         */
        @Test
        @DisplayName("a redelivered decision on a payment that is no longer held does nothing")
        void redeliveredDecisionIsANoOp() {
            saga.reservedFunds("HLD-001");
            saga.heldForReview(RiskLevel.HIGH, 0.87, FraudDecision.HOLD, "FRA-1", false);
            saga.riskReviewReleased("USR-ANALYST", "Confirmed by phone");
            saga.authorized("SWIFTPAY", "AUTH-1", "REF-1", Money.of("5.00", "INR"));

            assertThat(saga.riskReviewReleased("USR-OTHER", "second delivery")).isFalse();
            assertThat(saga.riskReviewRefused("USR-OTHER", "second delivery")).isFalse();

            assertThat(saga.getStatus()).isEqualTo(SagaStatus.AUTHORIZED);
            assertThat(saga.getRiskReviewedBy())
                    .as("the first decision stands")
                    .isEqualTo("USR-ANALYST");
        }

        /** A decision on a payment that was never held must not park or move it. */
        @Test
        @DisplayName("a decision on a payment that was never held does nothing")
        void decisionOnUnheldPaymentIsANoOp() {
            advanceToRiskAssessed();

            assertThat(saga.riskReviewReleased("USR-ANALYST", "review only")).isFalse();
            assertThat(saga.getStatus()).isEqualTo(SagaStatus.RISK_ASSESSED);
            assertThat(saga.getRiskReviewedBy()).isNull();
        }
    }

    /**
     * The risk context must survive a crash, because a resumed saga no longer has the event that
     * carried it. Re-deriving it later would score a situation the customer was never in.
     */
    @Test
    @DisplayName("retains the risk context the payment was made in")
    void retainsRiskContext() {
        PaymentSaga withSignals = PaymentSaga.start(createdPayload(Money.of("100.00", "INR")),
                "corr-1", "{\"countryCode\":\"IN\"}");

        assertThat(withSignals.getRiskSignals()).isEqualTo("{\"countryCode\":\"IN\"}");
    }

    // ---------------------------------------------------------------- helpers

    private void advanceToRiskAssessed() {
        saga.reservedFunds("HLD-001");
        saga.recordRiskVerdict(RiskLevel.LOW, 0d, FraudDecision.APPROVE);
    }

    private void advanceToAuthorized() {
        advanceToRiskAssessed();
        saga.authorized("SWIFTPAY", "SWIFTPAY-AUTH-1", "SWIFTPAY-REF-1", Money.of("5.00", "INR"));
    }

    private void advanceToCaptured() {
        advanceToAuthorized();
        saga.ledgerPosted("LTX-PAY-" + PAYMENT_ID);
        saga.capturedFunds();
    }

    private static PaymentCreatedPayload createdPayload(Money amount) {
        return new PaymentCreatedPayload(
                PAYMENT_ID, "TXN0001", "key-1", "USR-PAYER",
                SOURCE_WALLET, DEST_WALLET, "USR-PAYEE",
                amount, PaymentType.P2P_TRANSFER, "Dinner split",
                signals(), Instant.now());
    }

    private static RiskSignals signals() {
        return new RiskSignals(null, null, "203.0.113.7", "curl/8.4.0",
                null, null, null, null, Channel.API, null, null);
    }
}
