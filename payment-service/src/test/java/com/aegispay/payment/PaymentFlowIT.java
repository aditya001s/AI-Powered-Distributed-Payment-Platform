package com.aegispay.payment;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.security.Roles;
import com.aegispay.common.testsupport.TestJwtIssuer;
import com.aegispay.payment.api.PaymentDtos.CreatePaymentRequest;
import com.aegispay.payment.api.PaymentDtos.PaymentResponse;
import com.aegispay.payment.api.PaymentDtos.RefundRequest;
import com.aegispay.payment.api.PaymentDtos.RefundResponse;
import com.aegispay.payment.api.PaymentDtos.RiskContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end payment behaviour: acceptance, idempotency, saga outcome handling and refunds.
 *
 * <h2>What this service is responsible for since Phase 5</h2>
 *
 * <p>Accepting a payment and keeping its record in step with the saga. It no longer reserves,
 * captures or compensates — payment-orchestrator does, from the {@code payment-created} event. So
 * the tests that used to prove "the reservation was released when the capture failed" have moved to
 * the orchestrator, and what is proved here instead is the pair of properties this service now owns:
 *
 * <ul>
 *   <li>An acceptable request is persisted exactly once, announced once, and <strong>nothing
 *       else</strong> — no reservation, no capture, no money moved by this service.</li>
 *   <li>An outcome event, arriving over a real broker in any order, lands on the payment row
 *       correctly and notifies the customer exactly once.</li>
 * </ul>
 *
 * <p>Assertions deliberately cover three surfaces: the HTTP response, the committed
 * {@code payments} row, and the wallet balances inside {@link StubWalletService}. A payment service
 * can fail in ways where any two agree and the third does not — a {@code 202} for a payment that was
 * never persisted, a {@code COMPLETED} row against a reservation still held, or money moved for a
 * payment recorded as {@code FAILED}.
 */
class PaymentFlowIT extends AbstractPaymentIntegrationTest {

    // ============================================================= acceptance

    @Nested
    @DisplayName("submitting a payment")
    class Submitting {

        /**
         * The contract change at the heart of Phase 5.
         *
         * <p>{@code 202}, status {@code CREATED}, a {@code Location} header, and not a paisa moved.
         * Anything else would be a lie to the caller: no provider has been asked yet, so the payment
         * genuinely has not happened.
         */
        @Test
        @DisplayName("accepts the payment with 202 and moves no money yet")
        void acceptsPayment() {
            String payer = newUserId();
            String payee = newUserId();
            String sourceWallet = WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(payee, CURRENCY, 0L);

            Response<PaymentResponse> response = pay(payer, destinationWallet, "2000.00");

            assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
            PaymentResponse payment = response.body();
            assertThat(payment).isNotNull();
            assertThat(payment.paymentId()).startsWith("PAY-");
            assertThat(payment.transactionId()).startsWith("TXN");
            assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
            assertThat(payment.amount().amountMinor()).isEqualTo(2000 * RUPEE);
            assertThat(payment.sourceWalletId()).isEqualTo(sourceWallet);
            assertThat(payment.destinationWalletId()).isEqualTo(destinationWallet);
            assertThat(payment.completedAt())
                    .as("nothing has completed, so there is no completion time to report")
                    .isNull();

            // The committed row, not just the response.
            assertThat(paymentStatus(payment.paymentId())).isEqualTo("CREATED");

            // No money moved, and no reservation taken: both are the saga's job.
            assertThat(WALLET.balanceMinor(sourceWallet)).isEqualTo(5000 * RUPEE);
            assertThat(WALLET.balanceMinor(destinationWallet)).isZero();
            assertThat(WALLET.heldMinor(sourceWallet)).isZero();
            assertThat(WALLET.reserveCalls())
                    .as("reserving here as well as in the saga would hold the funds twice")
                    .isZero();
            assertThat(WALLET.captureCalls()).isZero();
        }

        /**
         * The response has to be actionable without reading the documentation.
         *
         * <p>A {@code 202} with no pointer to the resource forces every client to guess how to find
         * out what happened, and the guesses will differ.
         */
        @Test
        @DisplayName("points at the payment with a Location header")
        void returnsALocationHeader() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            var result = client.post().uri("/api/payments")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header(org.springframework.http.HttpHeaders.AUTHORIZATION,
                            "Bearer " + TestJwtIssuer.user(payer))
                    .header("Idempotency-Key", newKey())
                    .body(request(destinationWallet, "100.00"))
                    .exchange()
                    .returnResult(PaymentResponse.class);

            assertThat(result.getStatus().value()).isEqualTo(202);
            assertThat(result.getResponseHeaders().getFirst("Location"))
                    .isEqualTo("/api/payments/" + result.getResponseBody().paymentId());
        }

        /**
         * The source wallet comes from the token, never from the body. Otherwise a caller
         * could pay <em>from</em> someone else's wallet by editing one field — the classic
         * broken-object-level-authorisation flaw.
         */
        @Test
        @DisplayName("resolves the payer from the token, never from the request")
        void payerComesFromTheToken() {
            String attacker = newUserId();
            String victim = newUserId();
            String attackerWallet = WALLET.addWallet(attacker, CURRENCY, 100 * RUPEE);
            String victimWallet = WALLET.addWallet(victim, CURRENCY, 100_000 * RUPEE);
            String payeeWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            // The attacker asks for more than they have. There is no field that would let
            // them nominate the victim's wallet as the source, so this must simply decline.
            Response<ApiError> response = payExpectingError(attacker, payeeWallet, "50000.00");

            assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(WALLET.balanceMinor(victimWallet))
                    .as("the victim's wallet must be untouched")
                    .isEqualTo(100_000 * RUPEE);
            assertThat(WALLET.balanceMinor(attackerWallet)).isEqualTo(100 * RUPEE);
        }

