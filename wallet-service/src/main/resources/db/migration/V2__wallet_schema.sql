-- =============================================================================
-- wallet-service : wallets, holds and movement history
--
-- Balances are the most safety-critical state in the platform, so the invariants
-- are enforced by the DATABASE as well as by Java. A CHECK constraint holds
-- regardless of which code path, migration or manual fix touches the row; an
-- application-level check only holds for the paths that remember to call it.
--
-- Money is BIGINT minor units (paise) throughout. Never NUMERIC, never a
-- floating-point type: doubles cannot represent 0.01 exactly, so accumulating
-- them loses money, and NUMERIC would invite accidental fractional arithmetic.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- wallets
--
-- Two balance columns, not one:
--
--   balance_minor  total funds the customer owns
--   held_minor     the portion currently reserved by in-flight payments
--
-- available = balance_minor - held_minor
--
-- Reservations are modelled as a separate column rather than by pre-emptively
-- debiting the balance, because a customer looking at their statement must still
-- see the money as theirs until the payment actually captures. Debiting early and
-- crediting back on failure would show a phantom debit-and-refund pair for every
-- abandoned payment.
-- -----------------------------------------------------------------------------
CREATE TABLE wallets (
    id                 BIGSERIAL     PRIMARY KEY,

    -- Public identifier. Opaque and random so wallet IDs cannot be enumerated.
    wallet_id          VARCHAR(40)   NOT NULL,

    -- Owner. A plain value, not a foreign key: users live in the auth service's
    -- database and cross-database foreign keys do not exist. Referential
    -- integrity across services is maintained by the events, not by the schema.
    user_id            VARCHAR(40)   NOT NULL,

    currency           CHAR(3)       NOT NULL,

    balance_minor      BIGINT        NOT NULL DEFAULT 0,
    held_minor         BIGINT        NOT NULL DEFAULT 0,

    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',

    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_wallets_wallet_id UNIQUE (wallet_id),

    -- One wallet per user per currency. This is what makes "the user's INR
    -- wallet" a well-defined phrase, and it prevents a double-spend achieved by
    -- opening a second wallet and racing both.
    CONSTRAINT uq_wallets_user_currency UNIQUE (user_id, currency),

    CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),

    -- ---- The three invariants that keep balances honest ----
    -- A customer wallet can never go negative. Overdraft is not a product here,
    -- so a negative balance is always a bug, and it is refused at the storage
    -- layer rather than discovered later during reconciliation.
    CONSTRAINT ck_wallets_balance_non_negative CHECK (balance_minor >= 0),
    CONSTRAINT ck_wallets_held_non_negative    CHECK (held_minor >= 0),
    -- Cannot reserve more than exists. This is the constraint that makes a
    -- double-spend physically impossible to persist: two concurrent payments that
    -- both passed an application-level check would fail here on commit.
    CONSTRAINT ck_wallets_held_within_balance  CHECK (held_minor <= balance_minor),

    CONSTRAINT ck_wallets_currency_upper CHECK (currency = upper(currency))
);

COMMENT ON TABLE  wallets IS 'Customer balances in integer minor units. A LIABILITY of the platform.';
COMMENT ON COLUMN wallets.balance_minor IS 'Total owned funds in minor units (paise). Never negative.';
COMMENT ON COLUMN wallets.held_minor IS 'Portion reserved by in-flight payments. available = balance_minor - held_minor.';
COMMENT ON CONSTRAINT ck_wallets_held_within_balance ON wallets IS
    'Makes over-reservation unpersistable, so a double-spend cannot survive commit.';

CREATE INDEX idx_wallets_user ON wallets (user_id);
CREATE INDEX idx_wallets_status ON wallets (status) WHERE status <> 'ACTIVE';

-- -----------------------------------------------------------------------------
-- wallet_holds
--
-- One row per fund reservation. A separate table rather than a single "reserved
-- amount" number, because the platform must know WHICH payment reserved WHAT:
-- capturing payment A must release exactly A's amount, not whatever happens to be
-- reserved at that moment. With a bare counter, two concurrent payments releasing
-- in the wrong order silently corrupt the held total.
--
-- expires_at exists so a crashed orchestrator cannot strand a customer's money
-- indefinitely. A sweeper releases holds past their expiry.
-- -----------------------------------------------------------------------------
CREATE TABLE wallet_holds (
    id            UUID          PRIMARY KEY,

    hold_id       VARCHAR(48)   NOT NULL,
    wallet_id     VARCHAR(40)   NOT NULL,

    -- The payment this reservation belongs to. UNIQUE below: one hold per
    -- payment, which makes a retried reserve call idempotent by construction.
    payment_id    VARCHAR(48)   NOT NULL,

    amount_minor  BIGINT        NOT NULL,
    currency      CHAR(3)       NOT NULL,

    state         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',

    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ   NOT NULL,
    captured_at   TIMESTAMPTZ,
    released_at   TIMESTAMPTZ,
    version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_wallet_holds_hold_id UNIQUE (hold_id),

    -- Idempotency by construction: a duplicate reserve for the same payment
    -- cannot create a second hold, so a retry cannot reserve the amount twice.
    CONSTRAINT uq_wallet_holds_payment UNIQUE (payment_id),

    CONSTRAINT fk_wallet_holds_wallet FOREIGN KEY (wallet_id)
        REFERENCES wallets (wallet_id) ON DELETE RESTRICT,

    CONSTRAINT ck_wallet_holds_state CHECK (state IN ('ACTIVE', 'CAPTURED', 'RELEASED')),
    CONSTRAINT ck_wallet_holds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_wallet_holds_expiry CHECK (expires_at > created_at),
    -- A terminal state must record when it happened; the sweeper and any dispute
    -- investigation both read these timestamps.
    CONSTRAINT ck_wallet_holds_captured_at CHECK (state <> 'CAPTURED' OR captured_at IS NOT NULL),
    CONSTRAINT ck_wallet_holds_released_at CHECK (state <> 'RELEASED' OR released_at IS NOT NULL)
);

