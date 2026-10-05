package com.aegispay.payment.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.payment.api.PaymentDtos.CreatePaymentRequest;
import com.aegispay.payment.api.PaymentDtos.PaymentResponse;
import com.aegispay.payment.api.PaymentDtos.PaymentSummary;
import com.aegispay.payment.api.PaymentDtos.RefundRequest;
import com.aegispay.payment.api.PaymentDtos.RefundResponse;
import com.aegispay.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing payment endpoints.
 *
 * <p>The payer is always the authenticated user. The source wallet is resolved from the
 * token, never accepted from the request body, so a caller cannot pay <em>from</em> someone
 * else's wallet by editing a field.
 *
 * <p>The client IP is read from the servlet request rather than the body for the same
 * reason: a client-supplied IP would let an attacker forge their apparent location and
 * defeat every geo-based fraud rule. The gateway overwrites {@code X-Forwarded-For} on the
 * way in, so the value here is trustworthy.
 */
@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments",
        description = "Submit payments for asynchronous processing, read history, issue refunds")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(summary = "Submit a payment for processing",
            description = """
                    Transfers funds from the authenticated user's wallet to a destination
                    wallet. Specify the destination by `destinationWalletId` or by
                    `destinationUserId`.

                    ### This returns 202, not 201

                    The payment is validated, persisted and **accepted**. It has not completed
                    yet. The response body carries status `CREATED` and the `Location` header
                    points at the payment; poll it, or wait for the notification, to learn the
                    outcome.

                    Processing is asynchronous because it involves routing the charge across
                    payment providers, retrying, and falling back to another provider when one
                    fails or declines. Holding the connection open for that would mean a request
                    that can take many seconds and a client timeout that says nothing about
                    whether the money moved. A `202` that is honest about being incomplete is
                    more useful than a `201` that is not.

                    A terminal status arrives by one of four routes, and they mean different
                    things: `COMPLETED` (charged and settled), `REJECTED` (refused — every
                    provider declined, or fraud controls said no; retrying will not help),
                    `FAILED` (something broke; retrying may well succeed), or `AUTHORIZED` while
                    still in flight.

                    ### Idempotency

                    **Requires an `Idempotency-Key` header.** This is the endpoint where a
                    duplicate costs real money, so the guarantee is layered: Redis answers a
                    replay in microseconds, and a composite primary key in PostgreSQL makes a
                    second execution impossible even if the cache is empty or down.

                    Replaying a key with the same body returns the original **acceptance**
                    response — the payment as it was when first submitted, so status `CREATED`
                    even if it has since completed. The idempotency store makes the write happen
                    once; it is not a status endpoint. Use `GET /api/payments/{paymentId}` for
                    the current state.

                    Replaying a key with a *different* body returns 409. Returning the first
                    response instead would silently drop the second transfer.

                    ### Nothing is reserved by this call

                    Funds are reserved by the saga, not here, so a request that is accepted and
                    then fails cannot leave money stranded by this endpoint. The saga releases
                    every reservation it takes, and wallet-service's hold-expiry sweeper is the
                    backstop behind that.""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted for processing. Not "
                    + "completed: poll the payment for its outcome."),
            @ApiResponse(responseCode = "400", description = "Validation failure, self-transfer, "
                    + "or mismatched currencies",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Destination wallet not found",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different "
                    + "payload, or an identical request is in flight",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Insufficient available balance",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "A dependency was unavailable. "
                    + "The payment was not accepted and no funds were moved.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<PaymentResponse> initiate(
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(description = "Client-generated unique key for this payment", required = true)
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey,
            HttpServletRequest httpRequest) {

        PaymentResponse response = paymentService.initiate(
                CurrentUser.requireUserId(),
                clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"),
                request,
                idempotencyKey);

        // 202 with a Location header: accepted, and here is where to look for the outcome. The
        // header is the part that makes the contract usable without reading the documentation.
        return ResponseEntity.accepted()
                .header("Location", "/api/payments/" + response.paymentId())
                .body(response);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment",
            description = """
                    Readable by either party to the payment. Anyone else receives 404 rather
                    than 403, because confirming that a payment exists between two other
                    people is itself a disclosure.""")
    @ApiResponse(responseCode = "404", description = "Not found, or the caller is not a party to it",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable @Size(max = 48) String paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId, CurrentUser.requireUserId()));
    }

    @GetMapping("/history")
    @Operation(summary = "Get the authenticated user's payment history",
            description = "Newest first. Optionally filtered by status.")
    public ResponseEntity<PageResponse<PaymentSummary>> history(
            @RequestParam(required = false)
            @Parameter(description = "Filter by lifecycle state, e.g. COMPLETED or FAILED")
            PaymentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(paymentService.history(
                CurrentUser.requireUserId(), status, pageable));
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund a payment",
            description = """
                    Reverses a completed payment in whole or in part. Omit `amount` to refund
                    everything not already refunded.

                    Requires an `Idempotency-Key` header — a retried refund that credited
                    twice would be a direct loss.

                    The money moves in reverse: the original payee is debited and the payer
                    credited. That means a refund can legitimately fail with 422 if the payee
                    has already spent the funds. Cumulative refunds can never exceed the
                    original amount; that limit is enforced in the domain and by a database
                    constraint.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refunded"),
            @ApiResponse(responseCode = "400", description = "Refund exceeds the remaining refundable amount",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The payment is not in a refundable state",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "The payee has insufficient funds to refund from",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<RefundResponse> refund(
            @PathVariable @Size(max = 48) String paymentId,
            @Valid @RequestBody RefundRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 120) String idempotencyKey) {

        return ResponseEntity.ok(paymentService.refund(
                paymentId, CurrentUser.requireUserId(), request, idempotencyKey));
    }

    /**
     * Resolves the client IP.
     *
     * <p>{@code X-Forwarded-For} is honoured because every request arrives through the
     * gateway, so the socket address would always be the gateway's. Only the first entry is
     * taken, and this is safe <em>only</em> because the ingress overwrites the header on the
     * way in — trusting it on a directly-exposed service would let any caller forge their
     * source IP.
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String candidate = forwardedFor.split(",")[0].trim();
            if (!candidate.isEmpty() && candidate.length() <= 45
                    && candidate.matches("^[0-9a-fA-F:.]+$")) {
                return candidate;
            }
        }
        return request.getRemoteAddr();
    }
}
