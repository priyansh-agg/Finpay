-- Settlement batches: on a schedule, each merchant's accrued MERCHANT_PAYABLE balance is
-- swept into a payout. This is a SIMULATION of the settlement cycle (no real bank rails) —
-- it produces the ledger movements and the payout records a real gateway would.

CREATE TABLE settlement_batches (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    merchant_count  INT         NOT NULL DEFAULT 0,
    total_minor     BIGINT      NOT NULL DEFAULT 0,
    currency        VARCHAR(3)  NOT NULL DEFAULT 'INR'
);

CREATE TABLE settlements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id        UUID        NOT NULL REFERENCES settlement_batches(id),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)  NOT NULL,
    ledger_tx_id    UUID        REFERENCES ledger_transactions(id),
    status          TEXT        NOT NULL DEFAULT 'PAID',   -- PAID (simulated instant payout)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_settlements_merchant ON settlements(merchant_id, created_at DESC);

-- The counterparty account for payouts (money leaving the platform to the merchant's bank).
INSERT INTO ledger_accounts (account_type, owner_type, owner_id, currency)
VALUES ('MERCHANT_SETTLED', 'PLATFORM', NULL, 'INR'),
       ('MERCHANT_SETTLED', 'PLATFORM', NULL, 'USD');
