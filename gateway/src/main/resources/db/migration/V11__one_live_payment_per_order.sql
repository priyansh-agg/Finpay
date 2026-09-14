-- At most one LIVE payment per order.
--
-- PaymentService now takes SELECT ... FOR UPDATE on the order and refuses a second payment
-- while one is in flight. This index is the database-level backstop for that rule, in the
-- same spirit as the ledger's append-only trigger: application code states the invariant,
-- the database makes it impossible to break even for code that skips the service.
--
-- "Live" means anything that has not ended without moving money. Failed, voided and
-- cancelled attempts drop out of the index as soon as their status changes, so a customer
-- whose card was declined can still retry the same order with another one. Expressed as
-- NOT IN (dead states) so that any future state is treated as live by default.
CREATE UNIQUE INDEX ux_payments_one_live_per_order
    ON payments (order_id)
    WHERE status NOT IN ('FAILED', 'VOIDED', 'CANCELLED');
