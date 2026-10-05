-- =============================================================================
-- payment-orchestrator : saga state, step log and provider attempts
--
-- A saga is a distributed transaction with no two-phase commit. Its correctness
-- rests entirely on being able to answer two questions after a crash:
--
--   1. How far did this payment get?
--   2. Which side effects therefore need undoing?
--
-- Neither is answerable from memory, so both are persisted BEFORE the side effect
-- they describe is attempted. That ordering is the whole design: a step recorded
-- as ATTEMPTED with no outcome means "we may have done this, check" — which is
-- exactly the state compensation needs to reason about. Recording after the fact
-- would leave a crash indistinguishable from a step never started.
--
-- Money is BIGINT minor units throughout. Never floating point.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- payment_sagas : one row per payment being orchestrated
--
-- Keyed by payment_id with a UNIQUE constraint, which is the entire idempotency
-- mechanism for this service. Kafka delivers payment-created at least once, so the
-- listener will see duplicates after any rebalance or retry; the second attempt
-- loses the insert and is recognised as a replay rather than starting a second
-- saga that would reserve the customer's funds twice.
--
-- The compensation-relevant handles (hold_id, authorization_id, ledger_transaction_id)
-- are written as each step succeeds. Their presence is what tells the compensator
-- what to undo, so nothing may be inferred: a NULL authorization_id means "no
-- provider charge to void", and it has to be trustworthy.
-- -----------------------------------------------------------------------------
CREATE TABLE payment_sagas (
    id                      BIGSERIAL     PRIMARY KEY,

    saga_id                 VARCHAR(48)   NOT NULL,

    -- Idempotency key. One saga per payment, enforced below.
    payment_id              VARCHAR(48)   NOT NULL,
    transaction_id          VARCHAR(48),

    initiator_user_id       VARCHAR(40)   NOT NULL,
    source_wallet_id        VARCHAR(40)   NOT NULL,
    destination_wallet_id   VARCHAR(40)   NOT NULL,
    destination_user_id     VARCHAR(40),

    amount_minor            BIGINT        NOT NULL,
    currency                CHAR(3)       NOT NULL,
    -- Provider fee, known only once a provider has been chosen and has charged.
    fee_minor               BIGINT        NOT NULL DEFAULT 0,

    payment_type            VARCHAR(24)   NOT NULL DEFAULT 'P2P_TRANSFER',
    description             VARCHAR(256),

    status                  VARCHAR(24)   NOT NULL DEFAULT 'STARTED',

    -- The step currently being attempted, for operational triage. "Which step is
    -- this payment stuck on" is the first question asked during an incident.
    current_step            VARCHAR(32),

    -- ---- Compensation handles ----
    -- Each is set the moment its step succeeds, and each one being non-NULL means
    -- there is a real side effect out there that a failure must undo.
    hold_id                 VARCHAR(48),
    provider_id             VARCHAR(32),
    authorization_id        VARCHAR(96),
    provider_reference      VARCHAR(96),
    ledger_transaction_id   VARCHAR(80),

    -- Providers tried, in order, comma-separated. More than one means a fallback
    -- occurred, which is the signal that routing did its job.
    provider_attempts       VARCHAR(256),

    -- ---- Risk verdict. Auto-approved until Phase 6 supplies a real one. ----
    risk_level              VARCHAR(16),
    fraud_decision          VARCHAR(16),
    fraud_probability       DOUBLE PRECISION,

    -- ---- Failure detail ----
    failure_code            VARCHAR(48),
    failure_reason          VARCHAR(512),
    failed_step             VARCHAR(32),

    -- FALSE means a side effect may still be outstanding and an operator must look.
    -- Surfaced on the payment-failed event rather than only logged, so alerting and
    -- analytics can both see it.
    compensation_complete   BOOLEAN       NOT NULL DEFAULT TRUE,

    -- How many times the saga has been picked up. A recovery sweep increments it,
    -- so a saga that keeps being retried without progressing is visible.
    attempt_count           INTEGER       NOT NULL DEFAULT 1,

    correlation_id          VARCHAR(64),

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    version                 BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_payment_sagas_saga_id UNIQUE (saga_id),

    -- The idempotency guarantee. A redelivered payment-created cannot start a
    -- second saga, so the customer's funds cannot be reserved twice.
    CONSTRAINT uq_payment_sagas_payment_id UNIQUE (payment_id),

    CONSTRAINT ck_payment_sagas_status CHECK (status IN
        ('STARTED', 'FUNDS_RESERVED', 'RISK_ASSESSED', 'AUTHORIZED', 'LEDGER_POSTED',
         'CAPTURED', 'COMPLETED', 'COMPENSATING', 'FAILED', 'REJECTED')),

    CONSTRAINT ck_payment_sagas_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payment_sagas_fee_non_negative CHECK (fee_minor >= 0),
    CONSTRAINT ck_payment_sagas_currency_upper CHECK (currency = upper(currency)),
    CONSTRAINT ck_payment_sagas_not_self CHECK (source_wallet_id <> destination_wallet_id),
    CONSTRAINT ck_payment_sagas_attempts CHECK (attempt_count >= 1),

    CONSTRAINT ck_payment_sagas_risk_level CHECK (risk_level IS NULL OR risk_level IN
        ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_payment_sagas_fraud_decision CHECK (fraud_decision IS NULL OR fraud_decision IN
        ('APPROVE', 'REVIEW', 'HOLD', 'REJECT')),
    CONSTRAINT ck_payment_sagas_fraud_probability CHECK (
        fraud_probability IS NULL OR (fraud_probability >= 0 AND fraud_probability <= 1)),

    -- A terminal state must record when it finished; the stuck-saga sweep and every
    -- latency report read this column.
    CONSTRAINT ck_payment_sagas_completed_at CHECK (
        status NOT IN ('COMPLETED', 'FAILED', 'REJECTED') OR completed_at IS NOT NULL),

    -- An authorisation without a provider is unattributable, and its fee could not
    -- be booked against any settlement account.
    CONSTRAINT ck_payment_sagas_authorization_attributed CHECK (
        authorization_id IS NULL OR provider_id IS NOT NULL),

    -- A fee can only exist once a provider charged it.
    CONSTRAINT ck_payment_sagas_fee_attributed CHECK (
        fee_minor = 0 OR provider_id IS NOT NULL)
);

COMMENT ON TABLE payment_sagas IS
    'Saga state per payment. Persisted before each side effect so a crash is recoverable.';
COMMENT ON COLUMN payment_sagas.payment_id IS
    'Idempotency key. The UNIQUE constraint is what stops a redelivered event reserving funds twice.';
COMMENT ON COLUMN payment_sagas.compensation_complete IS
    'FALSE means a side effect may be outstanding. Alerted on; never merely logged.';
COMMENT ON CONSTRAINT ck_payment_sagas_fee_attributed ON payment_sagas IS
    'An unattributable fee could not be booked against any provider settlement account.';

-- The recovery sweep: sagas that are neither finished nor progressing.
CREATE INDEX idx_payment_sagas_in_flight ON payment_sagas (updated_at)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'REJECTED');

