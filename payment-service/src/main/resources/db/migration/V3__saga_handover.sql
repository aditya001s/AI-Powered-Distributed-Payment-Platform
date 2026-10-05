-- =============================================================================
-- V3: hand orchestration over to payment-orchestrator
--
-- Phase 3 ran the payment sequence inside payment-service: reserve, assess,
-- capture, complete, and compensate on failure. Phase 5 moves that sequence into
-- payment-orchestrator's saga, which routes across providers, retries, falls back
-- and compensates. payment-service now does two things: accept the request (with
-- idempotency) and keep the customer-facing record in step with the saga's
-- outcome events.
--
-- The one schema consequence is hold_id.
--
-- It existed so that a failure inside payment-service could find the wallet
-- reservation and release it. That compensation now lives in the saga, together
-- with the authoritative handle in payment_sagas.hold_id. Keeping a second copy
-- here would be a handle this service can no longer act on -- and a column that
-- looks actionable but is not is worse than no column at all, because the next
-- person to read it will write code against it.
--
-- Dropped rather than left nullable and unwritten for the same reason. There is
-- no data to preserve: any payment that still had a live reservation when this
-- migration runs is mid-flight, and its reservation is either captured by the
-- saga or released by wallet-service's own hold-expiry sweeper.
-- =============================================================================

ALTER TABLE payments
    DROP COLUMN IF EXISTS hold_id;

-- Refresh the table comment so it describes what this table is now: a record of
-- what the customer asked for and how it turned out, not a saga log.
COMMENT ON TABLE payments IS
    'Customer-facing payment records and their lifecycle state. Accepted here; orchestrated by payment-orchestrator, which advances these rows through its payment-approved / payment-completed / payment-failed / payment-rejected events.';
