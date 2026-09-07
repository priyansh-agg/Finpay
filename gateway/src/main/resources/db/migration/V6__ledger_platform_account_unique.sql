-- The UNIQUE(account_type, owner_id, currency) on ledger_accounts does not constrain
-- platform accounts, because owner_id IS NULL and NULL <> NULL in a unique constraint.
-- A partial unique index fixes that: at most one platform account per (type, currency).
CREATE UNIQUE INDEX ux_ledger_platform_account
    ON ledger_accounts (account_type, currency)
    WHERE owner_id IS NULL;
