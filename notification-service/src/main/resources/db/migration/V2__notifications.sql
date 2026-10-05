-- =============================================================================
-- notification-service : notifications, per-channel delivery, retry and DLQ
--
-- ---- The shape of the problem ----
--
-- One event produces one notification, which fans out to several channels. Those
-- two things fail completely differently, so they are two tables:
--
--   notifications           WHAT the customer was told. Written once, never
--                           changed. Uniquely keyed on the producer's
--                           deduplication_key, which is what makes delivery
--                           effectively-once under Kafka's at-least-once.
--
--   notification_deliveries WHETHER each channel got it. Mutable by design: a
--                           delivery is retried, and its status moves.
--
-- Splitting them is not tidiness. If retry lived on the notification, a transient
-- SMS failure would re-send the email that already arrived. The customer would
-- get one SMS and four emails, and every one of those emails would be correct --
-- which is why nobody would notice it in a log.
--
-- ---- Why there is a retry queue here at all ----
--
-- Kafka already gives this service a retry-and-dead-letter path: the shared
-- DefaultErrorHandler retries a failed record and then publishes it to
-- notification-required.DLT. That is the right mechanism for a record that could
-- not be PROCESSED, and this service still relies on it.
--
-- It is the wrong mechanism for a delivery that could not be SENT:
--
--   * A partition is ordered. Backing off a record because an SMTP host is slow
--     stops every later notification on that partition, including notifications
--     to other customers about other payments.
--   * Redelivery replays the whole event, so channels that already succeeded are
--     attempted again -- see above.
--   * A Kafka DLT is a topic, not a work queue. Nothing retries it on a schedule
--     and nothing can query "which customers are currently un-notified".
--
-- So a send failure is committed as state (status FAILED, next_attempt_at set)
-- and a scheduled worker drains it. The Kafka offset advances, because the event
-- HAS been processed: the durable record of what must be sent now exists.
--
-- The distinction is load-bearing and easy to get wrong in the other direction:
-- if this service cannot WRITE the notification, it must throw, because then
-- nothing durable exists and Kafka's redelivery is the only thing that will ever
-- bring the event back.
--
-- Platform-wide conventions:
--   * TIMESTAMPTZ, never TIMESTAMP.
--   * Money is BIGINT minor units. This service stores no amounts of its own --
--     it renders them from the producer's variables -- so none appear below.
--   * Public identifiers are opaque and random.
--   * Flyway owns the schema; Hibernate runs with ddl-auto=validate.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- notifications
--
-- Immutable. A notification is a record of something a customer was told, and
-- once a message has left the building, editing the record of it only makes the
-- record wrong. A corrected message is a new notification.
-- -----------------------------------------------------------------------------
CREATE TABLE notifications (
    id                  BIGSERIAL     PRIMARY KEY,

    notification_id     VARCHAR(48)   NOT NULL,

    -- ---- THE idempotency guarantee ----
    -- Supplied by the producer, unique per logical notification, e.g.
    -- 'PAY-7F3C:sent:USR-91AB'. Kafka will deliver payment-completed twice and
    -- the customer must not be told twice, so this is a UNIQUE constraint rather
    -- than a check-then-insert: two concurrent consumer threads on two instances
    -- would both pass a check, and only the database can arbitrate.
    deduplication_key   VARCHAR(160)  NOT NULL,

    recipient_user_id   VARCHAR(40)   NOT NULL,

    -- Which copy to render, e.g. 'payment.sent'. Not the rendered text: keeping
    -- the key means copy and wording can change without redeploying the services
    -- that decide a notification is warranted.
    template_key        VARCHAR(64)   NOT NULL,

    priority            VARCHAR(16)   NOT NULL,

    -- Related payment, for deep-linking. Nullable: not every notification is
    -- about a payment.
    payment_id          VARCHAR(48),

    -- The interpolation values, as given. JSONB so a specific variable can be
    -- queried across notifications when a template turns out to render badly.
    --
    -- Stored REDACTED. Producers are told not to put secrets here, but a value
    -- whose name looks like a credential is dropped on the way in rather than
    -- trusted, because this table is read by support staff.
    variables           JSONB         NOT NULL DEFAULT '{}'::jsonb,

    -- Provenance: which event produced this. Makes a customer complaint
    -- traceable back through the log to the payment that caused it.
    source_event_id     VARCHAR(64),
    source              VARCHAR(64),

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_notification_id UNIQUE (notification_id),
    CONSTRAINT uq_notification_dedup UNIQUE (deduplication_key),
    CONSTRAINT ck_notification_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH'))
);