-- Operators query by outcome over a time window.
CREATE INDEX idx_payment_sagas_status ON payment_sagas (status, created_at DESC);
CREATE INDEX idx_payment_sagas_created_at ON payment_sagas (created_at DESC);
CREATE INDEX idx_payment_sagas_provider ON payment_sagas (provider_id, created_at DESC)
    WHERE provider_id IS NOT NULL;

-- Expected to be empty. Every row here is money that may be stranded.
CREATE INDEX idx_payment_sagas_uncompensated ON payment_sagas (created_at DESC)
    WHERE compensation_complete = FALSE;

-- -----------------------------------------------------------------------------
-- saga_steps : an append-only log of every step attempt
--
-- One row per attempt, not per step, so a retried step leaves both attempts
-- visible. That distinction matters during an incident: "reserve succeeded on the
-- third try" and "reserve succeeded" describe very different provider health.
--
-- Written BEFORE the side effect is attempted, then updated with the outcome. A
-- row left in ATTEMPTED after a crash is the signal that the effect may or may not
-- have happened, which is precisely what compensation must assume.
-- -----------------------------------------------------------------------------
CREATE TABLE saga_steps (
    id              BIGSERIAL     PRIMARY KEY,

    saga_id         VARCHAR(48)   NOT NULL,
    payment_id      VARCHAR(48)   NOT NULL,

    step_name       VARCHAR(32)   NOT NULL,
    attempt         INTEGER       NOT NULL DEFAULT 1,

    status          VARCHAR(16)   NOT NULL DEFAULT 'ATTEMPTED',

    -- Free-form detail: the provider chosen, the hold returned, the error seen.
    detail          VARCHAR(512),
    error_code      VARCHAR(48),

    started_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,

    CONSTRAINT fk_saga_steps_saga FOREIGN KEY (saga_id)
        REFERENCES payment_sagas (saga_id) ON DELETE RESTRICT,

    CONSTRAINT uq_saga_steps_attempt UNIQUE (saga_id, step_name, attempt),

    CONSTRAINT ck_saga_steps_status CHECK (status IN
        ('ATTEMPTED', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'COMPENSATED')),

    CONSTRAINT ck_saga_steps_attempt CHECK (attempt >= 1),

    -- A finished step must say when it finished.
    CONSTRAINT ck_saga_steps_finished_at CHECK (
        status = 'ATTEMPTED' OR finished_at IS NOT NULL)
);

