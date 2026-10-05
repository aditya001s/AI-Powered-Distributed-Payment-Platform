package com.aegispay.wallet.service;

import com.aegispay.common.exception.DomainExceptions.UpstreamUnavailableException;
import com.aegispay.wallet.api.WalletDtos.CrossServiceReconciliationStatus;
import com.aegispay.wallet.api.WalletDtos.WalletLiabilitySnapshot;
import com.aegispay.wallet.client.LedgerLiabilityClient;
import com.aegispay.wallet.client.LedgerLiabilityClient.LiabilitySnapshot;
import com.aegispay.wallet.client.LedgerLiabilityClient.MoneyView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletLedgerReconciliationServiceTest {

    private WalletService wallets;
    private LedgerLiabilityClient ledger;
    private SimpleMeterRegistry meters;
    private WalletLedgerReconciliationService service;

    @BeforeEach
    void setUp() {
        wallets = mock(WalletService.class);
        ledger = mock(LedgerLiabilityClient.class);
        meters = new SimpleMeterRegistry();
        service = new WalletLedgerReconciliationService(wallets, ledger, meters);
    }

    @Test
    @DisplayName("reports an exact match using integer minor units")
    void matches() {
        Instant walletAt = Instant.parse("2026-09-17T10:00:00Z");
        Instant ledgerAt = walletAt.plusMillis(50);
        when(wallets.liabilitySnapshot("inr"))
                .thenReturn(new WalletLiabilitySnapshot("INR", 125_000L, 5_000L, walletAt));
        when(ledger.fetch("INR"))
                .thenReturn(new LiabilitySnapshot("INR", new MoneyView(125_000L, "INR"), ledgerAt));

        var result = service.reconcile("inr");

        assertThat(result.status()).isEqualTo(CrossServiceReconciliationStatus.MATCH);
        assertThat(result.differenceMinor()).isZero();
        assertThat(result.retryRequired()).isFalse();
        assertThat(result.walletSnapshotAt()).isEqualTo(walletAt);
        assertThat(result.ledgerSnapshotAt()).isEqualTo(ledgerAt);
    }

    @Test
    @DisplayName("reports but never hides an observed mismatch")
    void mismatchRequiresRetry() {
        when(wallets.liabilitySnapshot("INR"))
                .thenReturn(new WalletLiabilitySnapshot("INR", 125_000L, 5_000L, Instant.now()));
        when(ledger.fetch("INR"))
                .thenReturn(new LiabilitySnapshot("INR", new MoneyView(120_000L, "INR"), Instant.now()));

        var result = service.reconcile("INR");

        assertThat(result.status()).isEqualTo(CrossServiceReconciliationStatus.MISMATCH_OBSERVED);
        assertThat(result.differenceMinor()).isEqualTo(5_000L);
        assertThat(result.retryRequired()).isTrue();
        assertThat(meters.get("aegis.wallet.ledger.reconciliation.mismatch")
                .tag("currency", "INR").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rejects a cross-currency ledger response instead of comparing unlike money")
    void rejectsCrossCurrencyResponse() {
        when(wallets.liabilitySnapshot("INR"))
                .thenReturn(new WalletLiabilitySnapshot("INR", 1L, 0L, Instant.now()));
        when(ledger.fetch("INR"))
                .thenReturn(new LiabilitySnapshot("USD", new MoneyView(1L, "USD"), Instant.now()));

        assertThatExceptionOfType(UpstreamUnavailableException.class)
                .isThrownBy(() -> service.reconcile("INR"));
    }
}
