package com.aegispay.payment.api;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.Channel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.aegispay.payment.domain.Payment;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request and response bodies for payment-service.
 *
 * <p>Amounts arrive as a decimal string and are returned in both decimal and minor-unit
 * form. Minor units are the authoritative field: a JSON number becomes a double in every
 * JavaScript client, and a double cannot represent every paise value exactly.
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    // ------------------------------------------------------------------ input

    /**
     * Initiates a payment.
     *
     * <p><strong>The payer is never named in this body.</strong> The source wallet is
     * resolved from the authenticated token, so a caller cannot pay <em>from</em> someone
     * else's wallet by editing a field — the classic broken-object-level-authorisation
     * flaw. Only the destination is client-supplied.
     */
    @Schema(description = "Initiate a payment from the authenticated user's wallet")
    public record CreatePaymentRequest(

            @Schema(description = """
                    Destination wallet ID. Provide this OR destinationUserId. If both are
                    given, the wallet ID wins.""",
                    example = "WAL-3F9A2B7C1D4E5F60")
            @Size(max = 40)
            String destinationWalletId,

            @Schema(description = "Destination user ID. Their wallet in this currency is resolved.",
                    example = "USR-9C2A4E6B8D0F1234")
            @Size(max = 40)
            String destinationUserId,

            @NotNull
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "10000000.00", message = "exceeds the per-transaction limit")
            @Digits(integer = 8, fraction = 2, message = "at most 2 decimal places")
            @Schema(description = "Amount in major units", example = "2000.00")
            BigDecimal amount,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO-4217 code")
            @Schema(example = "INR")
            String currency,

            @Schema(example = "P2P_TRANSFER", defaultValue = "P2P_TRANSFER")
            PaymentType paymentType,

            @Size(max = 256)
            @Schema(example = "Dinner split")
            String description,

            @Valid
            @Schema(description = """
                    Device and location context for fraud scoring. Captured once and never
                    recomputed, so a decision stays reproducible. Optional, but a payment
                    with no context scores as higher risk because absence is itself a signal.""")
            RiskContext riskContext
    ) {
        public Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    /**
     * Client-supplied risk context.
     *
     * <p>Note what is <em>not</em> here: the IP address. It is taken from the gateway's
     * observed value rather than from the body, because a client-supplied IP would let an
     * attacker forge their apparent location and defeat every geo-based fraud rule.
     */
    @Schema(description = "Device and location context for fraud scoring")
    public record RiskContext(

            @Size(max = 96)
            @Schema(description = "Stable per-installation device identifier")
            String deviceId,

            @Size(max = 128)
            @Schema(description = "Hash of device characteristics")
            String deviceFingerprint,

            @Pattern(regexp = "^$|^[A-Za-z]{2}$", message = "must be an ISO-3166 alpha-2 code")
            @Schema(example = "IN")
            String countryCode,

            @Size(max = 96)
            String city,

            @Schema(description = "Latitude, used only for distance-from-last-transaction",
                    example = "19.076")
            Double latitude,

            @Schema(example = "72.8777")
            Double longitude,

            @Schema(example = "MOBILE_ANDROID")
            Channel channel,

            @Size(max = 48)
            @Schema(description = "Present for merchant payments")
            String merchantId,

            @Size(max = 48)
            @Schema(description = "Merchant category code; some categories carry far higher fraud rates")
            String merchantCategory
    ) {
    }

    @Schema(description = "Refund a completed payment, in whole or in part")
    public record RefundRequest(

            @Schema(description = """
                    Amount to refund. Omit to refund the full remaining amount. Cannot
                    exceed what has not already been refunded.""",
                    example = "500.00")
            @DecimalMin(value = "0.01")
            @Digits(integer = 8, fraction = 2)
            BigDecimal amount,

            @NotBlank
            @Size(max = 256)
            @Schema(example = "Customer requested a refund")
            String reason
    ) {
    }

    // ----------------------------------------------------------------- output

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A monetary amount in both decimal and minor-unit form")
    public record MoneyView(
            @Schema(example = "2000.00") BigDecimal amount,
            @Schema(example = "200000", description = "Exact value in minor units. Prefer this.")
            long amountMinor,
            @Schema(example = "INR") String currency
    ) {
        public static MoneyView of(Money money) {
            return new MoneyView(money.toDecimal(), money.amountMinor(), money.currency());
        }
    }

    /**
     * The fraud outcome, when one exists.
     *
     * <p>Exposes the risk bucket and the decision but deliberately not the model's internal
     * feature contributions: telling a fraudster exactly which signal tripped is a
     * roadmap for evading it next time. Analysts see the full reasoning through the
     * investigation endpoint instead.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Fraud assessment summary")
    public record RiskView(
            RiskLevel riskLevel,
            FraudDecision decision,
            @Schema(description = "Calibrated fraud probability in [0,1]", example = "0.07")
            Double probability
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A payment and its current state")
    public record PaymentResponse(

            String paymentId,

            @Schema(description = "Human-facing reference. Quote this to support.",
                    example = "TXN7F3A9C2E4B61")
            String transactionId,

            PaymentStatus status,
            PaymentType paymentType,

            MoneyView amount,
            MoneyView fee,

            String sourceWalletId,
            String destinationWalletId,
            String destinationUserId,

            String description,

            @Schema(description = "Present once a fraud assessment has been made")
            RiskView risk,

            @Schema(description = "Provider that settled the payment. Populated from Phase 5.")
            String providerId,

            @Schema(description = "Cumulative amount refunded")
            MoneyView refunded,

            @Schema(description = "Machine-readable failure code, when the payment did not succeed")
            String failureCode,
            String failureReason,

            Instant createdAt,
            Instant completedAt
    ) {
        public static PaymentResponse from(Payment payment) {
            RiskView risk = payment.getRiskLevel() == null && payment.getFraudDecision() == null
                    ? null
                    : new RiskView(payment.getRiskLevel(), payment.getFraudDecision(),
                            payment.getFraudProbability());

            return new PaymentResponse(
                    payment.getPaymentId(),
                    payment.getTransactionId(),
                    payment.getStatus(),
                    payment.getPaymentType(),
                    MoneyView.of(payment.amount()),
                    MoneyView.of(payment.fee()),
                    payment.getSourceWalletId(),
                    payment.getDestinationWalletId(),
                    payment.getDestinationUserId(),
                    payment.getDescription(),
                    risk,
                    payment.getProviderId(),
                    MoneyView.of(payment.refunded()),
                    payment.getFailureCode(),
                    payment.getFailureReason(),
                    payment.getCreatedAt(),
                    payment.getCompletedAt());
        }
    }

    /** Compact form for history listings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Payment history entry")
    public record PaymentSummary(
            String paymentId,
            String transactionId,
            PaymentStatus status,
            MoneyView amount,
            @Schema(description = "OUTGOING when the caller paid, INCOMING when they were paid")
            String direction,
            String counterpartyWalletId,
            String description,
            RiskLevel riskLevel,
            Instant createdAt,
            Instant completedAt
    ) {
        /**
         * @param viewerWalletId the caller's wallet, used to decide direction. A transfer
         *                       appears in both parties' history and must read correctly
         *                       for each.
         */
        public static PaymentSummary from(Payment payment, String viewerWalletId) {
            boolean outgoing = payment.getSourceWalletId().equals(viewerWalletId);
            return new PaymentSummary(
                    payment.getPaymentId(),
                    payment.getTransactionId(),
                    payment.getStatus(),
                    MoneyView.of(payment.amount()),
                    outgoing ? "OUTGOING" : "INCOMING",
                    outgoing ? payment.getDestinationWalletId() : payment.getSourceWalletId(),
                    payment.getDescription(),
                    payment.getRiskLevel(),
                    payment.getCreatedAt(),
                    payment.getCompletedAt());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Outcome of a refund")
    public record RefundResponse(
            String paymentId,
            String refundId,
            MoneyView refundedNow,
            MoneyView refundedTotal,
            MoneyView remainingRefundable,
            PaymentStatus status,
            Instant refundedAt
    ) {
    }
}
