package com.aegispay.common.observability;

/**
 * Canonical Micrometer metric names.
 *
 * <p>Centralised because a metric name is an API: Grafana dashboards and
 * Prometheus alert rules reference these strings, so a typo in one service
 * silently produces a second, near-identical time series that no dashboard reads.
 * Referencing constants makes that class of bug a compile error.
 *
 * <p>Naming follows the Micrometer convention of dot-separated lowercase
 * segments, which the Prometheus registry converts to
 * {@code aegis_payment_latency_seconds} style names automatically.
 *
 * <p><strong>Cardinality discipline.</strong> Tags must be low-cardinality.
 * Tagging by {@code providerId} (3 values) or {@code riskLevel} (4 values) is
 * fine. Tagging by {@code paymentId} or {@code userId} would create an unbounded
 * number of time series and take the monitoring stack down, so those belong in
 * traces and logs instead.
 */
public final class MetricNames {

    private MetricNames() {
    }

    // ---- Payments ----

    /** Timer: full orchestration latency, creation to terminal state. Tag: outcome. */
    public static final String PAYMENT_LATENCY = "aegis.payment.latency";

    /** Counter: payments by terminal outcome. Tags: outcome, paymentType. */
    public static final String PAYMENT_OUTCOME = "aegis.payment.outcome";

    /** Counter: payment requests deduplicated by an idempotency key replay. */
    public static final String PAYMENT_IDEMPOTENT_REPLAY = "aegis.payment.idempotent.replay";

    /**
     * Counter: requests accepted for orchestration. Tags: outcome, paymentType.
     *
     * <p>Deliberately separate from {@link #PAYMENT_OUTCOME}. Since Phase 5 the API returns as soon
     * as the payment is persisted, so "accepted" and "completed" are different events at different
     * times and are recorded by different services. Counting them on one meter would make the
     * success rate read as 100% for as long as the saga takes.
     */
    public static final String PAYMENT_ACCEPTED = "aegis.payment.accepted";

    /** Gauge: payments currently mid-saga. A sustained rise means the saga is stalling. */
    public static final String PAYMENT_IN_FLIGHT = "aegis.payment.in.flight";

    // ---- Saga ----

    /** Timer: duration of one saga step. Tags: step, outcome. */
    public static final String SAGA_STEP_LATENCY = "aegis.saga.step.latency";

    /** Counter: compensating actions executed. Tags: step, result. */
    public static final String SAGA_COMPENSATION = "aegis.saga.compensation";

    /** Counter: sagas that failed to compensate. Every increment needs a human. */
    public static final String SAGA_COMPENSATION_FAILED = "aegis.saga.compensation.failed";

    // ---- Fraud & ML ----

    /** Timer: fraud decision latency, including the ML call. */
    public static final String FRAUD_DECISION_LATENCY = "aegis.fraud.decision.latency";

    /** Timer: the ML scoring HTTP call alone. */
    public static final String FRAUD_ML_CALL_LATENCY = "aegis.fraud.ml.call.latency";

    /** Counter: fraud decisions. Tags: decision, riskLevel, degraded. */
    public static final String FRAUD_DECISION = "aegis.fraud.decision";

    /** Counter: decisions made without the model because it was unreachable. */
    public static final String FRAUD_DEGRADED_DECISION = "aegis.fraud.degraded.decision";

    /** Distribution summary: fraud probability, for drift monitoring. */
    public static final String FRAUD_SCORE_DISTRIBUTION = "aegis.fraud.score";

    /** Counter: deterministic rule hits. Tag: ruleId. */
    public static final String FRAUD_RULE_HIT = "aegis.fraud.rule.hit";

    // ---- AI investigation ----

    /** Timer: end-to-end investigation generation, including retrieval. */
    public static final String AI_INVESTIGATION_LATENCY = "aegis.ai.investigation.latency";

    /** Timer: RAG retrieval alone. */
    public static final String AI_RETRIEVAL_LATENCY = "aegis.ai.retrieval.latency";

