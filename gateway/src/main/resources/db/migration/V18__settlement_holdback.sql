-- Interview sprint Day 9: close the gap Day 7 found — a payment auto-captured via the
-- confidently-LOW shortcut (never reviewed, never auto-cleared) could be swept into a payout
-- before its hidden fraud ground truth even had a chance to resolve. A captured payment now
-- becomes settlement-eligible only once its ground truth has actually resolved as legitimate;
-- one confirmed as fraud is written off instead, whatever path captured it.
ALTER TABLE payments
    ADD COLUMN settled_at     TIMESTAMPTZ,
    ADD COLUMN settlement_id  UUID REFERENCES settlements(id),
    ADD COLUMN written_off_at TIMESTAMPTZ;

CREATE INDEX idx_payments_settlement_pending ON payments(merchant_id, currency)
    WHERE status = 'CAPTURED' AND settled_at IS NULL AND written_off_at IS NULL;
