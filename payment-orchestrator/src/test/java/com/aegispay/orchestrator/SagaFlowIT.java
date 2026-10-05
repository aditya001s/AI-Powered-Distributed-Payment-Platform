package com.aegispay.orchestrator;

import com.aegispay.common.events.KafkaTopics;
import com.aegispay.orchestrator.provider.NexusPayProvider;
import com.aegispay.orchestrator.provider.PayBridgeProvider;
import com.aegispay.orchestrator.provider.SimulationControls.FaultMode;
import com.aegispay.orchestrator.provider.SwiftPayProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end saga behaviour: routing, fallback, retry, breaking, compensation and recovery.
 *
 * <p>Every test here drives the orchestrator the only way production does — by publishing
 * {@code payment-created} onto a real broker — and then asserts on three surfaces: the committed
 * saga row, the wallet and ledger state inside {@link StubDownstreams}, and the outbox. A saga can
 * fail in ways where any two agree and the third does not: a {@code COMPLETED} row against a
 * reservation still held, a captured payment with no ledger posting, or a {@code FAILED} row for money
 * that actually moved.
 *
 * <p>The fault injection is deterministic throughout. A random failure rate would make every one of
 * these tests flaky and would leave a genuine regression indistinguishable from an unlucky run.
 */
class SagaFlowIT extends AbstractOrchestratorIntegrationTest {

    // ============================================================ happy path

    @Nested
    @DisplayName("a payment that works first time")
    class HappyPath {

        /**
         * The full sequence, and the ordering that matters within it.
         *
         * <p>Reserve, assess, authorise, <strong>post the ledger, then capture</strong>. Money never
         * moves before it has been recorded, so a crash between the two leaves the books ahead of the
         * wallets — detectable by reconciliation and undoable by a reversal — rather than money moved
         * with no record of why.
         */
        @Test
        @DisplayName("routes, posts the ledger, captures and completes")
        void completesPayment() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");

            // The cheapest eligible rail, because everything is healthy.
            assertThat(sagaProvider(paymentId)).isEqualTo(SwiftPayProvider.ID);
            assertThat(sagaProviderAttempts(paymentId))
                    .as("one provider means no fallback was needed")
                    .isEqualTo(SwiftPayProvider.ID);

            // 25 bp of ₹2,000 = ₹5.00, and it is the provider's number, not one we recomputed.
            assertThat(sagaFeeMinor(paymentId)).isEqualTo(5 * RUPEE);

            // The money actually moved, and nothing is left reserved.
            assertThat(DOWNSTREAM.balanceMinor(source)).isEqualTo(3000 * RUPEE);
            assertThat(DOWNSTREAM.balanceMinor(destination)).isEqualTo(2000 * RUPEE);
            assertThat(DOWNSTREAM.heldMinor(source)).isZero();
            assertThat(DOWNSTREAM.holdState(paymentId)).isEqualTo("CAPTURED");

            // The books were written, and before the capture.
            assertThat(DOWNSTREAM.hasPosting("LTX-PAY-" + paymentId)).isTrue();
            assertThat(sagaLedgerTransactionId(paymentId)).isEqualTo("LTX-PAY-" + paymentId);
            assertThat(DOWNSTREAM.postCalls()).isEqualTo(1);

            // Nothing outstanding, so nothing for an operator to chase.
            assertThat(sagaCompensationComplete(paymentId)).isTrue();
            assertThat(DOWNSTREAM.reverseCalls()).isZero();
            assertThat(DOWNSTREAM.releaseCalls()).isZero();
        }

        /**
         * The step log is what an incident is reconstructed from, so it has to be written even when
         * nothing goes wrong — a log that only appears on failure is a log nobody trusts.
         */
        @Test
        @DisplayName("records every step it ran")
        void recordsTheStepLog() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitTerminalSaga(paymentId);

