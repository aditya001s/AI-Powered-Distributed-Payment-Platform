package com.aegispay.common.events.payload;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Terminal and near-terminal saga outcome payloads.
 *
 * <p>Grouped because they form one closed set: exactly one of approved /
 * rejected is emitted after the risk stage, and exactly one of completed /
 * failed is emitted at the end of the saga. Reading them together makes the
 * outcome contract obvious.
 */
public final class PaymentOutcomePayloads {

    private PaymentOutcomePayloads() {
    }

    /** Fraud controls cleared the payment; the provider charge stage is next. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentApprovedPayload(
            String paymentId,
            String transactionId,
            String initiatorUserId,
            Money amount,
            RiskLevel riskLevel,
            double fraudProbability,
            Instant approvedAt
    ) {
    }

    /**
     * The payment was refused. {@code fundsReleased} tells downstream consumers
     * whether compensation already ran, so the UI can say "no money left your
     * wallet" with confidence rather than hedging.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentRejectedPayload(
            String paymentId,
            String transactionId,
            String initiatorUserId,
            Money amount,
            RiskLevel riskLevel,
            FraudDecision decision,
            /** Stable machine code, e.g. {@code FRAUD_HOLD}, {@code ANALYST_REJECTED}. */
            String reasonCode,
            String reason,
            List<String> reasons,
            boolean fundsReleased,
            /** True when a human analyst, not the automated engine, made the call. */
            boolean humanDecision,
            Instant rejectedAt
    ) {
    }

    /** Provider charged, funds captured, ledger posted. Terminal success. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentCompletedPayload(
            String paymentId,
            String transactionId,
            String initiatorUserId,
            String destinationUserId,
            String sourceWalletId,
            String destinationWalletId,
            Money amount,
            /** Provider fee, booked to an expense account in the same posting. */
            Money fee,
            String providerId,
            /** The provider's own reference, needed for reconciliation and refunds. */
            String providerReference,
            /** Providers attempted, in order. Length > 1 means a fallback occurred. */
            List<String> providerAttempts,
            String ledgerTransactionId,
            /** Wall-clock latency from payment creation to completion. */
            long endToEndLatencyMs,
            Instant completedAt
    ) {
    }

    /**
     * The payment could not be completed. {@code compensationComplete} is the
     * critical field: when false, an operator must intervene because money may be
     * stranded in a reservation or at a provider.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentFailedPayload(
            String paymentId,
            String transactionId,
            String initiatorUserId,
            Money amount,
            /** e.g. {@code ALL_PROVIDERS_UNAVAILABLE}, {@code RESERVATION_FAILED}. */
            String failureCode,
            String failureReason,
            /** The saga step that failed, for operational triage. */
            String failedStep,
            List<String> providerAttempts,
            int attemptCount,
            /** False means manual reconciliation is required. Alerted on. */
            boolean compensationComplete,
            Instant failedAt
    ) {
    }

    /**
     * A completed payment was reversed, in whole or in part.
     *
     * <p>Carries both wallet IDs because a refund is a real money movement and its consumer
     * has to know which accounts it touched. ledger-service books the reverse of the
     * original transfer, and the alternative — looking the wallets up from the earlier
     * {@code payment-completed} posting — would make refund accounting depend on
     * cross-topic ordering that Kafka does not provide.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentRefundedPayload(
            String paymentId,
            String transactionId,
            String refundId,
            String initiatorUserId,
            /** Wallet the original payment was paid from; credited by the refund. */
            String sourceWalletId,
            /** Wallet the original payment was paid to; debited by the refund. */
            String destinationWalletId,
            /** The amount refunded by this operation, not the payment total. */
            Money refundedAmount,
            /** Cumulative refunded amount across all refunds on this payment. */
            Money totalRefunded,
            Money originalAmount,
            boolean fullRefund,
            String reason,
            /** User or admin who authorised the refund. */
            String initiatedBy,
            String ledgerTransactionId,
            Instant refundedAt
    ) {
    }
}
