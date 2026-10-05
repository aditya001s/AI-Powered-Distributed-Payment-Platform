package com.aegispay.fraud.api;

import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.security.CurrentUser;
import com.aegispay.common.security.Roles;
import com.aegispay.fraud.api.FraudDtos.AlertView;
import com.aegispay.fraud.api.FraudDtos.AnalystDecisionRequest;
import com.aegispay.fraud.api.FraudDtos.AnalystDecisionView;
import com.aegispay.fraud.api.FraudDtos.AssessmentDetailView;
import com.aegispay.fraud.api.FraudDtos.AssessmentView;
import com.aegispay.fraud.api.FraudDtos.EngineInfoView;
import com.aegispay.fraud.api.FraudDtos.FraudStatsView;
import com.aegispay.fraud.domain.FraudAlert;
import com.aegispay.fraud.service.FraudMutationService;
import com.aegispay.fraud.service.FraudQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;

/**
 * The analyst console: read verdicts, work the alert queue, decide held payments.
 *
 * <p><strong>Staff only, enforced per handler.</strong> Every method carries
 * {@code @PreAuthorize}, and that is doing real work rather than duplicating a chain rule: the shared
 * security chain restricts {@code /api/admin/**}, and these paths are {@code /api/fraud/**} because
 * the gateway routes by prefix. Without the method-level annotation, any authenticated customer could
 * read every other customer's risk score, device history and location — a far worse leak than the
 * payment data itself, because it is data the customer never knowingly provided.
 *
 * <p><strong>There is no endpoint that sets a score or edits a verdict.</strong> An assessment is a
 * statement about a payment at a moment in time, and an analyst's disagreement with it is recorded as
 * a decision <em>alongside</em> it rather than as an edit. That is what lets a dispute six months
 * later see both the machine's verdict and the human's judgement about it, instead of only whichever
 * one was written last.
 *
 * <p><strong>Deciding a payment is asynchronous by design.</strong> The decision commits here and an
 * event resumes the parked saga; this endpoint does not wait for the provider charge. A synchronous
 * resume would make an analyst's click depend on a provider being up, and a timeout would leave them
 * unsure whether the decision was recorded at all.
 */
@RestController
@RequestMapping("/api/fraud")
@Tag(name = "Fraud intelligence",
        description = "Risk verdicts, the analyst alert queue, and decisions on held payments")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
public class FraudController {

    private final FraudQueryService queries;
    private final FraudMutationService mutations;

    public FraudController(FraudQueryService queries, FraudMutationService mutations) {
        this.queries = queries;
        this.mutations = mutations;
    }

    // ----------------------------------------------------------- assessments

