package com.aegispay.analytics.service;

import com.aegispay.analytics.api.AnalyticsDtos.FailureShareView;
import com.aegispay.analytics.api.AnalyticsDtos.FreshnessView;
import com.aegispay.analytics.api.AnalyticsDtos.LatencyView;
import com.aegispay.analytics.api.AnalyticsDtos.MoneyView;
import com.aegispay.analytics.api.AnalyticsDtos.OverviewView;
import com.aegispay.analytics.api.AnalyticsDtos.PerformanceView;
import com.aegispay.analytics.api.AnalyticsDtos.ProjectionInfoView;
import com.aegispay.analytics.api.AnalyticsDtos.ProviderShareView;
import com.aegispay.analytics.api.AnalyticsDtos.RiskBandView;
import com.aegispay.analytics.api.AnalyticsDtos.RiskDistributionView;
import com.aegispay.analytics.api.AnalyticsDtos.ScoreBucketView;
import com.aegispay.analytics.api.AnalyticsDtos.StreamView;
import com.aegispay.analytics.api.AnalyticsDtos.TimeBucketView;
import com.aegispay.analytics.api.AnalyticsDtos.TimeSeriesView;
import com.aegispay.analytics.api.AnalyticsDtos.WindowView;
import com.aegispay.analytics.config.AnalyticsProperties;
import com.aegispay.analytics.domain.PaymentFact;
import com.aegispay.analytics.domain.StreamState;
import com.aegispay.analytics.repository.PaymentFactRepository;
import com.aegispay.analytics.repository.StreamStateRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Every read the dashboard makes against the read model.
 *
 * <p>All read-only, and that is not decoration: a projection is rebuildable, so an accidental write on a
 * query path would not corrupt anything permanently — but it would make the read model disagree with the
 * events it is supposed to be derived from, and the disagreement would survive until somebody rebuilt it.
 *
 * <h2>Why every response carries freshness</h2>
 *
 * <p>See {@link com.aegispay.analytics.domain.StreamState}. In short: a read model that stops updating
 * keeps serving old numbers confidently, and a dashboard is exactly the place where that is dangerous.
 * Attaching the figure to every response rather than exposing a separate health endpoint means a caller
 * cannot forget to check it.
 */
@Service
public class AnalyticsQueryService {

    /**
     * The topics this read model is built from.
     *
     * <p>Published through the API rather than left implicit. Without it, "does the dashboard know about
     * refunds" is answerable only by reading the listeners.
     */
    private static final List<String> PROJECTED_TOPICS = List.of(
            KafkaTopics.PAYMENT_CREATED,
            KafkaTopics.FRAUD_CHECKED,
            KafkaTopics.PAYMENT_APPROVED,
            KafkaTopics.PAYMENT_COMPLETED,
            KafkaTopics.PAYMENT_FAILED,
            KafkaTopics.PAYMENT_REJECTED,
            KafkaTopics.PAYMENT_REFUNDED);

    /**
     * The topics deliberately not projected, and where to read them instead.
     *
     * <p>Stating the boundary is the point. A dashboard that silently lacked ledger data would look like a
     * missing feature; saying that ledger-service is the book of record explains why a second running total
     * of the platform's money would be a liability rather than a convenience.
     */
    private static final List<String> NOT_PROJECTED = List.of(
            KafkaTopics.INVESTIGATION_REQUESTED + ", " + KafkaTopics.INVESTIGATION_COMPLETED
                    + " — read from ai-investigation-service, which returns the trust caveats a narrative "
                    + "must be read alongside",
            KafkaTopics.WALLET_MOVEMENT_RECORDED + ", " + KafkaTopics.LEDGER_POSTED
                    + " — read from ledger-service, the book of record. A second independently-derived "
                    + "total would eventually disagree with it and nobody could tell which was right",
            KafkaTopics.NOTIFICATION_REQUIRED + " — read from notification-service, which owns delivery "
                    + "state and the dead-letter queue",
            KafkaTopics.FRAUD_DECISION_APPLIED
                    + " — read from fraud-service, which owns the append-only decision trail");

    private final PaymentFactRepository facts;
    private final StreamStateRepository streams;
    private final AnalyticsProperties properties;

    public AnalyticsQueryService(PaymentFactRepository facts, StreamStateRepository streams,
                                 AnalyticsProperties properties) {
        this.facts = facts;
        this.streams = streams;
        this.properties = properties;
    }

