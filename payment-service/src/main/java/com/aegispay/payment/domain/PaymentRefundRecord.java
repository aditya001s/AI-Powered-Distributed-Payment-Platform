package com.aegispay.payment.domain;

import com.aegispay.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable natural identity for one externally retried refund operation. */
@Entity
@Table(name = "payment_refunds")
public class PaymentRefundRecord {

    @Id
    @Column(name = "refund_id", length = 32, nullable = false, updatable = false)
    private String refundId;

    @Column(name = "payment_id", length = 48, nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentRefundRecord() {
        // JPA
    }

    public static PaymentRefundRecord record(String refundId, String paymentId, Money amount) {
        PaymentRefundRecord record = new PaymentRefundRecord();
        record.refundId = refundId;
        record.paymentId = paymentId;
        record.amountMinor = amount.amountMinor();
        record.currency = amount.currency();
        record.createdAt = Instant.now();
        return record;
    }

    public boolean matches(String expectedPaymentId, Money expectedAmount) {
        return paymentId.equals(expectedPaymentId)
                && amountMinor == expectedAmount.amountMinor()
                && currency.equals(expectedAmount.currency());
    }

    public String getRefundId() { return refundId; }
    public String getPaymentId() { return paymentId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