    @GetMapping("/assessments")
    @Operation(summary = "Browse risk verdicts",
            description = """
                    Newest first. Filters are mutually exclusive and applied in the order
                    `userId`, `decision`, `riskLevel` — the queue this serves asks "today's holds" or
                    "everything for this customer", never both at once.

                    Watch `degradedMode`. A page of `LOW` verdicts that are all degraded means the
                    model has been unreachable, not that traffic has been clean.""")
    public ResponseEntity<PageResponse<AssessmentView>> assessments(
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) FraudDecision decision,
            @RequestParam(required = false) @Size(max = 40) String userId,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(PageResponse.of(
                queries.browseAssessments(riskLevel, decision, userId, pageable),
                AssessmentView::from));
    }

    @GetMapping("/assessments/{paymentId}")
    @Operation(summary = "The verdict on one payment")
    @ApiResponse(responseCode = "404", description = "That payment has not been assessed",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AssessmentView> assessment(
            @PathVariable @NotBlank @Size(max = 48) String paymentId) {
        return ResponseEntity.ok(queries.assessmentForPayment(paymentId));
    }

    @GetMapping("/assessments/{paymentId}/detail")
    @Operation(summary = "The verdict, the exact feature vector, and every human decision",
            description = """
                    The dispute view. Three things together make an automated decision defensible:
                    the score, the exact inputs it was computed from, and who overrode it if anyone
                    did.

                    In `featureSnapshot`, a null value means the fact was genuinely unknown — never
                    zero. A brand-new account has no average payment amount, and recording that as
                    zero would tell the model the account's usual amount is nothing, which is a
                    different and false claim.

                    The snapshot is null when the verdict was reached in degraded mode: nothing was
                    scored, so there is no vector to show.""")
    @ApiResponse(responseCode = "404", description = "That payment has not been assessed",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AssessmentDetailView> assessmentDetail(
            @PathVariable @NotBlank @Size(max = 48) String paymentId) {
        return ResponseEntity.ok(queries.assessmentDetail(paymentId));
    }

    // ----------------------------------------------------------------- alerts

    @GetMapping("/alerts")
    @Operation(summary = "The analyst work queue",
            description = """
                    Unresolved alerts, most urgent first.

                    Ordered by `blocksPayment` **before** risk score, deliberately. A held payment is
                    a customer waiting right now with their funds reserved; a review on a payment
                    that has already settled can wait until tomorrow. Sorting by score alone would
                    bury the first behind the second, and the customer would feel it.

                    `blockingOnly=true` narrows it to just those.""")
    public ResponseEntity<PageResponse<AlertView>> alerts(
            @RequestParam(defaultValue = "false") boolean blockingOnly,
            @RequestParam(required = false) FraudAlert.Status status,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(PageResponse.of(
                status == null
                        ? queries.queue(blockingOnly, pageable)
                        : queries.alertsByStatus(status, pageable),
                AlertView::from));
    }

    @GetMapping("/alerts/{alertId}")
    @Operation(summary = "Read one alert")
    @ApiResponse(responseCode = "404", description = "No such alert",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AlertView> alert(
            @PathVariable @NotBlank @Size(max = 48) String alertId) {
        return ResponseEntity.ok(queries.alert(alertId));
    }

    @GetMapping("/alerts/{alertId}/decisions")
    @Operation(summary = "Every human decision on one alert, oldest first")
    @ApiResponse(responseCode = "404", description = "No such alert",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<AnalystDecisionView>> alertDecisions(
            @PathVariable @NotBlank @Size(max = 48) String alertId) {
        return ResponseEntity.ok(queries.decisionsForAlert(alertId));
    }

    @PostMapping("/alerts/{alertId}/take")
    @Operation(summary = "Assign an alert to yourself",
            description = """
                    Claims the alert so two analysts do not investigate the same payment and reach
                    opposite conclusions, with whichever committed second winning by accident.

                    The assignee comes from your token, never from the request. Optimistic locking on
                    the alert row means the second person to claim an open alert is told to refresh
                    rather than silently overwriting the first.

                    Refuses an alert that is already resolved.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned to you"),
            @ApiResponse(responseCode = "404", description = "No such alert",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Already resolved, or claimed "
                    + "concurrently by someone else",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AlertView> take(
            @PathVariable @NotBlank @Size(max = 48) String alertId) {
        return ResponseEntity.ok(AlertView.from(
                mutations.takeAlert(alertId, CurrentUser.requireUserId())));
    }

    @PostMapping("/alerts/{alertId}/decide")
    @Operation(summary = "Release or refuse a held payment",
            description = """
                    Records the decision, closes the alert, and emits the event that resumes the
                    parked payment.

                    **`RELEASE`** lets the payment continue: the saga charges a provider, posts the
                    ledger entry and captures the customer's reservation. **`REJECT`** refuses it
                    terminally and the reservation is released.

                    **`reason` is mandatory** and enforced by a database CHECK as well as by
                    validation. It is the only record of why a customer's payment was released or
                    refused, and it is what an audit reads. `decidedBy` is taken from your token,
                    never from the request — "who authorised this" is the first question asked about
                    a released payment that turned out to be fraud, and an answer the caller could
                    choose for itself would be worthless.

                    **The decision is append-only.** It does not overwrite the machine's verdict; a
                    database trigger refuses `UPDATE` and `DELETE` on the decision table, so both the
                    original assessment and the human judgement about it survive.

                    **Returns as soon as the decision is durable**, before the payment finishes. The
                    resume happens asynchronously through the outbox, so an analyst's click does not
                    depend on a provider being reachable, and a lost response cannot leave the alert
                    resolved with the payment parked forever.

                    Deciding an alert that was only a `REVIEW` — one that never stopped the payment —
                    is accepted and recorded. The orchestrator finds no parked saga and does nothing,
                    which is correct: the payment already settled, and the decision is still worth
                    keeping because it is what marks the payment as confirmed fraud for future
                    scoring.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decision recorded; the payment will "
                    + "resume or be refused shortly"),
            @ApiResponse(responseCode = "400", description = "No reason was given",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No such alert",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The alert has already been decided",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
    public ResponseEntity<AnalystDecisionView> decide(
            @PathVariable @NotBlank @Size(max = 48) String alertId,
            @Valid @RequestBody AnalystDecisionRequest request) {

        return ResponseEntity.ok(AnalystDecisionView.from(mutations.applyDecision(
                alertId, request.action(), request.reason(), CurrentUser.requireUserId())));
    }

    // ------------------------------------------------------------- oversight

    @GetMapping("/stats")
    @Operation(summary = "Fraud engine overview",
            description = """
                    The last 24 hours, one window for every number so they can be compared against
                    each other.

                    Two are worth alerting on. `degradedAssessments24h` counts verdicts reached
                    without the model — a degraded fraud posture that produces no errors and is
                    otherwise invisible. `blockingAlerts` counts customers whose funds are reserved
                    waiting on a human.

                    `scoresByModelVersion` is the drift signal. A fraud model degrades silently: it
                    keeps returning numbers, and the shape of their distribution moves first.
                    Grouping by build means a shift can be attributed to a deployment rather than to
                    the world.""")
    public ResponseEntity<FraudStatsView> stats() {
        return ResponseEntity.ok(queries.stats());
    }

    @GetMapping("/engine")
    @Operation(summary = "Engine configuration and model provenance",
            description = """
                    The configured thresholds, every rule id this engine can emit, and ml-service's
                    own model metadata.

                    The model metadata includes the training metrics and the **synthetic-data
                    caveat**: the model is trained on generated transactions, so its reported
                    precision and recall describe the generator's fraud patterns and not any real
                    population. Read them as evidence that the pipeline works, not as a claim about
                    real-world performance.

                    `model` is null when ml-service is unreachable, which is exactly the answer
                    someone investigating a degraded verdict is looking for.""")
    public ResponseEntity<EngineInfoView> engine() {
        return ResponseEntity.ok(queries.engineInfo());
    }
}