    /** Counter: investigations produced. Tags: provider, fallback. */
    public static final String AI_INVESTIGATION_COUNT = "aegis.ai.investigation.count";

    /**
     * Counter: policy citations a generator produced that were NOT in its retrieved context.
     *
     * <p>A hallucinated citation. Detectable because the set of documents put into the prompt is
     * known exactly, so any id outside it was invented — and an invented citation is the single most
     * damaging output this service can produce, because it looks like evidence.
     */
    public static final String AI_HALLUCINATED_CITATION = "aegis.ai.citation.hallucinated";

    /**
     * Counter: investigations where retrieval found no policy above the similarity floor.
     *
     * <p>Not an error. It means the narrative had to say "no policy matched" rather than reach for
     * one, and a rising rate means the knowledge base has drifted away from the traffic.
     */
    public static final String AI_UNGROUNDED_INVESTIGATION = "aegis.ai.investigation.ungrounded";

    /** Distribution summary: policy chunks retrieved above the floor, per investigation. */
    public static final String AI_RETRIEVED_CHUNKS = "aegis.ai.retrieval.chunks";

    /**
     * Counter: recommendations a generator returned that were not in the permitted vocabulary.
     *
     * <p>Either a confused model or a successful prompt injection. Both are refused and downgraded
     * to the most cautious recommendation, and both are worth alerting on.
     */
    public static final String AI_REJECTED_RECOMMENDATION = "aegis.ai.recommendation.rejected";

    // ---- Providers ----

    /** Timer: provider charge latency. Tags: providerId, outcome. */
    public static final String PROVIDER_CALL_LATENCY = "aegis.provider.call.latency";

    /** Counter: provider call results. Tags: providerId, outcome. */
    public static final String PROVIDER_CALL = "aegis.provider.call";

    /** Counter: fallbacks from one provider to the next. Tags: from, to. */
    public static final String PROVIDER_FALLBACK = "aegis.provider.fallback";

    /** Gauge: rolling failure rate per provider, as used by the router. */
    public static final String PROVIDER_FAILURE_RATE = "aegis.provider.failure.rate";

    /** Gauge: 1 when healthy, 0 when the circuit breaker is open. Tag: providerId. */
    public static final String PROVIDER_HEALTH = "aegis.provider.health";

    // ---- Ledger ----

    /** Timer: time to commit a posting. */
    public static final String LEDGER_POSTING_LATENCY = "aegis.ledger.posting.latency";

    /** Counter: postings committed. Tag: postingType. */
    public static final String LEDGER_POSTING = "aegis.ledger.posting";

    /** Counter: unbalanced postings rejected. Must stay at zero. */
    public static final String LEDGER_IMBALANCE_REJECTED = "aegis.ledger.imbalance.rejected";

    // ---- Wallet ----

    /** Timer: how long a wallet distributed lock was held. */
    public static final String WALLET_LOCK_HELD = "aegis.wallet.lock.held";

    /** Counter: failures to acquire a wallet lock within the timeout. */
    public static final String WALLET_LOCK_TIMEOUT = "aegis.wallet.lock.timeout";

    /** Counter: transfers refused for insufficient funds. */
    public static final String WALLET_INSUFFICIENT_FUNDS = "aegis.wallet.insufficient.funds";

    // ---- Events / Kafka ----

    public static final String EVENTS_PUBLISHED = "aegis.events.published";
    public static final String EVENTS_PUBLISH_FAILED = "aegis.events.publish.failed";

    /** Counter: events consumed. Tags: topic, outcome. */
    public static final String EVENTS_CONSUMED = "aegis.events.consumed";

    /** Timer: handler execution time. Tag: topic. */
    public static final String EVENTS_HANDLER_LATENCY = "aegis.events.handler.latency";

    /** Counter: records routed to a dead-letter topic. Tag: topic. */
    public static final String EVENTS_DEAD_LETTERED = "aegis.events.dead.lettered";

    /** Counter: duplicate events suppressed by the consumer's de-duplication. */
    public static final String EVENTS_DUPLICATE_SUPPRESSED = "aegis.events.duplicate.suppressed";

