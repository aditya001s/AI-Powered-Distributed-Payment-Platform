-- =============================================================================
-- analytics-service : baseline schema
--
-- Admin analytics and platform read models.
--
-- Phase 9 adds the projections and admin analytics endpoints.
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
VALUES ('analytics-service', 'Admin analytics and platform read models.');