    // -------------------------------------------------------------- overview

    @Transactional(readOnly = true)
    public OverviewView overview(AnalyticsWindow window) {
        PaymentFactRepository.OverviewRow row = facts.overview(window.from(), window.to());
        String currency = properties.getReportingCurrency();

        long terminal = row.getCompleted() + row.getFailed() + row.getRejected();

        return new OverviewView(
                view(window),
                freshness(),
                row.getTotal(),
                row.getCompleted(),
                row.getFailed(),
                row.getRejected(),
                row.getRefunded(),
                row.getInFlight(),
                row.getOnHold(),
                row.getDegraded(),
                row.getFellBack(),
                row.getHighRisk(),
                // Null rather than zero when nothing settled either way. A zero success rate says every
                // payment failed; null says none finished yet, and on a quiet morning those look identical
                // on a tile while meaning opposite things.
                terminal == 0 ? null : (double) row.getCompleted() / terminal,
                MoneyView.ofMinor(row.getSettledMinor(), currency),
                MoneyView.ofMinor(row.getFeeMinor(), currency),
                MoneyView.ofMinor(row.getRefundedMinor(), currency));
    }

    // ------------------------------------------------------------ time series

    @Transactional(readOnly = true)
    public TimeSeriesView timeSeries(AnalyticsWindow window) {
        String currency = properties.getReportingCurrency();

        List<TimeBucketView> buckets = facts
                .timeSeries(window.granularity().sqlUnit(), window.from(), window.to())
                .stream()
                .map(row -> new TimeBucketView(
                        row.getBucketStart(),
                        row.getTotal(),
                        row.getCompleted(),
                        row.getUnsuccessful(),
                        row.getHighRisk(),
                        row.getDegraded(),
                        MoneyView.ofMinor(row.getSettledMinor(), currency)))
                .toList();

        return new TimeSeriesView(view(window), freshness(), buckets);
    }

    // ------------------------------------------------------------------- risk

    @Transactional(readOnly = true)
    public RiskDistributionView riskDistribution(AnalyticsWindow window) {
        String currency = properties.getReportingCurrency();

        List<RiskBandView> bands = facts.riskBands(window.from(), window.to()).stream()
                .map(row -> new RiskBandView(
                        row.getRiskLevel(),
                        row.getTotal(),
                        row.getCompleted(),
                        row.getOnHold(),
                        row.getRejected(),
                        row.getDegraded(),
                        MoneyView.ofMinor(row.getAmountMinor(), currency)))
                .sorted(java.util.Comparator.comparingInt(band -> bandOrder(band.riskLevel())))
                .toList();

        List<ScoreBucketView> histogram = facts.scoreHistogram(window.from(), window.to()).stream()
                .map(row -> new ScoreBucketView(row.getBucketFloor(), row.getTotal(),
                        row.getCompleted(), row.getStopped()))
                .toList();

        long degraded = bands.stream().mapToLong(RiskBandView::degradedVerdicts).sum();
        long unscored = bands.stream()
                .filter(band -> "UNSCORED".equals(band.riskLevel()))
                .mapToLong(RiskBandView::payments)
                .sum();

        return new RiskDistributionView(view(window), freshness(), bands, histogram, degraded,
                unscored);
    }

    /**
     * Orders the bands by severity rather than alphabetically.
     *
     * <p>Alphabetically, {@code CRITICAL} sorts before {@code HIGH} before {@code LOW} before
     * {@code MEDIUM} — which produces a chart whose x-axis runs critical, high, low, medium and reads as
     * meaningless. Sorting in the service rather than in SQL keeps the ordering with the presentation
     * concern that motivates it.
     */
    private static int bandOrder(String riskLevel) {
        return switch (riskLevel == null ? "" : riskLevel.toUpperCase(Locale.ROOT)) {
            case "LOW" -> 0;
            case "MEDIUM" -> 1;
            case "HIGH" -> 2;
            case "CRITICAL" -> 3;
            default -> 4;
        };
    }

    // ------------------------------------------------------------ performance

