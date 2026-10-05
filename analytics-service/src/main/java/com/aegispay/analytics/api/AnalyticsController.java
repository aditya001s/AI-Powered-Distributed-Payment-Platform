package com.aegispay.analytics.api;

import com.aegispay.analytics.api.AnalyticsDtos.OverviewView;
import com.aegispay.analytics.api.AnalyticsDtos.PaymentFactView;
import com.aegispay.analytics.api.AnalyticsDtos.PerformanceView;
import com.aegispay.analytics.api.AnalyticsDtos.ProjectionInfoView;
import com.aegispay.analytics.api.AnalyticsDtos.RiskDistributionView;
import com.aegispay.analytics.api.AnalyticsDtos.TimeSeriesView;
import com.aegispay.analytics.config.AnalyticsProperties;
import com.aegispay.analytics.service.AnalyticsQueryService;
import com.aegispay.analytics.service.AnalyticsWindow;
import com.aegispay.analytics.service.Granularity;
import com.aegispay.common.api.ApiError;
import com.aegispay.common.api.PageResponse;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Platform analytics for the admin dashboard.
 *
 * <h2>What this service adds, and what it deliberately does not</h2>
 *
 * <p>It answers only questions nothing else can: aggregates and time series across every customer, and a
 * platform-wide payment list. payment-service is customer-scoped and has no cross-customer view, and the
 * orchestrator's saga list describes execution rather than outcome.
 *
 * <p>Everything else the dashboard shows is fetched from the service that owns it — the alert queue and
 * risk verdicts from fraud-service, provider health from payment-orchestrator, investigation narratives
 * from ai-investigation-service, delivery failures from notification-service, the trial balance from
 * ledger-service. Re-serving those through here would create a second, staler copy of each, and a
 * dashboard reading a stale copy of a fraud alert would show an analyst a payment that had already been
 * decided.
 *
 * <h2>Read-only, structurally</h2>
 *
 * <p>There is no write endpoint on this controller and no client for any other service. Approve and block
 * are performed by the dashboard calling {@code POST /api/fraud/alerts/{alertId}/decide} directly, so the
 * decision is attributed to the analyst's own token and recorded in fraud-service's append-only trail.
 * Proxying it through here would put an extra hop between the person and the audit record, and would make
 * this service's identity part of the chain of custody for a decision it played no part in.
 *
 * <h2>Every response carries its own freshness</h2>
 *
 * <p>A projection that stops updating keeps serving the last numbers it had, confidently. An operator
 * reading "0 payments held" cannot tell whether the queue is clear or a consumer died three hours ago, and
 * those call for opposite responses. So the staleness figure is attached to each response rather than
 * exposed on a separate endpoint a caller could forget to check.
 */
@RestController
@RequestMapping("/api/admin/analytics")
@Tag(name = "Platform analytics",
        description = "Cross-customer aggregates, time series and the platform-wide payment list")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyAuthority('" + Roles.ROLE_ADMIN + "','" + Roles.ROLE_FRAUD_ANALYST + "')")
public class AnalyticsController {

    private final AnalyticsQueryService queries;
    private final AnalyticsProperties properties;

