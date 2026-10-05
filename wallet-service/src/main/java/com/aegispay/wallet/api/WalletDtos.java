package com.aegispay.wallet.api;

import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.money.Money;
import com.aegispay.wallet.domain.Wallet;
import com.aegispay.wallet.domain.WalletHold;
import com.aegispay.wallet.domain.WalletTransaction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request and response bodies for wallet-service.
 *
 * <p><strong>Money on the wire.</strong> Amounts are accepted as a decimal string
 * ({@code "2000.00"}) and returned as both a decimal and an explicit minor-unit
 * integer. Decimal in, because that is what a human and a form field produce; minor
 * units out as well, because a JSON number cannot be trusted to survive a JavaScript
 * client without precision loss — {@code JSON.parse} maps every number to a double.
 * Returning both lets a correct client use {@code amountMinor} and a casual one still
 * display something sensible.
 *
 * <p>{@code @Digits} caps the accepted scale so a value with more precision than the
 * currency supports is rejected at the edge rather than silently rounded deeper in.
 */
public final class WalletDtos {

    private WalletDtos() {
    }

    // ------------------------------------------------------------------ input

    @Schema(description = "Open a wallet in a given currency")
    public record CreateWalletRequest(

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a 3-letter ISO-4217 code")
            @Schema(example = "INR", defaultValue = "INR")
            String currency
    ) {
    }

    /**
     * A deposit or withdrawal.
     *
     * <p>Amount bounds are business limits, not technical ones: an upper cap makes a
     * mistyped amount (a stray extra zero) fail validation instead of moving a fortune,
     * and the lower bound rejects zero-value movements that would clutter a statement
     * with nothing.
     */
    @Schema(description = "Deposit or withdrawal instruction")
    public record FundsMovementRequest(

            @NotNull
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "10000000.00", message = "exceeds the per-transaction limit")
            @Digits(integer = 8, fraction = 2, message = "at most 2 decimal places")
            @Schema(example = "2000.00")
            BigDecimal amount,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$")
            @Schema(example = "INR")
            String currency,

