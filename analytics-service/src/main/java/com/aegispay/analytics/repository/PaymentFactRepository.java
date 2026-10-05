package com.aegispay.analytics.repository;

import com.aegispay.analytics.domain.PaymentFact;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.domain.RiskLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads over the payment read model.
 *
 * <h2>Why the aggregates are native queries</h2>
 *
 * <p>Every chart is a {@code date_trunc} bucket, and {@code date_trunc} has no JPQL equivalent. The
 * alternative is loading a window of rows into memory and grouping them in Java, which for a
 * thirty-day window over a busy platform means transferring every payment to draw one line — and the
 * page would get slower exactly as the platform got busier.
 *
 * <p>Percentiles are the same story, only worse: {@code percentile_cont} is a PostgreSQL ordered-set
 * aggregate, and computing a p99 in Java requires the whole sorted set in memory.
 *
 * <h2>Why every aggregate is derived rather than stored</h2>
 *
 * <p>A pre-aggregated counter is not idempotent: a redelivered event increments it twice and nothing
 * reports that it did. Deriving from one row per payment means at-least-once delivery cannot inflate a
 * total, because applying the same event again writes the same row. See the migration header for the
 * full argument.
 */
public interface PaymentFactRepository extends JpaRepository<PaymentFact, Long> {

    Optional<PaymentFact> findByPaymentId(String paymentId);

    // ------------------------------------------------------------------ listing

    /**
     * The cross-customer payment list.
     *
     * <p>The gap this service exists to fill: payment-service is customer-scoped and has no view across
     * customers, and the only other platform-wide payment listing is the orchestrator's saga list, which
     * describes execution rather than outcome.
     *
     * <p>Filters are all optional and combine, unlike fraud-service's mutually-exclusive ones — a
     * dashboard genuinely does want "failed payments over 10,000 on SWIFTPAY yesterday".
     */
    @Query("""
            SELECT f FROM PaymentFact f
            WHERE (:status IS NULL OR f.status = :status)
              AND (:riskLevel IS NULL OR f.riskLevel = :riskLevel)
              AND (:userId IS NULL OR f.initiatorUserId = :userId)
              AND (:providerId IS NULL OR f.providerId = :providerId)
              AND (:minAmountMinor IS NULL OR f.amountMinor >= :minAmountMinor)
              AND (:minRiskScore IS NULL OR f.riskScore >= :minRiskScore)
              AND (:degradedOnly = FALSE OR f.degradedMode = TRUE)
              AND f.createdAt >= :from
              AND f.createdAt < :to
            """)
    Page<PaymentFact> search(@Param("status") PaymentStatus status,
                             @Param("riskLevel") RiskLevel riskLevel,
                             @Param("userId") String userId,
                             @Param("providerId") String providerId,
                             @Param("minAmountMinor") Long minAmountMinor,
                             /*
                              * A score floor rather than a band. "Everything over 70" spans HIGH and
                              * CRITICAL, and an analyst reviewing a threshold change needs the payments
                              * either side of it, not the ones the current thresholds happen to bucket
                              * together.
                              */
                             @Param("minRiskScore") Short minRiskScore,
                             /*
                              * A primitive boolean, not a nullable Boolean. Absent and false mean the same
                              * thing here — "do not restrict" — so there is no third state to represent,
                              * and the FALSE comparison keeps the predicate constant-foldable rather than
                              * forcing a null check into every row.
                              */
                             @Param("degradedOnly") boolean degradedOnly,
                             @Param("from") Instant from,
                             @Param("to") Instant to,
                             Pageable pageable);

    // --------------------------------------------------------------- overview

    /**
     * The KPI tiles, in one query.
     *
     * <p>One round trip rather than eleven. A dashboard that issued a query per tile would take eleven
     * times as long to load and, worse, could show tiles computed at eleven slightly different instants —
     * so "created" and "completed" could disagree in a way that looks like lost payments.
     *
     * <p>{@code FILTER} rather than {@code CASE WHEN} sums: it says what it means, and PostgreSQL
     * evaluates it in a single pass over the same rows.
     */
    @Query(value = """
            SELECT
                COUNT(*)                                                          AS total,
                COUNT(*) FILTER (WHERE status = 'COMPLETED')                       AS completed,
                COUNT(*) FILTER (WHERE status = 'FAILED')                          AS failed,
                COUNT(*) FILTER (WHERE status = 'REJECTED')                        AS rejected,
                COUNT(*) FILTER (WHERE status IN ('REFUNDED', 'PARTIALLY_REFUNDED')) AS refunded,
                COUNT(*) FILTER (WHERE status NOT IN
                    ('COMPLETED', 'FAILED', 'REJECTED', 'REFUNDED', 'PARTIALLY_REFUNDED')) AS inFlight,
                COUNT(*) FILTER (WHERE status = 'ON_HOLD')                         AS onHold,
                COUNT(*) FILTER (WHERE degraded_mode)                              AS degraded,
                COUNT(*) FILTER (WHERE provider_attempt_count > 1)                 AS fellBack,
                COUNT(*) FILTER (WHERE risk_level IN ('HIGH', 'CRITICAL'))         AS highRisk,
                COALESCE(SUM(amount_minor) FILTER (WHERE status = 'COMPLETED'), 0)  AS settledMinor,
                COALESCE(SUM(fee_minor)    FILTER (WHERE status = 'COMPLETED'), 0)  AS feeMinor,
                COALESCE(SUM(refunded_minor), 0)                                    AS refundedMinor
            FROM payment_facts
            WHERE created_at >= :from AND created_at < :to
            """, nativeQuery = true)
    OverviewRow overview(@Param("from") Instant from, @Param("to") Instant to);