    @Transactional(readOnly = true)
    public PerformanceView performance(AnalyticsWindow window) {
        PaymentFactRepository.LatencyRow latency = facts.latency(window.from(), window.to());
        String currency = properties.getReportingCurrency();

        List<ProviderShareView> providers = facts.byProvider(window.from(), window.to()).stream()
                .map(row -> new ProviderShareView(
                        row.getProviderId(),
                        row.getTotal(),
                        row.getCompleted(),
                        row.getFellBack(),
                        MoneyView.ofMinor(row.getSettledMinor(), currency),
                        row.getAverageLatencyMs()))
                .toList();

        List<FailureShareView> failures = facts.topFailures(window.from(), window.to()).stream()
                .map(row -> new FailureShareView(row.getFailureCode(), row.getTotal()))
                .toList();

        return new PerformanceView(
                view(window),
                freshness(),
                new LatencyView(latency.getSamples(), latency.getP50(), latency.getP95(),
                        latency.getP99(), latency.getWorst()),
                providers,
                failures);
    }

    // ---------------------------------------------------------- payment list

    @Transactional(readOnly = true)
    public Page<PaymentFact> searchPayments(AnalyticsWindow window, PaymentStatus status,
                                            RiskLevel riskLevel, String userId, String providerId,
                                            Long minAmountMinor, Integer minRiskScore,
                                            boolean degradedOnly, Pageable pageable) {

        return facts.search(status, riskLevel, blankToNull(userId), blankToNull(providerId),
                minAmountMinor, narrowScore(minRiskScore), degradedOnly,
                window.from(), window.to(), pageable);
    }

    /**
     * Narrows a 0-100 score to the {@code smallint} the column is.
     *
     * <p>Bound as {@code Integer} at the API because that is what a query parameter naturally is, and
     * narrowed here rather than in the controller so the conversion happens once. The range is already
     * validated by bean validation, so this cannot silently truncate — and if the validation were ever
     * removed, a cast of 300 to a short would produce 44, which would filter to the wrong payments while
     * looking like it worked.
     */
    private static Short narrowScore(Integer minRiskScore) {
        if (minRiskScore == null) {
            return null;
        }
        if (minRiskScore < 0 || minRiskScore > 100) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "minRiskScore must be between 0 and 100. Received " + minRiskScore + ".");
        }
        return minRiskScore.shortValue();
    }

    @Transactional(readOnly = true)
    public PaymentFact requirePayment(String paymentId) {
        return facts.findByPaymentId(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        // Says which service to try instead. A payment absent from a read model is
                        // usually a projection that has not caught up rather than a payment that does not
                        // exist, and sending somebody to payment-service is the useful answer.
                        "No payment " + paymentId + " in the analytics read model. It may not have been "
                                + "projected yet — payment-service is authoritative for whether it "
                                + "exists."));
    }

    // ------------------------------------------------------------- projection

    @Transactional(readOnly = true)
    public ProjectionInfoView projectionInfo() {
        return new ProjectionInfoView(
                freshness(),
                PROJECTED_TOPICS,
                NOT_PROJECTED,
                facts.count(),
                properties.getStaleAfter().toSeconds(),
                true);
    }

    /**
     * Builds the freshness figure.
     *
     * <p>Per stream and in aggregate. The aggregate is the newest event applied to any payment rather than
     * the newest across the stream table, so a topic that has never delivered anything does not make the
     * whole projection look stale — an empty {@code payment-refunded} stream on a platform with no refunds
     * is not a fault.
     */
    @Transactional(readOnly = true)
    public FreshnessView freshness() {
        Duration tolerance = properties.getStaleAfter();
        Instant now = Instant.now();

        List<StreamView> streamViews = streams.findAllByOrderByTopicAsc().stream()
                .map(state -> {
                    long lag = Duration.between(state.getLastEventAt(), now).toSeconds();
                    return new StreamView(state.getTopic(), state.getLastEventAt(),
                            state.getLastAppliedAt(), lag, lag > tolerance.toSeconds());
                })
                .toList();

        Instant newest = facts.newestEventApplied().orElse(null);

        // Null lag when nothing has been projected. Reporting a huge number would be indistinguishable
        // from a broken consumer, when the honest answer is "this platform has taken no payments yet".
        Long lagSeconds = newest == null ? null : Duration.between(newest, now).toSeconds();
        boolean stale = lagSeconds != null && lagSeconds > tolerance.toSeconds();

        return new FreshnessView(newest, lagSeconds, stale, streamViews);
    }

    // ---------------------------------------------------------------- helpers

    private static WindowView view(AnalyticsWindow window) {
        return new WindowView(window.from(), window.to(),
                window.granularity().name().toLowerCase(Locale.ROOT));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
