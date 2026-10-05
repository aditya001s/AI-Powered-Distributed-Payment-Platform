package com.aegispay.fraud.repository;

import com.aegispay.fraud.domain.AnalystDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Reads and appends analyst decisions.
 *
 * <p>Deliberately exposes no update or delete beyond what {@link JpaRepository} inherits, and the
 * table refuses both by trigger regardless. The audit trail of why a customer's payment was released
 * or refused must not be rewritable through any route, including this one.
 */
public interface AnalystDecisionRepository extends JpaRepository<AnalystDecision, Long> {

    /**
     * Every decision on one alert, oldest first.
     *
     * <p>Plural on purpose. An alert normally has one decision, but the audit question is "what
     * happened to this", and a list answers that even when something unexpected produced two.
     */
    List<AnalystDecision> findByAlertIdOrderByDecidedAtAsc(String alertId);

    List<AnalystDecision> findByPaymentIdOrderByDecidedAtAsc(String paymentId);

    /** One analyst's recent decisions, for oversight of the people doing the reviewing. */
    List<AnalystDecision> findByDecidedByOrderByDecidedAtDesc(String decidedBy);

    long countByActionAndDecidedAtAfter(AnalystDecision.Action action, Instant since);
}
