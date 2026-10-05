package com.aegispay.common.events.payload;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Published by payment-service through the transactional outbox once a payment
 * row is committed. This is the event that starts the orchestration saga.
 *
 * <p>Carries everything the saga needs so no participant has to call back into
 * payment-service to find out what it is processing. That keeps the saga
 * available even while payment-service is being redeployed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentCreatedPayload(

        /** Public payment identifier, e.g. {@code PAY-01JQ...}. Also the partition key. */
        String paymentId,

        /** Human-facing transaction reference shown in statements, e.g. {@code TXN12345}. */
        String transactionId,

        /** The idempotency key the client supplied, retained for audit. */
        String idempotencyKey,

        /** Authenticated user who initiated the payment. */
        String initiatorUserId,

        String sourceWalletId,

        String destinationWalletId,

        /** Owner of the destination wallet; used for notifications. */
        String destinationUserId,

        Money amount,

        PaymentType paymentType,

        /** Free-text note from the payer. Never interpreted, only displayed. */
        String description,

        /** Fraud context captured at initiation. */
        RiskSignals riskSignals,

        Instant createdAt
) {
}
