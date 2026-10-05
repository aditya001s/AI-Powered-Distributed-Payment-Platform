package com.aegispay.wallet.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.common.security.Roles;
import com.aegispay.wallet.api.WalletDtos.CreateWalletRequest;
import com.aegispay.wallet.api.WalletDtos.FundsMovementRequest;
import com.aegispay.wallet.api.WalletDtos.LedgerReconciliationResponse;
import com.aegispay.wallet.api.WalletDtos.MovementResponse;
import com.aegispay.wallet.api.WalletDtos.ReconciliationResponse;
import com.aegispay.wallet.api.WalletDtos.TransactionResponse;
import com.aegispay.wallet.api.WalletDtos.WalletResponse;
import com.aegispay.wallet.service.WalletLedgerReconciliationService;
import com.aegispay.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer-facing wallet endpoints.
 *
 * <p>Every handler derives the acting user from {@link CurrentUser#requireUserId()} — the
 * validated token — and never from a request field. The service layer then refuses any
 * wallet the caller does not own. Without both halves, a wallet ID in a URL would be
 * enough to drain someone else's balance, which is the classic broken-object-level
 * authorisation flaw.
 */
@RestController
@RequestMapping("/api/wallets")
@Tag(name = "Wallets", description = "Balances, deposits, withdrawals and statements")
@SecurityRequirement(name = "bearerAuth")
public class WalletController {

    private final WalletService walletService;
    private final WalletLedgerReconciliationService ledgerReconciliation;

    public WalletController(WalletService walletService,
                            WalletLedgerReconciliationService ledgerReconciliation) {
        this.walletService = walletService;
        this.ledgerReconciliation = ledgerReconciliation;
    }

    @PostMapping
    @Operation(summary = "Open a wallet",
            description = """
                    Opens a wallet for the authenticated user in the requested currency.

                    One wallet per user per currency, enforced by a unique constraint. A
                    second request for the same currency returns 409 rather than creating a
                    duplicate — two wallets in one currency would let funds be split to
                    defeat per-wallet balance checks.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Wallet opened"),
            @ApiResponse(responseCode = "409", description = "A wallet already exists for this currency",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<WalletResponse> openWallet(@Valid @RequestBody CreateWalletRequest request) {
        WalletResponse wallet = walletService.openWallet(
                CurrentUser.requireUserId(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(wallet);
    }

    @GetMapping
    @Operation(summary = "List the authenticated user's wallets")
    public ResponseEntity<List<WalletResponse>> listWallets() {
        return ResponseEntity.ok(walletService.listWallets(CurrentUser.requireUserId()));
    }

    @GetMapping("/balance")
    @Operation(summary = "Get the authenticated user's balance in one currency",
            description = """
                    Resolved from the token plus a currency rather than a wallet ID, so this
                    endpoint cannot be aimed at another user's wallet.

                    Returns three amounts: `balance` (everything owned), `held` (reserved by
                    in-flight payments) and `available` (spendable now). Clients should
                    display `available`, because that is what a new payment is checked
                    against.""")
    public ResponseEntity<WalletResponse> getBalance(
            @RequestParam(defaultValue = "INR")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency) {
        return ResponseEntity.ok(walletService.getBalance(CurrentUser.requireUserId(), currency));
    }

    @GetMapping("/{walletId}")
    @Operation(summary = "Get one of the authenticated user's wallets")
    @ApiResponse(responseCode = "404", description = "Not found, or not owned by the caller",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<WalletResponse> getWallet(@PathVariable @Size(max = 40) String walletId) {
        return ResponseEntity.ok(walletService.getWallet(walletId, CurrentUser.requireUserId()));
    }

    @GetMapping("/{walletId}/transactions")
    @Operation(summary = "Get a wallet statement",
            description = "Per-wallet movement history, newest first. This is a statement, "
                    + "not the double-entry ledger.")
    public ResponseEntity<PageResponse<TransactionResponse>> statement(
            @PathVariable @Size(max = 40) String walletId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(walletService.statement(
                walletId, CurrentUser.requireUserId(), pageable));
    }

    @PostMapping("/{walletId}/deposit")
    @Operation(summary = "Deposit funds",
            description = """
                    Credits the wallet. Simulates a top-up from an external funding source;
                    this platform never contacts a real bank.

                    **Requires an `Idempotency-Key` header.** A retried deposit whose
                    response was lost must not credit twice. Replaying the same key with the
                    same body returns the original response; replaying it with a *different*
                    body is rejected with 409, because that is unambiguously a client bug
                    and either possible shortcut would be wrong.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deposited, or the original response replayed"),
            @ApiResponse(responseCode = "409", description = "Key reused with a different payload, "
                    + "or an identical request is still in flight",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<MovementResponse> deposit(
            @PathVariable @Size(max = 40) String walletId,
            @Valid @RequestBody FundsMovementRequest request,
            @Parameter(description = "Client-generated unique key for this operation", required = true)
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey) {

        return ResponseEntity.ok(walletService.deposit(
                CurrentUser.requireUserId(), walletId, request, idempotencyKey));
    }

    @PostMapping("/{walletId}/withdraw")
    @Operation(summary = "Withdraw funds",
            description = """
                    Debits the wallet. Checked against **available** funds, not the total
                    balance, so a withdrawal cannot spend money already reserved by an
                    in-flight payment.

                    Requires an `Idempotency-Key` header. Returns 422 when available funds
                    are insufficient, with the available amount in the error details.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn"),
            @ApiResponse(responseCode = "422", description = "Insufficient available balance",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<MovementResponse> withdraw(
            @PathVariable @Size(max = 40) String walletId,
            @Valid @RequestBody FundsMovementRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey) {

        return ResponseEntity.ok(walletService.withdraw(
                CurrentUser.requireUserId(), walletId, request, idempotencyKey));
    }

    // ------------------------------------------------------- administration

    @GetMapping("/admin/reconcile-ledger")
    @Operation(summary = "Compare all wallet balances with ledger customer liabilities",
            description = "Takes independent read-only snapshots. MATCH is exact; a first "
                    + "MISMATCH_OBSERVED must be retried after Kafka propagation before it is "
                    + "treated as persistent. This endpoint never repairs either database.")
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public ResponseEntity<LedgerReconciliationResponse> reconcileLedger(
            @RequestParam(defaultValue = "INR")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency) {
        return ResponseEntity.ok(ledgerReconciliation.reconcile(currency));
    }

    @GetMapping("/admin/{walletId}/reconcile")
    @Operation(summary = "Reconcile a wallet",
            description = """
                    Recomputes the balance by replaying every recorded movement, and
                    recomputes the held total by summing ACTIVE reservations, then compares
                    both against the stored columns.

                    `consistent: false` means money was created or destroyed relative to the
                    audit trail and needs investigation immediately.""")
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public ResponseEntity<ReconciliationResponse> reconcile(
            @PathVariable @Size(max = 40) String walletId) {
        return ResponseEntity.ok(walletService.reconcile(walletId));
    }
}
