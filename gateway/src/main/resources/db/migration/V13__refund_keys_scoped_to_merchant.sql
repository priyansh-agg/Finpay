-- Refund idempotency keys are scoped to the merchant, like payment keys (V10).
--
-- V2 declared refunds.idem_key UNIQUE across the whole platform, and RefundService looked
-- the key up without the merchant. So merchant B sending a refund key that merchant A had
-- already used got A's refund back — a 201 "SUCCEEDED" carrying another merchant's payment
-- id and amount — and B's own refund never ran. A cross-tenant data leak and a refund that
-- silently didn't happen, from a key string the caller picks.
--
-- NULL keys stay unconstrained (a refund without a key is allowed, and NULLs are distinct).
ALTER TABLE refunds DROP CONSTRAINT IF EXISTS refunds_idem_key_key;

ALTER TABLE refunds
    ADD CONSTRAINT ux_refunds_merchant_idem_key UNIQUE (merchant_id, idem_key);
