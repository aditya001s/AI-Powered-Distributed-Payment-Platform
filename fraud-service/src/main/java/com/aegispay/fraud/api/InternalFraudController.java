package com.aegispay.fraud.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.security.Roles;
import com.aegispay.fraud.api.FraudDtos.AssessPaymentRequest;
import com.aegispay.fraud.api.FraudDtos.AssessmentView;
import com.aegispay.fraud.api.FraudDtos.EngineInfoView;
import com.aegispay.fraud.service.FraudQueryService;
import com.aegispay.fraud.service.FraudScoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Service-to-service risk assessment: the endpoint the saga calls at its risk step.
 *
 * <p><strong>Not reachable from the internet.</strong> The gateway has no route for
 * {@code /internal/**} and denies the prefix outright, and every handler here requires a
 * client-credentials token carrying {@code ROLE_SERVICE}. Two independent controls, because an
 * endpoint that decides whether a payment proceeds is worth exactly as much protection as one that
 * moves money.
 *
 * <p><strong>Why the saga calls this synchronously rather than over Kafka.</strong> The saga cannot
 * continue without a verdict — the whole point of the risk step is to gate the provider
 * authorisation — so an event-driven request/reply would park the saga awaiting a reply and the
 * recovery sweep would then see a state that has not advanced and treat it as stalled. Same
 * reasoning as the ledger call in Phase 5.
 *
 * <p><strong>Assessment is idempotent on the payment id.</strong> A redelivery or a resumed saga
 * gets the stored verdict back rather than a fresh one. A payment whose risk verdict changed between
 * attempts would be indefensible in a dispute, and would mean a customer could be held on one attempt
 * and approved on the next purely by timing.
 */
@RestController
@RequestMapping("/internal/fraud")
@Tag(name = "Internal fraud assessment",
        description = "Score a payment for fraud risk. Requires ROLE_SERVICE.")
@PreAuthorize("hasAuthority('" + Roles.ROLE_SERVICE + "')")
public class InternalFraudController {

    private final FraudScoringService scoring;
    private final FraudQueryService queries;

    public InternalFraudController(FraudScoringService scoring, FraudQueryService queries) {
        this.scoring = scoring;
        this.queries = queries;
    }

    @PostMapping("/assess")
    @Operation(summary = "Assess one payment for fraud risk",
            description = """
                    Builds the behavioural feature vector from this service's own history, runs the
                    deterministic rules, asks the model for a calibrated probability, and returns a
                    combined verdict.

                    **Idempotent on `paymentId`.** A second call returns the stored verdict
                    unchanged rather than re-scoring, so a Kafka redelivery or a resumed saga cannot
                    produce a different answer for the same payment.

                    **Always 200, even when the model is down.** `degradedMode: true` means
                    ml-service was unreachable and the deterministic rules alone decided. Read
                    `riskLevel` only alongside that flag: `LOW` with `degradedMode: true` means
                    "nothing was found", not "nothing is there". Refusing every payment while the
                    model is unavailable would turn a fraud-service outage into a total payment
                    outage, so the default is to fall back and mark it — an operator who prefers the
                    other trade-off sets `aegis.fraud.ml.reject-when-unavailable`.

                    **`decision` is the field to act on.** `APPROVE` continues, `REVIEW` continues
                    but raises an alert, `HOLD` stops the payment with the customer's funds still
                    reserved until an analyst decides, `REJECT` refuses it terminally. `HOLD` is
                    reachable from a high probability; `REJECT` is reachable only from a rule
                    resting on prior confirmed fraud, never from a score.

                    Behavioural aggregates are **not** taken from the request. They are computed
                    here from this service's own read model, so a caller cannot influence the score
                    by choosing what to report, and they cover the window **prior to** this payment
                    — the payment being scored is excluded from every aggregate by id.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verdict returned. Check degradedMode."),
            @ApiResponse(responseCode = "400", description = "The request is not a valid payment "
                    + "to assess",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Not a service token",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AssessmentView> assess(@Valid @RequestBody AssessPaymentRequest request) {

        return ResponseEntity.ok(AssessmentView.from(scoring.assess(
                request.paymentId(),
                request.transactionId(),
                request.initiatorUserId(),
                request.destinationWalletId(),
                request.toMoney(),
                request.paymentType(),
                request.riskSignals(),
                // Falls back to now when the caller omits it. The aggregates are windowed from this
                // instant, so using the caller's value when it has one keeps the score anchored to
                // when the customer actually transacted rather than to when this call happened to
                // run — which differs by however long the saga queued.
                request.occurredAt() == null ? Instant.now() : request.occurredAt())));
    }

    @GetMapping("/assessments/{paymentId}")
    @Operation(summary = "Read the stored verdict for a payment",
            description = """
                    Used by a saga to discover whether an assessment it may have requested actually
                    committed, after a lost response. Cheaper and safer than calling `/assess`
                    again: it cannot create anything.""")
    @ApiResponse(responseCode = "404", description = "That payment has not been assessed",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AssessmentView> assessment(
            @PathVariable @NotBlank @Size(max = 48) String paymentId) {
        return ResponseEntity.ok(queries.assessmentForPayment(paymentId));
    }

    @GetMapping("/engine")
    @Operation(summary = "Engine configuration and model provenance",
            description = """
                    The configured thresholds, every rule id this engine can emit, and ml-service's
                    own model metadata — including its training metrics and the synthetic-data
                    caveat.

                    `model` is null when ml-service is unreachable, which is the answer an operator
                    checking a degraded verdict is looking for.""")
    public ResponseEntity<EngineInfoView> engine() {
        return ResponseEntity.ok(queries.engineInfo());
    }
}
