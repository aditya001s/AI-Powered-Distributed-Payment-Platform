package com.aegispay.wallet.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.security.Roles;
import com.aegispay.wallet.api.WalletDtos.CaptureRequest;
import com.aegispay.wallet.api.WalletDtos.CaptureResponse;
import com.aegispay.wallet.api.WalletDtos.ExtendHoldRequest;
import com.aegispay.wallet.api.WalletDtos.HoldResponse;
import com.aegispay.wallet.api.WalletDtos.InternalWalletView;
import com.aegispay.wallet.api.WalletDtos.ReleaseRequest;
import com.aegispay.wallet.api.WalletDtos.ReserveRequest;
import com.aegispay.wallet.service.WalletMutationService;
import com.aegispay.wallet.service.WalletService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Service-to-service wallet operations: the reserve / capture / release primitives the
 * payment saga is built on.
 *
 * <p><strong>Not reachable from the internet.</strong> The gateway has no route for
 * {@code /internal/**} and additionally denies the path outright, and every handler here
 * requires a client-credentials token carrying {@code ROLE_SERVICE}. Two independent
 * controls, because these endpoints can move customer money without the customer being
 * present.
 *
 * <p><strong>All three are idempotent</strong>, which is what makes them safe for a saga
 * to retry:
 *
 * <ul>
 *   <li>{@code reserve} — one hold per payment, enforced by a unique constraint;</li>
 *   <li>{@code capture} — an already-captured hold returns the original outcome;</li>
 *   <li>{@code release} — releasing twice is a no-op.</li>
 * </ul>
 *
 * <p>In Phase 3 the payment service calls these directly. Phase 5 moves the orchestration
 * into the saga coordinator, which will add provider routing and compensation around the
 * same primitives — the contract here does not change.
 */
@RestController
@RequestMapping("/internal/wallets")
@Tag(name = "Internal wallet operations",
        description = "Reserve, capture and release. Requires ROLE_SERVICE.")
@PreAuthorize("hasAuthority('" + Roles.ROLE_SERVICE + "')")
public class InternalWalletController {

    private final WalletService walletService;
    private final WalletMutationService mutations;

    public InternalWalletController(WalletService walletService, WalletMutationService mutations) {
        this.walletService = walletService;
        this.mutations = mutations;
    }

    @GetMapping("/{walletId}")
    @Operation(summary = "Get a wallet's balance state",
            description = "Minimal view: balances and status only, no statement history.")
    public ResponseEntity<InternalWalletView> getWallet(
            @PathVariable @Size(max = 40) String walletId) {
        return ResponseEntity.ok(walletService.internalView(walletId));
    }

    @GetMapping("/by-user/{userId}")
    @Operation(summary = "Resolve a user's wallet in a currency",
            description = "Used when a payment names its payee by user rather than by wallet.")
    public ResponseEntity<InternalWalletView> getWalletByUser(
            @PathVariable @Size(max = 40) String userId,
            @RequestParam(defaultValue = "INR")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency) {
        return ResponseEntity.ok(walletService.internalViewByUser(userId, currency));
    }

    @PostMapping("/reserve")
    @Operation(summary = "Reserve funds for a payment",
            description = """
                    Makes funds unspendable without moving them: raises the wallet's held
                    total and leaves its balance untouched, so the customer still sees the
                    money as theirs.

                    Idempotent by construction — one hold per `paymentId`, enforced by a
                    unique constraint, so a retry cannot reserve the amount twice.

                    Returns 422 when available funds are insufficient. Reservations expire
                    (default 15 minutes) and are swept, so a crashed saga cannot strand a
                    customer's money indefinitely.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserved, or the existing reservation returned"),
            @ApiResponse(responseCode = "422", description = "Insufficient available balance",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Payment already reserved a different amount",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<HoldResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        Duration ttl = request.ttlSeconds() == null
                ? null
                : Duration.ofSeconds(request.ttlSeconds());

        return ResponseEntity.ok(mutations.reserve(
                request.paymentId(), request.walletId(), request.toMoney(), ttl));
    }

    @PostMapping("/capture")
    @Operation(summary = "Capture a reservation and credit the destination",
            description = """
                    Converts the reservation into a real debit and credits the destination
                    wallet **in one transaction**.

                    Deliberately a single operation. Debiting and crediting in two calls
                    would leave a window in which the money existed in neither wallet, and a
                    crash inside that window would destroy customer funds.

                    Locks both wallets in a globally consistent order, so simultaneous
                    A→B and B→A transfers cannot deadlock. Idempotent: an
                    already-captured payment returns its original outcome.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Captured, or the original outcome replayed"),
            @ApiResponse(responseCode = "404", description = "No reservation exists for this payment",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Reservation was released, or wallets mismatch",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<CaptureResponse> capture(@Valid @RequestBody CaptureRequest request) {
        return ResponseEntity.ok(mutations.captureAndTransfer(
                request.paymentId(),
                request.sourceWalletId(),
                request.destinationWalletId(),
                request.description()));
    }

    @PostMapping("/release")
    @Operation(summary = "Release a reservation without moving money",
            description = """
                    The compensating action for a rejected or failed payment.

                    Idempotent: releasing an already-released reservation is a no-op, because
                    compensation paths legitimately run more than once. Releasing a
                    *captured* reservation is refused — that would hand back money which was
                    legitimately taken.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Released, or already released"),
            @ApiResponse(responseCode = "409", description = "Reservation was already captured",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<HoldResponse> release(@Valid @RequestBody ReleaseRequest request) {
        return ResponseEntity.ok(mutations.release(
                request.paymentId(),
                request.reason() == null ? "Released by the payment saga" : request.reason()));
    }

    @PostMapping("/extend")
    @Operation(summary = "Extend a reservation's expiry",
            description = """
                    Keeps a reservation alive past the saga's own budget.

                    **Why this exists.** The default fifteen-minute lifetime is sized for a payment
                    that runs to completion. A payment held for fraud review does not — it stops and
                    waits for a human. Without an extension the hold-expiry sweeper would release
                    the reservation while the saga still believed the funds were held: the customer
                    could spend the money, and the analyst releasing the payment would capture
                    against a balance that had moved underneath them.

                    Monotonic and idempotent. A request for a shorter lifetime never shortens an
                    expiry that is already further out, because a shortened expiry could sweep a
                    reservation a saga is still relying on. Extending twice is harmless.

                    Capped at seven days per call. Extension must not become a way to freeze a
                    customer's money indefinitely, which is the failure the expiry exists to
                    prevent — it would simply arrive by extension instead of by omission. Reapply
                    the extension if a review genuinely runs longer.

                    Refuses a captured or released reservation: there is nothing left to hold, and
                    the caller needs to know that now rather than at capture time.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Extended; the new expiry is returned"),
            @ApiResponse(responseCode = "404", description = "No reservation exists for this payment",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The reservation is already captured "
                    + "or released",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<HoldResponse> extend(@Valid @RequestBody ExtendHoldRequest request) {
        return ResponseEntity.ok(mutations.extend(
                request.paymentId(), request.ttl(), request.reason()));
    }
}