COMMENT ON TABLE notifications IS
    'What a customer was told. Append-only; UPDATE and DELETE are refused by trigger.';
COMMENT ON COLUMN notifications.deduplication_key IS
    'Producer-supplied logical identity. The UNIQUE constraint is what makes at-least-once event delivery produce at-most-one message.';
COMMENT ON COLUMN notifications.variables IS
    'Interpolation values, with credential-looking keys redacted before storage.';

CREATE INDEX idx_notifications_recipient ON notifications (recipient_user_id, created_at DESC);
CREATE INDEX idx_notifications_payment ON notifications (payment_id, created_at DESC)
    WHERE payment_id IS NOT NULL;
CREATE INDEX idx_notifications_template ON notifications (template_key, created_at DESC);
CREATE INDEX idx_notifications_created_at ON notifications (created_at DESC);

-- -----------------------------------------------------------------------------
-- notification_deliveries
--
-- One row per (notification, channel). Mutable: this is the work queue.
-- -----------------------------------------------------------------------------
CREATE TABLE notification_deliveries (
    id                  BIGSERIAL     PRIMARY KEY,

    delivery_id         VARCHAR(48)   NOT NULL,
    notification_id     VARCHAR(48)   NOT NULL,

    -- Denormalised from notifications so the retry worker and the inbox query can
    -- both be served without a join. Deliberate: the parent row is immutable, so
    -- these copies cannot drift out of date -- which is the usual objection to
    -- denormalising and does not apply here.
    recipient_user_id   VARCHAR(40)   NOT NULL,
    priority            VARCHAR(16)   NOT NULL,

    channel             VARCHAR(16)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,

    -- Where it was sent. Stored because "we sent it to that address" is the
    -- answer to a support question, and because a customer changing their email
    -- must not rewrite history. Never logged in full -- see the masking helper.
    destination         VARCHAR(254),

    -- What was actually sent, not what the template says today. If the copy is
    -- reworded next month, the record of what this customer read must not change
    -- with it.
    rendered_subject    VARCHAR(200),
    rendered_body       TEXT,

    -- ---- retry state ----
    attempt_count       INT           NOT NULL DEFAULT 0,
    -- NULL when there is nothing scheduled: SENT, SUPPRESSED or dead. The partial
    -- index below relies on that, so the retry query touches only due work.
    next_attempt_at     TIMESTAMPTZ,
    last_error          VARCHAR(512),

    -- Why nothing was attempted. Set only for SUPPRESSED, and required for it:
    -- a suppressed delivery with no reason is indistinguishable from a bug.
    suppressed_reason   VARCHAR(48),

    sent_at             TIMESTAMPTZ,

    -- The IN_APP delivery row IS the inbox entry -- it already holds the rendered
    -- body, the recipient and the timestamp, so a separate inbox table would be a
    -- second copy of all three. The CHECK states the restriction that makes this
    -- honest rather than a shortcut.
    read_at             TIMESTAMPTZ,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Optimistic lock. The retry worker claims rows with FOR UPDATE SKIP LOCKED,
    -- which already stops two instances taking the same row. This guards the other
    -- race: the worker holding a row while an operator replays it through the admin
    -- API. Without it the later write wins silently, and a replay could reset an
    -- attempt count that had just been incremented.
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_delivery_id UNIQUE (delivery_id),

    -- ---- per-channel idempotency ----
    -- One attempt-set per channel per notification. Without this, a retry that
    -- raced with a redelivered event could create a second EMAIL row for the same
    -- notification and send the message twice.
    CONSTRAINT uq_delivery_per_channel UNIQUE (notification_id, channel),

    CONSTRAINT fk_delivery_notification FOREIGN KEY (notification_id)
        REFERENCES notifications (notification_id) ON DELETE RESTRICT,

    CONSTRAINT ck_delivery_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),

    -- DEAD_LETTERED is this service's own terminal state and is deliberately not
    -- in the shared NotificationStatus enum: it is a delivery-level fact, and the
    -- shared enum describes a notification.
    CONSTRAINT ck_delivery_status CHECK (status IN
        ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED', 'DEAD_LETTERED')),

    CONSTRAINT ck_delivery_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),

    -- A suppressed delivery must say why.
    CONSTRAINT ck_delivery_suppressed_reason CHECK (
        (status = 'SUPPRESSED') = (suppressed_reason IS NOT NULL)),

    -- Only a sent delivery has a send time, and a sent one must have it. Catches
    -- a status set without its timestamp, which would make every latency and
    -- backlog figure quietly wrong.
    CONSTRAINT ck_delivery_sent_at CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),

    -- Nothing is scheduled for a terminal state. This is what keeps the retry
    -- worker's partial index equal to "the work queue".
    CONSTRAINT ck_delivery_next_attempt CHECK (
        next_attempt_at IS NULL OR status IN ('PENDING', 'FAILED')),

    -- Reading applies to the inbox and nowhere else. An email cannot be marked
    -- read, because this platform does not track whether one was opened.
    CONSTRAINT ck_delivery_read_only_in_app CHECK (read_at IS NULL OR channel = 'IN_APP'),

    CONSTRAINT ck_delivery_attempts_non_negative CHECK (attempt_count >= 0)
);

