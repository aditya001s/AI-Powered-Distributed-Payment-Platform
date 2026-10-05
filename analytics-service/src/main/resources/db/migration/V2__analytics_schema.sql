-- =============================================================================
-- analytics-service : the payment read model
--
-- ---- Why this is a projection and not a set of counters ----
--
-- The obvious way to build a dashboard from an event stream is to increment
-- counters: payments_today += 1 on payment-created, completed_today += 1 on
-- payment-completed. It is also wrong, and wrong in the way that never gets
-- noticed.
--
-- Kafka delivers at least once. A redelivered payment-completed increments a
-- counter twice, and the dashboard then reports more completed payments than
-- exist. Nothing errors. No row is malformed. The number is simply too big, and
-- there is no way to tell by how much, because the correct value was never
-- stored anywhere.
--
-- So this service stores ONE ROW PER PAYMENT and derives every total, rate and
-- time series from those rows at query time. A redelivered event re-writes the
-- same row with the same values, which is a no-op. At-least-once delivery
-- therefore cannot inflate anything: idempotency is a property of the shape of
-- the data rather than of a de-duplication cache in front of it.
--
-- The de-duplication cache still runs (EventConsumerSupport, Redis, 24h) because
-- it saves work. It is not what makes the numbers right.
--
-- ---- Why aggregates are computed on the fly ----
--
-- Every chart is a `date_trunc` GROUP BY over this table. No pre-aggregated
-- rollup table exists, deliberately: a rollup is a counter with extra steps, and
-- it would reintroduce exactly the double-count this design removes.
--
-- That is a genuine trade rather than a free win. At millions of payments a day
-- these queries would need a rollup, and the honest way to add one later is a
-- scheduled job that recomputes a bucket from these rows -- idempotently, by
-- replacing the bucket rather than incrementing it. The partial and covering
-- indexes below are what keep the on-the-fly version viable until then.
--
-- ---- Why event ordering needs a guard ----
--
-- Kafka orders records within one topic partition and makes no promise across
-- topics. payment-created and payment-completed are different topics, so a
-- completion can be applied before the creation it belongs to -- routinely, on a
-- cold start replaying history, and occasionally under normal load.
--
-- Two mechanisms handle it, and neither is optional:
--
--   * status_rank. A status only moves forward. A late payment-created cannot
--     drag a COMPLETED payment back to CREATED, which would make the dashboard
--     report a settled payment as in-flight forever.
--   * COALESCE on every write. A later event never nulls a field an earlier one
--     filled. payment-completed carries no payment type; applying it must not
--     erase the type payment-created already recorded.
--
-- Platform-wide conventions:
--   * TIMESTAMPTZ, never TIMESTAMP. A naive timestamp is ambiguous as soon as two
--     replicas run in different zones, and uninterpretable after the fact.
--   * Money is always BIGINT minor units (paise), never a floating-point type.
--     Doubles cannot represent 0.01 exactly, so summing them loses money.
--   * Public identifiers are opaque and random, never sequential, so they cannot
--     be enumerated and do not leak row counts.
--   * Flyway owns the schema; Hibernate runs with ddl-auto=validate.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- payment_facts
--
-- One row per payment, assembled from several topics. This is the only table the
-- dashboard's numbers come from.
--
-- It is a READ MODEL, not a record of truth. payment-service owns a payment,
-- ledger-service owns the money, fraud-service owns the verdict. Everything here
-- is a copy kept for querying, and it can be rebuilt by replaying the topics --
-- which is why nothing in this service is allowed to be the only place a fact
-- exists.
-- -----------------------------------------------------------------------------
CREATE TABLE payment_facts (
    id                      BIGSERIAL     PRIMARY KEY,

    -- The natural key, and UNIQUE. This is what makes a redelivered event an
    -- upsert instead of a second row -- the single most important constraint in
    -- the file.
    payment_id              VARCHAR(48)   NOT NULL,

    transaction_id          VARCHAR(48),

    -- ---- who ----
    initiator_user_id       VARCHAR(40),
    destination_user_id     VARCHAR(40),
    source_wallet_id        VARCHAR(48),
    destination_wallet_id   VARCHAR(48),

    -- ---- how much ----
    amount_minor            BIGINT,
    currency                VARCHAR(3),
    -- The platform's fee, which is revenue. Separate from the amount because a
    -- dashboard that added them would report money the platform never moved.
    fee_minor               BIGINT,
    -- Cumulative, so a second partial refund replaces rather than adds. A
    -- running total incremented per event is precisely the double-count this
    -- table exists to avoid.
    refunded_minor          BIGINT        NOT NULL DEFAULT 0,

    payment_type            VARCHAR(32),

    -- ---- where it got to ----
    status                  VARCHAR(24)   NOT NULL,

    -- Denormalised ordering key for the out-of-order guard. Stored rather than
    -- derived in the query because the guard runs on every write, and a CASE
    -- expression in the UPDATE's WHERE clause could not use an index.
    status_rank             SMALLINT      NOT NULL,

    -- ---- what fraud controls said ----
    risk_level              VARCHAR(16),
    fraud_decision          VARCHAR(16),
    fraud_probability       DOUBLE PRECISION,
    -- The same number as percentage points. Kept as a small integer so a
    -- histogram is a GROUP BY on a cheap column rather than arithmetic over
    -- every row.
    risk_score              SMALLINT,
    -- True when the verdict was reached WITHOUT the model, on rules alone. It
    -- changes what every other risk number means: a LOW level with this set says
    -- "nothing was found", not "nothing is there". A dashboard that charted risk
    -- without it would show a clean day during a model outage.
    degraded_mode           BOOLEAN       NOT NULL DEFAULT FALSE,
    model_version           VARCHAR(64),
    rule_hits               VARCHAR(512),

    -- ---- how it settled ----
    provider_id             VARCHAR(32),
    -- More than one means routing fell back. Counted from the attempt list on
    -- the completion event rather than incremented, for the same reason as
    -- refunded_minor.
    provider_attempt_count  SMALLINT,
    ledger_transaction_id   VARCHAR(48),

    failure_code            VARCHAR(64),
    failure_reason          VARCHAR(512),

    -- ---- timings ----
    -- Measured by the orchestrator, from acceptance to settlement. The only
    -- end-to-end latency figure on the platform: every service knows its own
    -- step, and nothing else knows the total.
    end_to_end_latency_ms   BIGINT,
    scoring_latency_ms      BIGINT,

    -- ---- time axis ----
    -- Every time series buckets on this. NOT NULL because a chart cannot plot a
    -- null, so it is seeded from whichever event created the row and corrected
    -- to the authoritative value when payment-created arrives. It only ever
    -- moves EARLIER (see the LEAST() in the projection): a payment cannot have
    -- been created after something that already happened to it.
    created_at              TIMESTAMPTZ   NOT NULL,
    completed_at            TIMESTAMPTZ,

    -- ---- projection bookkeeping ----
    -- When this service first saw the payment, as opposed to when the payment
    -- happened. The gap between the two is replay lag, and telling them apart is
    -- what stops a backfill looking like a traffic spike.
    first_seen_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- occurredAt of the newest event applied. Drives the freshness figure the
    -- API returns with every response.
    last_event_at           TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_payment_facts_payment UNIQUE (payment_id),

    CONSTRAINT ck_payment_facts_status CHECK (status IN (
        'CREATED', 'FUNDS_RESERVED', 'RISK_ASSESSED', 'ON_HOLD', 'AUTHORIZED',
        'CAPTURED', 'COMPLETED', 'REJECTED', 'FAILED', 'REFUNDED',
        'PARTIALLY_REFUNDED')),

    CONSTRAINT ck_payment_facts_risk_level CHECK (
        risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    CONSTRAINT ck_payment_facts_decision CHECK (
        fraud_decision IS NULL OR fraud_decision IN ('APPROVE', 'REVIEW', 'HOLD', 'REJECT')),

    -- Money is never negative here. A negative amount would flow into a SUM and
    -- silently reduce reported volume rather than failing.
    CONSTRAINT ck_payment_facts_amount CHECK (amount_minor IS NULL OR amount_minor >= 0),
    CONSTRAINT ck_payment_facts_fee CHECK (fee_minor IS NULL OR fee_minor >= 0),
    CONSTRAINT ck_payment_facts_refunded CHECK (refunded_minor >= 0),

    CONSTRAINT ck_payment_facts_probability CHECK (
        fraud_probability IS NULL OR (fraud_probability >= 0.0 AND fraud_probability <= 1.0)),

    -- The score and the probability are the same fact twice. Pairing them by
    -- CHECK means they cannot drift, which they otherwise would the first time
    -- one is written without the other.
    CONSTRAINT ck_payment_facts_score_pairs_probability CHECK (
        (risk_score IS NULL) = (fraud_probability IS NULL)),
    CONSTRAINT ck_payment_facts_score_matches_probability CHECK (
        risk_score IS NULL OR risk_score = ROUND(fraud_probability * 100)),

    CONSTRAINT ck_payment_facts_latency CHECK (
        end_to_end_latency_ms IS NULL OR end_to_end_latency_ms >= 0),

    -- created_at is the time axis, so a completion before creation would produce
    -- a negative duration and a chart that plots backwards.
    CONSTRAINT ck_payment_facts_completed_after_created CHECK (
        completed_at IS NULL OR completed_at >= created_at),

    CONSTRAINT ck_payment_facts_rank CHECK (status_rank BETWEEN 0 AND 100)
);

COMMENT ON TABLE payment_facts IS
    'Read model: one row per payment, assembled from several topics. Every dashboard number is derived from these rows, so at-least-once delivery cannot inflate a total. Rebuildable by replaying the topics.';
COMMENT ON COLUMN payment_facts.status_rank IS
    'Monotonic ordering key. A status only moves forward, so a late payment-created cannot drag a settled payment back to CREATED.';
COMMENT ON COLUMN payment_facts.degraded_mode IS
    'The verdict was reached without the model. A LOW risk level then means "nothing was found", not "nothing is there".';
COMMENT ON COLUMN payment_facts.created_at IS
    'The time axis for every chart. Seeded from whichever event created the row and only ever corrected earlier.';
COMMENT ON COLUMN payment_facts.first_seen_at IS
    'When the projection saw the payment, not when it happened. The gap is replay lag, which stops a backfill looking like a traffic spike.';

-- ---- the time-series query, and nothing else ----
-- Every chart is a date_trunc GROUP BY over a window on created_at. This index
-- carries status so the common "volume and success rate over time" query is an
-- index-only scan rather than a heap fetch per row.
CREATE INDEX idx_payment_facts_timeline ON payment_facts (created_at DESC, status);

-- The cross-customer payment list, which is the gap this service fills:
-- payment-service is customer-scoped and has no such view.
CREATE INDEX idx_payment_facts_user ON payment_facts (initiator_user_id, created_at DESC);
CREATE INDEX idx_payment_facts_status ON payment_facts (status, created_at DESC);
CREATE INDEX idx_payment_facts_provider ON payment_facts (provider_id, created_at DESC)
    WHERE provider_id IS NOT NULL;

-- Risk visualisation: the band histogram and the high-risk list.
CREATE INDEX idx_payment_facts_risk ON payment_facts (risk_level, created_at DESC)
    WHERE risk_level IS NOT NULL;
CREATE INDEX idx_payment_facts_score ON payment_facts (risk_score DESC, created_at DESC)
    WHERE risk_score IS NOT NULL;

-- Partial, because these are the rows an operator actually goes looking for and
-- they are a small fraction of the table.
--
-- A degraded verdict produces no error anywhere: the payment succeeds, the score
-- is 0.0, and the only trace is this flag. A rising count means the platform has
-- been deciding without its model.
CREATE INDEX idx_payment_facts_degraded ON payment_facts (created_at DESC)
    WHERE degraded_mode;

-- Payments that needed more than one provider. Routing working, but worth
-- watching: a rising rate means a rail is failing.
CREATE INDEX idx_payment_facts_fallback ON payment_facts (created_at DESC)
    WHERE provider_attempt_count > 1;

-- Latency percentiles are computed over completed payments only. An in-flight
-- payment has no duration, and including it as zero would drag every percentile
-- down and make the platform look faster the more backlog it had.
CREATE INDEX idx_payment_facts_latency ON payment_facts (end_to_end_latency_ms)
    WHERE end_to_end_latency_ms IS NOT NULL;

-- -----------------------------------------------------------------------------
-- projection_stream_state
--
-- One row per topic this service consumes: the newest event it has applied.
--
-- ---- Why a dashboard must publish its own staleness ----
--
-- A read model that stops updating does not go blank. It keeps serving the last
-- numbers it had, confidently and with no indication that they are old. An
-- operator reading "0 held payments" cannot tell whether the queue is clear or
-- the consumer died three hours ago, and those call for opposite responses.
--
-- So the API returns a freshness figure with every response, derived from this
-- table, and it is per topic rather than global. A single global figure would go
-- stale only when EVERY stream stopped; per topic, a broken fraud-checked
-- consumer is visible while payments still flow.
--
-- Deliberately holds no event count. A count would be a counter, and a
-- redelivered event would inflate it -- the exact mistake payment_facts is
-- shaped to avoid. Timestamps are idempotent: applying the same event twice
-- writes the same value.
-- -----------------------------------------------------------------------------
CREATE TABLE projection_stream_state (
    topic           VARCHAR(96)   PRIMARY KEY,

    -- occurredAt of the newest event applied from this topic. The lag figure is
    -- now() minus this, which is what an operator actually wants to know.
    last_event_at   TIMESTAMPTZ   NOT NULL,

    -- When this service applied it. Distinct from last_event_at: during a replay
    -- the two diverge by hours, and confusing them makes a backfill look like an
    -- outage.
    last_applied_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE projection_stream_state IS
    'Per-topic high-water mark, so the API can report its own staleness. A read model that stops updating keeps serving old numbers confidently; this is what makes that visible.';
COMMENT ON COLUMN projection_stream_state.last_event_at IS
    'occurredAt of the newest applied event. now() minus this is the consumer lag.';
COMMENT ON COLUMN projection_stream_state.last_applied_at IS
    'When it was applied. Diverges from last_event_at during a replay, which is what stops a backfill reading as an outage.';
