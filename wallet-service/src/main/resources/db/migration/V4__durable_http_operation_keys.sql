-- Close the crash window between committing a deposit/withdrawal and completing
-- the shared idempotency response row. The movement itself carries the operation
-- identity, so a retry can recover the committed result without moving money twice.
ALTER TABLE wallet_transactions
    ADD COLUMN operation_key VARCHAR(160),
    ADD COLUMN operation_fingerprint VARCHAR(64),
    ADD CONSTRAINT ck_wallet_transactions_operation_pair CHECK (
        (operation_key IS NULL AND operation_fingerprint IS NULL)
        OR (operation_key IS NOT NULL AND operation_fingerprint IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_wallet_transactions_operation_key
    ON wallet_transactions (operation_key)
    WHERE operation_key IS NOT NULL;

COMMENT ON COLUMN wallet_transactions.operation_key IS
    'Natural key for an external deposit/withdrawal request; survives idempotency response-record failure';
COMMENT ON COLUMN wallet_transactions.operation_fingerprint IS
    'SHA-256 of business-significant request fields; rejects key reuse with a different request';
