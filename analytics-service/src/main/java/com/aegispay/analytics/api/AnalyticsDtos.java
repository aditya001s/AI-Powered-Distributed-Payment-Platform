package com.aegispay.analytics.api;

import com.aegispay.analytics.domain.PaymentFact;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Response shapes for the admin analytics API. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A monetary amount in both decimal and minor-unit form")
    public record MoneyView(
            @Schema(example = "2000.00") BigDecimal amount,
            @Schema(example = "200000", description = "Exact value in minor units. Prefer this.")
            long amountMinor,
            @Schema(example = "INR") String currency
    ) {
        public static MoneyView of(Money money) {
            return money == null ? null
                    : new MoneyView(money.toDecimal(), money.amountMinor(), money.currency());
        }

        /** For an aggregate, where the currency comes from configuration rather than a row. */
        public static MoneyView ofMinor(long amountMinor, String currency) {
            return of(Money.ofMinor(amountMinor, currency));
        }
    }

    /**
     * How current the read model is.
     *
     * <p><strong>Returned with every response, and the reason is worth stating.</strong> A projection that
     * stops updating does not go blank — it keeps serving the last numbers it had, confidently. An
     * operator reading "0 payments held" cannot tell whether the queue is clear or the consumer died three
     * hours ago, and those call for opposite responses.
     *
     * <p>Per stream rather than one figure, because a single number would only look stale once every
     * consumer had stopped. Per stream, a broken {@code fraud-checked} consumer is visible while payments
     * still flow.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Read-model freshness. Read this before trusting any number beside it.")
    public record FreshnessView(
            @Schema(description = "occurredAt of the newest event applied to any payment. Null when "
                    + "nothing has been projected yet, which is different from being stale.")
            Instant newestEventAt,

            @Schema(description = "Seconds between the newest applied event and now. Null when nothing "
                    + "has been projected.")
            Long lagSeconds,

            @Schema(description = "True when the lag is beyond the configured tolerance. Every number "
                    + "in this response should be treated as historical.")
            boolean stale,

            @Schema(description = "Per-topic high-water marks, so a single broken consumer is visible "
                    + "while the others still flow.")
            List<StreamView> streams
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One consumed topic's high-water mark")
    public record StreamView(
            String topic,
            @Schema(description = "occurredAt of the newest event applied from this topic")
            Instant lastEventAt,
            @Schema(description = "When this service applied it. Diverges from lastEventAt during a "
                    + "replay, which is what stops a backfill reading as an outage.")
            Instant lastAppliedAt,
            long lagSeconds,
            boolean stale
    ) {
    }

    /** The window a response covers, echoed back so a chart's axis and its data cannot disagree. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "The resolved time window")
    public record WindowView(
            Instant from,
            @Schema(description = "Exclusive upper bound")
            Instant to,
            @Schema(description = "Bucket size actually used. May be coarser than requested only by "
                    + "explicit rejection, never silently.")
            String granularity
    ) {
    }

    // -------------------------------------------------------------- overview

    /**
     * The KPI tiles.
     *
     * <p>Every figure comes from one query over one window, so they are mutually consistent. Issuing a
     * query per tile would let "created" and "completed" be computed instants apart, which under load
     * looks exactly like lost payments.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Platform overview for a time window")
    public record OverviewView(
            WindowView window,
            FreshnessView freshness,

            long payments,
            long completed,
            long failed,
            long rejected,
            long refunded,

            @Schema(description = "Not yet terminal. Read it next to onHold: a rising figure that is "
                    + "entirely held payments is a stuck review queue, not slow processing.")
            long inFlight,

            @Schema(description = "Held for a fraud decision. Each one is a customer whose funds are "
                    + "reserved and who cannot spend them.")
            long onHold,

            @Schema(description = "Verdicts reached WITHOUT the fraud model, on rules alone. Produces "
                    + "no error anywhere else: the payments succeed and the scores read 0.0. A rising "
                    + "count means the platform has been deciding blind.")
            long degradedVerdicts,

            @Schema(description = "Payments that needed more than one provider. Routing working - but a "
                    + "rising rate means a rail is failing.")
            long fellBack,

            long highRisk,

            @Schema(description = "Share of terminal payments that completed, 0-1. Null when nothing "
                    + "reached a terminal state in the window - which is different from a zero rate.")
            Double successRate,

            @Schema(description = "Total settled. Completed payments only: counting in-flight ones "
                    + "would report money that has not moved.")
            MoneyView settled,

            @Schema(description = "Platform fee revenue on settled payments")
            MoneyView fees,

            MoneyView refundedAmount
    ) {
    }

    // ------------------------------------------------------------ time series

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Volume and outcome over time")
    public record TimeSeriesView(
            WindowView window,
            FreshnessView freshness,
            @Schema(description = "Every bucket in the window, including empty ones. A missing bucket "
                    + "would let a chart join a straight line across a period of no activity.")
            List<TimeBucketView> buckets
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One time bucket")
    public record TimeBucketView(
            Instant bucketStart,
            long payments,
            long completed,
            @Schema(description = "Failed or rejected")
            long unsuccessful,
            long highRisk,
            long degradedVerdicts,
            MoneyView settled
    ) {
    }

    // ------------------------------------------------------------------- risk

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Risk distribution for a time window")
    public record RiskDistributionView(
            WindowView window,
            FreshnessView freshness,

            @Schema(description = "Payments per risk band, with how each band actually ended. The band "
                    + "alone says nothing about whether the controls did anything.")
            List<RiskBandView> bands,

            @Schema(description = "Risk-score histogram in ten-point buckets")
            List<ScoreBucketView> histogram,

            @Schema(description = "Verdicts reached without the model. A LOW band containing these "
                    + "means 'nothing was found', not 'nothing is there'.")
            long degradedVerdicts,

            @Schema(description = "Payments with no score at all, e.g. rejected before scoring")
            long unscored
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One risk band, and how its payments ended")
    public record RiskBandView(
            @Schema(description = "LOW, MEDIUM, HIGH, CRITICAL, or UNSCORED")
            String riskLevel,
            long payments,
            long completed,
            long onHold,
            long rejected,
            long degradedVerdicts,
            MoneyView amount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One ten-point risk-score bucket")
    public record ScoreBucketView(
            @Schema(description = "Inclusive lower bound of the bucket, 0-90", example = "70")
            int bucketFloor,
            long payments,
            long completed,
            @Schema(description = "Held or rejected")
            long stopped
    ) {
    }

    // ------------------------------------------------------------ performance

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Settlement performance for a time window")
    public record PerformanceView(
            WindowView window,
            FreshnessView freshness,

            @Schema(description = "End-to-end latency percentiles over COMPLETED payments only. An "
                    + "in-flight payment has no duration, and counting it as zero would make the "
                    + "platform look faster the more backlog it had.")
            LatencyView latency,

            List<ProviderShareView> providers,

            @Schema(description = "Most common failure codes. A bare failure count prompts the wrong "
                    + "question; grouped by code it says whether a rail is timing out or customers are "
                    + "running out of money.")
            List<FailureShareView> failures
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Latency percentiles, in milliseconds")
    public record LatencyView(
            @Schema(description = "How many completed payments the percentiles are computed over. Read "
                    + "it first: a p99 over four samples is not a p99.")
            long samples,
            double p50,
            double p95,
            @Schema(description = "The tail a customer experiences as 'the app is broken'. A mean would "
                    + "hide it entirely.")
            double p99,
            long worst
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "One provider's share of settlement")
    public record ProviderShareView(
            String providerId,
            long payments,
            long completed,
            @Schema(description = "Payments this provider handled after another was tried first. A "
                    + "rising share means a rail is failing before it fails outright.")
            long fellBack,
            MoneyView settled,
            double averageLatencyMs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FailureShareView(String failureCode, long payments) {
    }

    // ---------------------------------------------------------- payment list

    /**
     * One payment in the cross-customer list.
     *
     * <p>The gap this service fills: payment-service is customer-scoped and has no view across customers,
     * and the orchestrator's saga list describes execution rather than outcome.
     *
     * <p>Deliberately absent: the payment description, and any device or location data. The description is
     * customer-written free text and the risk signals are data the customer never knowingly provided —
     * this list is read by staff looking at other people's payments, and neither is needed to do that.
     * fraud-service shows the signals where there is a reason to.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "A payment as the dashboard sees it")
    public record PaymentFactView(
            String paymentId,
            String transactionId,
            String initiatorUserId,
            String destinationUserId,
            PaymentStatus status,
            PaymentType paymentType,
            MoneyView amount,
            MoneyView fee,
            MoneyView refunded,

            RiskLevel riskLevel,
            FraudDecision fraudDecision,
            @Schema(description = "0-100. Read only alongside degradedMode.")
            Short riskScore,
            @Schema(description = "True when the verdict was reached without the model. A LOW level "
                    + "then means 'nothing was found', not 'nothing is there'.")
            boolean degradedMode,
            String modelVersion,
            List<String> ruleHits,

            String providerId,
            @Schema(description = "More than one means routing fell back")
            Short providerAttempts,
            String ledgerTransactionId,

            String failureCode,
            String failureReason,

            Long endToEndLatencyMs,
            Instant createdAt,
            Instant completedAt,

            @Schema(description = "When this projection first saw the payment. The gap from createdAt "
                    + "is replay lag, which is what stops a backfill looking like a traffic spike.")
            Instant firstSeenAt
    ) {
        public static PaymentFactView from(PaymentFact fact) {
            String currency = fact.getCurrency();
            return new PaymentFactView(
                    fact.getPaymentId(),
                    fact.getTransactionId(),
                    fact.getInitiatorUserId(),
                    fact.getDestinationUserId(),
                    fact.getStatus(),
                    fact.getPaymentType(),
                    MoneyView.of(fact.amount()),
                    fact.getFeeMinor() == null || currency == null
                            ? null : MoneyView.ofMinor(fact.getFeeMinor(), currency),
                    currency == null ? null : MoneyView.ofMinor(fact.getRefundedMinor(), currency),
                    fact.getRiskLevel(),
                    fact.getFraudDecision(),
                    fact.getRiskScore(),
                    fact.isDegradedMode(),
                    fact.getModelVersion(),
                    fact.ruleHitList(),
                    fact.getProviderId(),
                    fact.getProviderAttemptCount(),
                    fact.getLedgerTransactionId(),
                    fact.getFailureCode(),
                    fact.getFailureReason(),
                    fact.getEndToEndLatencyMs(),
                    fact.getCreatedAt(),
                    fact.getCompletedAt(),
                    fact.getFirstSeenAt());
        }
    }

    /**
     * What the read model is and is not.
     *
     * <p>Exists so a reader does not have to infer the boundary from which endpoints happen to exist. The
     * most useful field is {@code notProjected}: it names the topics this service deliberately ignores and
     * says where to read them instead, which is otherwise discoverable only by reading the listeners.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Read-model scope and freshness")
    public record ProjectionInfoView(
            FreshnessView freshness,
            @Schema(description = "Topics projected into this read model")
            List<String> projectedTopics,
            @Schema(description = "Topics deliberately NOT projected, and where to read them instead")
            List<String> notProjected,
            long paymentsProjected,
            @Schema(description = "Seconds of lag beyond which a stream is reported stale")
            long staleAfterSeconds,
            @Schema(description = "True when every aggregate is derived from per-payment rows rather "
                    + "than from stored counters, so at-least-once delivery cannot inflate a total.")
            boolean derivedAggregates
    ) {
    }
}
