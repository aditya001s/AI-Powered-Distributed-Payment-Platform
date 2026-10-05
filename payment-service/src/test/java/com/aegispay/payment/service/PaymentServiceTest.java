package com.aegispay.payment.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.ConflictException;
import com.aegispay.common.exception.DomainExceptions.InsufficientFundsException;
import com.aegispay.common.idempotency.IdempotencyService;
import com.aegispay.common.money.Money;
import com.aegispay.payment.api.PaymentDtos.CreatePaymentRequest;
import com.aegispay.payment.api.PaymentDtos.RefundRequest;
import com.aegispay.payment.client.WalletClient;
import com.aegispay.payment.domain.Payment;
import com.aegispay.payment.domain.PaymentRefundRecord;
import com.aegispay.payment.repository.PaymentRefundRepository;
import com.aegispay.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PaymentService} acceptance.
 *
 * <p><strong>What changed in Phase 5, and why these tests look so different.</strong> This class used
 * to reserve funds, capture them and compensate on failure, and the bulk of its tests were about
 * whether a reservation was released. All of that now lives in payment-orchestrator's saga, so the
 * concern here is narrower and sharper: does an obviously impossible request get refused
 * <em>before</em> anything is persisted, and does an acceptable one get persisted exactly once with
 * nothing else touched.
 *
 * <p>The pre-checks matter even though wallet-service re-checks the balance authoritatively under a
 * row lock. Without them, a payment from a frozen wallet or in the wrong currency would be accepted
 * with a {@code 202}, and the caller would learn it was impossible only from an asynchronous failure
 * — a worse experience and a needless saga.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final String PAYER = "USR-PAYER";
    private static final String PAYEE = "USR-PAYEE";
    private static final String SOURCE_WALLET = "WAL-SOURCE";
    private static final String DEST_WALLET = "WAL-DEST";
    private static final String PAYMENT_ID = "PAY-TEST0001";
    private static final String IDEMPOTENCY_KEY = "client-key-1";

    @Mock
    private PaymentRepository payments;
    @Mock
    private PaymentRefundRepository refunds;
    @Mock
    private PaymentMutationService mutations;
    @Mock
    private WalletClient walletClient;
    @Mock
    private IdempotencyService idempotency;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(payments, refunds, mutations, walletClient, idempotency,
                new SimpleMeterRegistry());

        // The idempotency wrapper is tested on its own; here it simply runs the operation
        // so the logic underneath is exercised.
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    // ------------------------------------------------------------ acceptance

    /**
     * The whole contract of the new initiation path.
     *
     * <p>Accepted means persisted and announced, nothing more. The returned status is
     * {@code CREATED}, which the API reports as {@code 202} — deliberately not {@code COMPLETED},
     * because at this point no provider has been asked and no money has moved.
     */
    @Test
    @DisplayName("persists the payment and returns it as CREATED, without moving any money")
    void acceptsPaymentForOrchestration() {
        givenWallets(200_000L);
        givenPaymentCreated();

        var response = service.initiate(PAYER, "203.0.113.7", "curl/8.4.0",
                request("2000.00"), IDEMPOTENCY_KEY);

        assertThat(response.status())
                .as("acceptance is not completion; the saga has not run yet")
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(response.paymentId()).isEqualTo(PAYMENT_ID);

        verify(mutations).createPayment(any(Payment.class));
    }

    /**
     * The reason the flow was made asynchronous in the first place.
     *
     * <p>Reserving here as well as in the saga would take the customer's funds twice — the saga's
     * reserve is keyed on the payment id, so the second call would either conflict or, worse,
     * succeed against a different key and double-hold. Capturing here would move money before any
     * provider had authorised it and before the ledger had recorded why.
     */
    @Test
    @DisplayName("does not reserve, capture or release anything: the saga owns every side effect")
    void takesNoSideEffectsOnTheWallet() {
        givenWallets(200_000L);
        givenPaymentCreated();

        service.initiate(PAYER, "203.0.113.7", "curl/8.4.0", request("2000.00"), IDEMPOTENCY_KEY);

        verify(walletClient, never()).reserve(anyString(), anyString(), any(), any());
        verify(walletClient, never()).capture(anyString(), anyString(), anyString(), any());
        verify(walletClient, never()).releaseQuietly(anyString(), anyString());
    }

    /**
     * There is nothing to compensate, because nothing was done.
     *
     * <p>Persisting the payment and recording {@code payment-created} share one transaction, so a
     * failure leaves neither behind. No half-state is reachable from here, which is exactly why the
     * elaborate compensation this class used to need is gone rather than merely moved.
     */
    @Test
    @DisplayName("a persistence failure leaves nothing behind to undo")
    void persistenceFailureNeedsNoCompensation() {
        givenWallets(200_000L);
        when(mutations.createPayment(any(Payment.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.initiate(PAYER, "203.0.113.7", "curl/8.4.0",
                        request("2000.00"), IDEMPOTENCY_KEY));

        verify(walletClient, never()).releaseQuietly(anyString(), anyString());
        verify(mutations, never()).applyFailed(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ------------------------------------------------------------ validation

    @Nested
    @DisplayName("validation")
    class Validation {

        /**
         * A decline, not a fault. Rejected before the payment is persisted, so there is no saga to
         * start and nothing for the customer to poll — the answer is already final.
         */
        @Test
        @DisplayName("refuses a payment that exceeds available funds, before persisting anything")
        void refusesInsufficientFunds() {
            // 1,000.00 available against a 2,000.00 payment.
            givenWallets(100_000L);

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> service.initiate(PAYER, "203.0.113.7", "curl/8.4.0",
                            request("2000.00"), IDEMPOTENCY_KEY));

            verify(mutations, never()).createPayment(any(Payment.class));
        }

        @Test
        @DisplayName("refuses a wallet paying itself")
        void refusesSelfTransfer() {
            WalletClient.WalletView wallet = walletView(SOURCE_WALLET, PAYER, "INR", 200_000L);
            when(walletClient.getWalletByUser(PAYER, "INR")).thenReturn(wallet);
            when(walletClient.getWallet(SOURCE_WALLET)).thenReturn(wallet);

            CreatePaymentRequest selfPay = new CreatePaymentRequest(
                    SOURCE_WALLET, null, new BigDecimal("100.00"), "INR",
                    PaymentType.P2P_TRANSFER, "oops", null);

            assertThatExceptionOfType(BadRequestException.class)
                    .isThrownBy(() -> service.initiate(PAYER, "203.0.113.7", "curl/8.4.0",
                            selfPay, IDEMPOTENCY_KEY))
                    .satisfies(ex -> assertThat(ex.errorCode())
                            .isEqualTo(ErrorCode.SELF_TRANSFER_NOT_ALLOWED));

            verify(mutations, never()).createPayment(any(Payment.class));
        }

        /**
         * Implicit FX would corrupt both balances, because a stored balance is a bare integer
         * with no currency of its own. Conversion must be an explicit, rate-stamped operation.
         */
        @Test
        @DisplayName("refuses a cross-currency transfer")
        void refusesCrossCurrency() {
            when(walletClient.getWalletByUser(PAYER, "INR"))
                    .thenReturn(walletView(SOURCE_WALLET, PAYER, "INR", 200_000L));
            when(walletClient.getWallet(DEST_WALLET))
                    .thenReturn(walletView(DEST_WALLET, PAYEE, "USD", 0L));

            assertThatExceptionOfType(BadRequestException.class)
                    .isThrownBy(() -> service.initiate(PAYER, "203.0.113.7", "curl/8.4.0",
                            request("100.00"), IDEMPOTENCY_KEY))
                    .satisfies(ex -> assertThat(ex.errorCode())
                            .isEqualTo(ErrorCode.UNSUPPORTED_CURRENCY));
        }

        @Test
        @DisplayName("refuses to send from a frozen wallet")
        void refusesFrozenSourceWallet() {
            when(walletClient.getWalletByUser(PAYER, "INR"))
                    .thenReturn(new WalletClient.WalletView(SOURCE_WALLET, PAYER, "INR",
                            200_000L, 0L, 200_000L, "FROZEN", false));
            when(walletClient.getWallet(DEST_WALLET))
                    .thenReturn(walletView(DEST_WALLET, PAYEE, "INR", 0L));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.initiate(PAYER, "203.0.113.7", "curl/8.4.0",
                            request("100.00"), IDEMPOTENCY_KEY));
        }

        @Test
        @DisplayName("requires a destination")
        void requiresADestination() {
            when(walletClient.getWalletByUser(PAYER, "INR"))
                    .thenReturn(walletView(SOURCE_WALLET, PAYER, "INR", 200_000L));

            CreatePaymentRequest noDestination = new CreatePaymentRequest(
                    null, null, new BigDecimal("100.00"), "INR",
                    PaymentType.P2P_TRANSFER, null, null);

            assertThatExceptionOfType(BadRequestException.class)
                    .isThrownBy(() -> service.initiate(PAYER, "203.0.113.7", "curl/8.4.0",
                            noDestination, IDEMPOTENCY_KEY))
                    .withMessageContaining("destinationWalletId or destinationUserId");
        }
    }

    // ---------------------------------------------------------- risk context

    @Test
    @DisplayName("takes the IP from the server, not the request body")
    void usesServerObservedIp() {
        givenWallets(200_000L);
        givenPaymentCreated();

        service.initiate(PAYER, "198.51.100.42", "Mozilla/5.0",
                request("100.00"), IDEMPOTENCY_KEY);

        var captor = org.mockito.ArgumentCaptor.forClass(Payment.class);
        verify(mutations).createPayment(captor.capture());
        RiskSignals signals = captor.getValue().riskSignals();

        // A client-supplied IP would let an attacker forge their apparent location and
        // defeat every geo-based fraud rule, so the observed value wins.
        assertThat(signals.ipAddress()).isEqualTo("198.51.100.42");
        assertThat(signals.userAgent()).isEqualTo("Mozilla/5.0");
    }

    /**
     * The risk context has to travel on {@code payment-created}.
     *
     * <p>It is captured here, at the only moment it exists — the customer's request. The saga runs
     * later on a Kafka consumer thread with no HTTP request in scope, and Phase 6's fraud service
     * runs later still. Anything not recorded now is gone, and re-deriving it would score a
     * different situation from the one the customer actually transacted in.
     */
    @Test
    @DisplayName("captures the risk context on the payment, since nothing downstream can re-derive it")
    void capturesRiskContextForTheSaga() {
        givenWallets(200_000L);
        givenPaymentCreated();

        service.initiate(PAYER, "198.51.100.42", "Mozilla/5.0",
                new CreatePaymentRequest(DEST_WALLET, null, new BigDecimal("100.00"), "INR",
                        PaymentType.P2P_TRANSFER, "Dinner split",
                        new com.aegispay.payment.api.PaymentDtos.RiskContext(
                                "device-1", "fp-1", "in", "Pune", 18.52d, 73.85d,
                                Channel.MOBILE_ANDROID, "MER-1", "5812")),
                IDEMPOTENCY_KEY);

        var captor = org.mockito.ArgumentCaptor.forClass(Payment.class);
        verify(mutations).createPayment(captor.capture());
        RiskSignals signals = captor.getValue().riskSignals();

        assertThat(signals.deviceId()).isEqualTo("device-1");
        assertThat(signals.countryCode())
                .as("normalised so a country rule cannot miss on case alone")
                .isEqualTo("IN");
        assertThat(signals.channel()).isEqualTo(Channel.MOBILE_ANDROID);
        assertThat(signals.merchantId()).isEqualTo("MER-1");
    }

    @Test
    @DisplayName("recovers a full refund by deterministic operation record after response loss")
    void recoversAppliedRefundWhenPaymentIsNoLongerRefundable() {
        Payment payment = mock(Payment.class);
        PaymentRefundRecord record = mock(PaymentRefundRecord.class);
        when(payments.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(payment.getInitiatorUserId()).thenReturn(PAYER);
        when(payment.getDestinationUserId()).thenReturn(PAYEE);
        when(payment.getStatus()).thenReturn(PaymentStatus.REFUNDED);
        when(payment.getCurrency()).thenReturn("INR");
        when(payment.refunded()).thenReturn(Money.of("1000.00", "INR"));
        when(payment.refundableRemaining()).thenReturn(Money.ofMinor(0L, "INR"));
        when(refunds.findById(anyString())).thenReturn(Optional.of(record));
        when(record.getRefundId()).thenReturn("RFD-RECOVERED");
        when(record.getAmountMinor()).thenReturn(100_000L);
        when(record.getCurrency()).thenReturn("INR");
        when(record.getCreatedAt()).thenReturn(Instant.parse("2026-09-17T12:00:00Z"));

        var response = service.refund(PAYMENT_ID, PAYER,
                new RefundRequest(null, "retry after response loss"), IDEMPOTENCY_KEY);

        assertThat(response.refundId()).isNotBlank();
        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(response.refundedNow().amountMinor()).isEqualTo(100_000L);
        verify(walletClient, never()).reserve(anyString(), anyString(), any(), any());
        verify(mutations, never()).applyRefund(anyString(), anyString(), any(), anyString(), anyString());
    }

    // ----------------------------------------------------------------- setup

    private CreatePaymentRequest request(String amount) {
        return new CreatePaymentRequest(DEST_WALLET, null, new BigDecimal(amount), "INR",
                PaymentType.P2P_TRANSFER, "Dinner split", null);
    }

    private void givenWallets(long sourceAvailableMinor) {
        when(walletClient.getWalletByUser(PAYER, "INR"))
                .thenReturn(walletView(SOURCE_WALLET, PAYER, "INR", sourceAvailableMinor));
        when(walletClient.getWallet(DEST_WALLET))
                .thenReturn(walletView(DEST_WALLET, PAYEE, "INR", 0L));
    }

    private void givenPaymentCreated() {
        when(mutations.createPayment(any(Payment.class))).thenReturn(newPayment());
    }

    private WalletClient.WalletView walletView(String walletId, String userId,
                                               String currency, long availableMinor) {
        return new WalletClient.WalletView(walletId, userId, currency,
                availableMinor, 0L, availableMinor, "ACTIVE", true);
    }

    private Payment newPayment() {
        return Payment.create(PAYMENT_ID, "TXN0001", IDEMPOTENCY_KEY,
                PAYER, SOURCE_WALLET, DEST_WALLET, PAYEE,
                Money.of("2000.00", "INR"), PaymentType.P2P_TRANSFER, "Dinner split",
                new RiskSignals(null, null, "203.0.113.7", "curl/8.4.0",
                        null, null, null, null, Channel.API, null, null));
    }

}
