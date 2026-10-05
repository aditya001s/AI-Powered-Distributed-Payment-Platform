-- =============================================================================
-- wallet-service : baseline schema
--
-- Wallet balances and fund reservations.
--
-- Phase 3 adds wallets, balances, deposits, withdrawals and holds.
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

-- Recorded so that a fresh database is provably at a known baseline, and so
-- `flyway info` on any environment shows which services have been migrated.
CREATE TABLE schema_baseline (
    service     VARCHAR(64)  PRIMARY KEY,
    applied_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    description VARCHAR(256) NOT NULL
);

INSERT INTO schema_baseline (service, description)
VALUES ('wallet-service', 'Wallet balances and fund reservations.');

-- -----------------------------------------------------------------------------
-- outbox_events
--
-- The transactional outbox. Domain events are inserted here in the SAME
-- transaction as the state change that produced them, which is what makes
-- "state changed" and "event published" atomic without a distributed
-- transaction across Postgres and Kafka.
--
-- A background drainer claims rows with SELECT ... FOR UPDATE SKIP LOCKED and
-- publishes them. Because the row is only marked PUBLISHED after the broker
-- acknowledges the record, a crash in between republishes rather than loses the
-- event: at-least-once, which consumer-side de-duplication on event_id turns
-- into effectively-once.
--
-- This table is mapped by com.aegispay.common.outbox.OutboxEvent and is
-- therefore identical in every service that emits events.
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              UUID          PRIMARY KEY,

    -- The envelope's event id. UNIQUE, so a retry cannot enqueue a second copy
    -- of the same logical event.
    event_id        VARCHAR(64)   NOT NULL,

    aggregate_type  VARCHAR(64)   NOT NULL,
    -- Also the Kafka partition key, which is what preserves per-aggregate
    -- ordering (payment-approved always before payment-completed).
    aggregate_id    VARCHAR(64)   NOT NULL,

    event_type      VARCHAR(96)   NOT NULL,
    topic           VARCHAR(96)   NOT NULL,

    -- The fully serialised EventEnvelope, republished byte-for-byte. Never
    -- re-serialised, so a later schema change cannot alter the meaning of an
    -- event recorded by an older build.
    payload         TEXT          NOT NULL,

    correlation_id  VARCHAR(64),

    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    last_error      VARCHAR(1024),

    -- Defence in depth against a double publish if SKIP LOCKED is ever bypassed.
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    -- A PUBLISHED row must record when it was published; the retention sweep
    -- reads that column.
    CONSTRAINT ck_outbox_published_at CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

COMMENT ON TABLE outbox_events IS
    'Transactional outbox. Guarantees atomicity between a state change and its event.';

-- The drainer's hot path. Partial index: only PENDING rows are ever claimed, so
-- the index stays small no matter how much history the table holds.
CREATE INDEX idx_outbox_dispatch ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

-- Supports the retention sweep for published rows.
CREATE INDEX idx_outbox_published_at ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- Operators query this to find events needing manual replay. Expected to be empty.
CREATE INDEX idx_outbox_failed ON outbox_events (created_at DESC)
    WHERE status = 'FAILED';
