-- A refund crosses wallet-service before payment state is updated. The deterministic
-- refund ID is therefore persisted as a natural operation key: if the wallet call
-- succeeded but this transaction or the generic idempotency response failed, retrying
-- cannot move money or apply the cumulative refund twice.
CREATE TABLE payment_refunds (
    refund_id      VARCHAR(32)  PRIMARY KEY,
    payment_id     VARCHAR(48)  NOT NULL,
    amount_minor   BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_payment_refunds_payment FOREIGN KEY (payment_id)
        REFERENCES payments (payment_id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_refunds_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payment_refunds_currency_upper CHECK (currency = upper(currency))
);

CREATE INDEX idx_payment_refunds_payment
    ON payment_refunds (payment_id, created_at ASC);

COMMENT ON TABLE payment_refunds IS
    'Durable natural identities of applied refunds; prevents replay after response-record failure';
