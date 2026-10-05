-- =============================================================================
-- fraud-service : risk scoring, alerting and the analyst workflow
--
-- Four tables, and the first one is the design decision worth explaining.
--
-- ---- Why fraud-service keeps its own payment history ----
--
-- The model needs behavioural aggregates: how many payments this account made in
-- the last hour, whether this device has been seen before, how far it is from the
-- last known location. Those could be fetched from payment-service and
-- wallet-service at scoring time, and that would be wrong twice over: it puts two
-- more network calls on the payment's critical path, and it makes a fraud decision
-- fail when an unrelated service is redeploying.
--
-- So fraud-service consumes the events it already receives and builds its own read
-- model in payment_observations. Aggregates then become local SQL, and the
-- scoring path touches exactly one database plus ml-service.
--
-- ---- Why the aggregates exclude the payment being scored ----
--
-- The saga calls fraud-service milliseconds after payment-created is emitted, so
-- the observation for the current payment may or may not have arrived yet. Every
-- aggregate query therefore excludes it by payment_id explicitly. Without that,
-- the same payment would score differently depending on a race between two
-- consumers -- and the feature contract says these windows are PRIOR to the
-- payment, which is what ml-service was trained on.
--
-- Platform-wide conventions:
--   * TIMESTAMPTZ, never TIMESTAMP.
--   * Money is BIGINT minor units (paise), never floating point.
--   * Public identifiers are opaque and random, never sequential.
--   * Flyway owns the schema; Hibernate runs with ddl-auto=validate.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- payment_observations
--
-- One row per payment this service has heard about, built from payment-created
-- and updated by the outcome events. Deliberately NOT a copy of the payment
-- aggregate: it holds only what the feature pipeline reads, because a read model
-- that mirrors another service's table becomes a second source of truth that
-- somebody will eventually query for the wrong reason.
-- -----------------------------------------------------------------------------
CREATE TABLE payment_observations (
    payment_id            VARCHAR(48)  PRIMARY KEY,

    initiator_user_id     VARCHAR(40)  NOT NULL,
    destination_wallet_id VARCHAR(40)  NOT NULL,

    amount_minor          BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    payment_type          VARCHAR(24)  NOT NULL,

    -- Risk context as captured at the edge. Nullable throughout: an API-channel
    -- payment has no device, and a customer who denied location has no
    -- coordinates. Absence is a signal the model is trained on, so it must not be
    -- defaulted here.
    device_id             VARCHAR(96),
    device_fingerprint    VARCHAR(128),
    country_code          VARCHAR(2),
    latitude              DOUBLE PRECISION,
    longitude             DOUBLE PRECISION,

    -- The payment's eventual fate, filled in by the outcome listeners. Needed
    -- because the velocity features distinguish attempts from failures: a burst of
    -- declines is a very different signal from a burst of successes.
    outcome               VARCHAR(16)  NOT NULL DEFAULT 'PENDING',

    -- Confirmed fraud, set when an analyst rejects the payment. This is what the
    -- prior_chargeback_count feature is derived from: the platform has no card
    -- network and therefore no chargebacks, so an analyst's REJECT is the closest
    -- honest equivalent -- a human confirming that a payment was fraudulent.
    confirmed_fraud       BOOLEAN      NOT NULL DEFAULT FALSE,

    occurred_at           TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_observation_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_observation_outcome CHECK (
        outcome IN ('PENDING', 'COMPLETED', 'FAILED', 'REJECTED')),
    -- Either both coordinates or neither. One alone cannot produce a distance and
    -- would silently disable the geo-velocity feature for that row.
    CONSTRAINT ck_observation_geo_pairwise CHECK (
        (latitude IS NULL) = (longitude IS NULL))
);

COMMENT ON TABLE payment_observations IS
    'fraud-service''s own read model of payment history, built from events. Feeds the behavioural features.';
COMMENT ON COLUMN payment_observations.confirmed_fraud IS
    'Set when an analyst rejects the payment. The platform has no card network, so an analyst REJECT is the honest stand-in for a chargeback.';

