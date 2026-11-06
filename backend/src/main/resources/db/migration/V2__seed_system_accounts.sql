-- ==============================================================================
-- LedgerLine — V2 Seed System Clearing Account
-- ==============================================================================
-- The SYSTEM_CLEARING account serves as the asset clearing pool for all external
-- cash inflows (deposits) and outflows (withdrawals).
-- Invariant: Sum(Customer Balances) == Balance(SYSTEM_CLEARING)
-- ==============================================================================

INSERT INTO accounts (
    account_number,
    name,
    account_type,
    currency,
    balance,
    statement_sequence_counter,
    status
) VALUES (
    'ACC-SYSTEM-CLEARING-001',
    'System Clearing Account',
    'SYSTEM_CLEARING',
    'INR',
    0.00,
    0,
    'ACTIVE'
) ON DUPLICATE KEY UPDATE name = name;
