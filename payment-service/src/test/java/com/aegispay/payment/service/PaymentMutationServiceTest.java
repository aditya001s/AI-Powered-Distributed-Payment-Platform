package com.aegispay.payment.service;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.NotificationPayload;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import com.aegispay.common.outbox.OutboxRecorder;
import com.aegispay.payment.domain.Payment;
import com.aegispay.payment.repository.PaymentRefundRepository;
import com.aegispay.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for how payment-service applies the saga's outcome events to its own record.
 *
 * <p>Two properties are load-bearing here, and neither is obvious from reading the methods:
 *
 * <ul>
 *   <li><strong>These handlers emit no domain events.</strong> payment-orchestrator already publishes
 *       {@code payment-completed} and the rest. Emitting them from here as well would put two
 *       differently-shaped payloads for one payment on the same topic — the orchestrator's, which
 *       knows the provider, the fee and the ledger posting, and this service's, which does not — and
 *       leave ledger-service and analytics guessing which is authoritative.</li>
 *   <li><strong>They are effectively exactly-once.</strong> Not because of the Redis de-duplication
 *       in front, which is only an optimisation, but because the guarded state machine refuses a
 *       transition already made. That is what stops a redelivered completion telling the payee twice
 *       that their money arrived.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentMutationServiceTest {

    private static final String PAYMENT_ID = "PAY-TEST0001";
    private static final String PAYER = "USR-PAYER";
    private static final String PAYEE = "USR-PAYEE";

    @Mock
    private PaymentRepository payments;
    @Mock
    private PaymentRefundRepository refunds;
    @Mock
    private OutboxRecorder outbox;

    private PaymentMutationService mutations;
    private Payment payment;

    @BeforeEach
    void setUp() {
        mutations = new PaymentMutationService(payments, refunds, outbox);
        payment = newPayment(PAYEE);

        when(payments.findByPaymentIdForUpdate(PAYMENT_ID)).thenAnswer(call -> Optional.of(payment));
        when(payments.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ------------------------------------------------------------- completion

    @Nested
    @DisplayName("payment-completed")
    class Completion {

        /**
         * The event may be the first one this service sees.
         *
         * <p>{@code payment-approved} travels on a different topic, and Kafka orders records only
         * within one topic's partition. A handler that could take a single step would reject this
         * and dead-letter a completion that had genuinely happened.
         */
        @Test
        @DisplayName("completes a payment still at CREATED, without waiting for payment-approved")
        void completesFromCreated() {
            Payment result = mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "SWIFTPAY-REF-1",
                    Money.of("5.00", "INR"), 812L);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(result.getProviderId()).isEqualTo("SWIFTPAY");
            assertThat(result.fee()).isEqualTo(Money.of("5.00", "INR"));
            assertThat(result.getCompletedAt()).isNotNull();
        }

        /**
         * The division of labour between the two services, asserted.
         *
         * <p>Notifications: this service's job, because it owns the customer-facing record.
         * {@code payment-completed}: the orchestrator's job, because only it knows the provider, the
         * fee and the ledger posting.
         */
        @Test
        @DisplayName("emits the two customer notifications and no payment-completed of its own")
        void emitsNotificationsButNotTheOutcomeEvent() {
            mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "REF-1", Money.of("5.00", "INR"), 1L);

            assertThat(recordedTopics())
                    .containsExactly(KafkaTopics.NOTIFICATION_REQUIRED,
                            KafkaTopics.NOTIFICATION_REQUIRED);

            verify(outbox, never()).record(eq(KafkaTopics.PAYMENT_COMPLETED), anyString(),
                    anyString(), any(EventEnvelope.class));
        }

        @Test
        @DisplayName("notifies only the payer when there is no identified payee")
        void notifiesOnlyThePayerWhenThePayeeIsUnknown() {
            payment = newPayment(null);

            mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "REF-1", Money.of("5.00", "INR"), 1L);

            assertThat(recordedTopics()).containsExactly(KafkaTopics.NOTIFICATION_REQUIRED);
        }

        /**
         * The property that makes at-least-once delivery safe without a de-duplication table.
         *
         * <p>A second delivery must not record a second notification, or the payee is told twice
         * that their money arrived — which reads to a customer as two payments.
         */
        @Test
        @DisplayName("a redelivery changes nothing and sends nothing")
        void redeliveryIsInert() {
            mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "REF-1", Money.of("5.00", "INR"), 1L);
            int afterFirst = recordedTopics().size();

            Payment result = mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "REF-1",
                    Money.of("5.00", "INR"), 1L);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(recordedTopics())
                    .as("no second notification for the same payment")
                    .hasSize(afterFirst);
        }

        /** Each notification is keyed so notification-service can suppress a duplicate too. */
        @Test
        @DisplayName("notifications carry a de-duplication key derived from the payment")
        void notificationsAreDeduplicable() {
            mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "REF-1", Money.of("5.00", "INR"), 1L);

            List<String> keys = recordedEnvelopes().stream()
                    .map(EventEnvelope::payload)
                    .filter(NotificationPayload.class::isInstance)
                    .map(NotificationPayload.class::cast)
                    .map(NotificationPayload::deduplicationKey)
                    .toList();

            assertThat(keys).containsExactly(
                    PAYMENT_ID + ":sent:" + PAYER,
                    PAYMENT_ID + ":received:" + PAYEE);
        }
    }

    // ------------------------------------------------------------ approval

    @Nested
    @DisplayName("payment-approved")
    class Approval {

        @Test
        @DisplayName("moves the payment to AUTHORIZED and records the risk verdict")
        void authorises() {
            Payment result = mutations.applyApproved(PAYMENT_ID, RiskLevel.LOW, 0.02d,
                    FraudDecision.APPROVE);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.LOW);
            assertThat(result.getFraudProbability()).isEqualTo(0.02d);
            assertThat(result.getFraudDecision()).isEqualTo(FraudDecision.APPROVE);
        }

        /**
         * The out-of-order case that would otherwise be a real bug.
         *
         * <p>An approval arriving after the completion must not drag the payment back to
         * {@code AUTHORIZED} — the money has moved, and a status saying otherwise would send anyone
         * investigating in exactly the wrong direction.
         */
        @Test
        @DisplayName("a late approval does not undo a completion")
        void lateApprovalDoesNotRewind() {
            mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "REF-1", Money.of("5.00", "INR"), 1L);

            Payment result = mutations.applyApproved(PAYMENT_ID, RiskLevel.LOW, 0d,
                    FraudDecision.APPROVE);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        }

        /**
         * A stale approval must not resurrect a failed payment. By that point the saga has released
         * the reservation and voided the authorisation, so an {@code AUTHORIZED} row would claim a
         * charge that no longer exists anywhere.
         */
        @Test
        @DisplayName("a stale approval does not revive a failed payment")
        void staleApprovalDoesNotRevive() {
            mutations.applyFailed(PAYMENT_ID, "ALL_PROVIDERS_UNAVAILABLE", "no rail",
                    "AUTHORIZE", true);

            Payment result = mutations.applyApproved(PAYMENT_ID, RiskLevel.LOW, 0d,
                    FraudDecision.APPROVE);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }
    }

    // -------------------------------------------------------------- failure

    @Nested
    @DisplayName("payment-failed and payment-rejected")
    class Outcomes {

        @Test
        @DisplayName("records the failure and notifies the payer, emitting no payment-failed")
        void failsWithoutReEmitting() {
            Payment result = mutations.applyFailed(PAYMENT_ID, "PROVIDER_TIMEOUT",
                    "no provider answered", "AUTHORIZE", true);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.getFailureCode()).isEqualTo("PROVIDER_TIMEOUT");

            assertThat(recordedTopics()).containsExactly(KafkaTopics.NOTIFICATION_REQUIRED);
            verify(outbox, never()).record(eq(KafkaTopics.PAYMENT_FAILED), anyString(),
                    anyString(), any(EventEnvelope.class));
        }

        /**
         * A rejection is a decision, not a fault, and the distinction reaches the customer: a
         * rejection means "this will not work", a failure means "try again".
         */
        @Test
        @DisplayName("records a rejection as REJECTED, distinct from a failure")
        void rejectsDistinctly() {
            Payment result = mutations.applyRejected(PAYMENT_ID, "DECLINED_BY_ALL_PROVIDERS",
                    "every provider declined", true);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.REJECTED);
            assertThat(result.getFailureCode()).isEqualTo("DECLINED_BY_ALL_PROVIDERS");
            verify(outbox, never()).record(eq(KafkaTopics.PAYMENT_REJECTED), anyString(),
                    anyString(), any(EventEnvelope.class));
        }

        /**
         * {@code REJECTED} is unreachable once a provider has authorised the charge, so an event
         * saying otherwise is an upstream anomaly. Recording it as {@code FAILED} is the honest
         * response: dropping it would leave the payment in flight forever with no trace of why.
         */
        @Test
        @DisplayName("a rejection arriving after authorisation is recorded as a failure, not dropped")
        void rejectionAfterAuthorisationBecomesAFailure() {
            mutations.applyApproved(PAYMENT_ID, RiskLevel.LOW, 0d, FraudDecision.APPROVE);

            Payment result = mutations.applyRejected(PAYMENT_ID, "DECLINED_BY_ALL_PROVIDERS",
                    "late rejection", false);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("a rejection redelivered against a completed payment changes nothing")
        void rejectionAgainstACompletedPaymentIsInert() {
            mutations.applyCompleted(PAYMENT_ID, "SWIFTPAY", "REF-1", Money.of("5.00", "INR"), 1L);

            Payment result = mutations.applyRejected(PAYMENT_ID, "DECLINED_BY_ALL_PROVIDERS",
                    "stale", true);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<String> recordedTopics() {
        ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
        verify(outbox, org.mockito.Mockito.atLeast(0))
                .record(topics.capture(), anyString(), anyString(), any(EventEnvelope.class));
        return topics.getAllValues();
    }

    private List<EventEnvelope<?>> recordedEnvelopes() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<?>> envelopes =
                ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox, org.mockito.Mockito.atLeast(0))
                .record(anyString(), anyString(), anyString(), envelopes.capture());
        return envelopes.getAllValues();
    }

    private Payment newPayment(String destinationUserId) {
        return Payment.create(PAYMENT_ID, "TXN0001", "client-key-1",
                PAYER, "WAL-SOURCE", "WAL-DEST", destinationUserId,
                Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER, "Dinner split",
                new RiskSignals(null, null, "203.0.113.7", "curl/8.4.0",
                        null, null, null, null, Channel.API, null, null));
    }
}