    public AnalyticsController(AnalyticsQueryService queries, AnalyticsProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    // -------------------------------------------------------------- overview

    @GetMapping("/overview")
    @Operation(summary = "The KPI tiles for a time window",
            description = """
                    Every figure comes from one query over one window, so they are mutually consistent. A
                    query per tile would let `payments` and `completed` be computed instants apart, which
                    under load looks exactly like lost payments.

                    Three numbers deserve attention beyond the headline volume:

                    * `degradedVerdicts` — verdicts reached **without** the fraud model, on rules alone.
                      These produce no error anywhere: the payments succeed and their scores read 0.0. A
                      rising count means the platform has been deciding blind.
                    * `onHold` — customers whose funds are reserved waiting on a human. Read it next to
                      `inFlight`: a rising `inFlight` that is entirely held payments is a stuck review
                      queue, not slow processing.
                    * `fellBack` — payments that needed more than one provider. Routing working, but a
                      rising rate means a rail is failing before it fails outright.

                    `successRate` is null rather than zero when nothing reached a terminal state in the
                    window. A zero rate says every payment failed; null says none finished yet, and on a
                    quiet morning those look identical on a tile while meaning opposite things.""")
    @ApiResponse(responseCode = "400", description = "The window is reversed, too long, or too fine "
            + "for its span",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<OverviewView> overview(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Granularity granularity) {

        return ResponseEntity.ok(queries.overview(AnalyticsWindow.resolve(from, to, granularity)));
    }

    // ------------------------------------------------------------ time series

    @GetMapping("/timeseries")
    @Operation(summary = "Volume and outcome over time",
            description = """
                    One bucket per interval, **including empty ones**. A missing bucket would let a chart
                    join a straight line across a period of no activity — showing smooth traffic through an
                    outage, which is the opposite of what an operator needs to see.

                    Granularity is a closed set (`minute`, `hour`, `day`, `week`) and each has a maximum
                    span, because an hourly series over five years is 43,800 buckets: the query would
                    succeed, return megabytes, and render as a solid block. Asking for a span too long for
                    the requested granularity is **refused with the granularity to use instead**, never
                    silently coarsened — quietly returning day buckets for a request that asked for minutes
                    would make the chart's own axis labels wrong.

                    Omitting `granularity` selects the finest resolution the span allows.""")
    @ApiResponse(responseCode = "400", description = "The window is reversed, too long, or too fine "
            + "for its span",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TimeSeriesView> timeSeries(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Granularity granularity) {

        return ResponseEntity.ok(queries.timeSeries(AnalyticsWindow.resolve(from, to, granularity)));
    }

    // ------------------------------------------------------------------- risk

    @GetMapping("/risk")
    @Operation(summary = "Risk distribution and the score histogram",
            description = """
                    Bands come with how their payments actually **ended**, because the band alone says
                    nothing about whether the controls did anything. "40 HIGH-risk payments" is not
                    actionable; "40 HIGH, of which 31 held and 9 settled" is.

                    `degradedVerdicts` is the field to read first. A verdict reached without the model
                    scores 0.0 and therefore lands in the LOW band — so a model outage renders as a
                    reassuringly clean week unless this number is read alongside the chart. It is the single
                    most misleading thing this endpoint could omit.

                    `UNSCORED` is a real band, not a rendering artefact: a payment rejected before scoring
                    never got a risk level, and hiding those would make the band counts fail to add up to
                    the payment total.""")
    public ResponseEntity<RiskDistributionView> risk(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Granularity granularity) {

        return ResponseEntity.ok(
                queries.riskDistribution(AnalyticsWindow.resolve(from, to, granularity)));
    }

    // ------------------------------------------------------------ performance

    @GetMapping("/performance")
    @Operation(summary = "Settlement latency, provider share and failure causes",
            description = """
                    Latency percentiles are computed over **completed payments only**. An in-flight payment
                    has no duration, and counting it as zero would drag every percentile down — making the
                    platform look faster the more backlog it accumulated.

                    Read `samples` before the percentiles. A p99 over four payments is not a p99.

                    p50/p95/p99 rather than a mean, because a mean hides the tail entirely and the tail is
                    what a customer experiences as "the app is broken".

                    `failures` is grouped by code rather than counted, because a bare failure count prompts
                    the wrong question. Grouped, it says whether one rail is timing out or customers are
                    running out of money — which call for completely different responses.""")
    public ResponseEntity<PerformanceView> performance(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Granularity granularity) {

        return ResponseEntity.ok(
                queries.performance(AnalyticsWindow.resolve(from, to, granularity)));
    }

    // ---------------------------------------------------------- payment list

    @GetMapping("/payments")
    @Operation(summary = "The platform-wide payment list",
            description = """
                    The view nothing else offers: payment-service is customer-scoped, and the orchestrator's
                    saga list describes execution rather than outcome.

                    Filters **combine**, unlike fraud-service's mutually-exclusive ones, because a dashboard
                    genuinely does want "failed payments over 10,000 on SWIFTPAY yesterday".

                    Deliberately absent from every row: the payment description and any device or location
                    data. The description is customer-written free text and the risk signals are data the
                    customer never knowingly provided — this list is read by staff looking at other people's
                    payments, and neither is needed to do that. fraud-service shows the signals where there
                    is a reason to.

                    Sorted newest first unless a sort is given.""")
    public ResponseEntity<PageResponse<PaymentFactView>> payments(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) @Size(max = 40) String userId,
            @RequestParam(required = false) @Size(max = 32) String providerId,
            @RequestParam(required = false) @Min(0) Long minAmountMinor,

            /*
             * A score floor rather than a band, because the question an analyst reviewing a threshold
             * asks is "show me everything over 70", which spans HIGH and CRITICAL.
             */
            @RequestParam(required = false) @Min(0) @Max(100) Integer minRiskScore,

            /*
             * Verdicts reached without the ML model, on deterministic rules alone.
             *
             * Its own filter rather than something to infer from the risk level, because that is exactly
             * what cannot be inferred: a LOW band full of degraded verdicts does not mean low risk, it
             * means nothing was found by the half of the system that was working. There is a partial
             * index for this predicate.
             *
             * Defaults to false rather than being nullable: absent and false both mean "do not restrict",
             * so there is no third state worth representing.
             */
            @RequestParam(required = false, defaultValue = "false") boolean degradedOnly,

            @PageableDefault(size = 25) Pageable pageable) {

        AnalyticsWindow window = AnalyticsWindow.resolve(from, to, null);

        return ResponseEntity.ok(PageResponse.of(
                queries.searchPayments(window, status, riskLevel, userId, providerId,
                        minAmountMinor, minRiskScore, degradedOnly, bound(pageable)),
                PaymentFactView::from));
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "One payment, as the read model has it",
            description = """
                    A 404 here means the payment has not been **projected**, which is not the same as not
                    existing — a payment accepted a moment ago may not have reached this read model yet. The
                    error says so and names payment-service as authoritative.

                    `firstSeenAt` next to `createdAt` is how a replay is told apart from live traffic: a
                    large gap means the row was backfilled, which is what stops a backfill looking like a
                    traffic spike.""")
    @ApiResponse(responseCode = "404", description = "Not in the read model — possibly not projected yet",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PaymentFactView> payment(
            @PathVariable @NotBlank @Size(max = 48) String paymentId) {

        return ResponseEntity.ok(PaymentFactView.from(queries.requirePayment(paymentId)));
    }

    // ------------------------------------------------------------- projection

    @GetMapping("/projection")
    @Operation(summary = "What the read model covers, and how current it is",
            description = """
                    Publishes the boundary rather than leaving it to be inferred from which endpoints happen
                    to exist. `notProjected` names the topics this service deliberately ignores **and where
                    to read them instead** — otherwise "does the dashboard know about refunds" is answerable
                    only by reading the listeners.

                    `derivedAggregates` being true is the correctness claim: every total and rate is
                    computed from one row per payment rather than from stored counters, so at-least-once
                    event delivery cannot inflate a figure. A redelivered event re-writes the same row,
                    which is a no-op.

                    Per-stream lag matters more than the aggregate. A single global figure would only look
                    stale once every consumer had stopped; per stream, a broken `fraud-checked` consumer is
                    visible while payments still flow.""")
    public ResponseEntity<ProjectionInfoView> projection() {
        return ResponseEntity.ok(queries.projectionInfo());
    }

    /**
     * Caps the page size and defaults the sort.
     *
     * <p>The cap matters more here than on a customer endpoint: a customer-scoped list is naturally limited
     * by how many payments one person made, while this one is limited only by what a caller asks for.
     */
    private Pageable bound(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), properties.getMaximumPageSize());
        Sort sort = pageable.getSort().isSorted()
                ? pageable.getSort()
                : Sort.by(Sort.Direction.DESC, "createdAt");

        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }
}
