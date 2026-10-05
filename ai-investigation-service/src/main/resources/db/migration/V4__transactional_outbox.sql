-- ai-investigation-service persists a completed investigation and its announcement
-- atomically. This table is mapped by com.aegispay.common.outbox.OutboxEvent and
-- must remain identical to the outbox schema in every event-producing service.
CREATE TABLE outbox_events (
    id              UUID          PRIMARY KEY,
    event_id        VARCHAR(64)   NOT NULL,
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(96)   NOT NULL,
    topic           VARCHAR(96)   NOT NULL,
    payload         TEXT          NOT NULL,
    correlation_id  VARCHAR(64),
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    last_error      VARCHAR(1024),
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_published_at CHECK (
        status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

COMMENT ON TABLE outbox_events IS
    'Transactional outbox. Guarantees atomicity between a state change and its event.';

CREATE INDEX idx_outbox_dispatch ON outbox_events (next_attempt_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_published_at ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';
CREATE INDEX idx_outbox_failed ON outbox_events (created_at DESC)
    WHERE status = 'FAILED';
