-- A retrieved passage and a passage explicitly cited by the report are different
-- provenance facts. Historical grounded rows cannot be reconstructed reliably, so
-- they default to "retrieved only"; historical hallucinations were necessarily
-- claimed by the generator and are marked cited.
ALTER TABLE investigation_citations
    ADD COLUMN cited_in_report BOOLEAN NOT NULL DEFAULT FALSE;

-- V3 makes citation rows append-only. Temporarily remove only that table's trigger
-- for this controlled schema migration; Flyway runs PostgreSQL migrations in one
-- transaction, so any backfill failure also restores the trigger on rollback.
DROP TRIGGER trg_investigation_citations_append_only ON investigation_citations;

UPDATE investigation_citations
SET cited_in_report = TRUE
WHERE hallucinated = TRUE;

CREATE TRIGGER trg_investigation_citations_append_only
    BEFORE UPDATE OR DELETE ON investigation_citations
    FOR EACH ROW EXECUTE FUNCTION refuse_investigation_mutation();

ALTER TABLE investigation_citations
    ALTER COLUMN cited_in_report DROP DEFAULT;

COMMENT ON COLUMN investigation_citations.cited_in_report IS
    'TRUE only when the generated report explicitly named this document; FALSE means retrieved context only';