            for (String step : new String[]{"RESERVE_FUNDS", "ASSESS_RISK", "AUTHORIZE",
                    "POST_LEDGER", "CAPTURE_FUNDS", "COMPLETE"}) {
                assertThat(countSteps(paymentId, step))
                        .as("step %s must be recorded", step)
                        .isEqualTo(1);
            }
            assertThat(countProviderAttempts(paymentId, "APPROVED")).isEqualTo(1);
        }

        /**
         * The outcome has to be announced through the outbox, in the same transaction as the state
         * change. Publishing directly would either lose the event on a crash after commit — leaving
         * payment-service permanently showing a payment as in flight — or announce a completion that
         * then rolled back.
         */
        @Test
        @DisplayName("announces approval and completion through the outbox, and drains them")
        void announcesTheOutcome() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitTerminalSaga(paymentId);

            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_APPROVED, paymentId)).isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_COMPLETED, paymentId)).isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_FAILED, paymentId))
                    .as("a completed payment must never announce a failure")
                    .isZero();

            long total = countOutboxEventsTotal();
            awaitUntil("the outbox drainer to publish every event", Duration.ofSeconds(30),
                    () -> countPublishedOutboxEvents() == total);
        }

        /** Every internal call must carry a service token, or it would be refused in production. */
        @Test
        @DisplayName("authenticates every call to wallet-service and ledger-service")
        void authenticatesInternalCalls() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            awaitTerminalSaga(submitPayment(payer, source, destination, "2000.00"));

            assertThat(DOWNSTREAM.requestsWithoutAuth()).isZero();
        }

        /**
         * Kafka delivers at least once, so this will happen. Two sagas for one payment would reserve
         * the customer's funds twice and charge two providers; the unique constraint on
         * {@code payment_id} is what makes that impossible.
         */
        @Test
        @DisplayName("a redelivered payment-created starts no second saga")
        void redeliveryStartsNoSecondSaga() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitTerminalSaga(paymentId);

            // The same payment announced again, as a rebalance or a retry would.
            submitPaymentAgain(paymentId, payer, source, destination, "2000.00");
            sleepBriefly();

            assertThat(countSagas()).isEqualTo(1);
            assertThat(DOWNSTREAM.balanceMinor(source))
                    .as("a second saga would have moved the money twice")
                    .isEqualTo(3000 * RUPEE);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_COMPLETED, paymentId)).isEqualTo(1);
        }
    }

    // ================================================================ routing

    @Nested
    @DisplayName("routing")
    class Routing {

        /**
         * Capability filtering, end to end.
         *
         * <p>₹1,20,000 is above SwiftPay's ceiling, so the cheapest rail is not merely outranked — it
         * is never asked. That matters because asking it would produce a capability error the router
         * caused itself.
         */
        @Test
        @DisplayName("skips a provider that cannot process the amount")
        void skipsIneligibleProvider() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 500_000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "120000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaProvider(paymentId)).isEqualTo(NexusPayProvider.ID);
            assertThat(sagaProviderAttempts(paymentId))
                    .as("SwiftPay was excluded before scoring, so it was never tried")
                    .doesNotContain(SwiftPayProvider.ID);
        }

        /**
         * The operator kill switch, exercised through the API it is actually operated from.
         *
         * <p>It persists, so it survives a rolling deploy — an in-memory switch would be silently
         * undone by the next restart, at the worst possible moment.
         */
        @Test
        @DisplayName("skips a provider an operator disabled, and says who and why")
        void skipsDisabledProvider() {
            Response<String> disabled = post(
                    "/api/orchestration/providers/" + SwiftPayProvider.ID + "/disable",
                    java.util.Map.of("reason", "Elevated timeouts on their status page"),
                    adminToken(), String.class);
            assertThat(disabled.status().is2xxSuccessful()).isTrue();
            assertThat(providerEnabled(SwiftPayProvider.ID))
                    .as("the decision must be persisted, or a deploy would undo the mitigation")
                    .isFalse();

            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaProvider(paymentId)).isEqualTo(NexusPayProvider.ID);
        }

        /** Re-enabling clears the switch and resets the breaker's window. */
        @Test
        @DisplayName("re-enabling a provider returns it to routing")
        void reEnablingRestoresRouting() {
            post("/api/orchestration/providers/" + SwiftPayProvider.ID + "/disable",
                    java.util.Map.of("reason", "temporary"), adminToken(), String.class);
            post("/api/orchestration/providers/" + SwiftPayProvider.ID + "/enable",
                    null, adminToken(), String.class);

            assertThat(providerEnabled(SwiftPayProvider.ID)).isTrue();

            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            awaitTerminalSaga(submitPayment(payer, source, destination, "2000.00"));
            assertThat(sagaProvider(newestPaymentProvider())).isNotNull();
        }
    }

    // =============================================================== fallback

    @Nested
    @DisplayName("fallback")
    class Fallback {

        /**
         * <strong>A decline is a working provider saying no.</strong>
         *
         * <p>SwiftPay refuses round amounts at or above ₹25,000, so the saga must try the next rail
         * rather than failing the payment — rails genuinely differ in what they accept. The payment
         * ends up dearer, which is the trade the fee weighting exists to make: paying more to complete
         * a payment beats not completing it.
         */
        @Test
        @DisplayName("falls back to the next rail when the first declines")
        void fallsBackOnDecline() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 60_000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            // ₹25,000 exactly: above SwiftPay's threshold and a round thousand, so it declines.
            String paymentId = submitPayment(payer, source, destination, "25000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaProvider(paymentId)).isEqualTo(NexusPayProvider.ID);
            assertThat(sagaProviderAttempts(paymentId))
                    .as("both rails must appear, in the order they were tried")
                    .isEqualTo(SwiftPayProvider.ID + "," + NexusPayProvider.ID);

            assertThat(countProviderAttempts(paymentId, "DECLINED")).isEqualTo(1);
            assertThat(countProviderAttempts(paymentId, "APPROVED")).isEqualTo(1);

            // 55 bp of ₹25,000 = ₹137.50, NexusPay's fee rather than SwiftPay's.
            assertThat(sagaFeeMinor(paymentId)).isEqualTo(13_750L);
            assertThat(DOWNSTREAM.balanceMinor(destination)).isEqualTo(25_000 * RUPEE);
        }

        /**
         * A decline must not count against the provider's health.
         *
         * <p>Counting it would take a perfectly healthy rail out of service because a run of customers
         * happened to hit its velocity rule — and every subsequent payment would then be routed to a
         * dearer provider for no reason.
         */
        @Test
        @DisplayName("a decline leaves the declining provider's breaker closed")
        void declineDoesNotBreakTheProvider() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 200_000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            for (int payment = 0; payment < 4; payment++) {
                awaitTerminalSaga(submitPayment(payer, source, destination, "25000.00"));
            }

            assertThat(gateway.breakerState(SwiftPayProvider.ID))
                    .as("four declines in a row are four correct answers")
                    .isEqualTo(CircuitBreaker.State.CLOSED);
        }

        /**
         * A fault, unlike a decline, is the provider failing to answer. The saga falls back, and this
         * one <em>does</em> count against the provider.
         */
        @Test
        @DisplayName("falls back to the next rail when the first is unreachable")
        void fallsBackOnFault() {
            arm(SwiftPayProvider.ID, FaultMode.UNREACHABLE, 4);

            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaProvider(paymentId)).isEqualTo(NexusPayProvider.ID);
            assertThat(countProviderAttempts(paymentId, "FAILED")).isEqualTo(1);
            assertThat(DOWNSTREAM.balanceMinor(destination)).isEqualTo(2000 * RUPEE);
        }

        /**
         * <strong>The case that makes naive compensation wrong.</strong>
         *
         * <p>SwiftPay authorises the charge and then fails to answer. The saga holds no authorisation
         * id, so there is nothing obvious to void — and without the lookup by idempotency key the
         * charge would sit at the rail forever, referenced by nothing and invisible to reconciliation.
         *
         * <p>So the saga looks for it, voids it, and only then falls back. The payment still succeeds,
         * on a different rail, with nothing left behind on the first.
         */
        @Test
        @DisplayName("voids the charge a timed-out provider was left holding, then falls back")
        void voidsAnOrphanedAuthorization() {
            arm(SwiftPayProvider.ID, FaultMode.TIMEOUT, 1);

            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaProvider(paymentId)).isEqualTo(NexusPayProvider.ID);

            assertThat(simulated(SwiftPayProvider.ID).outstandingAuthorizationCount())
                    .as("the orphaned charge must have been discovered and released")
                    .isZero();
            assertThat(countProviderAttempts(paymentId, "TIMEOUT")).isEqualTo(1);
            assertThat(countProviderAttempts(paymentId, "VOIDED"))
                    .as("the void must be recorded, or nobody could tell it happened")
                    .isEqualTo(1);
        }

        /**
         * Every rail declining is a business outcome, not a fault.
         *
         * <p>Nothing was charged, so the customer can be told plainly rather than asked to retry into
         * the same answer. That is why it is {@code REJECTED} and not {@code FAILED} — the distinction
         * reaches the customer as "this will not work" versus "try again".
         */
        @Test
        @DisplayName("rejects the payment when every rail declines")
        void rejectsWhenEveryRailDeclines() {
            simulated(SwiftPayProvider.ID).controls().injectFaultIndefinitely(FaultMode.DECLINE);
            simulated(NexusPayProvider.ID).controls().injectFaultIndefinitely(FaultMode.DECLINE);
            simulated(PayBridgeProvider.ID).controls().injectFaultIndefinitely(FaultMode.DECLINE);

            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId))
                    .as("a refusal is a decision, not a fault")
                    .isEqualTo("REJECTED");
            assertThat(sagaFailureCode(paymentId)).isEqualTo("DECLINED_BY_ALL_PROVIDERS");

            // Nothing was charged and nothing is left reserved.
            assertThat(DOWNSTREAM.balanceMinor(source)).isEqualTo(5000 * RUPEE);
            assertThat(DOWNSTREAM.heldMinor(source)).isZero();
            assertThat(sagaCompensationComplete(paymentId)).isTrue();
            assertThat(DOWNSTREAM.postCalls())
                    .as("nothing was authorised, so nothing may be booked")
                    .isZero();

            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_REJECTED, paymentId)).isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_FAILED, paymentId)).isZero();
        }

        /**
         * Every rail broken is a fault, not a decision. Reported as {@code FAILED} so the customer is
         * told to try again — the answer may well be different in a minute.
         */
        @Test
        @DisplayName("fails the payment when every rail is broken")
        void failsWhenEveryRailIsBroken() {
            for (String provider : new String[]{SwiftPayProvider.ID, NexusPayProvider.ID,
                    PayBridgeProvider.ID}) {
                simulated(provider).controls().injectFaultIndefinitely(FaultMode.UPSTREAM_ERROR);
            }

            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("FAILED");
            assertThat(sagaFailureCode(paymentId)).isEqualTo("ALL_PROVIDERS_UNAVAILABLE");
            assertThat(DOWNSTREAM.heldMinor(source))
                    .as("the reservation must be released, or the customer's funds are frozen")
                    .isZero();
            assertThat(sagaCompensationComplete(paymentId)).isTrue();
        }
    }

    // ======================================================= circuit breaking

    @Nested
    @DisplayName("circuit breaking")
    class Breaking {

        /**
         * The breaker's value is not graceful failure, it is <em>not asking</em>.
         *
         * <p>Once open, a payment routed near that provider is refused before any time or thread is
         * spent, so the saga falls back immediately instead of waiting out another timeout. The
         * observable consequence is that later payments stop reaching the broken rail at all.
         */
        @Test
        @DisplayName("stops routing to a provider that keeps failing")
        void breakerExcludesAFailingProvider() {
            var swiftPay = simulated(SwiftPayProvider.ID);
            swiftPay.controls().injectFaultIndefinitely(FaultMode.UNREACHABLE);

            // Train the breaker at its production minimum directly. Driving these samples through
            // complete sagas would not be deterministic: after the first recorded SwiftPay failure,
            // adaptive routing correctly ranks a proven healthy rail ahead of it.
            for (int call = 0; call < 8; call++) {
                String suffix = Integer.toString(call);
                var request = new com.aegispay.orchestrator.provider.PaymentProvider
                        .AuthorizationRequest(
                        "PAY-BREAKER-" + suffix,
                        "TXN-BREAKER-" + suffix,
                        com.aegispay.common.money.Money.of("2000.00", CURRENCY),
                        com.aegispay.common.domain.SharedEnums.PaymentType.P2P_TRANSFER,
                        "WAL-BREAKER-SOURCE",
                        "WAL-BREAKER-DESTINATION",
                        "Circuit breaker integration test",
                        "idem-breaker-" + suffix);
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> gateway.authorize(swiftPay, request))
                        .isInstanceOf(com.aegispay.orchestrator.provider.ProviderFaultException.class);
            }

            assertThat(gateway.breakerState(SwiftPayProvider.ID))
                    .as("a rail that never answers must be taken out of rotation")
                    .isEqualTo(CircuitBreaker.State.OPEN);

            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            // A real saga must now exclude the rail before contacting it.
            swiftPay.controls().clearFaults();
            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaProviderAttempts(paymentId))
                    .as("an open breaker means the provider is not offered to the saga at all")
                    .isEqualTo(NexusPayProvider.ID);
        }

        /**
         * Reported so an operator can see it. Three separate signals — the kill switch, the last probe
         * and the live breaker — plus the combined verdict, because combining them correctly in one's
         * head mid-incident is exactly what goes wrong.
         */
        @Test
        @DisplayName("reports provider health with the breaker state that routing actually reads")
        void reportsProviderHealth() {
            Response<String> response = get("/api/orchestration/providers", adminToken(),
                    String.class);

            assertThat(response.status().is2xxSuccessful()).isTrue();
            assertThat(response.body())
                    .contains(SwiftPayProvider.ID)
                    .contains(NexusPayProvider.ID)
                    .contains(PayBridgeProvider.ID)
                    .contains("circuitBreakerState")
                    .contains("routable");
        }
    }

    // =========================================================== compensation

    @Nested
    @DisplayName("compensation")
    class Compensation {

        /**
         * <strong>The most important test in the class.</strong>
         *
         * <p>The capture fails after the provider authorised and the ledger was posted. Three side
         * effects are outstanding, and all three must be undone in reverse order of creation:
         * the posting reversed, the authorisation voided, the reservation released. Miss any one and
         * either the customer's money is frozen, a charge sits on a rail, or the books claim a transfer
         * that never happened.
         */
        @Test
        @DisplayName("reverses the ledger, voids the authorisation and releases the funds")
        void compensatesEverything() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);
            DOWNSTREAM.failNextCapture(503);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("FAILED");

            // No money moved.
            assertThat(DOWNSTREAM.balanceMinor(source)).isEqualTo(5000 * RUPEE);
            assertThat(DOWNSTREAM.balanceMinor(destination)).isZero();

            // The reservation was released, so the customer can spend again.
            assertThat(DOWNSTREAM.heldMinor(source)).isZero();
            assertThat(DOWNSTREAM.holdState(paymentId)).isEqualTo("RELEASED");

            // The posting was reversed rather than deleted: the journal is append-only.
            assertThat(DOWNSTREAM.postingReversed("LTX-PAY-" + paymentId)).isTrue();

            // The charge is not left sitting on the rail.
            assertThat(simulated(sagaProviderOrSwiftPay(paymentId)).outstandingAuthorizationCount())
                    .isZero();

            // Every handle cleared, so nothing reads as outstanding.
            assertThat(sagaHoldId(paymentId)).isNull();
            assertThat(sagaAuthorizationId(paymentId)).isNull();
            assertThat(sagaLedgerTransactionId(paymentId)).isNull();
            assertThat(sagaCompensationComplete(paymentId)).isTrue();
        }

        /**
         * Insufficient funds is a decline, not a fault. No reservation was taken, so there is nothing
         * to compensate, and no provider was ever asked — charging a rail for money the customer does
         * not have wastes a call and produces an avoidable decline.
         */
        @Test
        @DisplayName("a declined reservation reaches no provider and needs no compensation")
        void declinedReservationStopsImmediately() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 100 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("REJECTED");
            assertThat(sagaFailureCode(paymentId)).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(countProviderAttempts(paymentId, "APPROVED")).isZero();
            assertThat(DOWNSTREAM.postCalls()).isZero();
            assertThat(sagaCompensationComplete(paymentId)).isTrue();
        }

        /**
         * A failed reserve may be a lost <em>response</em> rather than a lost request: the hold can
         * exist while the acknowledgement was dropped, and from here the two are indistinguishable.
         * Release is idempotent and tolerates a hold that never existed, so the harmless call beats
         * the optimistic assumption — whose downside is frozen customer money.
         */
        @Test
        @DisplayName("attempts a release even when the reserve itself failed")
        void releasesEvenWhenReserveFailed() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);
            DOWNSTREAM.failNextReserve(503);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("FAILED");
            assertThat(DOWNSTREAM.releaseCalls())
                    .as("assuming nothing was reserved would strand funds whenever that guess "
                            + "was wrong")
                    .isEqualTo(1);
        }

        /**
         * When compensation itself fails, the saga must say so rather than claiming it cleaned up.
         *
         * <p>{@code compensationComplete = false} is what puts the payment in front of an operator.
         * Silently recording success would leave a customer's funds reserved with nothing scheduled to
         * release them and nothing indicating a problem.
         */
        @Test
        @DisplayName("records that compensation did not complete when the release fails")
        void reportsIncompleteCompensation() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);
            DOWNSTREAM.failNextCapture(503);
            DOWNSTREAM.rejectNextRelease();

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("FAILED");
            assertThat(sagaCompensationComplete(paymentId))
                    .as("this flag is the only thing that puts the payment in front of a human")
                    .isFalse();

            // And it is surfaced, not merely logged.
            Response<String> uncompensated = get("/api/orchestration/sagas/uncompensated",
                    adminToken(), String.class);
            assertThat(uncompensated.body()).contains(paymentId);

            // The hold survives, which is exactly what the wallet's expiry sweeper is for.
            assertThat(DOWNSTREAM.heldMinor(source)).isEqualTo(2000 * RUPEE);
        }

        /**
         * A failed ledger posting happens before any money moves, so nothing has to be reversed — but
         * the authorisation and the reservation still do.
         */
        @Test
        @DisplayName("undoes the authorisation and the reservation when the ledger refuses")
        void compensatesAFailedPosting() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);
            DOWNSTREAM.failNextPosting(503);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("FAILED");
            assertThat(DOWNSTREAM.balanceMinor(source)).isEqualTo(5000 * RUPEE);
            assertThat(DOWNSTREAM.heldMinor(source)).isZero();
            assertThat(DOWNSTREAM.reverseCalls())
                    .as("nothing was posted, so nothing may be reversed")
                    .isZero();
            assertThat(sagaAuthorizationId(paymentId)).isNull();
            assertThat(sagaCompensationComplete(paymentId)).isTrue();
        }
    }

    // ================================================================ recovery

    @Nested
    @DisplayName("recovery")
    class Recovery {

        /**
         * The sweep exists because a saga is driven by a thread holding no transaction across four
         * network calls. If that thread dies — a crash, a pod eviction, a redeploy mid-payment — the
         * saga is left mid-flight with a reservation and possibly an authorisation outstanding, and
         * nothing scheduled to resolve them.
         *
         * <p>Simulated here by writing a saga that has not changed state for longer than the stale
         * threshold, which is exactly the state a crash leaves behind.
         */
        @Test
        @DisplayName("resumes a saga whose orchestrating thread disappeared")
        void resumesAnAbandonedSaga() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            // A payment that gets as far as FUNDS_RESERVED and then stops, because the provider
            // never answers and the process is imagined to die.
            simulated(SwiftPayProvider.ID).controls().injectFaultIndefinitely(FaultMode.UNREACHABLE);
            simulated(NexusPayProvider.ID).controls().injectFaultIndefinitely(FaultMode.UNREACHABLE);
            simulated(PayBridgeProvider.ID).controls().injectFaultIndefinitely(FaultMode.UNREACHABLE);
            // Preserve the real wallet hold when the initial failed run compensates. Rewinding only
            // the saga row after a successful release would fabricate a hold that the wallet does
            // not have, and recovery would correctly fail its later capture.
            DOWNSTREAM.rejectNextRelease();

            String paymentId = submitPayment(payer, source, destination, "2000.00");
            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("FAILED");
            assertThat(DOWNSTREAM.holdState(paymentId)).isEqualTo("ACTIVE");

            // Rewind the persisted saga to the state in which its owner disappeared. Its original
            // hold handle and the matching active wallet reservation are deliberately retained.
            jdbc.update("""
                    UPDATE payment_sagas
                       SET status = 'FUNDS_RESERVED', current_step = 'RESERVE_FUNDS',
                           failure_code = NULL, failure_reason = NULL, failed_step = NULL,
                           completed_at = NULL, updated_at = now() - interval '10 minutes'
                     WHERE payment_id = ?
                    """, paymentId);

            for (var provider : router.allProviders()) {
                simulated(provider.id()).controls().clearFaults();
                gateway.reset(provider.id());
            }

            Response<String> swept = post("/api/orchestration/recovery/sweep", null, adminToken(),
                    String.class);
            assertThat(swept.status().is2xxSuccessful()).isTrue();

            assertThat(awaitTerminalSaga(paymentId))
                    .as("the sweep must drive the saga to a terminal state, not merely log it")
                    .isEqualTo("COMPLETED");

            // Resumed from where it was, not restarted: the reservation was reused.
            assertThat(DOWNSTREAM.reserveCalls())
                    .as("a restart would have reserved the customer's funds a second time")
                    .isEqualTo(1);
            assertThat(DOWNSTREAM.balanceMinor(destination)).isEqualTo(2000 * RUPEE);
        }

        /**
         * A saga that is still progressing must not be disturbed. Staleness is measured on the last
         * state change rather than on age, precisely so a payment waiting on a slow provider is not
         * compensated out from under a charge that is about to be authorised.
         */
        @Test
        @DisplayName("leaves a saga that is still progressing alone")
        void doesNotTouchAProgressingSaga() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitTerminalSaga(paymentId);

            // A fresh mid-flight saga: recently updated, so out of scope for the sweep.
            jdbc.update("""
                    UPDATE payment_sagas
                       SET status = 'FUNDS_RESERVED', completed_at = NULL, updated_at = now()
                     WHERE payment_id = ?
                    """, paymentId);

            post("/api/orchestration/recovery/sweep", null, adminToken(), String.class);
            sleepBriefly();

            assertThat(sagaStatus(paymentId))
                    .as("a saga updated a moment ago is still someone's responsibility")
                    .isEqualTo("FUNDS_RESERVED");
        }
    }

    // ============================================================ diagnostics

    @Nested
    @DisplayName("the operational API")
    class OperationalApi {

        @Test
        @DisplayName("returns a saga in full, with its step log and provider calls")
        void returnsASagaInFull() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 60_000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            // A payment that falls back, so there is more than one provider call to report.
            String paymentId = submitPayment(payer, source, destination, "25000.00");
            awaitTerminalSaga(paymentId);

            Response<String> response = get("/api/orchestration/payments/" + paymentId,
                    adminToken(), String.class);

            assertThat(response.status().is2xxSuccessful()).isTrue();
            assertThat(response.body())
                    .contains(paymentId)
                    .contains("\"steps\"")
                    .contains("\"providerCalls\"")
                    .contains(SwiftPayProvider.ID)
                    .contains(NexusPayProvider.ID);
        }

        /**
         * Exists because "why did this payment go to the expensive provider" is otherwise answerable
         * only by reading the scoring code and guessing at the inputs.
         */
        @Test
        @DisplayName("explains how a hypothetical charge would be routed")
        void explainsRouting() {
            Response<String> response = get(
                    "/api/orchestration/providers/routing?amount=120000.00&currency=INR",
                    adminToken(), String.class);

            assertThat(response.status().is2xxSuccessful()).isTrue();
            assertThat(response.body())
                    .contains("exclusionReason")
                    .as("the explanation must say what excluded SwiftPay, not merely that it was")
                    .contains("cannot process");
        }

        /**
         * A payment that fell back looks like an ordinary success everywhere else, so the fallback
         * count is the only visible sign that routing did its job.
         */
        @Test
        @DisplayName("counts payments that needed a fallback")
        void reportsFallbackCount() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 60_000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            awaitTerminalSaga(submitPayment(payer, source, destination, "25000.00"));

            Response<String> stats = get("/api/orchestration/stats", adminToken(), String.class);

            assertThat(stats.status().is2xxSuccessful()).isTrue();
            assertThat(stats.body()).contains("\"fallbackCount\":1");
        }

        @Test
        @DisplayName("404s for a payment with no saga")
        void unknownPayment() {
            assertThat(get("/api/orchestration/payments/PAY-NOPE", adminToken(), String.class)
                    .status().value()).isEqualTo(404);
        }
    }

    // ========================================================== authorisation

    @Nested
    @DisplayName("authentication and authorisation")
    class Security {

        @Test
        @DisplayName("rejects a request with no token")
        void rejectsMissingToken() {
            assertThat(get("/api/orchestration/providers", null, String.class).status().value())
                    .isEqualTo(401);
        }

        /**
         * The saga exposes counterparties, amounts and provider routing for every payment on the
         * platform. Without method security a customer token would read all of it.
         */
        @Test
        @DisplayName("denies the operational API to a customer token")
        void deniesCustomers() {
            assertThat(get("/api/orchestration/providers",
                    com.aegispay.common.testsupport.TestJwtIssuer.user(newUserId()), String.class)
                    .status().value())
                    .isEqualTo(403);
        }

        /**
         * Reading a saga and disabling a payment rail are not the same privilege, so a fraud analyst
         * may do the first and not the second.
         */
        @Test
        @DisplayName("lets a fraud analyst read sagas but not disable a provider")
        void analystIsReadOnly() {
            String analyst = com.aegispay.common.testsupport.TestJwtIssuer.user(
                    newUserId(), com.aegispay.common.security.Roles.FRAUD_ANALYST);

            assertThat(get("/api/orchestration/providers", analyst, String.class)
                    .status().is2xxSuccessful()).isTrue();
            assertThat(post("/api/orchestration/providers/" + SwiftPayProvider.ID + "/disable",
                    java.util.Map.of("reason", "not my call"), analyst, String.class)
                    .status().value()).isEqualTo(403);
            assertThat(providerEnabled(SwiftPayProvider.ID)).isTrue();
        }

        /**
         * A reason is mandatory, and enforced by the schema as well. "Who turned this off and why" is
         * unanswerable an hour later otherwise, and the answer decides whether it is safe to turn back
         * on.
         */
        @Test
        @DisplayName("refuses to disable a provider without a reason")
        void requiresAReason() {
            assertThat(post("/api/orchestration/providers/" + SwiftPayProvider.ID + "/disable",
                    java.util.Map.of("reason", ""), adminToken(), String.class)
                    .status().value()).isEqualTo(400);
            assertThat(providerEnabled(SwiftPayProvider.ID)).isTrue();
        }

        @Test
        @DisplayName("404s for a provider that is not registered")
        void unknownProvider() {
            assertThat(post("/api/orchestration/providers/NOPE/disable",
                    java.util.Map.of("reason", "typo"), adminToken(), String.class)
                    .status().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("denies /internal/** to a human token")
        void deniesInternalToHumanTokens() {
            assertThat(get("/internal/providers/simulation",
                    com.aegispay.common.testsupport.TestJwtIssuer.user(newUserId(),
                            com.aegispay.common.security.Roles.ADMIN), String.class)
                    .status().value())
                    .as("a human token must never reach the machine surface")
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("leaves health and API docs open")
        void probesAndDocsArePublic() {
            assertThat(get("/actuator/health", null, String.class).status().value()).isEqualTo(200);
            assertThat(get("/v3/api-docs", null, String.class).status().value()).isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String adminToken() {
        return com.aegispay.common.testsupport.TestJwtIssuer.user(
                newUserId(), com.aegispay.common.security.Roles.ADMIN);
    }

    // ======================================================== the risk gate

    @Nested
    @DisplayName("the fraud risk step")
    class RiskGate {

        /** The ordinary case: the verdict clears the payment and nothing about the flow changes. */
        @Test
        @DisplayName("an approved verdict lets the payment complete")
        void approvedVerdictCompletes() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(DOWNSTREAM.assessCalls())
                    .as("the risk step must actually call fraud-service, not assume an answer")
                    .isEqualTo(1);
            assertThat(sagaRiskAssessmentId(paymentId))
                    .as("the verdict's provenance must be stored: the two services have separate "
                            + "databases, so without it a dispute cannot be traced back")
                    .isNotNull();
            assertThat(sagaRiskDegraded(paymentId)).isFalse();
        }

        /**
         * REVIEW settles the payment and leaves the alert for afterwards. Holding here instead would
         * make a customer wait on a signal that was, by construction, not strong enough to stop them.
         */
        @Test
        @DisplayName("a review verdict settles the payment rather than making the customer wait")
        void reviewVerdictStillCompletes() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictReviews(0.52, "First payment from this device");
            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaFraudDecision(paymentId)).isEqualTo("REVIEW");
        }

        /**
         * <strong>The held case, and every property that has to hold while a payment waits.</strong>
         * The saga stops before charging a provider, the customer's funds stay reserved, and the
         * reservation is extended past the fifteen minutes the default hold would have given it.
         */
        @Test
        @DisplayName("a hold verdict parks the payment with the funds still reserved")
        void holdVerdictParksThePayment() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictHolds(0.91, "6,000km in eleven minutes");
            String paymentId = submitPayment(payer, source, destination, "2000.00");

            awaitSagaStatus(paymentId, "RISK_HELD");

            assertThat(sagaRiskHeldAt(paymentId))
                    .as("how long the customer has waited must be answerable")
                    .isNotNull();
            assertThat(DOWNSTREAM.holdState(paymentId))
                    .as("the funds stay reserved while a human decides")
                    .isEqualTo("ACTIVE");
            assertThat(DOWNSTREAM.heldMinor(source)).isEqualTo(2000 * RUPEE);

            assertThat(DOWNSTREAM.extendCalls())
                    .as("""
                            the default 15-minute reservation is sized for a payment that completes \
                            in seconds; without an extension the wallet sweeper would release the \
                            funds while this saga still believed they were held""")
                    .isEqualTo(1);
            assertThat(DOWNSTREAM.holdExpiry(paymentId))
                    .isAfter(java.time.Instant.now().plus(Duration.ofMinutes(20)));

            assertThat(DOWNSTREAM.postCalls())
                    .as("nothing may be booked for a payment that has not been authorised")
                    .isZero();
            assertThat(DOWNSTREAM.captureCalls()).isZero();
        }

        /**
         * A parked saga must not be "recovered". Its {@code updatedAt} stops moving precisely because
         * it is behaving as designed, so a sweep that treated it as abandoned would pick it up on every
         * pass — incrementing the attempt count until a healthy payment looked like a chronic failure.
         */
        @Test
        @DisplayName("the recovery sweep leaves a parked payment alone")
        void sweepIgnoresParkedPayments() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictHolds(0.91, "held for review");
            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitSagaStatus(paymentId, "RISK_HELD");

            long attemptsBefore = sagaAttemptCount(paymentId);

            // The configured stalled-after in these tests is one second, so this saga is well past it
            // by now. Driven through the operator endpoint, which is the same entry point the
            // scheduler uses.
            sleepBriefly();
            post("/api/orchestration/recovery/sweep", null, adminToken(), String.class);
            post("/api/orchestration/recovery/sweep", null, adminToken(), String.class);
            sleepBriefly();

            assertThat(sagaStatus(paymentId))
                    .as("a payment waiting on a person is parked, not abandoned")
                    .isEqualTo("RISK_HELD");
            assertThat(sagaAttemptCount(paymentId))
                    .as("recovering it repeatedly would make a healthy saga look like a failing one")
                    .isEqualTo(attemptsBefore);
            assertThat(DOWNSTREAM.assessCalls())
                    .as("a re-entered saga would score the same payment again")
                    .isEqualTo(1);
        }

        /**
         * The release rejoins the normal path and takes exactly the same steps an unheld payment
         * would, which is why it lands in RISK_ASSESSED rather than jumping to AUTHORIZED.
         */
        @Test
        @DisplayName("an analyst's release resumes the payment to completion")
        void releaseResumesThePayment() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictHolds(0.91, "held for review");
            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitSagaStatus(paymentId, "RISK_HELD");

            applyAnalystDecision(paymentId, "RELEASE", "USR-ANALYST",
                    "Customer confirmed the payment by phone on a known number");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");

            assertThat(sagaRiskReviewedBy(paymentId))
                    .as("who let this through must be answerable from the payment's own history")
                    .isEqualTo("USR-ANALYST");
            assertThat(sagaFraudDecision(paymentId))
                    .as("""
                            the engine's verdict is a fact and is not rewritten by the override; \
                            somebody reviewing a released fraud needs to see that it was stopped""")
                    .isEqualTo("HOLD");

            assertThat(DOWNSTREAM.balanceMinor(destination)).isEqualTo(2000 * RUPEE);
            assertThat(DOWNSTREAM.holdState(paymentId)).isEqualTo("CAPTURED");
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_COMPLETED, paymentId)).isEqualTo(1);
        }

        @Test
        @DisplayName("an analyst's refusal releases the funds and rejects the payment")
        void refusalCompensates() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictHolds(0.91, "held for review");
            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitSagaStatus(paymentId, "RISK_HELD");

            applyAnalystDecision(paymentId, "REJECT", "USR-ANALYST",
                    "Customer denies making this payment");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("REJECTED");

            assertThat(DOWNSTREAM.holdState(paymentId)).isEqualTo("RELEASED");
            assertThat(DOWNSTREAM.heldMinor(source))
                    .as("the customer gets their money back to spendable")
                    .isZero();
            assertThat(DOWNSTREAM.balanceMinor(source)).isEqualTo(5000 * RUPEE);
            assertThat(sagaCompensationComplete(paymentId))
                    .as("compensation runs before the outcome is recorded, so the flag is a fact")
                    .isTrue();
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_REJECTED, paymentId)).isEqualTo(1);
        }

        /**
         * Kafka delivers at least once, so a decision will be seen twice. The second must change
         * nothing — not charge the provider again, and not overwrite who decided.
         */
        @Test
        @DisplayName("a redelivered decision does not move the payment twice")
        void redeliveredDecisionIsIdempotent() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictHolds(0.91, "held for review");
            String paymentId = submitPayment(payer, source, destination, "2000.00");
            awaitSagaStatus(paymentId, "RISK_HELD");

            applyAnalystDecision(paymentId, "RELEASE", "USR-ANALYST", "Confirmed by phone");
            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");

            int capturesAfterFirst = DOWNSTREAM.captureCalls();
            applyAnalystDecision(paymentId, "RELEASE", "USR-SOMEONE-ELSE", "second delivery");
            sleepBriefly();

            assertThat(sagaStatus(paymentId)).isEqualTo("COMPLETED");
            assertThat(DOWNSTREAM.captureCalls()).isEqualTo(capturesAfterFirst);
            assertThat(sagaRiskReviewedBy(paymentId))
                    .as("the first decision stands")
                    .isEqualTo("USR-ANALYST");
            assertThat(DOWNSTREAM.balanceMinor(destination))
                    .as("the payee must not be credited twice")
                    .isEqualTo(2000 * RUPEE);
        }

        /**
         * A REJECT verdict is a business refusal, not a fault: nothing was charged, so the customer can
         * be told plainly rather than asked to retry into the same answer.
         */
        @Test
        @DisplayName("a refused verdict rejects the payment without charging anything")
        void refusedVerdictRejects() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictRefuses("This account has 3 payments confirmed as fraud");
            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("REJECTED");
            assertThat(sagaFailureCode(paymentId)).isEqualTo("REFUSED_BY_FRAUD_CONTROLS");
            assertThat(DOWNSTREAM.postCalls()).isZero();
            assertThat(DOWNSTREAM.allHoldsSettled())
                    .as("a refused payment must not leave the customer's funds reserved")
                    .isTrue();
        }

        /**
         * <strong>Fails the payment rather than letting an unassessed one through.</strong> Stricter
         * than the model-down fallback on purpose: a model outage still leaves the deterministic rules
         * running, whereas a fraud-service outage leaves no control at all — and recording an
         * unassessed payment as approved would be a false entry in the audit trail of an automated
         * decision.
         */
        @Test
        @DisplayName("an unreachable fraud service fails the payment rather than skipping the check")
        void unreachableFraudServiceFailsThePayment() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.failNextAssessment(503);
            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("FAILED");

            assertThat(DOWNSTREAM.postCalls())
                    .as("no payment may be booked without a risk verdict")
                    .isZero();
            assertThat(DOWNSTREAM.holdState(paymentId))
                    .as("nothing was charged, so the reservation is released and the customer "
                            + "can retry")
                    .isEqualTo("RELEASED");
            assertThat(sagaCompensationComplete(paymentId)).isTrue();
        }

        /**
         * A degraded verdict is acted on, but the saga records that it was reached without the model.
         * A LOW level with {@code degradedMode} means "nothing was found", not "nothing is there", and
         * this service chose to continue on that reduced assurance — which is the fact an audit asks
         * about.
         */
        @Test
        @DisplayName("records that it continued on a verdict reached without the model")
        void recordsDegradedVerdict() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictIsDegraded("APPROVE",
                    "The fraud model was unavailable, so this verdict came from the rules alone");
            String paymentId = submitPayment(payer, source, destination, "2000.00");

            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");
            assertThat(sagaRiskDegraded(paymentId))
                    .as("a payment that completed on rules alone must say so on its own row")
                    .isTrue();
            assertThat(sagaRiskAssessmentId(paymentId))
                    .as("rules still produced an assessment; degraded records that no model ran")
                    .isEqualTo("FRA-STUB-" + paymentId.substring(4, 12));
        }

        /**
         * A failed extension must not fail the payment. The hold is still valid for its original
         * window, so the worst case is that a long review outruns it and the capture then compensates
         * — a failed payment rather than money moving with no funds behind it.
         */
        @Test
        @DisplayName("a failed hold extension still parks the payment")
        void failedExtensionStillParks() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            DOWNSTREAM.nextVerdictHolds(0.91, "held for review");
            DOWNSTREAM.rejectNextExtend();
            String paymentId = submitPayment(payer, source, destination, "2000.00");

            awaitSagaStatus(paymentId, "RISK_HELD");

            assertThat(DOWNSTREAM.holdState(paymentId))
                    .as("the original reservation is untouched and still valid")
                    .isEqualTo("ACTIVE");
        }

        /**
         * A resumed saga no longer has the event that carried the risk context, so it has to be stored.
         * Re-deriving device or location data later would score a situation the customer was never in.
         */
        @Test
        @DisplayName("retains the risk context so a resumed saga scores the right situation")
        void retainsRiskContext() {
            String payer = newUserId();
            String source = DOWNSTREAM.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destination = DOWNSTREAM.addWallet(newUserId(), CURRENCY, 0L);

            String paymentId = submitPayment(payer, source, destination, "2000.00");
            assertThat(awaitTerminalSaga(paymentId)).isEqualTo("COMPLETED");

            assertThat(sagaRiskSignals(paymentId))
                    .as("the device, IP and location the customer transacted with must survive a "
                            + "crash, because a resumed saga cannot get them back")
                    .isNotNull()
                    .contains("203.0.113.7");
        }
    }

    /**
     * Re-publishes {@code payment-created} for a payment that already exists, as a Kafka rebalance or
     * a producer retry would.
     */
    private void submitPaymentAgain(String paymentId, String payer, String sourceWalletId,
                                    String destinationWalletId, String amount) {
        events.publishSync(KafkaTopics.PAYMENT_CREATED,
                com.aegispay.common.events.EventEnvelope.of(
                        KafkaTopics.PAYMENT_CREATED, "Payment", paymentId, "payment-service",
                        new com.aegispay.common.events.payload.PaymentCreatedPayload(
                                paymentId,
                                "TXN" + paymentId.substring(4, 16),
                                "idem-" + paymentId,
                                payer,
                                sourceWalletId,
                                destinationWalletId,
                                "USR-PAYEE",
                                com.aegispay.common.money.Money.of(amount, CURRENCY),
                                com.aegispay.common.domain.SharedEnums.PaymentType.P2P_TRANSFER,
                                "Redelivered",
                                new com.aegispay.common.events.payload.RiskSignals(
                                        null, null, "203.0.113.7", "curl/8.4.0", null, null, null,
                                        null, com.aegispay.common.domain.SharedEnums.Channel.API,
                                        null, null),
                                java.time.Instant.now())));
    }

    /** The provider a saga settled on, falling back to SwiftPay when it was cleared by compensation. */
    private String sagaProviderOrSwiftPay(String paymentId) {
        String provider = sagaProvider(paymentId);
        return provider == null ? SwiftPayProvider.ID : provider;
    }

    /** The most recent saga's payment id, for assertions that only need "the last one". */
    private String newestPaymentProvider() {
        return jdbc.queryForObject(
                "SELECT payment_id FROM payment_sagas ORDER BY created_at DESC LIMIT 1",
                String.class);
    }
}
