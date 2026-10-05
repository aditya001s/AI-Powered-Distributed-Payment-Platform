package com.aegispay.fraud.service;

import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.exception.DomainExceptions.NotFoundException;
import com.aegispay.fraud.api.FraudDtos.AlertView;
import com.aegispay.fraud.api.FraudDtos.AnalystDecisionView;
import com.aegispay.fraud.api.FraudDtos.AssessmentDetailView;
import com.aegispay.fraud.api.FraudDtos.AssessmentView;
import com.aegispay.fraud.api.FraudDtos.EngineInfoView;
import com.aegispay.fraud.api.FraudDtos.FraudStatsView;
import com.aegispay.fraud.api.FraudDtos.ModelScoreSummary;
import com.aegispay.fraud.client.MlScoringClient;
import com.aegispay.fraud.config.FraudProperties;
import com.aegispay.fraud.domain.FraudAlert;
import com.aegispay.fraud.domain.FraudAssessment;
import com.aegispay.fraud.repository.AnalystDecisionRepository;
import com.aegispay.fraud.repository.FraudAlertRepository;
import com.aegispay.fraud.repository.FraudAssessmentRepository;
import com.aegispay.fraud.rules.RuleEngine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every read of fraud state.
 *
 * <p><strong>Read-only, entirely.</strong> Separated from {@link FraudMutationService} so the
 * boundary is visible in the type rather than only in an annotation: nothing reachable from here can
 * change a verdict, which is the property a reader of this service most needs to be able to assume.
 *
 * <p>Every method is {@code readOnly}, which is not decoration. It puts the JDBC connection in
 * read-only mode, so a mapping mistake that tried to flush a dirty entity fails at the database
 * rather than quietly rewriting an assessment that is supposed to be immutable.
 */
@Service
@Transactional(readOnly = true)
public class FraudQueryService {

    /**
     * The window every "recent" statistic uses.
     *
     * <p>One constant rather than a parameter per call, because the numbers are compared against each
     * other on a dashboard: approvals over a day next to holds over an hour would read as a far
     * healthier approval rate than reality.
     */
    private static final Duration STATS_WINDOW = Duration.ofHours(24);

    private final FraudAssessmentRepository assessments;
    private final FraudAlertRepository alerts;
    private final AnalystDecisionRepository decisions;
    private final MlScoringClient ml;
    private final RuleEngine rules;
    private final FraudProperties properties;

    public FraudQueryService(FraudAssessmentRepository assessments,
                             FraudAlertRepository alerts,
                             AnalystDecisionRepository decisions,
                             MlScoringClient ml,
                             RuleEngine rules,
                             FraudProperties properties) {
        this.assessments = assessments;
        this.alerts = alerts;
        this.decisions = decisions;
        this.ml = ml;
        this.rules = rules;
        this.properties = properties;
    }

    // ----------------------------------------------------------- assessments

    /** The verdict on one payment. */
    public AssessmentView assessmentForPayment(String paymentId) {
        return AssessmentView.from(requireAssessmentForPayment(paymentId));
    }

    /**
     * The verdict on one payment plus the vector that produced it and every human decision on it.
     *
     * <p>This is the dispute view. The three together are what makes an automated decision
     * defensible: the score, the exact inputs it was computed from, and who overrode it if anyone
     * did. Splitting them across three calls would let a caller assemble an inconsistent picture.
     */
    public AssessmentDetailView assessmentDetail(String paymentId) {
        FraudAssessment assessment = requireAssessmentForPayment(paymentId);

        return new AssessmentDetailView(
                AssessmentView.from(assessment),
                assessment.getFeatureSnapshot(),
                decisions.findByPaymentIdOrderByDecidedAtAsc(paymentId).stream()
                        .map(AnalystDecisionView::from)
                        .toList());
    }

    /**
     * Browses assessments, newest first, optionally filtered.
     *
     * <p>Filters are mutually exclusive by design and applied in a fixed precedence. Combining them
     * would need a specification or a criteria query, and the queue this actually serves — "show me
     * today's holds" or "show me everything for this customer" — never asks for two at once.
     */
    public Page<FraudAssessment> browseAssessments(RiskLevel riskLevel,
                                                   FraudDecision decision,
                                                   String userId,
                                                   Pageable pageable) {
        if (userId != null && !userId.isBlank()) {
            return assessments.findByInitiatorUserIdOrderByEvaluatedAtDesc(userId, pageable);
        }
        if (decision != null) {
            return assessments.findByDecisionOrderByEvaluatedAtDesc(decision, pageable);
        }
        if (riskLevel != null) {
            return assessments.findByRiskLevelOrderByEvaluatedAtDesc(riskLevel, pageable);
        }
        return assessments.findAllByOrderByEvaluatedAtDesc(pageable);
    }

