-- Hibernate maps Java String currency codes as VARCHAR(3). Normalize the original
-- fixed-width column to the platform-wide SQL type.
ALTER TABLE payments
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);