        /**
         * Resolution happens here, not in the saga, and the resolved wallet is stored.
         *
         * <p>Deliberate: the saga runs later on a consumer thread and must not have to re-resolve a
         * user's wallet, because the answer could have changed by then — a customer could have
         * opened a second wallet in the same currency, and the payment would silently move to a
         * different account from the one the payer was shown.
         */
        @Test
        @DisplayName("resolves a payee named by user id and stores the wallet the saga will use")
        void resolvesPayeeByUserId() {
            String payer = newUserId();
            String payee = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(payee, CURRENCY, 0L);

            Response<PaymentResponse> response = post("/api/payments",
                    new CreatePaymentRequest(null, payee, new BigDecimal("1500.00"), CURRENCY,
                            PaymentType.P2P_TRANSFER, "By user id", null),
                    TestJwtIssuer.user(payer), newKey(), PaymentResponse.class);

            assertThat(response.status()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().destinationWalletId()).isEqualTo(destinationWallet);
        }

        /**
         * The risk verdict is the saga's to make, so it is absent at acceptance.
         *
         * <p>Left null rather than defaulted to an approval: a stored {@code LOW}/{@code APPROVE}
         * that no assessment produced would be indistinguishable from a real verdict when a disputed
         * payment is reviewed later.
         */
        @Test
        @DisplayName("carries no risk verdict or provider until the saga supplies them")
        void hasNoVerdictAtAcceptance() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            PaymentResponse payment = pay(payer, destinationWallet, "100.00").body();

