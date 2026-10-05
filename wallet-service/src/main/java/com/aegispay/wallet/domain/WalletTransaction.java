package com.aegispay.wallet.domain;

import com.aegispay.common.correlation.CorrelationContext;
import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement on one wallet: the customer's statement line.
 *
 * <p><strong>This is not the double-entry ledger.</strong> It is deliberately
 * single-sided — it records what happened to <em>this</em> wallet and nothing about
 * the other side. The double-entry journal that proves the platform's whole book
 * balances lives in ledger-service. The two answer different questions:
 *
 * <ul>
 *   <li>this table: "what happened to my money?" — a customer-facing statement;</li>
 *   <li>the ledger: "do total debits equal total credits?" — an accounting control.</li>
 * </ul>
 *
 * <p>Conflating them produces a system that can answer neither question reliably: a
 * statement cluttered with internal clearing entries, or a ledger that cannot balance
 * because it only ever saw one side.
 *
 * <p>Append-only. A correction is a new, opposite row (a {@code REVERSAL}), never an
 * edit — the same discipline the ledger uses, for the same reason: a statement a
 * customer disputed must still show what they originally saw.
 */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    /** What caused the movement. Drives how it is described on a statement. */
    public enum Type {
        DEPOSIT,
        WITHDRAWAL,
        /** Received from another wallet. */
        TRANSFER_IN,
        /** Sent to another wallet. */
        TRANSFER_OUT,
        REFUND,
        /** Correction of an earlier movement. */
        REVERSAL,
        FEE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, updatable = false, length = 48)
    private String transactionId;

    @Column(name = "wallet_id", nullable = false, updatable = false, length = 40)
    private String walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private PostingDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * The wallet's balance immediately after this movement.
     *
     * <p>Denormalised on purpose. Deriving a historical balance by summing prior rows
     * is O(n) per statement line and silently wrong if any row is ever backfilled out
     * of order. Storing the result makes every line independently checkable, and makes
     * a corrupted balance detectable by comparing consecutive rows.
     */
    @Column(name = "balance_after_minor", nullable = false)
    private long balanceAfterMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private Type type;

    /** The payment, hold or deposit that caused this movement. */
    @Column(name = "reference_id", length = 48)
    private String referenceId;

    /**
     * Durable HTTP operation identity for deposits/withdrawals. This natural key
     * closes the crash window between committing money and completing the shared
     * idempotency response record.
     */
    @Column(name = "operation_key", length = 160, updatable = false)
    private String operationKey;

    @Column(name = "operation_fingerprint", length = 64, updatable = false)
    private String operationFingerprint;

    @Column(name = "description", length = 256)
    private String description;

    /** Links the movement to the distributed trace of the request that caused it. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WalletTransaction() {
        // JPA
    }

    /**
     * Records a movement.
     *
     * @param wallet    the wallet AFTER the mutator has been applied, so the balance
     *                  recorded here is the true resulting balance
     * @param direction CREDIT increases the customer's money, DEBIT decreases it
     */
    public static WalletTransaction record(Wallet wallet,
                                           PostingDirection direction,
                                           Money amount,
                                           Type type,
                                           String referenceId,
                                           String operationKey,
                                           String operationFingerprint,
                                           String description) {
        WalletTransaction tx = new WalletTransaction();
        tx.transactionId = "WTX-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 24).toUpperCase();
        tx.walletId = wallet.getWalletId();
        tx.direction = direction;
        tx.amountMinor = amount.amountMinor();
        tx.currency = amount.currency();
        tx.balanceAfterMinor = wallet.getBalanceMinor();
        tx.type = type;
        tx.referenceId = referenceId;
        tx.operationKey = operationKey;
        tx.operationFingerprint = operationFingerprint;
        tx.description = truncate(description, 256);
        tx.correlationId = CorrelationContext.get();
        tx.createdAt = Instant.now();
        return tx;
    }

    public Money amount() {
        return Money.ofMinor(amountMinor, currency);
    }

    public Money balanceAfter() {
        return Money.ofMinor(balanceAfterMinor, currency);
    }

    /** The effect on the customer's money: positive for a credit, negative for a debit. */
    public long signedAmountMinor() {
        return direction == PostingDirection.CREDIT ? amountMinor : -amountMinor;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    public Long getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getWalletId() {
        return walletId;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceAfterMinor() {
        return balanceAfterMinor;
    }

    public Type getType() {
        return type;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public String getOperationFingerprint() {
        return operationFingerprint;
    }

    public String getDescription() {
        return description;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
