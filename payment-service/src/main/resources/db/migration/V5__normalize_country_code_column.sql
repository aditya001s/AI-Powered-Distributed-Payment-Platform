-- Hibernate maps String fields as VARCHAR. Normalize the legacy fixed-width
-- country code so schema validation and existing installations agree.
ALTER TABLE payments
    ALTER COLUMN country_code TYPE VARCHAR(2)
    USING btrim(country_code)::VARCHAR(2);