            assertThat(payment).isNotNull();
            assertThat(payment.risk())
                    .as("nothing has assessed this payment yet, and saying otherwise would be a lie")
                    .isNull();
            assertThat(payment.providerId()).isNull();
        }

        /**
         * The IP is taken from the gateway's observed value, never from the body. A
         * client-supplied IP would let an attacker forge their apparent location and defeat
         * every geo-based fraud rule, so the risk context has no field for it and the value
         * stored must be the connection's.
         */
        @Test
        @DisplayName("stores the observed IP, not one supplied by the client")
        void storesObservedIp() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            PaymentResponse payment = post("/api/payments",
                    new CreatePaymentRequest(destinationWallet, null, new BigDecimal("100.00"),
                            CURRENCY, PaymentType.P2P_TRANSFER, "With context",
                            new RiskContext("device-1", "fingerprint-1", "IN", "Mumbai",
                                    19.076, 72.8777, Channel.MOBILE_ANDROID, null, null)),
                    TestJwtIssuer.user(payer), newKey(), PaymentResponse.class).body();

            assertThat(payment).isNotNull();
            assertThat(storedIpAddress(payment.paymentId()))
                    .as("the IP must come from the connection, and there is no body field for it")
                    .isNotBlank();
        }

        @Test
        @DisplayName("declines when the payer cannot cover the amount")
        void declinesInsufficientFunds() {
            String payer = newUserId();
            String sourceWallet = WALLET.addWallet(payer, CURRENCY, 100 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            Response<ApiError> response = payExpectingError(payer, destinationWallet, "500.00");

            assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS.name());

            assertThat(WALLET.balanceMinor(sourceWallet)).isEqualTo(100 * RUPEE);
            assertThat(WALLET.allHoldsSettled())
                    .as("a decline must not leave funds reserved")
                    .isTrue();
        }

        /**
         * A pre-flight decline writes no payment row, which also means it starts no saga.
         *
         * <p>Intentional. Recording a payment for every rejected attempt would fill the table with
         * noise, and — more importantly — emitting {@code payment-created} for a payment that cannot
         * work would put the orchestrator to work reserving funds only to release them again.
         */
        @Test
        @DisplayName("a pre-flight decline writes no row and starts no saga")
        void declineWritesNoRow() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 100 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            payExpectingError(payer, destinationWallet, "500.00");

            assertThat(countPayments()).isZero();
            assertThat(countOutboxEventsTotal()).isZero();
        }

        @Test
        @DisplayName("refuses a wallet paying itself")
        void refusesSelfTransfer() {
            String payer = newUserId();
            String wallet = WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);

            Response<ApiError> response = payExpectingError(payer, wallet, "100.00");

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code())
                    .isEqualTo(ErrorCode.SELF_TRANSFER_NOT_ALLOWED.name());
            assertThat(countPayments()).isZero();
        }

        /**
         * Cross-currency needs an explicit, rate-stamped conversion. Transferring between
         * mismatched currencies would corrupt both balances by treating paise as cents.
         */
        @Test
        @DisplayName("refuses a cross-currency transfer")
        void refusesCrossCurrency() {
            String payer = newUserId();
            WALLET.addWallet(payer, "INR", 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), "USD", 0L);

            Response<ApiError> response = payExpectingError(payer, destinationWallet, "100.00");

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.UNSUPPORTED_CURRENCY.name());
            assertThat(countPayments()).isZero();
        }

        @Test
        @DisplayName("returns 404 for an unknown destination wallet")
        void unknownDestination() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);

            assertThat(payExpectingError(payer, "WAL-NOPE", "100.00").status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(countPayments()).isZero();
        }

        @Test
        @DisplayName("returns 404 when the payer has no wallet in that currency")
        void payerHasNoWallet() {
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            assertThat(payExpectingError(newUserId(), destinationWallet, "100.00").status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("declines a payment from a frozen wallet without persisting it")
        void declinesFrozenSource() {
            String payer = newUserId();
            String sourceWallet = WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            WALLET.freeze(sourceWallet);

            Response<ApiError> response = payExpectingError(payer, destinationWallet, "2000.00");

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(countPayments())
                    .as("a wallet that cannot spend must be refused before a saga is started")
                    .isZero();
            assertThat(WALLET.balanceMinor(sourceWallet)).isEqualTo(5000 * RUPEE);
        }

        @Test
        @DisplayName("requires a destination")
        void requiresADestination() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);

            Response<ApiError> response = post("/api/payments",
                    new CreatePaymentRequest(null, null, new BigDecimal("100.00"), CURRENCY,
                            PaymentType.P2P_TRANSFER, "Nowhere", null),
                    TestJwtIssuer.user(payer), newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("requires an Idempotency-Key header")
        void requiresIdempotencyKey() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            Response<ApiError> response = post("/api/payments",
                    request(destinationWallet, "100.00"), TestJwtIssuer.user(payer), null,
                    ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(countPayments())
                    .as("a request rejected at the edge must not start a saga")
                    .isZero();
        }

        @Test
        @DisplayName("rejects a zero, negative or over-precise amount")
        void rejectsInvalidAmounts() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            for (String amount : List.of("0.00", "-100.00", "10.999")) {
                assertThat(payExpectingError(payer, destinationWallet, amount).status())
                        .as("amount %s must be rejected", amount)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
            }
            assertThat(countPayments()).isZero();
        }

        /** The interceptor must attach a service token to every internal call. */
        @Test
        @DisplayName("authenticates every call to wallet-service")
        void authenticatesInternalCalls() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            pay(payer, destinationWallet, "100.00");

            assertThat(WALLET.requestsWithoutAuth())
                    .as("an internal call with no bearer token would be refused in production")
                    .isZero();
        }
    }

    // ========================================================= saga outcomes

    /**
     * How this service learns what happened.
     *
     * <p>Every test here publishes a real record onto a real broker through the same
     * {@link com.aegispay.common.kafka.EventPublisher} the orchestrator's outbox drainer uses, and
     * waits for the committed row to change. Calling the listener directly would skip the encoding,
     * the container, the de-duplication registry and the error handler — which is where the
     * interesting failures live.
     */
    @Nested
    @DisplayName("applying saga outcomes")
    class SagaOutcomes {

        @Test
        @DisplayName("payment-completed settles the payment and records the provider and fee")
        void completionSettlesThePayment() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaCompleted(payment, payer, "SWIFTPAY", 5 * RUPEE);
            awaitPaymentStatus(payment.paymentId(), "COMPLETED");

            Response<PaymentResponse> reread = get("/api/payments/" + payment.paymentId(),
                    TestJwtIssuer.user(payer), PaymentResponse.class);

            assertThat(reread.body()).isNotNull();
            assertThat(reread.body().status()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(reread.body().providerId()).isEqualTo("SWIFTPAY");
            assertThat(reread.body().fee().amountMinor())
                    .as("the provider fee is a fact only the saga knows, and it must be stored")
                    .isEqualTo(5 * RUPEE);
            assertThat(reread.body().completedAt()).isNotNull();
        }

        @Test
        @DisplayName("payment-approved moves the payment to AUTHORIZED with its risk verdict")
        void approvalAuthorises() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaApproved(payment, payer);
            awaitPaymentStatus(payment.paymentId(), "AUTHORIZED");

            Response<PaymentResponse> reread = get("/api/payments/" + payment.paymentId(),
                    TestJwtIssuer.user(payer), PaymentResponse.class);
            assertThat(reread.body()).isNotNull();
            assertThat(reread.body().risk()).isNotNull();
            assertThat(reread.body().risk().riskLevel())
                    .isEqualTo(com.aegispay.common.domain.RiskLevel.LOW);
        }

        /**
         * The out-of-order case, which is not hypothetical.
         *
         * <p>{@code payment-approved} and {@code payment-completed} travel on different topics, and
         * Kafka orders records only within one topic's partition. A handler that could take a single
         * step would reject this completion and dead-letter a payment that had genuinely succeeded.
         */
        @Test
        @DisplayName("a completion arriving before its approval still lands correctly")
        void handlesCompletionBeforeApproval() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaCompleted(payment, payer, "NEXUSPAY", 11 * RUPEE);
            awaitPaymentStatus(payment.paymentId(), "COMPLETED");

            // The straggler arrives afterwards and must not drag the payment backwards.
            sagaApproved(payment, payer);

            awaitUntil("the late approval to be processed", Duration.ofSeconds(10),
                    () -> "COMPLETED".equals(paymentStatus(payment.paymentId())));
            assertThat(paymentStatus(payment.paymentId())).isEqualTo("COMPLETED");
        }

        /**
         * The property that makes at-least-once delivery safe.
         *
         * <p>Two notifications for one payment, no matter how many times the event is delivered. A
         * second pair would read to the payee as a second payment arriving.
         */
        @Test
        @DisplayName("a redelivered completion notifies the customer only once")
        void redeliveredCompletionNotifiesOnce() {
            String payer = newUserId();
            String payee = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(payee, CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaCompleted(payment, payer, "SWIFTPAY", 0L);
            awaitPaymentStatus(payment.paymentId(), "COMPLETED");
            sagaCompleted(payment, payer, "SWIFTPAY", 0L);

            awaitUntil("the duplicate completion to be consumed", Duration.ofSeconds(10),
                    () -> countOutboxEvents(KafkaTopics.NOTIFICATION_REQUIRED,
                            payment.paymentId()) >= 2);

            // Give the second delivery time to do damage if it is going to.
            sleepBriefly();
            assertThat(countOutboxEvents(KafkaTopics.NOTIFICATION_REQUIRED, payment.paymentId()))
                    .as("one notification per party, however many times the event arrives")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("payment-failed records the code, the step and the compensation outcome")
        void failureIsRecorded() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaFailed(payment, payer, "ALL_PROVIDERS_UNAVAILABLE", "AUTHORIZE", true);
            awaitPaymentStatus(payment.paymentId(), "FAILED");

            assertThat(failureCode(payment.paymentId())).isEqualTo("ALL_PROVIDERS_UNAVAILABLE");
            assertThat(countOutboxEvents(KafkaTopics.NOTIFICATION_REQUIRED, payment.paymentId()))
                    .as("the payer is told their payment failed")
                    .isEqualTo(1);
        }

        /**
         * A rejection is a decision, not a fault, and the distinction reaches the customer: a
         * rejection means "this will not work", a failure means "try again". Collapsing them would
         * send customers back to retry a payment that can only be declined again.
         */
        @Test
        @DisplayName("payment-rejected is recorded as REJECTED, not FAILED")
        void rejectionIsDistinctFromFailure() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaRejected(payment, payer, "DECLINED_BY_ALL_PROVIDERS");
            awaitPaymentStatus(payment.paymentId(), "REJECTED");

            assertThat(failureCode(payment.paymentId())).isEqualTo("DECLINED_BY_ALL_PROVIDERS");
        }

        /**
         * The saga is the only authority on the outcome, so a stale event must not override a
         * settled one. By the time a payment is {@code COMPLETED} the money has moved, and a row
         * that then said {@code FAILED} would send anyone investigating in the wrong direction.
         */
        @Test
        @DisplayName("a stale failure cannot overwrite a completed payment")
        void staleFailureCannotOverwriteCompletion() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaCompleted(payment, payer, "SWIFTPAY", 0L);
            awaitPaymentStatus(payment.paymentId(), "COMPLETED");

            sagaFailed(payment, payer, "PROVIDER_TIMEOUT", "AUTHORIZE", true);
            sleepBriefly();

            assertThat(paymentStatus(payment.paymentId()))
                    .as("COMPLETED is terminal; FAILED is unreachable from it")
                    .isEqualTo("COMPLETED");
        }
    }

    // =========================================================== idempotency

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        /**
         * The retry case: the response was lost, the client sends it again, one payment.
         *
         * <p>Note what the replay returns — the original <em>acceptance</em>, so status
         * {@code CREATED}. The idempotency store exists to make the write happen once, not to serve
         * as a status endpoint, and pretending otherwise would mean storing a response that changes
         * after it was stored.
         */
        @Test
        @DisplayName("replaying a key returns the original acceptance and creates one payment")
        void replayCreatesOnce() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            String key = newKey();

            PaymentResponse first = post("/api/payments", request(destinationWallet, "2000.00"),
                    TestJwtIssuer.user(payer), key, PaymentResponse.class).body();
            Response<PaymentResponse> replay = post("/api/payments",
                    request(destinationWallet, "2000.00"), TestJwtIssuer.user(payer), key,
                    PaymentResponse.class);

            assertThat(first).isNotNull();
            assertThat(replay.body()).isNotNull();
            assertThat(replay.body().paymentId())
                    .as("a replay must return the ORIGINAL payment")
                    .isEqualTo(first.paymentId());

            assertThat(countPayments()).isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_CREATED, first.paymentId()))
                    .as("one payment-created means one saga, and therefore one reservation")
                    .isEqualTo(1);
        }

        /**
         * The database is the guarantee, not Redis. Flushing the cache between the two
         * attempts simulates an eviction or outage; the composite primary key must still
         * prevent a second payment. A Redis-only design would create two here.
         */
        @Test
        @DisplayName("still creates one payment when the Redis fast path is empty")
        void replayCreatesOnceWithoutCache() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            String key = newKey();

            post("/api/payments", request(destinationWallet, "2000.00"),
                    TestJwtIssuer.user(payer), key, PaymentResponse.class);

            redis.getConnectionFactory().getConnection().serverCommands().flushDb();

            Response<PaymentResponse> replay = post("/api/payments",
                    request(destinationWallet, "2000.00"), TestJwtIssuer.user(payer), key,
                    PaymentResponse.class);

            assertThat(replay.status()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(countPayments())
                    .as("Redis may fail open; the database must not")
                    .isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_CREATED)).isEqualTo(1);
        }

        /**
         * Returning the first payment's response would silently drop a genuinely different
         * second transfer; executing it would break the client's own contract. Both are
         * wrong, so this is a client bug and reported as one.
         */
        @Test
        @DisplayName("rejects the same key with a different amount")
        void rejectsKeyReuseWithDifferentAmount() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 10_000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            String key = newKey();

            post("/api/payments", request(destinationWallet, "2000.00"),
                    TestJwtIssuer.user(payer), key, PaymentResponse.class);

            Response<ApiError> conflict = post("/api/payments",
                    request(destinationWallet, "3000.00"), TestJwtIssuer.user(payer), key,
                    ApiError.class);

            assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflict.body()).isNotNull();
            assertThat(conflict.body().code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED.name());
            assertThat(countPayments()).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects the same key with a different destination")
        void rejectsKeyReuseWithDifferentDestination() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 10_000 * RUPEE);
            String firstPayee = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            String secondPayee = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            String key = newKey();

            post("/api/payments", request(firstPayee, "2000.00"),
                    TestJwtIssuer.user(payer), key, PaymentResponse.class);

            assertThat(post("/api/payments", request(secondPayee, "2000.00"),
                    TestJwtIssuer.user(payer), key, String.class).status())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(countPayments())
                    .as("the second payee must not be paid off the first payee's key")
                    .isEqualTo(1);
        }

        /**
         * Eight identical requests at once — a double-tapped "Send" button. Exactly one
         * payment may be created, because exactly one saga must run: two would reserve the
         * customer's funds twice and charge two providers.
         */
        @Test
        @DisplayName("concurrent duplicates create exactly one payment")
        void concurrentDuplicatesCreateOnce() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 10_000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            String key = newKey();
            String token = TestJwtIssuer.user(payer);

            List<HttpStatus> statuses = inParallel(8, () ->
                    post("/api/payments", request(destinationWallet, "2000.00"), token, key,
                            String.class).status());

            assertThat(statuses).allSatisfy(status -> assertThat(status)
                    .as("every outcome must be an acceptance replay or a conflict, never a 500")
                    .isIn(HttpStatus.ACCEPTED, HttpStatus.CONFLICT));

            assertThat(countPayments()).isEqualTo(1);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_CREATED)).isEqualTo(1);
        }

        /** Distinct keys must all apply; the service must not collapse distinct requests. */
        @Test
        @DisplayName("concurrent distinct payments all apply")
        void concurrentDistinctPaymentsAllApply() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 10_000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            String token = TestJwtIssuer.user(payer);
            int payments = 10;

            List<HttpStatus> statuses = inParallel(payments, () ->
                    post("/api/payments", request(destinationWallet, "100.00"), token, newKey(),
                            String.class).status());

            assertThat(statuses).allMatch(s -> s == HttpStatus.ACCEPTED);
            assertThat(countPayments()).isEqualTo(payments);
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_CREATED)).isEqualTo(payments);
        }
    }

    // ================================================================ events

    @Nested
    @DisplayName("events")
    class Events {

        /**
         * The outbox makes "the payment was accepted" and "the orchestrator was told" atomic. Both
         * halves are asserted: the row must exist, and the drainer must subsequently publish it.
         * Recording without draining would leave a payment accepted and never orchestrated — the
         * worst outcome available, because the customer has been told it went through.
         */
        @Test
        @DisplayName("records payment-created and drains it")
        void recordsAndDrainsPaymentCreated() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_CREATED, payment.paymentId()))
                    .isEqualTo(1);

            long total = countOutboxEventsTotal();
            awaitUntil("the outbox drainer to publish every event", Duration.ofSeconds(30),
                    () -> countPublishedOutboxEvents() == total);
        }

        /**
         * The division of labour, asserted at the topic level.
         *
         * <p>payment-orchestrator publishes the outcome events, because only it knows the provider,
         * the fee and the ledger posting. If this service published them too, ledger-service and
         * analytics would see two differently-shaped payloads for one payment and have no way to
         * tell which was authoritative.
         */
        @Test
        @DisplayName("never publishes an outcome event of its own")
        void doesNotPublishOutcomeEvents() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "2000.00").body();
            assertThat(payment).isNotNull();

            sagaCompleted(payment, payer, "SWIFTPAY", 0L);
            awaitPaymentStatus(payment.paymentId(), "COMPLETED");

            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_COMPLETED))
                    .as("the orchestrator owns this topic")
                    .isZero();
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_APPROVED)).isZero();
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_FAILED)).isZero();
            assertThat(countOutboxEvents(KafkaTopics.PAYMENT_REJECTED)).isZero();

            // What it does own: the customer notifications, one per party.
            assertThat(countOutboxEvents(KafkaTopics.NOTIFICATION_REQUIRED, payment.paymentId()))
                    .isEqualTo(2);
        }

        /**
         * A declined payment writes no row, so it must also emit nothing. A
         * {@code payment-created} for a payment that does not exist would start a saga that
         * reserves a customer's funds for a transfer nobody asked to keep.
         */
        @Test
        @DisplayName("emits nothing for a payment declined before it was persisted")
        void emitsNothingForPreFlightDecline() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 100 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            payExpectingError(payer, destinationWallet, "500.00");

            assertThat(countOutboxEventsTotal()).isZero();
        }
    }

    // =============================================================== history

    @Nested
    @DisplayName("history and lookup")
    class History {

        @Test
        @DisplayName("returns the caller's payments")
        void returnsOwnPayments() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 10_000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            pay(payer, destinationWallet, "100.00");
            pay(payer, destinationWallet, "200.00");

            Response<String> response = get("/api/payments/history",
                    TestJwtIssuer.user(payer), String.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull().contains("OUTGOING");
            assertThat(countPayments()).isEqualTo(2);
        }

        @Test
        @DisplayName("filters history by status")
        void filtersByStatus() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 10_000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            PaymentResponse completed = pay(payer, destinationWallet, "100.00").body();
            PaymentResponse failed = pay(payer, destinationWallet, "200.00").body();
            assertThat(completed).isNotNull();
            assertThat(failed).isNotNull();

            sagaCompleted(completed, payer, "SWIFTPAY", 0L);
            sagaFailed(failed, payer, "ALL_PROVIDERS_UNAVAILABLE", "AUTHORIZE", true);
            awaitPaymentStatus(completed.paymentId(), "COMPLETED");
            awaitPaymentStatus(failed.paymentId(), "FAILED");

            assertThat(get("/api/payments/history?status=COMPLETED",
                    TestJwtIssuer.user(payer), String.class).status()).isEqualTo(HttpStatus.OK);
            assertThat(countPaymentsWithStatus("COMPLETED")).isEqualTo(1);
            assertThat(countPaymentsWithStatus("FAILED")).isEqualTo(1);
        }

        @Test
        @DisplayName("lets the payee read a payment they received")
        void payeeCanReadPayment() {
            String payer = newUserId();
            String payee = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(payee, CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "100.00").body();
            assertThat(payment).isNotNull();

            assertThat(get("/api/payments/" + payment.paymentId(),
                    TestJwtIssuer.user(payee), PaymentResponse.class).status())
                    .isEqualTo(HttpStatus.OK);
        }

        /**
         * Not-found rather than forbidden: confirming that a payment exists between two other
         * people is itself a disclosure.
         */
        @Test
        @DisplayName("hides a payment from a third party behind a 404")
        void hidesPaymentFromThirdParty() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "100.00").body();
            assertThat(payment).isNotNull();

            Response<ApiError> response = get("/api/payments/" + payment.paymentId(),
                    TestJwtIssuer.user(newUserId()), ApiError.class);

            assertThat(response.status())
                    .as("403 would confirm the payment exists")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // =============================================================== refunds

    /**
     * Refunds are still synchronous, and deliberately so.
     *
     * <p>A refund has no provider leg — it is a wallet-to-wallet reversal — so there is nothing to
     * route, nothing to fall back to, and no reason to make the caller poll. It can still fail for a
     * real reason: the payee may have spent the money.
     *
     * <p>Each test arranges the post-payment wallet state directly, because the saga that would have
     * captured the funds is not running here. Making {@code payment-completed} move money would be a
     * fixture that lies about which service owns balances.
     */
    @Nested
    @DisplayName("refunds")
    class Refunds {

        @Test
        @DisplayName("reverses the full amount and marks the payment REFUNDED")
        void fullRefund() {
            Settled settled = settledPayment("2000.00");

            Response<RefundResponse> response = refund(settled.paymentId(), settled.payer(), null);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            RefundResponse refund = response.body();
            assertThat(refund).isNotNull();
            assertThat(refund.refundedNow().amountMinor()).isEqualTo(2000 * RUPEE);
            assertThat(refund.refundedTotal().amountMinor()).isEqualTo(2000 * RUPEE);
            assertThat(refund.remainingRefundable().amountMinor()).isZero();
            assertThat(refund.status()).isEqualTo(PaymentStatus.REFUNDED);

            // The money moved back.
            assertThat(WALLET.balanceMinor(settled.sourceWallet())).isEqualTo(2000 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.destinationWallet())).isZero();
            assertThat(paymentStatus(settled.paymentId())).isEqualTo("REFUNDED");
            assertThat(refundedMinor(settled.paymentId())).isEqualTo(2000 * RUPEE);
        }

        @Test
        @DisplayName("supports repeated partial refunds up to the original amount")
        void repeatedPartialRefunds() {
            Settled settled = settledPayment("1000.00");

            assertThat(refund(settled.paymentId(), settled.payer(), "300.00").body().status())
                    .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(refund(settled.paymentId(), settled.payer(), "400.00").body().status())
                    .as("PARTIALLY_REFUNDED is the one state allowed to repeat")
                    .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(refund(settled.paymentId(), settled.payer(), "300.00").body().status())
                    .isEqualTo(PaymentStatus.REFUNDED);

            assertThat(refundedMinor(settled.paymentId())).isEqualTo(1000 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.sourceWallet())).isEqualTo(1000 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.destinationWallet())).isZero();
        }

        /** The cumulative cap is what makes repeated partial refunds safe. */
        @Test
        @DisplayName("refuses a refund that would exceed the original amount")
        void refusesOverRefund() {
            Settled settled = settledPayment("1000.00");
            refund(settled.paymentId(), settled.payer(), "800.00");

            Response<ApiError> response = post(
                    "/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(new BigDecimal("500.00"), "Too much"),
                    TestJwtIssuer.user(settled.payer()), newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refundedMinor(settled.paymentId())).isEqualTo(800 * RUPEE);
        }

        @Test
        @DisplayName("refuses to refund a payment twice in full")
        void refusesSecondFullRefund() {
            Settled settled = settledPayment("1000.00");
            refund(settled.paymentId(), settled.payer(), null);

            Response<ApiError> response = post(
                    "/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(null, "Again"), TestJwtIssuer.user(settled.payer()),
                    newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.REFUND_NOT_ALLOWED.name());
            assertThat(refundedMinor(settled.paymentId())).isEqualTo(1000 * RUPEE);
        }

        /** A retried refund that credited twice would be a direct loss. */
        @Test
        @DisplayName("replaying a refund key credits once")
        void refundIsIdempotent() {
            Settled settled = settledPayment("1000.00");
            String key = newKey();

            RefundResponse first = post("/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(new BigDecimal("400.00"), "Partial"),
                    TestJwtIssuer.user(settled.payer()), key, RefundResponse.class).body();
            Response<RefundResponse> replay = post(
                    "/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(new BigDecimal("400.00"), "Partial"),
                    TestJwtIssuer.user(settled.payer()), key, RefundResponse.class);

            assertThat(first).isNotNull();
            assertThat(replay.body()).isNotNull();
            assertThat(replay.body().refundId()).isEqualTo(first.refundId());

            assertThat(refundedMinor(settled.paymentId()))
                    .as("a replayed refund must not credit twice")
                    .isEqualTo(400 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.sourceWallet())).isEqualTo(400 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.destinationWallet())).isEqualTo(600 * RUPEE);
        }

        @Test
        @DisplayName("recovers a committed full refund when the response idempotency record is missing")
        void refundNaturalKeyClosesCompletionCrashWindow() {
            Settled settled = settledPayment("1000.00");
            String key = newKey();

            RefundResponse first = post("/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(null, "Full refund"), TestJwtIssuer.user(settled.payer()),
                    key, RefundResponse.class).body();
            assertThat(first).isNotNull();

            // Exact failure window: wallet capture and payment/refund row committed, but
            // the generic response record/cache did not survive.
            jdbc.update("DELETE FROM idempotency_keys WHERE scope = ? AND idempotency_key = ?",
                    "payment.refund", key);
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();

            RefundResponse recovered = post("/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(null, "Full refund"), TestJwtIssuer.user(settled.payer()),
                    key, RefundResponse.class).body();

            assertThat(recovered).isNotNull();
            assertThat(recovered.refundId()).isEqualTo(first.refundId());
            assertThat(refundedMinor(settled.paymentId())).isEqualTo(1000 * RUPEE);
            assertThat(countRefundRecords(settled.paymentId())).isEqualTo(1L);
            assertThat(WALLET.balanceMinor(settled.sourceWallet())).isEqualTo(1000 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.destinationWallet())).isZero();
        }

        /**
         * A refund debits the original payee. If they have already spent the money the refund
         * genuinely cannot proceed, and that is surfaced as 422 rather than hidden — the
         * platform will not create funds to cover it.
         */
        @Test
        @DisplayName("declines when the payee has already spent the funds")
        void declinesWhenPayeeHasSpentTheFunds() {
            String payer = newUserId();
            String payee = newUserId();
            String sourceWallet = WALLET.addWallet(payer, CURRENCY, 1000 * RUPEE);
            // The payee received the payment and has already moved it on, so their balance is bare.
            String destinationWallet = WALLET.addWallet(payee, CURRENCY, 0L);

            PaymentResponse payment = pay(payer, destinationWallet, "1000.00").body();
            assertThat(payment).isNotNull();
            completedPayment(payment, payer, "SWIFTPAY");
            // payment-service observes saga outcomes but does not move money itself; arrange the
            // post-settlement balances at the wallet-service boundary.
            WALLET.setBalanceMinor(sourceWallet, 0L);

            Response<ApiError> response = post(
                    "/api/payments/" + payment.paymentId() + "/refund",
                    new RefundRequest(null, "Customer request"), TestJwtIssuer.user(payer),
                    newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refundedMinor(payment.paymentId())).isZero();
            assertThat(paymentStatus(payment.paymentId()))
                    .as("a declined refund must leave the payment COMPLETED")
                    .isEqualTo("COMPLETED");
            assertThat(WALLET.allHoldsSettled())
                    .as("a declined refund must not strand the payee's funds")
                    .isTrue();
        }

        /**
         * The one compensating action payment-service still owns.
         *
         * <p>A refund reserves from the payee and then captures. If the capture fails, the payee's
         * funds are reserved for a refund that will never happen, so the reservation must be
         * released — otherwise their spendable balance silently drops until the hold expires.
         */
        @Test
        @DisplayName("releases the payee's reservation when a refund capture fails")
        void releasesReservationWhenRefundCaptureFails() {
            Settled settled = settledPayment("1000.00");
            WALLET.failNextCapture(503);

            Response<ApiError> response = post(
                    "/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(null, "Customer request"),
                    TestJwtIssuer.user(settled.payer()), newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(WALLET.heldMinor(settled.destinationWallet()))
                    .as("a failed refund must not strand the payee's funds")
                    .isZero();
            assertThat(WALLET.releaseCalls()).isEqualTo(1);
            assertThat(refundedMinor(settled.paymentId())).isZero();
            assertThat(paymentStatus(settled.paymentId()))
                    .as("a failed refund leaves the payment refundable")
                    .isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("refuses to refund a payment that never completed")
        void refusesRefundOfFailedPayment() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            PaymentResponse payment = pay(payer, destinationWallet, "1000.00").body();
            assertThat(payment).isNotNull();
            sagaFailed(payment, payer, "ALL_PROVIDERS_UNAVAILABLE", "AUTHORIZE", true);
            awaitPaymentStatus(payment.paymentId(), "FAILED");

            Response<ApiError> response = post(
                    "/api/payments/" + payment.paymentId() + "/refund",
                    new RefundRequest(null, "Should not work"), TestJwtIssuer.user(payer),
                    newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().code()).isEqualTo(ErrorCode.REFUND_NOT_ALLOWED.name());
        }

        /** A payment still in flight has not moved any money, so there is nothing to reverse. */
        @Test
        @DisplayName("refuses to refund a payment still in flight")
        void refusesRefundOfInFlightPayment() {
            String payer = newUserId();
            WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);
            PaymentResponse payment = pay(payer, destinationWallet, "1000.00").body();
            assertThat(payment).isNotNull();

            Response<ApiError> response = post(
                    "/api/payments/" + payment.paymentId() + "/refund",
                    new RefundRequest(null, "Too early"), TestJwtIssuer.user(payer),
                    newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(WALLET.reserveCalls())
                    .as("refunding an unsettled payment must not touch the payee's wallet")
                    .isZero();
        }

        @Test
        @DisplayName("refuses a refund requested by a third party")
        void refusesThirdPartyRefund() {
            Settled settled = settledPayment("1000.00");

            Response<ApiError> response = post(
                    "/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(null, "Not mine"), TestJwtIssuer.user(newUserId()),
                    newKey(), ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(refundedMinor(settled.paymentId())).isZero();
        }

        @Test
        @DisplayName("concurrent refunds of one payment credit at most the full amount")
        void concurrentRefundsRespectTheCap() {
            Settled settled = settledPayment("1000.00");
            String token = TestJwtIssuer.user(settled.payer());

            // Six threads each try to refund the whole amount with distinct keys.
            inParallel(6, () -> post("/api/payments/" + settled.paymentId() + "/refund",
                    new RefundRequest(new BigDecimal("1000.00"), "Race"), token, newKey(),
                    String.class).status());

            assertThat(refundedMinor(settled.paymentId()))
                    .as("the cumulative cap must hold under concurrency")
                    .isEqualTo(1000 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.sourceWallet())).isEqualTo(1000 * RUPEE);
            assertThat(WALLET.balanceMinor(settled.destinationWallet())).isZero();
        }
    }

    // ======================================================== authentication

    @Nested
    @DisplayName("authentication and authorisation")
    class Security {

        @Test
        @DisplayName("rejects a request with no token")
        void rejectsMissingToken() {
            assertThat(get("/api/payments/history", null, String.class).status())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("rejects a token signed by an untrusted key")
        void rejectsForgedSignature() {
            assertThat(get("/api/payments/history", TestJwtIssuer.forged(newUserId()),
                    String.class).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("rejects an expired token")
        void rejectsExpiredToken() {
            assertThat(get("/api/payments/history", TestJwtIssuer.expired(newUserId()),
                    String.class).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("rejects a token from a different issuer")
        void rejectsWrongIssuer() {
            assertThat(get("/api/payments/history", TestJwtIssuer.wrongIssuer(newUserId()),
                    String.class).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * A forged token must not be able to submit a payment. Asserting that no row and no event
         * exist rules out a chain that rejects the response after the side effect has happened —
         * here the side effect would be a saga that reserves a customer's funds.
         */
        @Test
        @DisplayName("a forged token cannot start a payment")
        void forgedTokenCannotPay() {
            String payer = newUserId();
            String sourceWallet = WALLET.addWallet(payer, CURRENCY, 5000 * RUPEE);
            String destinationWallet = WALLET.addWallet(newUserId(), CURRENCY, 0L);

            Response<ApiError> response = post("/api/payments",
                    request(destinationWallet, "2000.00"), TestJwtIssuer.forged(payer), newKey(),
                    ApiError.class);

            assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(WALLET.balanceMinor(sourceWallet)).isEqualTo(5000 * RUPEE);
            assertThat(countPayments()).isZero();
            assertThat(countOutboxEventsTotal()).isZero();
        }

        @Test
        @DisplayName("denies /internal/** to a human token")
        void deniesInternalToHumanTokens() {
            assertThat(get("/internal/payments", TestJwtIssuer.user(newUserId(), Roles.ADMIN),
                    String.class).status())
                    .as("a human token must never reach the machine surface")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("leaves health and API docs open")
        void probesAndDocsArePublic() {
            assertThat(get("/actuator/health", null, String.class).status())
                    .isEqualTo(HttpStatus.OK);
            assertThat(get("/v3/api-docs", null, String.class).status())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static CreatePaymentRequest request(String destinationWalletId, String amount) {
        return new CreatePaymentRequest(destinationWalletId, null, new BigDecimal(amount),
                CURRENCY, PaymentType.P2P_TRANSFER, "Integration test payment", null);
    }

    private Response<PaymentResponse> pay(String payer, String destinationWalletId, String amount) {
        return post("/api/payments", request(destinationWalletId, amount),
                TestJwtIssuer.user(payer), newKey(), PaymentResponse.class);
    }

    private Response<ApiError> payExpectingError(String payer, String destinationWalletId,
                                                 String amount) {
        return post("/api/payments", request(destinationWalletId, amount),
                TestJwtIssuer.user(payer), newKey(), ApiError.class);
    }

    /** A completed payment together with the parties and wallets involved. */
    private record Settled(String paymentId, String payer, String sourceWallet,
                           String destinationWallet) {
    }

    /**
     * A payment carried all the way to {@code COMPLETED}, with the wallets left as the saga would
     * have left them: the payee holds the funds and the payer does not.
     *
     * <p>Arranged directly on the stub rather than by driving the money through this service, because
     * this service does not move money any more. A fixture that pretended otherwise would test a
     * flow that does not exist.
     */
    private Settled settledPayment(String amount) {
        String payer = newUserId();
        String payee = newUserId();
        long amountMinor = new BigDecimal(amount).movePointRight(2).longValueExact();

        String sourceWallet = WALLET.addWallet(payer, CURRENCY, amountMinor);
        String destinationWallet = WALLET.addWallet(payee, CURRENCY, amountMinor);

        PaymentResponse payment = post("/api/payments", request(destinationWallet, amount),
                TestJwtIssuer.user(payer), newKey(), PaymentResponse.class).body();
        assertThat(payment).isNotNull();

        completedPayment(payment, payer, "SWIFTPAY");
        // The orchestrator moves funds in production. This service only consumes its outcome,
        // so arrange the resulting payer balance directly on the wallet-service test boundary.
        WALLET.setBalanceMinor(sourceWallet, 0L);
        return new Settled(payment.paymentId(), payer, sourceWallet, destinationWallet);
    }

    /** @param amount {@code null} refunds the full remaining amount. */
    private Response<RefundResponse> refund(String paymentId, String requester, String amount) {
        return post("/api/payments/" + paymentId + "/refund",
                new RefundRequest(amount == null ? null : new BigDecimal(amount), "Customer request"),
                TestJwtIssuer.user(requester), newKey(), RefundResponse.class);
    }

    /**
     * A short pause used only to give a <em>duplicate</em> event time to cause damage.
     *
     * <p>Ordinary waits use {@link #awaitUntil}, which polls for a condition. This is the one case a
     * poll cannot cover: proving that nothing happened has no condition to wait for, so the test has
     * to allow a plausible window and then assert the absence.
     */
    private static void sleepBriefly() {
        try {
            Thread.sleep(1_500L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a duplicate", ex);
        }
    }
}
