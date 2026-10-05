package com.aegispay.orchestrator.service;

import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.exception.DomainExceptions.UpstreamTransportException;
import com.aegispay.common.money.Money;
import com.aegispay.orchestrator.client.FraudClient;
import com.aegispay.orchestrator.client.LedgerClient;
import com.aegispay.orchestrator.client.WalletClient;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.aegispay.orchestrator.domain.PaymentSaga;
import com.aegispay.orchestrator.domain.SagaStatus;
import com.aegispay.orchestrator.domain.SagaStep;
import com.aegispay.orchestrator.provider.PaymentProvider;
import com.aegispay.orchestrator.provider.ProviderFaultException;
import com.aegispay.orchestrator.provider.ProviderGateway;
import com.aegispay.orchestrator.provider.ProviderRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the money-safety boundary after a downstream side effect succeeds: failure
 * to persist the local transition must leave the saga recoverable, not compensate
 * from stale state.
 */
class SagaRecoveryBoundaryTest {

    private SagaMutationService mutations;
    private WalletClient wallet;
    private LedgerClient ledger;
    private ProviderRouter router;
    private ProviderGateway gateway;
    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        mutations = mock(SagaMutationService.class);
        wallet = mock(WalletClient.class);
        ledger = mock(LedgerClient.class);
        router = mock(ProviderRouter.class);
        gateway = mock(ProviderGateway.class);
        orchestrator = new SagaOrchestrator(
                mutations,
                wallet,
                ledger,
                mock(FraudClient.class),
                router,
                gateway,
                new OrchestrationProperties(),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("does not release a confirmed reservation when its saga transition fails")
    void reserveSuccessThenStateFailureWaitsForRecovery() {
        PaymentSaga saga = sagaAt(SagaStatus.STARTED);
        when(mutations.beginStep(saga, SagaStep.Name.RESERVE_FUNDS)).thenReturn(1L);
        when(wallet.reserve(anyString(), anyString(), any(), any()))
                .thenReturn(new WalletClient.HoldView("HLD-1", "PAY-1", "WAL-1",
                        new WalletClient.MoneyView(BigDecimal.ONE, 100L, "INR"),
                        "ACTIVE", Instant.now().plusSeconds(900)));
        when(mutations.markFundsReserved("PAY-1", "HLD-1"))
                .thenThrow(new IllegalStateException("database unavailable"));

        orchestrator.resume(saga);

        verify(wallet, never()).releaseQuietly(anyString(), anyString());
        verify(mutations, never()).beginCompensating(anyString());
        verify(mutations, never()).markFailed(anyString(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("does not reverse a confirmed ledger posting when its saga transition fails")
    void ledgerSuccessThenStateFailureWaitsForRecovery() {
        PaymentSaga saga = sagaAt(SagaStatus.AUTHORIZED);
        PaymentCompletedPayload payload = mock(PaymentCompletedPayload.class);
        when(mutations.beginStep(saga, SagaStep.Name.POST_LEDGER)).thenReturn(2L);
        when(mutations.completedPayload(saga)).thenReturn(payload);
        when(ledger.bookPayment(payload)).thenReturn(new LedgerClient.JournalEntryView(
                42L, "LTX-PAY-1", "PAY-1", "PAYMENT", null, false));
        when(mutations.markLedgerPosted("PAY-1", "LTX-PAY-1"))
                .thenThrow(new IllegalStateException("database unavailable"));

        orchestrator.resume(saga);

        verify(ledger, never()).reverseQuietly(anyString(), anyString());
        verify(mutations, never()).beginCompensating(anyString());
        verify(mutations, never()).markFailed(anyString(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("does not reverse ledger or release funds after confirmed wallet capture")
    void captureSuccessThenStateFailureFailsForward() {
        PaymentSaga saga = sagaAt(SagaStatus.LEDGER_POSTED);
        when(mutations.beginStep(saga, SagaStep.Name.CAPTURE_FUNDS)).thenReturn(3L);
        when(wallet.capture("PAY-1", "WAL-1", "WAL-2", "test"))
                .thenReturn(new WalletClient.CaptureView("PAY-1", "HLD-1", null,
                        "WAL-1", "WAL-2", "WTX-DEBIT", "WTX-CREDIT", Instant.now()));
        when(mutations.markCaptured("PAY-1"))
                .thenThrow(new IllegalStateException("database unavailable"));

        orchestrator.resume(saga);

        verify(ledger, never()).reverseQuietly(anyString(), anyString());
        verify(wallet, never()).releaseQuietly(anyString(), anyString());
        verify(mutations, never()).beginCompensating(anyString());
        verify(mutations, never()).markFailed(anyString(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("ambiguous capture remains recovery-required even when diagnostic persistence fails")
    void diagnosticFailureCannotMaskAmbiguousCapture() {
        PaymentSaga saga = sagaAt(SagaStatus.LEDGER_POSTED);
        when(mutations.beginStep(saga, SagaStep.Name.CAPTURE_FUNDS)).thenReturn(4L);
        when(wallet.capture("PAY-1", "WAL-1", "WAL-2", "test"))
                .thenThrow(new UpstreamTransportException("wallet-service", "response lost",
                        new IllegalStateException("connection closed before a response")));
        org.mockito.Mockito.doThrow(new IllegalStateException("orchestrator database unavailable"))
                .when(mutations).stepFailed(eq(4L), anyString(), anyString());

        boolean completed = orchestrator.resume(saga);

        org.assertj.core.api.Assertions.assertThat(completed).isFalse();
        verify(ledger, never()).reverseQuietly(anyString(), anyString());
        verify(wallet, never()).releaseQuietly(anyString(), anyString());
        verify(mutations, never()).beginCompensating(anyString());
    }

    @Test
    @DisplayName("does not fall back when a timed-out provider authorization cannot be reconciled")
    void unresolvedProviderAuthorizationBlocksFallback() {
        PaymentSaga saga = sagaAt(SagaStatus.RISK_ASSESSED);
        when(saga.amount()).thenReturn(Money.ofMinor(100L, "INR"));
        when(saga.getTransactionId()).thenReturn("TXN-1");
        PaymentProvider first = mock(PaymentProvider.class);
        PaymentProvider second = mock(PaymentProvider.class);
        when(first.id()).thenReturn("FIRST");
        when(second.id()).thenReturn("SECOND");
        when(router.route(any(), any())).thenReturn(List.of(
                new ProviderRouter.RoutingCandidate(first, 1.0, Money.ofMinor(0L, "INR"), 1.0, 1L),
                new ProviderRouter.RoutingCandidate(second, 0.5, Money.ofMinor(0L, "INR"), 1.0, 1L)));
        when(mutations.beginStep(saga, SagaStep.Name.AUTHORIZE)).thenReturn(5L);
        when(gateway.authorize(eq(first), any()))
                .thenThrow(ProviderFaultException.timedOut("FIRST", "acknowledgement lost"));
        when(first.findAuthorizationId("SAGA-1"))
                .thenThrow(new IllegalStateException("provider lookup unavailable"));

        boolean completed = orchestrator.resume(saga);

        org.assertj.core.api.Assertions.assertThat(completed).isFalse();
        verify(gateway, never()).authorize(eq(second), any());
        verify(mutations, never()).beginCompensating(anyString());
    }

    @Test
    @DisplayName("recovery reconciles a previously attempted provider before calculating fallback")
    void recoveryChecksPersistedProviderAttemptBeforeRouting() {
        PaymentSaga saga = sagaAt(SagaStatus.RISK_ASSESSED);
        when(saga.providerAttemptList()).thenReturn(List.of("FIRST"));
        when(mutations.providersRequiringAuthorizationReconciliation(saga))
                .thenReturn(List.of("FIRST"));
        PaymentProvider first = mock(PaymentProvider.class);
        when(first.id()).thenReturn("FIRST");
        when(router.byId("FIRST")).thenReturn(java.util.Optional.of(first));
        when(first.findAuthorizationId("SAGA-1"))
                .thenThrow(new IllegalStateException("provider lookup unavailable"));

        boolean completed = orchestrator.resume(saga);

        org.assertj.core.api.Assertions.assertThat(completed).isFalse();
        verify(router, never()).route(any(), any());
        verify(gateway, never()).authorize(any(), any());
        verify(mutations, never()).beginCompensating(anyString());
    }

    private PaymentSaga sagaAt(SagaStatus status) {
        PaymentSaga saga = mock(PaymentSaga.class);
        when(saga.getSagaId()).thenReturn("SAGA-1");
        when(saga.getPaymentId()).thenReturn("PAY-1");
        when(saga.getSourceWalletId()).thenReturn("WAL-1");
        when(saga.getDestinationWalletId()).thenReturn("WAL-2");
        when(saga.getDescription()).thenReturn("test");
        when(saga.getStatus()).thenReturn(status);
        when(saga.providerAttemptList()).thenReturn(List.of());
        when(saga.isTerminal()).thenReturn(false);
        when(saga.isParked()).thenReturn(false);
        when(mutations.recordRecoveryAttempt("PAY-1")).thenReturn(saga);
        return saga;
    }
}
