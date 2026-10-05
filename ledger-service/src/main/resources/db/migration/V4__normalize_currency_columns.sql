-- Hibernate maps Java String currency codes as VARCHAR(3). Normalize the original
-- fixed-width columns so schema validation and all services use one SQL type.
ALTER TABLE ledger_accounts
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);

ALTER TABLE journal_entries
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);

ALTER TABLE journal_lines
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);