COMMENT ON TABLE saga_steps IS
    'Append-only step log. A row still ATTEMPTED after a crash means the effect may have happened.';
COMMENT ON COLUMN saga_steps.attempt IS
    'One row per attempt. "Succeeded on the third try" and "succeeded" mean different things.';

CREATE INDEX idx_saga_steps_saga ON saga_steps (saga_id, started_at);
CREATE INDEX idx_saga_steps_payment ON saga_steps (payment_id);
-- Finds steps that were interrupted mid-flight.
CREATE INDEX idx_saga_steps_attempted ON saga_steps (started_at)
    WHERE status = 'ATTEMPTED';

-- -----------------------------------------------------------------------------
-- provider_attempts : one row per call to a payment provider
--
-- Separate from saga_steps because this is the data the router feeds on. Health,
-- latency and failure rate are all derived from here, so it is queried by provider
-- and time window rather than by saga.
--
-- Keeping declines distinct from faults is the point of `outcome`. A provider that
-- declines a card is working perfectly; one that times out is not. Collapsing the
-- two would make a run of legitimate declines look like an outage and route traffic
-- away from a healthy provider.
-- -----------------------------------------------------------------------------
CREATE TABLE provider_attempts (
    id                  BIGSERIAL     PRIMARY KEY,

    saga_id             VARCHAR(48),
    payment_id          VARCHAR(48),

    provider_id         VARCHAR(32)   NOT NULL,
    -- Position in the routing order: 1 is the router's first choice. Anything above
    -- 1 means a fallback happened.
    attempt_number      INTEGER       NOT NULL DEFAULT 1,

    operation           VARCHAR(24)   NOT NULL DEFAULT 'AUTHORIZE',

    outcome             VARCHAR(24)   NOT NULL,

    amount_minor        BIGINT,
    currency            CHAR(3),
    fee_minor           BIGINT,

    authorization_id    VARCHAR(96),
    provider_reference  VARCHAR(96),

    error_code          VARCHAR(48),
    error_message       VARCHAR(512),

    latency_ms          BIGINT        NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_provider_attempts_outcome CHECK (outcome IN
        ('APPROVED', 'DECLINED', 'FAILED', 'TIMEOUT', 'CIRCUIT_OPEN', 'VOIDED')),

    CONSTRAINT ck_provider_attempts_operation CHECK (operation IN
        ('AUTHORIZE', 'VOID', 'HEALTH_CHECK')),

    CONSTRAINT ck_provider_attempts_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_provider_attempts_latency CHECK (latency_ms >= 0),
    CONSTRAINT ck_provider_attempts_amounts CHECK (
        (amount_minor IS NULL OR amount_minor > 0) AND (fee_minor IS NULL OR fee_minor >= 0)),

    -- An approval must return something to void it with later.
    CONSTRAINT ck_provider_attempts_approved_has_auth CHECK (
        outcome <> 'APPROVED' OR operation <> 'AUTHORIZE' OR authorization_id IS NOT NULL)
);