    // ----------------------------------------------------------------- alerts

    /**
     * The analyst work queue.
     *
     * @param blockingOnly restrict to alerts holding a payment — the ones with a customer waiting
     */
    public Page<FraudAlert> queue(boolean blockingOnly, Pageable pageable) {
        return blockingOnly ? alerts.findBlockingQueue(pageable) : alerts.findQueue(pageable);
    }

    public Page<FraudAlert> alertsByStatus(FraudAlert.Status status, Pageable pageable) {
        return alerts.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    public AlertView alert(String alertId) {
        return AlertView.from(requireAlert(alertId));
    }

    /** Every decision made on one alert, oldest first. */
    public List<AnalystDecisionView> decisionsForAlert(String alertId) {
        // Resolved through the alert first so an unknown id is a 404 rather than an empty list: those
        // mean very different things to whoever is asking.
        requireAlert(alertId);
        return decisions.findByAlertIdOrderByDecidedAtAsc(alertId).stream()
                .map(AnalystDecisionView::from)
                .toList();
    }

    // ------------------------------------------------------------------ stats

    /**
     * The fraud overview.
     *
     * <p>The two numbers worth alerting on are {@code degradedAssessments24h} and
     * {@code blockingAlerts}. The first means verdicts are being reached without the model, which
     * produces no errors and is otherwise invisible; the second is a count of customers whose funds
     * are reserved waiting on a human.
     */
    public FraudStatsView stats() {
        Instant since = Instant.now().minus(STATS_WINDOW);

        Map<FraudDecision, Long> byDecision = new EnumMap<>(FraudDecision.class);
        for (Object[] row : assessments.countByDecisionSince(since)) {
            byDecision.put((FraudDecision) row[0], ((Number) row[1]).longValue());
        }
        long total = byDecision.values().stream().mapToLong(Long::longValue).sum();

        List<ModelScoreSummary> byModel = assessments.summariseScoresByModelVersion(since).stream()
                .map(row -> new ModelScoreSummary(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        row[2] == null ? 0.0 : ((Number) row[2]).doubleValue()))
                .toList();

        return new FraudStatsView(
                total,
                byDecision.getOrDefault(FraudDecision.APPROVE, 0L),
                byDecision.getOrDefault(FraudDecision.REVIEW, 0L),
                byDecision.getOrDefault(FraudDecision.HOLD, 0L),
                byDecision.getOrDefault(FraudDecision.REJECT, 0L),
                assessments.countByDegradedModeAndEvaluatedAtAfter(true, since),
                alerts.countByStatus(FraudAlert.Status.OPEN)
                        + alerts.countByStatus(FraudAlert.Status.IN_REVIEW),
                alerts.countBlockingOpen(),
                assessments.averageScoringLatencyMs(since),
                byModel,
                Instant.now());
    }

    /**
     * What the engine is configured to do, and what the model says about itself.
     *
     * <p>The model metadata is fetched live rather than cached, deliberately: the question this
     * answers is "which build is scoring my payments right now", and a cached answer could name a
     * build that is no longer running. Null when ml-service is unreachable, which is itself the
     * answer an operator is looking for.
     */
    public EngineInfoView engineInfo() {
        return new EngineInfoView(
                properties.getThresholds().getHighRisk(),
                properties.getThresholds().getMediumRisk(),
                rules.ruleIds(),
                properties.getMl().isRejectWhenUnavailable(),
                ml.modelInfo().orElse(null));
    }

    // -------------------------------------------------------------- internals

    private FraudAssessment requireAssessmentForPayment(String paymentId) {
        return assessments.findByPaymentId(paymentId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Payment " + paymentId + " has not been assessed"));
    }

    private FraudAlert requireAlert(String alertId) {
        return alerts.findByAlertId(alertId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No fraud alert with id " + alertId));
    }
}
