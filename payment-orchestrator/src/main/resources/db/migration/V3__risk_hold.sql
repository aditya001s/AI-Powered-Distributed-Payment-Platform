-- =============================================================================
-- V3 : the held-for-review state
--
-- Phase 6 replaces the Phase 5 auto-approval with a real fraud verdict, and one
-- of its outcomes has no equivalent in the existing state machine: HOLD stops
-- the payment and waits for a human.
--
-- That needs a persisted state rather than a flag, because the difference
-- between "parked awaiting an analyst" and "abandoned by a crashed process" is
-- exactly what the recovery sweep keys off. Without RISK_HELD, a held payment
-- would look stalled and the sweep would "recover" it once a minute forever.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- The new state
--
-- Postgres cannot add a value to a CHECK constraint in place, so it is dropped
-- and recreated. Safe here because it is a constraint definition change with no
-- table rewrite of the data itself.
-- -----------------------------------------------------------------------------
ALTER TABLE payment_sagas DROP CONSTRAINT ck_payment_sagas_status;

ALTER TABLE payment_sagas ADD CONSTRAINT ck_payment_sagas_status CHECK (status IN
    ('STARTED', 'FUNDS_RESERVED', 'RISK_ASSESSED', 'RISK_HELD', 'AUTHORIZED',
     'LEDGER_POSTED', 'CAPTURED', 'COMPLETED', 'COMPENSATING', 'FAILED', 'REJECTED'));

-- -----------------------------------------------------------------------------
-- Provenance of the verdict the saga acted on
-- -----------------------------------------------------------------------------

-- Links the payment to the fraud assessment that decided it. Without this, a
-- disputed payment can be traced from fraud-service to the saga but not back:
-- the two live in separate databases, so the reference has to be stored.
ALTER TABLE payment_sagas ADD COLUMN risk_assessment_id VARCHAR(48);

-- TRUE when the verdict was reached without the ML model, on the deterministic
-- rules alone. Stored on the saga and not just on the assessment because it
-- describes a decision *this* service made: it chose to continue a payment on a
-- verdict of reduced assurance, and that is the fact an audit asks about.
ALTER TABLE payment_sagas ADD COLUMN risk_degraded BOOLEAN NOT NULL DEFAULT FALSE;

-- -----------------------------------------------------------------------------
-- The review itself
-- -----------------------------------------------------------------------------

-- When the payment was parked. This, minus now(), is how long a customer has
-- been waiting with their funds reserved — the number an operator dashboard
-- should lead with, and the reason it is a column rather than an inference from
-- updated_at (which any later write would move).
ALTER TABLE payment_sagas ADD COLUMN risk_held_at TIMESTAMPTZ;

ALTER TABLE payment_sagas ADD COLUMN risk_reviewed_at TIMESTAMPTZ;

-- The analyst, copied from the decision event, which took it from their token.
-- Duplicated here from fraud-service's append-only record on purpose: "who let
-- this payment through" must be answerable from the payment's own history
-- without a cross-service join, because that is the question asked when the
-- payment turns out to have been fraud.
ALTER TABLE payment_sagas ADD COLUMN risk_reviewed_by VARCHAR(40);

ALTER TABLE payment_sagas ADD COLUMN risk_review_reason VARCHAR(512);

-- A held payment must record when it was held. Otherwise "how long has this
-- customer been waiting" has no answer, and a held saga with no timestamp is
-- indistinguishable from one whose park was half-written.
ALTER TABLE payment_sagas ADD CONSTRAINT ck_payment_sagas_held_at CHECK (
    status <> 'RISK_HELD' OR risk_held_at IS NOT NULL);

-- A review outcome is only meaningful alongside who produced it.
ALTER TABLE payment_sagas ADD CONSTRAINT ck_payment_sagas_review_attributed CHECK (
    risk_reviewed_at IS NULL OR risk_reviewed_by IS NOT NULL);

COMMENT ON COLUMN payment_sagas.risk_held_at IS
    'When fraud controls parked this payment. now() minus this is how long the customer has waited.';
COMMENT ON COLUMN payment_sagas.risk_degraded IS
    'TRUE when the saga continued on a verdict reached without the ML model.';
COMMENT ON COLUMN payment_sagas.risk_reviewed_by IS
    'The analyst who released or refused the payment, from their token. Never from a request field.';

-- -----------------------------------------------------------------------------
-- Keep the recovery sweep away from parked sagas
--
-- The partial index backing the sweep must exclude RISK_HELD for the same reason
-- the query does. Leaving it in would not just waste the index — it would make
-- the sweep's plan look correct while it kept picking up payments that are
-- behaving exactly as designed, incrementing attempt_count on each pass until a
-- perfectly healthy held payment looked like a chronic failure.
-- -----------------------------------------------------------------------------
DROP INDEX idx_payment_sagas_in_flight;

CREATE INDEX idx_payment_sagas_in_flight ON payment_sagas (updated_at)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'REJECTED', 'RISK_HELD');

-- The analyst-facing queue from the orchestrator's side: payments parked for
-- review, longest-waiting first. Every row is a customer whose funds are
-- reserved, so this is small by design and alerted on when it is not.
CREATE INDEX idx_payment_sagas_held ON payment_sagas (risk_held_at)
    WHERE status = 'RISK_HELD';

-- -----------------------------------------------------------------------------
-- The risk context the customer actually transacted in
--
-- Device, IP, coordinates and channel arrive on payment-created and are the raw
-- inputs the fraud model turns into features. Until now the saga did not keep
-- them, because nothing needed them: the risk step was auto-approved.
--
-- They have to be stored rather than carried in memory. A saga resumed by the
-- recovery sweep no longer has the event, and re-deriving device or location data
-- later would produce a different context than the one the customer transacted
-- with — so a resumed payment would be scored on a fabricated situation, silently
-- and with every device and location feature wrong.
--
-- Stored as JSON rather than as columns because this service does not interpret
-- it: it forwards the object to fraud-service verbatim. Columns would commit the
-- orchestrator to a shape it has no opinion about, and every future signal would
-- need a migration here as well as there.
--
-- Personal data. Retained for the fraud-investigation window only, never rendered
-- to another customer, and IP addresses are truncated before reaching analytics.
-- -----------------------------------------------------------------------------
ALTER TABLE payment_sagas ADD COLUMN risk_signals JSONB;

COMMENT ON COLUMN payment_sagas.risk_signals IS
    'Device/IP/geo context from payment-created, forwarded to fraud-service verbatim. Personal data.';