COMMENT ON TABLE provider_attempts IS
    'Per-call provider history. The routers health and latency signals are derived from this.';
COMMENT ON COLUMN provider_attempts.outcome IS
    'DECLINED is a working provider saying no. FAILED/TIMEOUT is a broken one. Never conflate them.';
COMMENT ON COLUMN provider_attempts.attempt_number IS
    'Routing position. Greater than 1 means the router fell back past a preferred provider.';

-- The router's hot path: recent attempts for one provider.
CREATE INDEX idx_provider_attempts_provider ON provider_attempts (provider_id, created_at DESC);
CREATE INDEX idx_provider_attempts_payment ON provider_attempts (payment_id)
    WHERE payment_id IS NOT NULL;
CREATE INDEX idx_provider_attempts_outcome ON provider_attempts (outcome, created_at DESC);

-- -----------------------------------------------------------------------------
-- provider_state : operator overrides and observed health, surviving restarts
--
-- The circuit breakers themselves live in memory, which is correct: they measure
-- what THIS instance is currently seeing, and a breaker restored from a database
-- would be reasoning about another instance's past. What must survive a restart is
-- the deliberate decision — an operator disabling a provider during an incident
-- has to stay disabled through a rolling deploy, or the deploy silently undoes the
-- mitigation.
-- -----------------------------------------------------------------------------
CREATE TABLE provider_state (
    provider_id         VARCHAR(32)   PRIMARY KEY,

    -- Operator kill switch. Deliberately persisted; see above.
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,

    -- Last observed probe result, for the admin view. Advisory only: routing
    -- decisions consult the live breaker, not this column, because a stored health
    -- value is stale the moment it is written.
    last_health         VARCHAR(16)   NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at     TIMESTAMPTZ,
    last_latency_ms     BIGINT,
    consecutive_failures INTEGER      NOT NULL DEFAULT 0,

    disabled_reason     VARCHAR(256),
    disabled_by         VARCHAR(64),
    disabled_at         TIMESTAMPTZ,

    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT ck_provider_state_health CHECK (last_health IN
        ('HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN')),
    CONSTRAINT ck_provider_state_failures CHECK (consecutive_failures >= 0),
    CONSTRAINT ck_provider_state_latency CHECK (last_latency_ms IS NULL OR last_latency_ms >= 0),

    -- A disabled provider must record why. "Who turned this off and why" is
    -- unanswerable an hour later otherwise, and the answer decides whether it is
    -- safe to turn back on.
    CONSTRAINT ck_provider_state_disabled_reason CHECK (
        enabled OR disabled_reason IS NOT NULL)
);

COMMENT ON TABLE provider_state IS
    'Operator overrides and last observed health. Breakers stay in memory; kill switches persist.';
COMMENT ON COLUMN provider_state.last_health IS
    'Advisory, for the admin view. Routing consults the live circuit breaker instead.';
COMMENT ON CONSTRAINT ck_provider_state_disabled_reason ON provider_state IS
    'Forces an explanation, because it decides whether re-enabling is safe.';

-- The three simulated providers. Rows are created here so an operator can disable a
-- provider before it has ever been called, and so the admin view lists all three
-- from a cold start rather than only those that happen to have taken traffic.
--
-- These are SIMULATED providers with invented names. Nothing in this platform
-- connects to a real bank, card network or UPI rail.
INSERT INTO provider_state (provider_id, enabled, last_health) VALUES
    ('SWIFTPAY',  TRUE, 'UNKNOWN'),
    ('NEXUSPAY',  TRUE, 'UNKNOWN'),
    ('PAYBRIDGE', TRUE, 'UNKNOWN');