-- The velocity query's index: every aggregate is scoped to one user over a recent
-- window, so (user, time) covers all of them with one traversal.
CREATE INDEX idx_observations_user_time
    ON payment_observations (initiator_user_id, occurred_at DESC);

-- "Has this account used this device before?" A partial index because a null
-- device carries no information and would otherwise bloat the index with the
-- API-channel traffic that has no device at all.
CREATE INDEX idx_observations_user_device
    ON payment_observations (initiator_user_id, device_fingerprint)
    WHERE device_fingerprint IS NOT NULL;

CREATE INDEX idx_observations_user_country
    ON payment_observations (initiator_user_id, country_code)
    WHERE country_code IS NOT NULL;

-- "Has this account paid this counterparty before?"
CREATE INDEX idx_observations_user_payee
    ON payment_observations (initiator_user_id, destination_wallet_id);


-- -----------------------------------------------------------------------------
-- fraud_assessments
--
-- The verdict on one payment, and the evidence behind it.
--
-- payment_id is UNIQUE, which is what makes assessment idempotent: the saga can
-- call this service twice for the same payment -- a Kafka redelivery, a recovery
-- sweep resuming an interrupted saga -- and must get the same answer rather than a
-- second, differently-scored one. A payment whose risk verdict changed on retry
-- would be indefensible in a dispute.
--
-- feature_snapshot and model_version exist for auditability, and that is a
-- regulatory expectation for automated decision-making rather than a
-- nice-to-have: if a customer disputes a hold six months later, the exact model
-- build and the exact feature values that produced the score have to be
-- reproducible.
-- -----------------------------------------------------------------------------
CREATE TABLE fraud_assessments (
    id                    BIGSERIAL    PRIMARY KEY,

    assessment_id         VARCHAR(48)  NOT NULL,
    -- The idempotency key. One assessment per payment, enforced here rather than
    -- in application code, so two concurrent calls cannot both create one.
    payment_id            VARCHAR(48)  NOT NULL,
    transaction_id        VARCHAR(48),
    initiator_user_id     VARCHAR(40)  NOT NULL,

    amount_minor          BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,

    -- ---- the verdict ----
    fraud_probability     DOUBLE PRECISION NOT NULL,
    -- The same number in percentage points. Stored rather than derived so a
    -- dashboard cannot disagree with the API about what an analyst was shown.
    risk_score            INTEGER      NOT NULL,
    risk_level            VARCHAR(16)  NOT NULL,
    decision              VARCHAR(16)  NOT NULL,

    -- ---- the evidence ----
    -- Ordered, human-readable, and shown in the UI. Stored as text rather than
    -- regenerated on read: a reason regenerated by a later build could differ from
    -- the one the customer was actually given.
    reasons               TEXT,
    -- Comma-separated rule IDs. A small fixed vocabulary, so a JSONB array would
    -- buy nothing over a LIKE query.
    rule_hits             VARCHAR(512),

    model_version         VARCHAR(64),
    -- JSONB so a specific feature can be queried across assessments when
    -- investigating drift, which a TEXT blob would make impossible.
    feature_snapshot      JSONB,

    scoring_latency_ms    BIGINT       NOT NULL DEFAULT 0,

    -- TRUE when ml-service was unreachable and the verdict came from rules alone.
    -- The single most important field on this table for interpreting a LOW risk
    -- level: "the model said low risk" and "the model was down and the rules found
    -- nothing" are completely different levels of assurance.
    degraded_mode         BOOLEAN      NOT NULL DEFAULT FALSE,

    evaluated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_assessment_id UNIQUE (assessment_id),
    CONSTRAINT uq_assessment_payment UNIQUE (payment_id),

    CONSTRAINT ck_assessment_probability CHECK (
        fraud_probability >= 0.0 AND fraud_probability <= 1.0),
    -- The score IS the probability in percentage points. Enforced, because two
    -- fields that are meant to agree and are written separately will eventually
    -- disagree, and an analyst would have no way to tell which one was wrong.
    CONSTRAINT ck_assessment_score_matches_probability CHECK (
        risk_score = ROUND(fraud_probability * 100)),
    CONSTRAINT ck_assessment_risk_level CHECK (
        risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_assessment_decision CHECK (
        decision IN ('APPROVE', 'REVIEW', 'HOLD', 'REJECT')),
    CONSTRAINT ck_assessment_latency CHECK (scoring_latency_ms >= 0),
    -- A non-degraded assessment must name the model that produced it, or the score
    -- is unattributable and cannot be reproduced.
    CONSTRAINT ck_assessment_model_attributed CHECK (
        degraded_mode OR model_version IS NOT NULL)
);

COMMENT ON TABLE fraud_assessments IS
    'One risk verdict per payment, with the feature snapshot and model version needed to reproduce it.';
COMMENT ON CONSTRAINT ck_assessment_score_matches_probability ON fraud_assessments IS
    'The 0-100 score is the calibrated probability in percentage points. Two fields meant to agree must be constrained to agree.';
COMMENT ON COLUMN fraud_assessments.degraded_mode IS
    'TRUE when the model was unreachable and rules alone decided. Distinguishes "model said low risk" from "model was down".';

CREATE INDEX idx_assessments_user_time
    ON fraud_assessments (initiator_user_id, evaluated_at DESC);

CREATE INDEX idx_assessments_evaluated_at ON fraud_assessments (evaluated_at DESC);

-- The analyst queue's index: partial, because the rows that need a human are a
-- tiny fraction of all traffic and the index should stay that size.
CREATE INDEX idx_assessments_needing_review
    ON fraud_assessments (evaluated_at DESC)
    WHERE decision IN ('REVIEW', 'HOLD');

-- Operators watch this: a rising count means the model is unreachable and every
-- verdict is being made on rules alone.
CREATE INDEX idx_assessments_degraded
    ON fraud_assessments (evaluated_at DESC)
    WHERE degraded_mode;

-- Drift monitoring by model build.
CREATE INDEX idx_assessments_model_version
    ON fraud_assessments (model_version, evaluated_at DESC)
    WHERE model_version IS NOT NULL;


-- -----------------------------------------------------------------------------
-- fraud_alerts
--
-- The analyst work queue. One alert per assessment that needs a human, which is
-- why it is a separate table from fraud_assessments: an assessment is a fact about
-- a payment and never changes, whereas an alert has a lifecycle -- open, taken,
-- resolved -- and is mutated by whoever works it.
--
-- Merging the two would mean every assessment row carried five mostly-null
-- workflow columns, and the queue query would have to filter 99% of traffic it
-- does not care about.
-- -----------------------------------------------------------------------------
CREATE TABLE fraud_alerts (
    id                BIGSERIAL    PRIMARY KEY,

    alert_id          VARCHAR(48)  NOT NULL,
    payment_id        VARCHAR(48)  NOT NULL,
    assessment_id     VARCHAR(48)  NOT NULL,
    initiator_user_id VARCHAR(40)  NOT NULL,

    risk_level        VARCHAR(16)  NOT NULL,
    risk_score        INTEGER      NOT NULL,
    -- What the engine decided, kept alongside the alert so the queue can be sorted
    -- by urgency without joining: a HOLD is blocking a customer's payment right
    -- now, a REVIEW is not.
    decision          VARCHAR(16)  NOT NULL,
    summary           VARCHAR(512) NOT NULL,

    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    assigned_to       VARCHAR(40),

    -- Whether this alert is blocking a payment. An OPEN alert on a held payment is
    -- a customer waiting; an OPEN alert on a settled one is a review that can wait.
    blocks_payment    BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,

    version           BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_alert_id UNIQUE (alert_id),
    -- One alert per payment. A redelivered event must not produce a second entry in
    -- the queue for the same payment: two analysts would work it independently and
    -- could reach opposite conclusions.
    CONSTRAINT uq_alert_payment UNIQUE (payment_id),

    CONSTRAINT ck_alert_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED')),
    CONSTRAINT ck_alert_risk_level CHECK (
        risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_alert_decision CHECK (
        decision IN ('APPROVE', 'REVIEW', 'HOLD', 'REJECT')),
    -- A resolved alert must record when. The SLA report reads that column, and a
    -- null would silently drop the row from it.
    CONSTRAINT ck_alert_resolved_at CHECK (
        status <> 'RESOLVED' OR resolved_at IS NOT NULL),
    CONSTRAINT ck_alert_in_review_assigned CHECK (
        status <> 'IN_REVIEW' OR assigned_to IS NOT NULL)
);

COMMENT ON TABLE fraud_alerts IS
    'Analyst work queue. Separate from fraud_assessments because an assessment is immutable and an alert has a lifecycle.';

-- The queue itself: open alerts, most urgent first. Partial, so it stays the size
-- of the backlog rather than the size of history.
CREATE INDEX idx_alerts_open_queue
    ON fraud_alerts (blocks_payment DESC, risk_score DESC, created_at)
    WHERE status <> 'RESOLVED';

CREATE INDEX idx_alerts_assigned ON fraud_alerts (assigned_to, status)
    WHERE assigned_to IS NOT NULL;

CREATE INDEX idx_alerts_user ON fraud_alerts (initiator_user_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- analyst_decisions
--
-- Append-only record of every human decision on an alert.
--
-- Append-only, not a status column on fraud_alerts, because the audit question is
-- "who decided what, when, and why" and a mutable column answers only the last of
-- those. An analyst who releases a payment and a second who later reverses it are
-- two facts, and both have to survive.
--
-- Enforced by a trigger rather than by convention: an UPDATE or DELETE here is
-- refused by the database, so no application bug and no ad-hoc SQL can quietly
-- rewrite the reason a customer's payment was blocked.
-- -----------------------------------------------------------------------------
CREATE TABLE analyst_decisions (
    id            BIGSERIAL    PRIMARY KEY,

    decision_id   VARCHAR(48)  NOT NULL,
    alert_id      VARCHAR(48)  NOT NULL,
    payment_id    VARCHAR(48)  NOT NULL,

    -- RELEASE lets the payment continue; REJECT refuses it terminally. Both are
    -- irreversible for the payment, which is why the reason is mandatory.
    action        VARCHAR(16)  NOT NULL,
    reason        VARCHAR(512) NOT NULL,

    -- Taken from the validated token, never from a request field. "Who authorised
    -- this" is the first question asked about a released payment that turned out to
    -- be fraud, and an answer the caller could choose would be worthless.
    decided_by    VARCHAR(40)  NOT NULL,
    decided_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_analyst_decision_id UNIQUE (decision_id),
    CONSTRAINT ck_analyst_action CHECK (action IN ('RELEASE', 'REJECT')),
    -- A blank reason is not a reason. Enforced here as well as in the aggregate
    -- because support tooling and batch scripts also write to this table.
    CONSTRAINT ck_analyst_reason_present CHECK (length(trim(reason)) > 0)
);

COMMENT ON TABLE analyst_decisions IS
    'Append-only audit of human decisions on fraud alerts. UPDATE and DELETE are refused by trigger.';

CREATE INDEX idx_analyst_decisions_alert ON analyst_decisions (alert_id, decided_at);
CREATE INDEX idx_analyst_decisions_payment ON analyst_decisions (payment_id, decided_at);
CREATE INDEX idx_analyst_decisions_by ON analyst_decisions (decided_by, decided_at DESC);

-- -----------------------------------------------------------------------------
-- Append-only enforcement
--
-- The same pattern ledger-service uses for its journal. A comment saying
-- "append-only" is a hope; a trigger is a guarantee, and it holds against psql as
-- well as against the application.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION refuse_analyst_decision_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'analyst_decisions is append-only: % refused on decision %. Record a new '
        'decision instead; the reason a payment was blocked must never be rewritten.',
        TG_OP, COALESCE(OLD.decision_id, '(unknown)')
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_analyst_decisions_append_only
    BEFORE UPDATE OR DELETE ON analyst_decisions
    FOR EACH ROW EXECUTE FUNCTION refuse_analyst_decision_mutation();
