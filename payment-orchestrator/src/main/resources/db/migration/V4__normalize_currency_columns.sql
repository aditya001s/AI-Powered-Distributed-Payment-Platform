-- Hibernate maps Java String currency codes as VARCHAR(3). Normalize the original
-- fixed-width columns so schema validation and all services use one SQL type.
ALTER TABLE payment_sagas
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);

ALTER TABLE provider_attempts
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);
