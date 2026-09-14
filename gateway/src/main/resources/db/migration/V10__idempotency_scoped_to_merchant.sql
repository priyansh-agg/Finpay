-- Idempotency keys are scoped to the merchant that issued them.
--
-- V1 declared UNIQUE (idem_key) -- globally unique across the whole platform. That is wrong
-- for a multi-tenant gateway: two merchants that both send `Idempotency-Key: order-42`
-- collide, and the second one either
--   * reads the FIRST merchant's cached response body back (a cross-tenant data leak), or
--   * is rejected with 422 "reused with a different request body" for a key they have
--     never used.
-- Both are wrong, and the merchant has no way to avoid it -- key strings are chosen by the
-- caller, and "order-42" is exactly what a caller picks.
--
-- Real gateways scope the key per account. The composite index below does that. It remains
-- the INSERT ... ON CONFLICT DO NOTHING target, so the concurrency primitive is unchanged:
-- concurrent callers still serialise on a unique index and exactly one wins. Only the scope
-- of "the same key" changes.
--
-- Safe to apply to existing data: idem_key was globally unique, so (merchant_id, idem_key)
-- cannot already contain duplicates.

ALTER TABLE idempotency_keys DROP CONSTRAINT IF EXISTS idempotency_keys_idem_key_key;
DROP INDEX IF EXISTS idempotency_keys_idem_key_key;

ALTER TABLE idempotency_keys
    ADD CONSTRAINT ux_idem_merchant_key UNIQUE (merchant_id, idem_key);