COMMENT ON TABLE notification_deliveries IS
    'One row per notification per channel. Mutable: this is the retry work queue.';
COMMENT ON COLUMN notification_deliveries.status IS
    'DEAD_LETTERED means every retry was exhausted: a customer was never told something the platform decided to tell them.';
COMMENT ON COLUMN notification_deliveries.rendered_body IS
    'What was actually sent. Retained verbatim so re-wording a template cannot change the record of what a customer read.';
COMMENT ON COLUMN notification_deliveries.read_at IS
    'Inbox read receipt. Only IN_APP, enforced by CHECK: nothing here tracks whether an email was opened.';

-- ---- the retry worker's query, and nothing else ----
-- Partial, so the index contains only rows that are actually due. A full index on
-- (status, next_attempt_at) would grow with every notification ever sent, which
-- for the one query that runs every few seconds forever is the wrong trade.
CREATE INDEX idx_deliveries_due ON notification_deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

-- The inbox: newest first, unread first. Partial on IN_APP because no other
-- channel is ever read through this path.
CREATE INDEX idx_deliveries_inbox ON notification_deliveries
    (recipient_user_id, created_at DESC) WHERE channel = 'IN_APP';

CREATE INDEX idx_deliveries_unread ON notification_deliveries (recipient_user_id)
    WHERE channel = 'IN_APP' AND read_at IS NULL;

CREATE INDEX idx_deliveries_notification ON notification_deliveries (notification_id);

-- Expected to be empty. Every row is a customer who was not reached.
CREATE INDEX idx_deliveries_dead ON notification_deliveries (updated_at DESC)
    WHERE status = 'DEAD_LETTERED';

CREATE INDEX idx_deliveries_channel_status ON notification_deliveries
    (channel, status, created_at DESC);

-- -----------------------------------------------------------------------------
-- notification_delivery_attempts
--
-- Append-only: one row per transport call.
--
-- Worth its own table rather than a counter on the delivery. A count says a
-- delivery failed four times; these rows say it failed four times with the same
-- connection timeout, which is a broken host, versus four different errors, which
-- is something else entirely. Replaying a dead delivery also overwrites
-- last_error, so without this the history of why it died would be lost at exactly
-- the moment somebody started investigating it.
-- -----------------------------------------------------------------------------
CREATE TABLE notification_delivery_attempts (
    id              BIGSERIAL     PRIMARY KEY,

    delivery_id     VARCHAR(48)   NOT NULL,
    notification_id VARCHAR(48)   NOT NULL,
    channel         VARCHAR(16)   NOT NULL,

    -- 1-based, matching what an operator reading a log expects.
    attempt_number  INT           NOT NULL,

    succeeded       BOOLEAN       NOT NULL,

    -- The transport's own reference for a successful send, so a message can be
    -- traced into the provider. Simulated here, and named to say so.
    provider_ref    VARCHAR(96),

    error_code      VARCHAR(64),
    error_message   VARCHAR(512),

    -- How long the transport call took. A provider that is slow before it is
    -- broken shows up here first.
    duration_ms     BIGINT        NOT NULL,

    attempted_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT fk_attempt_delivery FOREIGN KEY (delivery_id)
        REFERENCES notification_deliveries (delivery_id) ON DELETE RESTRICT,
    CONSTRAINT uq_attempt_per_delivery UNIQUE (delivery_id, attempt_number),
    CONSTRAINT ck_attempt_number_positive CHECK (attempt_number >= 1),
    CONSTRAINT ck_attempt_duration_non_negative CHECK (duration_ms >= 0),
    -- A failure must carry a reason, and a success must not pretend to have one.
    CONSTRAINT ck_attempt_error CHECK ((succeeded = FALSE) = (error_code IS NOT NULL))
);

