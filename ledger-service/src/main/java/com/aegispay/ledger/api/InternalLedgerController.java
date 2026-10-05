package com.aegispay.ledger.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.common.security.Roles;
import com.aegispay.ledger.api.LedgerDtos.CreatePostingRequest;
import com.aegispay.ledger.api.LedgerDtos.JournalEntryResponse;
import com.aegispay.ledger.api.LedgerDtos.ReversePostingRequest;
import com.aegispay.ledger.api.LedgerDtos.WalletLiabilitySnapshot;
import com.aegispay.ledger.service.LedgerService;
import com.aegispay.ledger.service.PostingBuilder;
import com.aegispay.ledger.service.PostingResult;
import com.aegispay.ledger.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Service-to-service ledger operations: posting, reversing, and reading the book of record.
 *
 * <p><strong>Not reachable from the internet.</strong> The gateway has no route for
 * {@code /internal/**} and additionally denies the path outright, and every handler here
 * requires a client-credentials token carrying {@code ROLE_SERVICE} and
 * {@code SCOPE_internal}. Two independent controls, because a posting changes the platform's
 * book of record.
 *
 * <p>In Phase 4 postings normally arrive as Kafka events rather than through this API — the
 * ledger consumes {@code payment-completed}, {@code payment-refunded} and
 * {@code wallet-movement-recorded} and books them itself. This endpoint exists for the cases
 * an event stream cannot serve: Phase 5's saga, which needs to know the posting committed
 * before it captures funds, and operator adjustments, which need a synchronous answer.
 */
@RestController
@RequestMapping("/internal/ledger")
@Tag(name = "Internal ledger operations",
        description = "Post, reverse and read journal entries. Requires ROLE_SERVICE.")
@PreAuthorize("hasAuthority('" + Roles.ROLE_SERVICE + "')")
public class InternalLedgerController {

    private final LedgerService ledgerService;
    private final ReconciliationService reconciliationService;
    private final PostingBuilder postingBuilder;

    public InternalLedgerController(LedgerService ledgerService,
                                    ReconciliationService reconciliationService,
                                    PostingBuilder postingBuilder) {
        this.ledgerService = ledgerService;
        this.reconciliationService = reconciliationService;
        this.postingBuilder = postingBuilder;
    }

    @PostMapping("/postings")
    @Operation(summary = "Post a balanced double entry",
            description = """
                    Appends a posting to the journal and applies it to every account it
                    touches, in one transaction.

                    **Idempotent on `ledgerTransactionId`.** Replaying the same key returns the
                    original posting with `duplicate: true` rather than booking the movement
                    twice. Derive the key from something the source already owns — a payment
                    ID, a wallet transaction ID — so that a retry produces the same value; a
                    random key would make every redelivery a new posting.

                    Total debits must equal total credits. That is checked here and again by a
                    deferred database constraint at commit, so an unbalanced posting cannot be
                    committed by any route.

                    Customer wallet accounts are created on first use. System accounts must
                    already exist: inventing one would mean guessing its account type, and a
                    wrong type silently inverts every posting against it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted"),
            @ApiResponse(responseCode = "200", description = "Already posted; the original is returned"),
            @ApiResponse(responseCode = "404", description = "A named system account does not exist",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The posting would drive an account "
                    + "below zero, or conflicts with the journal's current state",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Debits do not equal credits",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<JournalEntryResponse> post(@Valid @RequestBody CreatePostingRequest request) {
        PostingResult result = ledgerService.post(request.toPayload(), callerName());

        // 200 for a replay, 201 for a new posting: the distinction tells a saga whether it
        // was the one that booked the movement.
        return ResponseEntity
                .status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(JournalEntryResponse.from(result));
    }

    @PostMapping("/postings/{ledgerTransactionId}/reverse")
    @Operation(summary = "Reverse a posting",
            description = """
                    Appends a new posting that is the exact opposite of the target, leg for leg.

                    This is the only way to undo a posting. The journal is append-only, so a
                    correction is a new entry and both remain visible — deleting or editing the
                    original would destroy the evidence that it ever happened, which is
                    precisely what an audit needs.

                    A posting may be reversed at most once, enforced by a partial unique index,
                    so two concurrent reversals cannot both succeed. A reversal cannot itself
                    be reversed; post a fresh entry instead.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reversed"),
            @ApiResponse(responseCode = "404", description = "No such posting",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Already reversed, or the target is "
                    + "itself a reversal",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<JournalEntryResponse> reverse(
            @PathVariable @NotBlank @Size(max = 80) String ledgerTransactionId,
            @Valid @RequestBody ReversePostingRequest request) {

        PostingResult result = ledgerService.reverse(
                ledgerTransactionId, request.reason(), callerName());
        return ResponseEntity.status(HttpStatus.CREATED).body(JournalEntryResponse.from(result));
    }

    @PostMapping("/postings/for-payment")
    @Operation(summary = "Book a completed payment",
            description = """
                    Takes the payment's business facts and lets the ledger decide how to book
                    them, returning once the posting is durably committed.

                    **Why this exists rather than the caller building its own posting.** The
                    saga needs to know the books are written *before* it captures the customer's
                    reservation — money should never move that has not been recorded. But
                    deciding which accounts a payment touches is an accounting decision, and it
                    belongs here with the chart of accounts rather than in the orchestrator. This
                    endpoint gives the caller a synchronous confirmation without moving the
                    accounting knowledge out of this service.

                    Uses exactly the same mapping as the `payment-completed` Kafka listener, so
                    when that event arrives later it derives the same idempotency key and is
                    recognised as a replay. One code path, two triggers, no double booking.

                    A provider fee, when present, is booked as a platform expense against that
                    provider's settlement balance — never against either customer wallet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booked"),
            @ApiResponse(responseCode = "200", description = "Already booked; the original is returned"),
            @ApiResponse(responseCode = "400", description = "The payment cannot be booked as it "
                    + "stands: a missing wallet, a self-transfer, or a fee naming no provider",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "A named settlement or system "
                    + "account does not exist",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The posting would drive an account "
                    + "below zero",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<JournalEntryResponse> bookPayment(
            @RequestBody PaymentCompletedPayload payment) {

        PostingResult result = ledgerService.post(
                postingBuilder.forPaymentCompleted(payment), callerName());

        return ResponseEntity
                .status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(JournalEntryResponse.from(result));
    }

    @GetMapping("/postings/{ledgerTransactionId}")
    @Operation(summary = "Read a posting by its idempotency key",
            description = "Used by a saga to discover whether a posting it may have submitted "
                    + "actually committed, after a lost response.")
    @ApiResponse(responseCode = "404", description = "No such posting",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JournalEntryResponse> getPosting(
            @PathVariable @NotBlank @Size(max = 80) String ledgerTransactionId) {
        return ResponseEntity.ok(
                JournalEntryResponse.from(ledgerService.getPosting(ledgerTransactionId)));
    }

    @GetMapping("/payments/{paymentId}/postings")
    @Operation(summary = "Every posting produced by one payment",
            description = "The transfer, plus any refunds or reversals that followed it.")
    public ResponseEntity<List<JournalEntryResponse>> getPostingsForPayment(
            @PathVariable @NotBlank @Size(max = 48) String paymentId) {
        return ResponseEntity.ok(ledgerService.getPostingsForPayment(paymentId).stream()
                .map(JournalEntryResponse::from)
                .toList());
    }

    @GetMapping("/wallet-liabilities")
    @Operation(summary = "Wallet liability balances as the ledger sees them",
            description = """
                    For cross-checking against wallet-service's own balances. The two must
                    agree: a divergence means money was created or destroyed on one side of the
                    platform without the other knowing, which is the single most important
                    discrepancy to be able to detect.

                    Comparable only because the ledger books deposits and withdrawals as well
                    as transfers. A ledger fed by payment events alone would be missing every
                    deposit and the comparison would always fail.""")
    public ResponseEntity<WalletLiabilitySnapshot> walletLiabilities(
            @RequestParam(defaultValue = "INR")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency) {
        return ResponseEntity.ok(reconciliationService.walletLiabilities(currency));
    }

    /**
     * The calling client's identity, recorded on the entry as its {@code source}.
     *
     * <p>Taken from the validated token, never from a request field. "Who booked this" is the
     * first question asked about an unexpected journal entry, and an answer a caller could
     * choose for itself would be worthless.
     */
    private String callerName() {
        return CurrentUser.find()
                .map(CurrentUser.AuthenticatedCaller::userId)
                .orElse("unknown");
    }
}