    /** Projection interface for {@link #overview}. Named getters, so a column rename fails loudly. */
    interface OverviewRow {
        long getTotal();

        long getCompleted();

        long getFailed();

        long getRejected();

        long getRefunded();

        long getInFlight();

        long getOnHold();

        long getDegraded();

        long getFellBack();

        long getHighRisk();

        long getSettledMinor();

        long getFeeMinor();

        long getRefundedMinor();
    }

    // ------------------------------------------------------------- time series

    /**
     * Volume and outcome per time bucket.
     *
     * <p>{@code date_trunc} with a caller-supplied unit. The unit is validated against a closed set
     * before it reaches here — it is interpolated into SQL, and an unvalidated value would be an
     * injection point in a query no parameter binding can protect.
     *
     * <p>{@code generate_series} fills empty buckets. Without it a quiet hour is simply absent from the
     * result, and a chart drawing straight from the rows would join across the gap — showing a smooth
     * line through a period when the platform did nothing, which is the opposite of what an operator
     * needs to see.
     */
    @Query(value = """
            SELECT
                bucket                                                             AS bucketStart,
                COALESCE(COUNT(f.id), 0)                                           AS total,
                COALESCE(COUNT(f.id) FILTER (WHERE f.status = 'COMPLETED'), 0)      AS completed,
                COALESCE(COUNT(f.id) FILTER (WHERE f.status IN ('FAILED', 'REJECTED')), 0) AS unsuccessful,
                COALESCE(COUNT(f.id) FILTER (WHERE f.risk_level IN ('HIGH', 'CRITICAL')), 0) AS highRisk,
                COALESCE(COUNT(f.id) FILTER (WHERE f.degraded_mode), 0)             AS degraded,
                COALESCE(SUM(f.amount_minor) FILTER (WHERE f.status = 'COMPLETED'), 0) AS settledMinor
            FROM generate_series(
                     date_trunc(:unit, CAST(:from AS timestamptz)),
                     date_trunc(:unit, CAST(:to AS timestamptz)),
                     CAST('1 ' || :unit AS interval)) AS bucket
            LEFT JOIN payment_facts f
                   ON f.created_at >= bucket
                  AND f.created_at < bucket + CAST('1 ' || :unit AS interval)
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<TimeBucketRow> timeSeries(@Param("unit") String unit,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to);

    interface TimeBucketRow {
        Instant getBucketStart();

        long getTotal();

        long getCompleted();

        long getUnsuccessful();

        long getHighRisk();

        long getDegraded();

        long getSettledMinor();
    }

    // ------------------------------------------------------- risk distribution

    /**
     * Payments per risk band, with how each band actually ended.
     *
     * <p>The band alone is not useful. "40 HIGH-risk payments" says nothing about whether the controls
     * did anything; "40 HIGH, of which 31 held and 9 settled" is the number worth looking at.
     */
    @Query(value = """
            SELECT
                COALESCE(risk_level, 'UNSCORED')                                   AS riskLevel,
                COUNT(*)                                                           AS total,
                COUNT(*) FILTER (WHERE status = 'COMPLETED')                        AS completed,
                COUNT(*) FILTER (WHERE status = 'ON_HOLD')                          AS onHold,
                COUNT(*) FILTER (WHERE status = 'REJECTED')                         AS rejected,
                COUNT(*) FILTER (WHERE degraded_mode)                               AS degraded,
                COALESCE(SUM(amount_minor), 0)                                      AS amountMinor
            FROM payment_facts
            WHERE created_at >= :from AND created_at < :to
            GROUP BY COALESCE(risk_level, 'UNSCORED')
            """, nativeQuery = true)
    List<RiskBandRow> riskBands(@Param("from") Instant from, @Param("to") Instant to);

    interface RiskBandRow {
        String getRiskLevel();

        long getTotal();

        long getCompleted();

        long getOnHold();

        long getRejected();

        long getDegraded();

        long getAmountMinor();
    }

    /**
     * The risk-score histogram, in ten-point buckets.
     *
     * <p>Bucketed in SQL rather than by fetching every score, because the whole point of the chart is
     * the shape of a distribution over a large number of payments.
     *
     * <p>Score 100 is folded into the top bucket deliberately: {@code risk_score / 10} would otherwise
     * produce an eleventh bucket containing only exact-100 scores, which reads as a rendering bug.
     */
    @Query(value = """
            SELECT
                LEAST(risk_score / 10, 9) * 10                                     AS bucketFloor,
                COUNT(*)                                                           AS total,
                COUNT(*) FILTER (WHERE status = 'COMPLETED')                        AS completed,
                COUNT(*) FILTER (WHERE status IN ('ON_HOLD', 'REJECTED'))           AS stopped
            FROM payment_facts
            WHERE risk_score IS NOT NULL
              AND created_at >= :from AND created_at < :to
            GROUP BY LEAST(risk_score / 10, 9) * 10
            ORDER BY 1
            """, nativeQuery = true)
    List<ScoreBucketRow> scoreHistogram(@Param("from") Instant from, @Param("to") Instant to);

    interface ScoreBucketRow {
        int getBucketFloor();

        long getTotal();

        long getCompleted();

        long getStopped();
    }

    // ------------------------------------------------------------- performance

    /**
     * End-to-end latency percentiles over completed payments.
     *
     * <p>Completed only, and the {@code WHERE} is doing real work. An in-flight payment has no duration;
     * counting it as zero would drag every percentile down and make the platform look <em>faster</em>
     * the more backlog it accumulated.
     *
     * <p>p50, p95 and p99 rather than a mean. A mean latency hides the tail entirely, and the tail is
     * what a customer experiences as "the app is broken".
     */
    @Query(value = """
            SELECT
                COUNT(*)                                                                   AS samples,
                COALESCE(percentile_cont(0.5)  WITHIN GROUP (ORDER BY end_to_end_latency_ms), 0) AS p50,
                COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY end_to_end_latency_ms), 0) AS p95,
                COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY end_to_end_latency_ms), 0) AS p99,
                COALESCE(MAX(end_to_end_latency_ms), 0)                                    AS worst
            FROM payment_facts
            WHERE end_to_end_latency_ms IS NOT NULL
              AND created_at >= :from AND created_at < :to
            """, nativeQuery = true)
    LatencyRow latency(@Param("from") Instant from, @Param("to") Instant to);

    interface LatencyRow {
        long getSamples();

        double getP50();

        double getP95();

        double getP99();

        long getWorst();
    }

    /**
     * Settlement per provider.
     *
     * <p>{@code fellBack} is the number to watch. A provider whose share of fallbacks is rising is
     * failing before it starts failing outright, and nothing else on the platform reports that.
     */
    @Query(value = """
            SELECT
                provider_id                                                        AS providerId,
                COUNT(*)                                                           AS total,
                COUNT(*) FILTER (WHERE status = 'COMPLETED')                        AS completed,
                COUNT(*) FILTER (WHERE provider_attempt_count > 1)                  AS fellBack,
                COALESCE(SUM(amount_minor) FILTER (WHERE status = 'COMPLETED'), 0)   AS settledMinor,
                COALESCE(AVG(end_to_end_latency_ms), 0)                             AS averageLatencyMs
            FROM payment_facts
            WHERE provider_id IS NOT NULL
              AND created_at >= :from AND created_at < :to
            GROUP BY provider_id
            ORDER BY 2 DESC
            """, nativeQuery = true)
    List<ProviderRow> byProvider(@Param("from") Instant from, @Param("to") Instant to);

    interface ProviderRow {
        String getProviderId();

        long getTotal();

        long getCompleted();

        long getFellBack();

        long getSettledMinor();

        double getAverageLatencyMs();
    }

    /**
     * Why payments are failing, most common first.
     *
     * <p>A failure count on its own prompts the wrong question. Grouped by code it answers the right
     * one: whether one rail is timing out or customers are running out of money.
     */
    @Query(value = """
            SELECT failure_code AS failureCode, COUNT(*) AS total
            FROM payment_facts
            WHERE status = 'FAILED'
              AND failure_code IS NOT NULL
              AND created_at >= :from AND created_at < :to
            GROUP BY failure_code
            ORDER BY 2 DESC
            LIMIT 10
            """, nativeQuery = true)
    List<FailureRow> topFailures(@Param("from") Instant from, @Param("to") Instant to);

    interface FailureRow {
        String getFailureCode();

        long getTotal();
    }

    // ------------------------------------------------------------- projection

    /** The newest event this projection has applied to any payment. Feeds the freshness figure. */
    @Query("SELECT MAX(f.lastEventAt) FROM PaymentFact f")
    Optional<Instant> newestEventApplied();

    long countByCreatedAtAfter(Instant since);
}
