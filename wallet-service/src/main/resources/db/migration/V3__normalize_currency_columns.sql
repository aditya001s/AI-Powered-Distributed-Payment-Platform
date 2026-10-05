-- Hibernate maps Java String currency codes as VARCHAR(3). Normalize the original
-- fixed-width columns so schema validation and all services use one SQL type.
ALTER TABLE wallets
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);

ALTER TABLE wallet_holds
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);

ALTER TABLE wallet_transactions
    ALTER COLUMN currency TYPE VARCHAR(3) USING btrim(currency)::VARCHAR(3);
