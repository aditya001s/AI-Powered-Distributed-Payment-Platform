package com.aegispay.ledger.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.security.Roles;
import com.aegispay.ledger.api.LedgerDtos.AccountReconciliationResponse;
import com.aegispay.ledger.api.LedgerDtos.AccountResponse;
import com.aegispay.ledger.api.LedgerDtos.JournalEntryResponse;
import com.aegispay.ledger.api.LedgerDtos.JournalLineResponse;
import com.aegispay.ledger.api.LedgerDtos.JournalSummary;
import com.aegispay.ledger.api.LedgerDtos.LedgerReconciliationResponse;
import com.aegispay.ledger.api.LedgerDtos.TrialBalanceResponse;
import com.aegispay.ledger.service.LedgerService;
import com.aegispay.ledger.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff-facing read access to the book of record.
 *
 * <p><strong>Read-only, entirely.</strong> There is no customer-facing write path to the
 * ledger and no staff-facing one either: a posting is a consequence of a business event, never
 * something entered by hand through this API. An operator correction goes through
 * {@code /internal/ledger} as an explicit, attributed adjustment.
 *
 * <p><strong>Staff only.</strong> Every handler carries {@code @PreAuthorize}. That is doing
 * real work here rather than duplicating a chain rule: the shared security chain restricts
 * {@code /api/admin/**}, and these paths are {@code /api/ledger/**} because the gateway routes
 * by prefix to this service. Without the method-level annotation the journal would be readable
 * by any authenticated customer, which would expose every other customer's movements.
 *
 * <p>Balances are shown in the account's <em>natural</em> direction, and each response says
 * whether a debit increases that account. Rendering a liability's balance without that context
 * is how a reader concludes the platform holds money it actually owes.
 */
@RestController
@RequestMapping("/api/ledger")
@Tag(name = "Ledger", description = "Journal, account balances, trial balance and reconciliation")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
public class LedgerController {

    private final LedgerService ledgerService;
    private final ReconciliationService reconciliationService;

    public LedgerController(LedgerService ledgerService,
                            ReconciliationService reconciliationService) {
        this.ledgerService = ledgerService;
        this.reconciliationService = reconciliationService;
    }

    // ---------------------------------------------------------------- journal

    @GetMapping("/journal")
    @Operation(summary = "Browse the journal",
            description = """
                    Postings in reverse sequence order, newest first.

                    Sequence numbers are monotonic but **not gapless**: a rolled-back
                    transaction still consumes a value, so a gap is normal and is not evidence
                    that a row was deleted. Making the sequence gapless would require
                    serialising every posting behind one lock.""")
    public ResponseEntity<PageResponse<JournalSummary>> journal(
            @RequestParam(required = false)
            @Pattern(regexp = "^$|^[A-Za-z]{3}$") String currency,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(PageResponse.of(
                ledgerService.journal(currency, pageable), JournalSummary::from));
    }

    @GetMapping("/journal/{journalSequence}")
    @Operation(summary = "Read one posting by sequence number, with all of its legs")
    @ApiResponse(responseCode = "404", description = "No posting at that sequence",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JournalEntryResponse> getBySequence(
            @PathVariable @Positive long journalSequence) {
        return ResponseEntity.ok(JournalEntryResponse.from(
                ledgerService.getPostingBySequence(journalSequence)));
    }

    @GetMapping("/postings/{ledgerTransactionId}")
    @Operation(summary = "Read one posting by its ledger transaction id")
    @ApiResponse(responseCode = "404", description = "No such posting",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JournalEntryResponse> getByKey(
            @PathVariable @NotBlank @Size(max = 80) String ledgerTransactionId) {
        return ResponseEntity.ok(JournalEntryResponse.from(
                ledgerService.getPosting(ledgerTransactionId)));
    }

    @GetMapping("/payments/{paymentId}/postings")
    @Operation(summary = "Every posting produced by one payment",
            description = "The transfer, plus any refunds or reversals that followed. This is "
                    + "how a support query about a disputed payment is answered.")
    public ResponseEntity<List<JournalEntryResponse>> postingsForPayment(
            @PathVariable @NotBlank @Size(max = 48) String paymentId) {
        return ResponseEntity.ok(ledgerService.getPostingsForPayment(paymentId).stream()
                .map(JournalEntryResponse::from)
                .toList());
    }

    // --------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    @Operation(summary = "List the chart of accounts for one currency")
    public ResponseEntity<List<AccountResponse>> accounts(
            @RequestParam(defaultValue = "INR")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency) {
        return ResponseEntity.ok(ledgerService.listAccounts(currency).stream()
                .map(AccountResponse::from)
                .toList());
    }

    @GetMapping("/accounts/{accountId}")
    @Operation(summary = "Read one account's balance",
            description = """
                    The balance is in the account's natural direction: positive means a normal
                    balance for its type. `debitIncreases` says which way that is, because a
                    debit increases an asset but decreases a liability — and customer wallets
                    are liabilities.""")
    @ApiResponse(responseCode = "404", description = "No such account",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AccountResponse> account(
            @PathVariable @NotBlank @Size(max = 96) String accountId) {
        return ResponseEntity.ok(AccountResponse.from(ledgerService.getAccount(accountId)));
    }

    @GetMapping("/accounts/{accountId}/entries")
    @Operation(summary = "An account statement",
            description = "Every leg touching this account, newest first. Each line carries the "
                    + "balance that resulted from it, so the statement reads without replaying "
                    + "the whole history.")
    public ResponseEntity<PageResponse<JournalLineResponse>> statement(
            @PathVariable @NotBlank @Size(max = 96) String accountId,
            @PageableDefault(size = 50) Pageable pageable) {

        return ResponseEntity.ok(PageResponse.of(
                ledgerService.accountStatement(accountId, pageable), JournalLineResponse::from));
    }

    // -------------------------------------------------- admin: reconciliation

    @GetMapping("/admin/trial-balance")
    @Operation(summary = "Trial balance for one currency",
            description = """
                    Total debits must equal total credits across every account. This is the
                    fundamental accounting identity: if `balanced` is false, money was created
                    or destroyed and no report built on these books can be trusted.

                    The same totals are recomputed independently from the journal lines.
                    `accountsAgreeWithJournal` false means the cached account totals have
                    drifted from the movements that justify them.""")
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public ResponseEntity<TrialBalanceResponse> trialBalance(
            @RequestParam(defaultValue = "INR")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency) {
        return ResponseEntity.ok(reconciliationService.trialBalance(currency));
    }

    @GetMapping("/admin/reconcile")
    @Operation(summary = "Reconcile the whole ledger",
            description = """
                    Runs every consistency check across every currency: trial balance, cached
                    totals against the journal, per-posting integrity, and each account's
                    running-balance chain.

                    `findings` is empty when the ledger is sound. Anything in it names a
                    specific problem an operator can act on.

                    Read-only: reconciliation never repairs what it finds, because silently
                    correcting a discrepancy destroys the evidence of how it arose — and the
                    cause matters far more than the symptom.""")
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public ResponseEntity<LedgerReconciliationResponse> reconcile() {
        return ResponseEntity.ok(reconciliationService.reconcileLedger());
    }

    @GetMapping("/admin/accounts/{accountId}/reconcile")
    @Operation(summary = "Reconcile one account against the journal",
            description = """
                    Recomputes the account's balance and its debit/credit totals by replaying
                    every line that touches it, then checks that the stored running balances
                    form a consistent chain.

                    `consistent: false` means either a balance was mutated without a line to
                    justify it, or a line was written that never reached the balance.""")
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    @ApiResponse(responseCode = "404", description = "No such account",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AccountReconciliationResponse> reconcileAccount(
            @PathVariable @NotBlank @Size(max = 96) String accountId) {
        return ResponseEntity.ok(reconciliationService.reconcileAccount(accountId));
    }
}
