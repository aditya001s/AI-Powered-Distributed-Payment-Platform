-- =============================================================================
-- payment-service : payment aggregate and idempotency
--
-- payment-service owns the customer-facing payment record and its lifecycle. It
-- does NOT hold balances (wallet-service) and does NOT hold the accounting journal
-- (ledger-service, Phase 4). Keeping those three separate is what allows each to
-- answer its own question definitively.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- payments
--
-- One row per payment attempt, carried through the state machine encoded in
-- com.aegispay.common.domain.PaymentStatus.
--
-- The risk_* columns are captured ONCE, at initiation, and never recomputed.
-- That is deliberate: the fraud model must score the context the user actually
-- transacted in. Re-deriving device or IP data later would score a different
-- situation than the one that occurred, and would make a disputed decision
-- impossible to reproduce.
-- -----------------------------------------------------------------------------
CREATE TABLE payments (
    id                     BIGSERIAL     PRIMARY KEY,

    -- Public identifier used across services and in events.
    payment_id             VARCHAR(48)   NOT NULL,

    -- Human-facing reference shown on statements and quoted to support.
    -- Separate from payment_id so the internal identifier can change shape
    -- without altering what a customer has already been told.
    transaction_id         VARCHAR(48)   NOT NULL,

    -- Retained for audit: which client key produced this payment.
    idempotency_key        VARCHAR(120)  NOT NULL,

    initiator_user_id      VARCHAR(40)   NOT NULL,
    source_wallet_id       VARCHAR(40)   NOT NULL,
    destination_wallet_id  VARCHAR(40)   NOT NULL,
    destination_user_id    VARCHAR(40),

    amount_minor           BIGINT        NOT NULL,
    currency               CHAR(3)       NOT NULL,
    -- Provider/platform fee. Zero in Phase 3; provider routing arrives in Phase 5.
    fee_minor              BIGINT        NOT NULL DEFAULT 0,

    payment_type           VARCHAR(24)   NOT NULL DEFAULT 'P2P_TRANSFER',
    status                 VARCHAR(24)   NOT NULL DEFAULT 'CREATED',

    description            VARCHAR(256),

    -- ---- Reservation linkage ----
    -- The wallet hold backing this payment, so a compensating release can find it.
    hold_id                VARCHAR(48),

    -- ---- Risk context, captured at initiation ----
    device_id              VARCHAR(96),
    device_fingerprint     VARCHAR(128),
    ip_address             VARCHAR(64),
    user_agent             VARCHAR(256),
    country_code           CHAR(2),
    city                   VARCHAR(96),
    latitude               DOUBLE PRECISION,
    longitude              DOUBLE PRECISION,
    channel                VARCHAR(24)   NOT NULL DEFAULT 'UNKNOWN',
    merchant_id            VARCHAR(48),
    merchant_category      VARCHAR(48),

    -- ---- Risk outcome. Populated by fraud-service in Phase 6. ----
    risk_level             VARCHAR(16),
    fraud_probability      DOUBLE PRECISION,
    fraud_decision         VARCHAR(16),

    -- ---- Provider outcome. Populated by the orchestrator in Phase 5. ----
    provider_id            VARCHAR(32),
    provider_reference     VARCHAR(96),

    -- ---- Failure detail ----
    failure_code           VARCHAR(48),
    failure_reason         VARCHAR(512),

    -- ---- Refunds ----
    -- Cumulative amount refunded. Capped at amount_minor by a CHECK, so
    -- over-refunding is unpersistable rather than merely discouraged.
    refunded_minor         BIGINT        NOT NULL DEFAULT 0,

    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    version                BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_payments_payment_id     UNIQUE (payment_id),
    CONSTRAINT uq_payments_transaction_id UNIQUE (transaction_id),

    -- Second line of defence behind the idempotency_keys table: even if that
    -- store were bypassed, one key cannot produce two payments.
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),

    CONSTRAINT ck_payments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payments_fee_non_negative CHECK (fee_minor >= 0),

    -- A wallet paying itself would net to nothing while still producing ledger
    -- entries and notifications. Refused outright.
    CONSTRAINT ck_payments_not_self CHECK (source_wallet_id <> destination_wallet_id),

    CONSTRAINT ck_payments_status CHECK (status IN
        ('CREATED', 'FUNDS_RESERVED', 'RISK_ASSESSED', 'ON_HOLD', 'AUTHORIZED',
         'CAPTURED', 'COMPLETED', 'REJECTED', 'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED')),

    CONSTRAINT ck_payments_type CHECK (payment_type IN
        ('P2P_TRANSFER', 'MERCHANT_PAYMENT', 'WALLET_TOPUP', 'WALLET_WITHDRAWAL', 'REFUND')),

    CONSTRAINT ck_payments_risk_level CHECK (risk_level IS NULL OR risk_level IN
        ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    CONSTRAINT ck_payments_fraud_decision CHECK (fraud_decision IS NULL OR fraud_decision IN
        ('APPROVE', 'REVIEW', 'HOLD', 'REJECT')),

    -- A probability outside [0,1] means the model or the mapping is broken.
    CONSTRAINT ck_payments_fraud_probability CHECK (
        fraud_probability IS NULL OR (fraud_probability >= 0 AND fraud_probability <= 1)),

    -- Cannot refund more than was paid.
    CONSTRAINT ck_payments_refunded_within_amount CHECK (
        refunded_minor >= 0 AND refunded_minor <= amount_minor),

    -- A terminal success must record when it completed; analytics and SLA
    -- reporting both depend on this column being present exactly when it should be.
    CONSTRAINT ck_payments_completed_at CHECK (
        status NOT IN ('COMPLETED', 'REFUNDED', 'PARTIALLY_REFUNDED') OR completed_at IS NOT NULL),

    CONSTRAINT ck_payments_currency_upper CHECK (currency = upper(currency))
);

