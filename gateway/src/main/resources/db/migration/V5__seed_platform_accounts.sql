-- Platform-owned ledger accounts (merchant-owned ones are created lazily on first capture).
INSERT INTO ledger_accounts (account_type, owner_type, owner_id, currency)
VALUES
    ('CUSTOMER_CLEARING', 'PLATFORM', NULL, 'INR'),
    ('PLATFORM_FEES',     'PLATFORM', NULL, 'INR'),
    ('CUSTOMER_CLEARING', 'PLATFORM', NULL, 'USD'),
    ('PLATFORM_FEES',     'PLATFORM', NULL, 'USD');
