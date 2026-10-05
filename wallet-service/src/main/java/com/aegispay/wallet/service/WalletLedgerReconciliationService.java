package com.aegispay.wallet.service;

import com.aegispay.common.exception.DomainExceptions.UpstreamUnavailableException;
import com.aegispay.wallet.api.WalletDtos.CrossServiceReconciliationStatus;
import com.aegispay.wallet.api.WalletDtos.LedgerReconciliationResponse;
import com.aegispay.wallet.api.WalletDtos.WalletLiabilitySnapshot;
import com.aegispay.wallet.client.LedgerLiabilityClient;
import com.aegispay.wallet.client.LedgerLiabilityClient.LiabilitySnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compares two independently owned databases without holding a transaction across
 * the network. A mismatch is observed, never repaired: Kafka may still be carrying
 * the corresponding ledger event, and automatic repair would destroy evidence.
 */
@Service
public class WalletLedgerReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(WalletLedgerReconciliationService.class);

    private final WalletService wallets;
    private final LedgerLiabilityClient ledger;
    private final MeterRegistry meterRegistry;

    public WalletLedgerReconciliationService(WalletService wallets,
                                             LedgerLiabilityClient ledger,
                                             MeterRegistry meterRegistry) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.meterRegistry = meterRegistry;
    }

    public LedgerReconciliationResponse reconcile(String currency) {
        // The proxied read-only method returns before the outbound call begins.
        WalletLiabilitySnapshot wallet = wallets.liabilitySnapshot(currency);
        LiabilitySnapshot book = ledger.fetch(wallet.currency());

        if (!wallet.currency().equalsIgnoreCase(book.currency())
                || book.totalCustomerLiability().currency() == null
                || !wallet.currency().equalsIgnoreCase(book.totalCustomerLiability().currency())) {
            throw new UpstreamUnavailableException("ledger-service",
                    "returned a cross-currency liability snapshot");
        }

        long difference;
        try {
            difference = Math.subtractExact(
                    wallet.totalBalanceMinor(), book.totalCustomerLiability().amountMinor());
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("wallet/ledger reconciliation difference overflowed", ex);
        }

        boolean matches = difference == 0L;
        if (!matches) {
            meterRegistry.counter("aegis.wallet.ledger.reconciliation.mismatch",
                    "currency", wallet.currency()).increment();
            log.error("Wallet/ledger liability mismatch observed for {}: wallet={} ledger={} "
                            + "difference={}. Retry after event propagation before declaring it persistent.",
                    wallet.currency(), wallet.totalBalanceMinor(),
                    book.totalCustomerLiability().amountMinor(), difference);
        }

        return new LedgerReconciliationResponse(
                wallet.currency(),
                wallet.totalBalanceMinor(),
                wallet.totalHeldMinor(),
                book.totalCustomerLiability().amountMinor(),
                difference,
                matches ? CrossServiceReconciliationStatus.MATCH
                        : CrossServiceReconciliationStatus.MISMATCH_OBSERVED,
                !matches,
                wallet.takenAt(),
                book.takenAt());
    }
}
