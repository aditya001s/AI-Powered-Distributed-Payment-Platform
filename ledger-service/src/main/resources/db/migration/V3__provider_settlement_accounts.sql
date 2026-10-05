-- =============================================================================
-- ledger-service : settlement accounts for the payment providers
--
-- Phase 5 introduces provider routing, and with it the first non-zero fees in the
-- platform. Phase 4 deliberately REFUSED to book a payment carrying a fee, because
-- deciding who bears it is a product decision and guessing would have silently
-- desynchronised the ledger from wallet balances. This migration is half of that
-- decision; PostingBuilder is the other half.
--
-- THE DECISION: the PLATFORM bears the provider fee.
--
--   * The payer is debited exactly the amount they agreed to pay.
--   * The payee is credited exactly that same amount.
--   * The fee is a platform EXPENSE, settled against the balance we hold at the
--     provider that charged it.
--
-- Why that way round. The alternative — deducting the fee from what the payee
-- receives, or adding it to what the payer pays — changes the amount of money that
-- moves between two customer wallets. wallet-service moves exactly `amount` during
-- capture, so either of those choices would make the ledger claim a movement the
-- wallet never made, and the customer-liability reconciliation would then fail on
-- every single payment. Booking the fee entirely outside customer accounts keeps
-- the two systems reconcilable, which is worth more than shifting a cost.
--
-- The resulting posting for a completed payment with a fee has four legs:
--
--   DEBIT   wallet:PAYER              amount   we owe the payer less
--   CREDIT  wallet:PAYEE              amount   we owe the payee more
--   DEBIT   expense:provider-fees     fee      a cost we incurred
--   CREDIT  asset:settlement:PROVIDER fee      taken from our balance at them
--                                    -------
--   debits == credits == amount + fee
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Settlement accounts: what the platform holds at each provider.
--
-- ASSET accounts, so a debit increases them (money we placed with the provider)
-- and a credit decreases them (the provider took its fee, or settled out to us).
--
-- allows_negative is TRUE, and that is not laziness. A settlement account is a
-- running bilateral balance: until the platform pre-funds it, every fee the
-- provider deducts drives it further negative, which correctly reads as "we owe
-- this provider money". Forcing it non-negative would refuse the first fee ever
-- charged.
--
-- Provider identifiers are deliberately invented names. These are SIMULATED
-- providers: nothing in this platform connects to a real bank, card network or UPI
-- rail, and the account names should never suggest otherwise.
-- -----------------------------------------------------------------------------
-- Naming follows com.aegispay.common.events.payload.LedgerPostingPayload
-- .providerSettlementAccount(providerId, currency), which is the shared contract.
INSERT INTO ledger_accounts
    (account_id, account_type, currency, allows_negative, system_account, description)
VALUES
    ('asset:settlement:SWIFTPAY:INR',  'ASSET', 'INR', TRUE, TRUE,
     'Simulated provider SWIFTPAY: platform settlement balance'),
    ('asset:settlement:SWIFTPAY:USD',  'ASSET', 'USD', TRUE, TRUE,
     'Simulated provider SWIFTPAY: platform settlement balance'),

    ('asset:settlement:NEXUSPAY:INR',  'ASSET', 'INR', TRUE, TRUE,
     'Simulated provider NEXUSPAY: platform settlement balance'),
    ('asset:settlement:NEXUSPAY:USD',  'ASSET', 'USD', TRUE, TRUE,
     'Simulated provider NEXUSPAY: platform settlement balance'),

    ('asset:settlement:PAYBRIDGE:INR', 'ASSET', 'INR', TRUE, TRUE,
     'Simulated provider PAYBRIDGE: platform settlement balance'),
    ('asset:settlement:PAYBRIDGE:USD', 'ASSET', 'USD', TRUE, TRUE,
     'Simulated provider PAYBRIDGE: platform settlement balance');

COMMENT ON CONSTRAINT ck_ledger_accounts_negative_allowed ON ledger_accounts IS
    'Customer wallets may never go negative. Clearing and settlement accounts may, '
    'because both are running bilateral balances that legitimately swing either way.';
