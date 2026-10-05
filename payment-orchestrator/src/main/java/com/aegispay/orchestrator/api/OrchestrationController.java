package com.aegispay.orchestrator.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.common.security.Roles;
import com.aegispay.orchestrator.api.OrchestratorDtos.DisableProviderRequest;
import com.aegispay.orchestrator.api.OrchestratorDtos.OrchestrationStatsView;
import com.aegispay.orchestrator.api.OrchestratorDtos.ProviderHealthView;
import com.aegispay.orchestrator.api.OrchestratorDtos.RoutingExplanationView;
import com.aegispay.orchestrator.api.OrchestratorDtos.SagaSummary;
import com.aegispay.orchestrator.api.OrchestratorDtos.SagaView;
import com.aegispay.orchestrator.domain.SagaStatus;
import com.aegispay.orchestrator.service.OrchestrationQueryService;
import com.aegispay.orchestrator.service.ProviderAdminService;
import com.aegispay.orchestrator.service.SagaRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Operational view of orchestration: saga state, provider health, and the kill switch.
 *
 * <p><strong>Staff only.</strong> Every handler carries {@code @PreAuthorize}, and that is doing real
 * work rather than duplicating a chain rule: the shared security chain auto-restricts
 * {@code /api/admin/**}, and these paths are {@code /api/orchestration/**} because the gateway routes
 * by prefix to this service. Without method security a customer token could read every payment's saga,
 * which exposes counterparties, amounts and provider routing across the whole platform.
 *
 * <p><strong>There is no endpoint that starts a payment.</strong> Orchestration is triggered by
 * {@code payment-created} and by nothing else, so there is exactly one way in and no second path to
 * keep consistent with it. Nor is there one that advances a stuck saga: a saga moves only through the
 * orchestrator or the recovery sweep, both of which drive it through the state machine rather than
 * around it.
 */
@RestController
@RequestMapping("/api/orchestration")
@Tag(name = "Orchestration",
        description = "Saga state, provider health and routing diagnostics")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
public class OrchestrationController {

    private final OrchestrationQueryService queries;
    private final ProviderAdminService providerAdmin;
    private final SagaRecoveryService recovery;

    public OrchestrationController(OrchestrationQueryService queries,
                                   ProviderAdminService providerAdmin,
                                   SagaRecoveryService recovery) {
        this.queries = queries;
        this.providerAdmin = providerAdmin;
        this.recovery = recovery;
    }

    // ----------------------------------------------------------------- sagas

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "Read a payment's saga in full",
            description = """
                    State, step log and every provider call, together.

                    An incident needs all three: the state says where the payment stopped, the steps say
                    what was attempted, and the provider calls say why. The compensation handles
                    (`holdId`, `authorizationId`, `ledgerTransactionId`) are the ones to read first on a
                    failed saga — a non-null value means that side effect is still outstanding.""")
    @ApiResponse(responseCode = "404", description = "No saga exists for that payment",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SagaView> byPayment(
            @PathVariable @NotBlank @Size(max = 48) String paymentId) {
        return ResponseEntity.ok(queries.byPaymentId(paymentId));
    }

    @GetMapping("/sagas/{sagaId}")
    @Operation(summary = "Read a saga by its own id")
    @ApiResponse(responseCode = "404", description = "No such saga",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SagaView> bySaga(
            @PathVariable @NotBlank @Size(max = 48) String sagaId) {
        return ResponseEntity.ok(queries.bySagaId(sagaId));
    }

    @GetMapping("/sagas")
    @Operation(summary = "List sagas, newest first",
            description = "Optionally filtered by status or by the provider that settled them.")
    public ResponseEntity<PageResponse<SagaSummary>> list(
            @RequestParam(required = false) SagaStatus status,
            @RequestParam(required = false) @Size(max = 32) String providerId,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(PageResponse.of(
                queries.list(status, providerId, pageable), SagaSummary::from));
    }

    @GetMapping("/sagas/uncompensated")
    @Operation(summary = "Sagas that failed with a side effect possibly still outstanding",
            description = """
                    **Expected to be empty.** Every entry is money that may be reserved at a wallet or
                    authorised at a provider with nothing scheduled to release it, so this is what an
                    operator alert should point at.

                    Deliberately not auto-retried: by this point the automated compensation paths have
                    already failed, and repeating them would bury the incident rather than resolve it.""")
    public ResponseEntity<List<SagaSummary>> uncompensated() {
        return ResponseEntity.ok(queries.uncompensated().stream()
                .map(SagaSummary::from)
                .toList());
    }

    @GetMapping("/sagas/held")
    @Operation(summary = "Payments parked awaiting a fraud analyst",
            description = """
                    Longest-waiting first. Every entry is a customer whose funds are reserved and who
                    cannot spend them, so `waitingSeconds` is the field to act on — not the risk
                    score, which explains why the payment was held rather than why it is still held.

                    **These are deliberately excluded from the stalled-saga sweep.** A held payment
                    is parked, not abandoned: its `updatedAt` stops moving precisely because it is
                    behaving as designed, and recovering it would increment its attempt count on
                    every pass until a healthy payment looked like a chronic failure. The only thing
                    that moves one is an analyst's decision arriving on `fraud-decision-applied`.

                    Which also means this list growing is the signal that the decision consumer has
                    stopped working. Nothing else would report it: no errors, no failures, just
                    customers waiting.

                    Decisions are made in fraud-service (`POST /api/fraud/alerts/{alertId}/decide`),
                    not here. This service applies them; it does not make them.""")
    public ResponseEntity<List<OrchestratorDtos.HeldPaymentView>> held() {
        return ResponseEntity.ok(queries.heldForReview().stream()
                .map(OrchestratorDtos.HeldPaymentView::from)
                .toList());
    }

    @GetMapping("/stats")
    @Operation(summary = "Orchestration overview",
            description = "In-flight and terminal counts, throughput per provider, and how many "
                    + "payments needed a fallback — which is otherwise invisible, because a payment "
                    + "that fell back still looks like an ordinary success.")
    public ResponseEntity<OrchestrationStatsView> stats() {
        return ResponseEntity.ok(queries.stats());
    }

    // ------------------------------------------------------------- providers

    @GetMapping("/providers")
    @Operation(summary = "Provider health and availability",
            description = """
                    Three separate signals, reported together because they mean different things:

                    * `enabled` — the operator kill switch. Survives restarts.
                    * `observedHealth` — the last probe result. Advisory only.
                    * `circuitBreakerState` — what routing actually reads, because it reflects what this
                      instance is seeing right now.

                    `routable` combines all three, so nobody has to do it in their head mid-incident.""")
    public ResponseEntity<List<ProviderHealthView>> providers() {
        return ResponseEntity.ok(queries.providerHealth());
    }

    @GetMapping("/providers/routing")
    @Operation(summary = "Explain how a charge would be routed",
            description = """
                    Scores every provider for a hypothetical amount and says why any were excluded.

                    Answers "why did this payment go to the expensive provider", which is otherwise
                    answerable only by reading the scoring code and guessing at the inputs.""")
    public ResponseEntity<RoutingExplanationView> explainRouting(
            @RequestParam @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @RequestParam(defaultValue = "INR")
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @RequestParam(defaultValue = "P2P_TRANSFER") PaymentType paymentType) {

        return ResponseEntity.ok(
                queries.explainRouting(Money.of(amount, currency), paymentType));
    }

    @PostMapping("/providers/{providerId}/disable")
    @Operation(summary = "Take a provider out of service",
            description = """
                    Excludes the provider from routing immediately, and **persists** the decision so it
                    survives a rolling deploy. That persistence is the point: an in-memory switch would
                    be silently undone by the next restart, at the worst possible moment.

                    A reason is mandatory, and enforced by the schema as well. "Who turned this off and
                    why" is unanswerable an hour later otherwise, and the answer decides whether it is
                    safe to turn back on.

                    Does not affect payments already in flight through this provider: an authorisation
                    already granted still needs capturing or voiding.""")
    @ApiResponse(responseCode = "404", description = "No such provider",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public ResponseEntity<ProviderHealthView> disable(
            @PathVariable @NotBlank @Size(max = 32) String providerId,
            @Valid @RequestBody DisableProviderRequest request) {

        return ResponseEntity.ok(providerAdmin.disable(providerId, request.reason(),
                CurrentUser.requireUserId()));
    }

    @PostMapping("/providers/{providerId}/enable")
    @Operation(summary = "Return a provider to service",
            description = """
                    Clears the kill switch and resets the circuit breaker's window.

                    Observed health is reset to `UNKNOWN` rather than `HEALTHY`: whether the provider has
                    actually recovered is for the next probe to establish, not for the operator to
                    assert.""")
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public ResponseEntity<ProviderHealthView> enable(
            @PathVariable @NotBlank @Size(max = 32) String providerId) {
        return ResponseEntity.ok(providerAdmin.enable(providerId, CurrentUser.requireUserId()));
    }

    // -------------------------------------------------------------- recovery

    @PostMapping("/recovery/sweep")
    @Operation(summary = "Run the stalled-saga sweep now",
            description = """
                    Normally scheduled. Exposed so an operator does not have to wait for the next cycle
                    while a customer's funds sit reserved by an abandoned saga.

                    Safe to run on every replica: each saga is re-read under a row lock and driven from
                    its persisted state, so two concurrent sweeps cannot both advance the same one.""")
    @PreAuthorize("hasAuthority('" + Roles.ROLE_ADMIN + "')")
    public ResponseEntity<SweepResult> sweep() {
        return ResponseEntity.ok(new SweepResult(recovery.sweep()));
    }

    /** How many abandoned sagas the sweep picked up. */
    public record SweepResult(int recovered) {
    }
}