            @Size(max = 256)
            @Schema(example = "Top-up from linked bank account")
            String description
    ) {
        public Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    /**
     * Internal: reserve funds for a payment.
     *
     * <p>{@code paymentId} is the idempotency key. One hold per payment is enforced by a
     * unique constraint, so a retried reservation is naturally idempotent rather than
     * relying on the caller to pass a separate key.
     */
    @Schema(description = "Internal: reserve funds against a wallet for an in-flight payment")
    public record ReserveRequest(

            @NotBlank
            @Size(max = 48)
            String paymentId,

            @NotBlank
            @Size(max = 40)
            String walletId,

            @NotNull
            @DecimalMin("0.01")
            @Digits(integer = 8, fraction = 2)
            BigDecimal amount,

            @NotBlank
            @Pattern(regexp = "^[A-Za-z]{3}$")
            String currency,

            @Schema(description = "Reservation lifetime in seconds. Defaults to 900.",
                    example = "900")
            Integer ttlSeconds
    ) {
        public Money toMoney() {
            return Money.of(amount, currency);
        }
    }

    /**
     * Internal: convert a reservation into a real transfer.
     *
     * <p>Capture and credit are one operation on purpose. Debiting the payer and
     * crediting the payee in two separate calls would leave a window in which the money
     * existed in neither wallet, and a crash in that window would destroy funds.
     */
    @Schema(description = "Internal: capture a reservation and credit the destination")
    public record CaptureRequest(

            @NotBlank
            @Size(max = 48)
            String paymentId,

            @NotBlank
            @Size(max = 40)
            @Schema(description = "Wallet holding the reservation")
            String sourceWalletId,

            @NotBlank
            @Size(max = 40)
            @Schema(description = "Wallet to credit")
            String destinationWalletId,

            @Size(max = 256)
            String description
    ) {
    }

    @Schema(description = "Internal: release a reservation without moving money")
    public record ReleaseRequest(

            @NotBlank
            @Size(max = 48)
            String paymentId,

            @Size(max = 256)
            String reason
    ) {
    }

    /**
     * Internal: keep a reservation alive past the saga's own budget.
     *
     * <p>Exists for the fraud hold. A payment stopped for review waits on a human, and the default
     * fifteen-minute reservation would be swept while the saga still believed the funds were held.
     *
     * <p>{@code reason} is mandatory. A reservation held beyond its normal life is a customer unable
     * to spend their own money, and the only acceptable record of why is one that cannot be omitted.
     */
    @Schema(description = "Internal: extend a reservation's expiry")
    public record ExtendHoldRequest(

            @NotBlank
            @Size(max = 48)
            String paymentId,

            @NotNull
            @Min(60)
            @Max(604_800)
            @Schema(description = "New lifetime in seconds, measured from now. Capped at 7 days, and "
                    + "never shortens an expiry that is already further out.",
                    example = "86400")
            Integer ttlSeconds,

            @NotBlank
            @Size(max = 256)
            @Schema(example = "Held for fraud review; alert ALR-7F3C2A")
            String reason
    ) {
        public java.time.Duration ttl() {
            return java.time.Duration.ofSeconds(ttlSeconds);
        }
    }

    // ----------------------------------------------------------------- output

    /**
     * An amount, rendered both ways.
     *
     * <p>{@code @JsonIgnoreProperties} so a client echoing this object back does not
     * fail on the redundant field.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A monetary amount in both decimal and minor-unit form")
    public record MoneyView(
            @Schema(example = "2000.00") BigDecimal amount,
            @Schema(example = "200000", description = "Exact value in minor units (paise). Prefer this.")
            long amountMinor,
            @Schema(example = "INR") String currency
    ) {
        public static MoneyView of(Money money) {
            return new MoneyView(money.toDecimal(), money.amountMinor(), money.currency());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Wallet balance and reservation state")
    public record WalletResponse(
            String walletId,
            String userId,
            String currency,

            @Schema(description = "Total funds owned by the customer")
            MoneyView balance,

            @Schema(description = "Portion reserved by in-flight payments")
            MoneyView held,

            @Schema(description = "Spendable now: balance minus held")
            MoneyView available,

            Wallet.Status status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static WalletResponse from(Wallet wallet) {
            return new WalletResponse(
                    wallet.getWalletId(),
                    wallet.getUserId(),
                    wallet.getCurrency(),
                    MoneyView.of(wallet.balance()),
                    MoneyView.of(wallet.held()),
                    MoneyView.of(wallet.available()),
                    wallet.getStatus(),
                    wallet.getCreatedAt(),
                    wallet.getUpdatedAt());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One line of a wallet statement")
    public record TransactionResponse(
            String transactionId,
            String walletId,
            PostingDirection direction,
            MoneyView amount,
            @Schema(description = "Balance immediately after this movement")
            MoneyView balanceAfter,
            WalletTransaction.Type type,
            String referenceId,
            String description,
            Instant createdAt
    ) {
        public static TransactionResponse from(WalletTransaction tx) {
            return new TransactionResponse(
                    tx.getTransactionId(),
                    tx.getWalletId(),
                    tx.getDirection(),
                    MoneyView.of(tx.amount()),
                    MoneyView.of(tx.balanceAfter()),
                    tx.getType(),
                    tx.getReferenceId(),
                    tx.getDescription(),
                    tx.getCreatedAt());
        }
    }

    /** Result of a deposit or withdrawal: the movement plus the resulting balance. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Outcome of a funds movement")
    public record MovementResponse(
            TransactionResponse transaction,
            @Schema(description = "The wallet's state after the movement")
            WalletResponse wallet
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Internal: a fund reservation")
    public record HoldResponse(
            String holdId,
            String paymentId,
            String walletId,
            MoneyView amount,
            WalletHold.State state,
            Instant expiresAt,
            @Schema(description = "Available balance remaining after the reservation")
            MoneyView availableAfter
    ) {
        public static HoldResponse of(WalletHold hold, Wallet wallet) {
            return new HoldResponse(
                    hold.getHoldId(),
                    hold.getPaymentId(),
                    hold.getWalletId(),
                    MoneyView.of(hold.amount()),
                    hold.getState(),
                    hold.getExpiresAt(),
                    MoneyView.of(wallet.available()));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Internal: outcome of a capture")
    public record CaptureResponse(
            String paymentId,
            String holdId,
            MoneyView amount,
            String sourceWalletId,
            String destinationWalletId,
            @Schema(description = "Debit recorded on the source wallet")
            String debitTransactionId,
            @Schema(description = "Credit recorded on the destination wallet")
            String creditTransactionId,
            Instant capturedAt
    ) {
    }

    /**
     * Internal identity-and-balance view used by other services.
     *
     * <p>Carries only what a caller needs to decide whether a payment can proceed. It
     * deliberately omits the statement and the reservation detail: the fraud service has
     * no reason to read a customer's transaction history through this endpoint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Internal: minimal wallet view for service-to-service calls")
    public record InternalWalletView(
            String walletId,
            String userId,
            String currency,
            long balanceMinor,
            long heldMinor,
            long availableMinor,
            Wallet.Status status,
            boolean canSpend
    ) {
        public static InternalWalletView from(Wallet wallet) {
            return new InternalWalletView(
                    wallet.getWalletId(),
                    wallet.getUserId(),
                    wallet.getCurrency(),
                    wallet.getBalanceMinor(),
                    wallet.getHeldMinor(),
                    wallet.availableMinor(),
                    wallet.getStatus(),
                    wallet.getStatus() == Wallet.Status.ACTIVE);
        }
    }

    /**
     * Reconciliation report.
     *
     * <p>Exposed as an admin endpoint because "are the balances still correct" should be
     * answerable on demand, not only when a customer complains.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Consistency check between balances, movement history and holds")
    public record ReconciliationResponse(
            String walletId,
            long storedBalanceMinor,
            @Schema(description = "Balance recomputed by replaying every movement")
            long replayedBalanceMinor,
            long storedHeldMinor,
            @Schema(description = "Held total recomputed by summing ACTIVE holds")
            long summedHoldsMinor,
            boolean balanceMatches,
            boolean heldMatches,
            @Schema(description = "True only when both checks agree")
            boolean consistent
    ) {
    }

    public enum CrossServiceReconciliationStatus {
        MATCH,
        MISMATCH_OBSERVED
    }

    /** Local total captured before the outbound ledger call. */
    public record WalletLiabilitySnapshot(String currency, long totalBalanceMinor,
                                          long totalHeldMinor, Instant takenAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Cross-service comparison of wallet balances and ledger liabilities")
    public record LedgerReconciliationResponse(
            String currency,
            long walletBalanceMinor,
            long walletHeldMinor,
            long ledgerCustomerLiabilityMinor,
            @Schema(description = "walletBalanceMinor minus ledgerCustomerLiabilityMinor")
            long differenceMinor,
            CrossServiceReconciliationStatus status,
            @Schema(description = "A first mismatch can be transient while Kafka catches up; retry "
                    + "before treating it as persistent corruption")
            boolean retryRequired,
            Instant walletSnapshotAt,
            Instant ledgerSnapshotAt
    ) {
    }
}