    // ---- Outbox ----

    /** Gauge: unpublished outbox rows. Sustained growth means the drainer is stuck. */
    public static final String OUTBOX_PENDING = "aegis.outbox.pending";

    /** Counter: outbox rows successfully drained to Kafka. */
    public static final String OUTBOX_PUBLISHED = "aegis.outbox.published";

    /** Counter: outbox rows that exhausted their retries. */
    public static final String OUTBOX_FAILED = "aegis.outbox.failed";

    /** Timer: age of a row when it was finally published (publication lag). */
    public static final String OUTBOX_PUBLISH_LAG = "aegis.outbox.publish.lag";

    // ---- Auth ----

    /** Counter: login attempts. Tag: outcome. Feeds credential-stuffing alerts. */
    public static final String AUTH_LOGIN_ATTEMPT = "aegis.auth.login.attempt";

    /** Counter: tokens issued. Tag: grantType. */
    public static final String AUTH_TOKEN_ISSUED = "aegis.auth.token.issued";

    /** Counter: refresh tokens rejected for reuse, i.e. suspected token theft. */
    public static final String AUTH_REFRESH_REUSE_DETECTED = "aegis.auth.refresh.reuse.detected";

    // ---- Notifications ----

    /**
     * Counter: delivery attempts. Tags: channel, outcome.
     *
     * <p>Counts <em>attempts</em>, not notifications, because one notification fans out to several
     * channels and a per-notification count would hide a channel that fails every time.
     */
    public static final String NOTIFICATION_DELIVERY_ATTEMPT = "aegis.notification.delivery.attempt";

    /** Timer: how long one transport call took. Tag: channel. */
    public static final String NOTIFICATION_DELIVERY_LATENCY = "aegis.notification.delivery.latency";

    /**
     * Counter: deliveries that exhausted every retry. Tag: channel.
     *
     * <p><strong>The number to alert on.</strong> Every increment is a customer who was never told
     * something the platform decided to tell them, and nothing else in the system reports it — the
     * payment itself succeeded, so no error surfaces anywhere else.
     */
    public static final String NOTIFICATION_DEAD_LETTERED = "aegis.notification.dead.lettered";

    /**
     * Counter: notifications suppressed before any transport was called. Tags: channel, reason.
     *
     * <p>Suppression is normal (an opted-out channel, no verified phone) but the reason matters: a
     * spike in {@code NO_TEMPLATE} means a producer is emitting a template key nobody wrote copy
     * for, and those customers are silently receiving nothing.
     */
    public static final String NOTIFICATION_SUPPRESSED = "aegis.notification.suppressed";

    /**
     * Counter: notification events that were already known. Tag: reason.
     *
     * <p>Expected to be non-zero forever: Kafka delivers at least once, so this is the de-duplication
     * working rather than a fault.
     */
    public static final String NOTIFICATION_DUPLICATE = "aegis.notification.duplicate";

    /** Gauge: deliveries waiting for a retry. A rising value means the backlog is not draining. */
    public static final String NOTIFICATION_RETRY_BACKLOG = "aegis.notification.retry.backlog";

    /** Counter: a delivery that failed and was rescheduled. Tag: channel. */
    public static final String NOTIFICATION_RETRY_SCHEDULED = "aegis.notification.retry.scheduled";

    /** Counter: an operator replayed a dead-lettered delivery. Tag: channel. */
    public static final String NOTIFICATION_REPLAYED = "aegis.notification.replayed";

    // ---- Common tag keys ----

    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_PROVIDER = "providerId";
    public static final String TAG_TOPIC = "topic";
    public static final String TAG_STEP = "step";
    public static final String TAG_DECISION = "decision";
    public static final String TAG_RISK_LEVEL = "riskLevel";
    public static final String TAG_PAYMENT_TYPE = "paymentType";
    public static final String TAG_CHANNEL = "channel";
    public static final String TAG_REASON = "reason";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_REJECTED = "rejected";
    public static final String OUTCOME_CIRCUIT_OPEN = "circuit_open";
}