COMMENT ON TABLE notification_delivery_attempts IS
    'Append-only record of every transport call. UPDATE and DELETE are refused by trigger.';

CREATE INDEX idx_attempts_delivery ON notification_delivery_attempts
    (delivery_id, attempt_number);
CREATE INDEX idx_attempts_failures ON notification_delivery_attempts
    (channel, attempted_at DESC) WHERE succeeded = FALSE;

-- -----------------------------------------------------------------------------
-- notification_preferences
--
-- ---- Why the phone number lives here and the email does not ----
--
-- An email address is an identity attribute: the user signed up with it, it is
-- verified at registration, and auth-service is its source of truth. This service
-- resolves it per delivery rather than copying it, so a changed address takes
-- effect immediately.
--
-- A phone number is not an identity attribute here -- nobody logs in with one.
-- It exists only so an SMS can be sent, and sending an SMS requires the user to
-- have asked for it. Storing it next to the consent that justifies it means the
-- number and the permission cannot get separated, and it keeps a phone number out
-- of the service every other service queries for identity.
-- -----------------------------------------------------------------------------
CREATE TABLE notification_preferences (
    user_id             VARCHAR(40)   PRIMARY KEY,

    -- Channel opt-outs. These govern LOW and NORMAL priority only; see the note
    -- on the CHECK below.
    email_enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
    sms_enabled         BOOLEAN       NOT NULL DEFAULT FALSE,
    in_app_enabled      BOOLEAN       NOT NULL DEFAULT TRUE,

    -- E.164, so it is unambiguous about country. Nullable: most users never add
    -- one, and SMS is suppressed rather than failed when it is absent.
    phone_e164          VARCHAR(20),

    -- When the user consented to SMS. Not derivable from sms_enabled, and the
    -- thing a regulator asks for. The CHECK makes the pair inseparable.
    sms_opt_in_at       TIMESTAMPTZ,

    -- Free-text locale tag. Kept even though only one language ships, because a
    -- template lookup that ignores it now is a much smaller change than adding
    -- the column to a populated table later.
    locale              VARCHAR(16)   NOT NULL DEFAULT 'en',

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- SMS cannot be on without a number to send to, and cannot be on without a
    -- record of the user asking. Enforced here because an SMS to somebody who did
    -- not opt in is a regulatory problem, not a bug to be found in review.
    CONSTRAINT ck_prefs_sms_requires_consent CHECK (
        sms_enabled = FALSE OR (phone_e164 IS NOT NULL AND sms_opt_in_at IS NOT NULL)),

    CONSTRAINT ck_prefs_phone_e164 CHECK (
        phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{7,18}$')
);

COMMENT ON TABLE notification_preferences IS
    'Per-user channel choices. Governs LOW and NORMAL priority only: a customer cannot switch off being told their payment failed.';
COMMENT ON COLUMN notification_preferences.sms_opt_in_at IS
    'Consent timestamp. Paired with phone_e164 by CHECK, because an SMS to somebody who did not ask for it is a regulatory problem.';

-- -----------------------------------------------------------------------------
-- Append-only enforcement
--
-- The same pattern ledger-service uses for its journal, fraud-service for its
-- analyst decisions and ai-investigation-service for its reports.
--
-- What is covered: what a customer was told, and every attempt to tell them.
-- What is deliberately NOT covered: notification_deliveries, whose whole purpose
-- is to move between states, and notification_preferences, which a user edits.
--
-- The reason to protect these two specifically is that they are the tables
-- somebody would be tempted to correct after the fact. "We did notify them" is a
-- claim made in a dispute, and it is worth nothing if the row backing it could
-- have been written afterwards.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION refuse_notification_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        '% is append-only: % refused. What a customer was told, and every attempt to tell '
        'them, are statements about a moment in time; record a new one instead of rewriting it.',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notifications_append_only
    BEFORE UPDATE OR DELETE ON notifications
    FOR EACH ROW EXECUTE FUNCTION refuse_notification_mutation();

CREATE TRIGGER trg_notification_attempts_append_only
    BEFORE UPDATE OR DELETE ON notification_delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION refuse_notification_mutation();
