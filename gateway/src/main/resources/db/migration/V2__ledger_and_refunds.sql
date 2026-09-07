-- Double-entry ledger. Entries are APPEND-ONLY: no UPDATE, no DELETE, ever.
-- Corrections are new offsetting entries. A reconciliation job asserts
-- SUM(debit) = SUM(credit) per ledger_transaction.

CREATE TABLE ledger_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type    TEXT        NOT NULL,   -- CUSTOMER_CLEARING | MERCHANT_PAYABLE | PLATFORM_FEES | REFUNDS_PAYABLE
    owner_type      TEXT        NOT NULL,   -- MERCHANT | PLATFORM
    owner_id        UUID,                   -- merchant_id, or NULL for platform accounts
    currency        VARCHAR(3)     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_type, owner_id, currency)
);

-- Groups a balanced set of entries (the "journal entry").
CREATE TABLE ledger_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tx_type         TEXT        NOT NULL,   -- CAPTURE | REFUND | REVERSAL | ADJUSTMENT
    reference_type  TEXT        NOT NULL,   -- PAYMENT | REFUND
    reference_id    UUID        NOT NULL,
    memo            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_tx_reference ON ledger_transactions(reference_type, reference_id);

CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_tx_id    UUID        NOT NULL REFERENCES ledger_transactions(id),
    account_id      UUID        NOT NULL REFERENCES ledger_accounts(id),
    direction       TEXT        NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_entries_tx ON ledger_entries(ledger_tx_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries(account_id);

-- Guard rail: ledger_entries is append-only. Block UPDATE/DELETE at the DB level.
CREATE OR REPLACE FUNCTION finpay_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION finpay_block_mutation();

CREATE TABLE refunds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL REFERENCES payments(id),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)     NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING | SUCCEEDED | FAILED
    reason          TEXT,
    idem_key        TEXT        UNIQUE,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refunds_payment ON refunds(payment_id);