COMMENT ON TABLE  payments IS 'Customer-facing payment records and their lifecycle state.';
COMMENT ON COLUMN payments.transaction_id IS 'Human-facing reference. Stable once shown to a customer.';
COMMENT ON COLUMN payments.hold_id IS 'Wallet reservation backing this payment, so compensation can release it.';
COMMENT ON COLUMN payments.device_fingerprint IS 'Captured once at initiation; never recomputed, so a decision stays reproducible.';
COMMENT ON CONSTRAINT ck_payments_refunded_within_amount ON payments IS
    'Makes over-refunding unpersistable.';

-- Payment history: one user, newest first. The single hottest query here.
CREATE INDEX idx_payments_initiator ON payments (initiator_user_id, created_at DESC);

-- Admin and analytics views filter by state over a time window.
CREATE INDEX idx_payments_status ON payments (status, created_at DESC);

-- Wallet-scoped history, covering both sides of a transfer.
CREATE INDEX idx_payments_source_wallet ON payments (source_wallet_id, created_at DESC);
CREATE INDEX idx_payments_destination_wallet ON payments (destination_wallet_id, created_at DESC);

-- Fraud review queue. Partial, because only elevated-risk payments are ever
-- listed this way, which keeps the index tiny next to total payment volume.
CREATE INDEX idx_payments_high_risk ON payments (created_at DESC)
    WHERE risk_level IN ('HIGH', 'CRITICAL');

-- Finds payments stuck mid-flight, for the stale-payment monitor.
CREATE INDEX idx_payments_in_flight ON payments (created_at)
    WHERE status IN ('CREATED', 'FUNDS_RESERVED', 'RISK_ASSESSED', 'AUTHORIZED', 'CAPTURED');

-- -----------------------------------------------------------------------------
-- idempotency_keys
--
-- Mapped by com.aegispay.common.idempotency.IdempotencyRecord, identical in every
-- service that needs it. The composite primary key is the guarantee that a
-- retried payment cannot execute twice; Redis only fronts it as a cache and is
-- allowed to fail open.
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(120)  NOT NULL,
    scope           VARCHAR(48)   NOT NULL,

    -- Rejects a key replayed with a DIFFERENT payload, rather than returning a
    -- response for a request that was never executed.
    request_hash    VARCHAR(64)   NOT NULL,

    status          VARCHAR(16)   NOT NULL DEFAULT 'IN_PROGRESS',

    response_body   TEXT,
    response_status INTEGER,
    resource_id     VARCHAR(48),

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

COMMENT ON TABLE idempotency_keys IS
    'Authoritative idempotency store. The composite PK is what prevents double execution.';

CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);