COMMENT ON TABLE wallet_holds IS 'Fund reservations. One per payment, enforced by uq_wallet_holds_payment.';
COMMENT ON COLUMN wallet_holds.expires_at IS 'Sweeper releases past this, so a crashed saga cannot strand funds.';

CREATE INDEX idx_wallet_holds_wallet ON wallet_holds (wallet_id, state);
-- Partial index for the expiry sweeper: only ACTIVE holds can expire, so the
-- index stays small regardless of historical volume.
CREATE INDEX idx_wallet_holds_expiry ON wallet_holds (expires_at) WHERE state = 'ACTIVE';

-- -----------------------------------------------------------------------------
-- wallet_transactions
--
-- Append-only movement history per wallet: what the customer sees as their
-- statement, and what makes a balance auditable.
--
-- This is NOT the double-entry ledger (that is ledger-service, Phase 4). This is
-- a single-sided per-wallet view. The distinction matters: this table answers
-- "what happened to MY wallet", the ledger answers "does the platform's whole
-- book balance". Conflating the two is how systems end up unable to prove either.
--
-- balance_after_minor is denormalised deliberately. Recomputing a historical
-- balance by summing every prior movement is O(n) and, worse, silently wrong if
-- any row is ever inserted out of order. Storing the resulting balance makes each
-- row independently verifiable.
-- -----------------------------------------------------------------------------
CREATE TABLE wallet_transactions (
    id                  BIGSERIAL     PRIMARY KEY,

    transaction_id      VARCHAR(48)   NOT NULL,
    wallet_id           VARCHAR(40)   NOT NULL,

    direction           VARCHAR(8)    NOT NULL,
    amount_minor        BIGINT        NOT NULL,
    currency            CHAR(3)       NOT NULL,

    -- The wallet's balance immediately after this movement was applied.
    balance_after_minor BIGINT        NOT NULL,

    type                VARCHAR(24)   NOT NULL,

    -- Correlates back to the payment, hold or deposit that caused this.
    reference_id        VARCHAR(48),
    description         VARCHAR(256),

    correlation_id      VARCHAR(64),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_wallet_transactions_tx_id UNIQUE (transaction_id),

    CONSTRAINT fk_wallet_transactions_wallet FOREIGN KEY (wallet_id)
        REFERENCES wallets (wallet_id) ON DELETE RESTRICT,

    CONSTRAINT ck_wallet_transactions_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    -- Always positive; direction carries the sign. Allowing negative amounts
    -- would create two ways to express one movement and make any SUM() ambiguous.
    CONSTRAINT ck_wallet_transactions_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_wallet_transactions_balance_non_negative CHECK (balance_after_minor >= 0),
    CONSTRAINT ck_wallet_transactions_type CHECK (type IN
        ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT', 'REFUND', 'REVERSAL', 'FEE'))
);

COMMENT ON TABLE wallet_transactions IS
    'Append-only per-wallet statement. NOT the double-entry ledger (see ledger-service).';

-- Statement query: one wallet, newest first.
CREATE INDEX idx_wallet_transactions_wallet ON wallet_transactions (wallet_id, created_at DESC);
CREATE INDEX idx_wallet_transactions_reference ON wallet_transactions (reference_id);

-- -----------------------------------------------------------------------------
-- idempotency_keys
--
-- The AUTHORITATIVE idempotency record. Redis is a fast path in front of this
-- (see IdempotencyGuard) and may be empty, flushed or unavailable without
-- affecting correctness: the unique primary key below is what actually prevents a
-- retried deposit from crediting twice.
--
-- request_hash is stored so a key replayed with a DIFFERENT body can be rejected
-- as a client bug, rather than silently returning the first response for a request
-- that was never executed.
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(120)  NOT NULL,

    -- Scopes the key to an operation, so the same client-generated key used for a
    -- deposit and a withdrawal cannot collide.
    scope           VARCHAR(48)   NOT NULL,

    request_hash    VARCHAR(64)   NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'IN_PROGRESS',

    -- The response to replay, so a retry returns the original outcome verbatim.
    response_body   TEXT,
    response_status INTEGER,

    -- Resource the operation produced, for correlation.
    resource_id     VARCHAR(48),

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

COMMENT ON TABLE idempotency_keys IS
    'Authoritative idempotency store. Redis fronts it as a cache and may fail open.';
COMMENT ON COLUMN idempotency_keys.request_hash IS
    'Rejects a key replayed with a different payload instead of returning the wrong response.';

CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);
