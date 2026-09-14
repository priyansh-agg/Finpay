-- The merchant an outbox event belongs to becomes a real column.
--
-- Until now WebhookDispatcher found the recipient by parsing payload.merchantId out of the
-- event JSON. The refund.succeeded payload never carried that field, so every refund event
-- resolved to "no merchant", fanned out to nobody, and was still marked PUBLISHED. No error,
-- no failed delivery, green tests.
--
-- Routing metadata does not belong inside the merchant-facing body. As a column it is set
-- through OutboxService.publish(..., merchantId, ...), so every publisher has to state its
-- tenant and the compiler enforces it.
--
-- Backfill what the old payloads can tell us. Rows that stay NULL (historical refund events)
-- are dead-lettered by the dispatcher with an explicit error instead of silently published.
ALTER TABLE outbox_events ADD COLUMN merchant_id UUID REFERENCES merchants(id);

UPDATE outbox_events
   SET merchant_id = (payload ->> 'merchantId')::uuid
 WHERE merchant_id IS NULL
   AND payload ? 'merchantId';

CREATE INDEX idx_outbox_merchant ON outbox_events (merchant_id);
