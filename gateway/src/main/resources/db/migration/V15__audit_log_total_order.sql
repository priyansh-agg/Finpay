-- audit_logs gets a monotonic total order, independent of created_at's timestamp resolution.
--
-- A single HTTP request commits several audit rows in ONE transaction (payment created, then
-- held, then a review decision). Postgres's now() returns the same transaction timestamp for
-- every statement in that transaction, so those rows share one created_at value exactly. A
-- caller who orders "by created_at" to reconstruct what happened first gets ties it cannot
-- break correctly — found by the audit trail's own test suite (PaymentAuditTrailTest),
-- ordering by created_at silently reversed same-instant rows.
--
-- seq is a real sequence: gapless creation order, comparable across any two rows, however
-- close in time.
ALTER TABLE audit_logs ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE INDEX idx_audit_entity_seq ON audit_logs (entity_type, entity_id, seq);
