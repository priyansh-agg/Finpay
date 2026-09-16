-- Interview sprint Day 8: a review the auto-clear proposer declines needs to be claimable and
-- trackable by an actual human, not just left in an undifferentiated PENDING pile.
ALTER TABLE analyst_reviews
    ADD COLUMN assigned_to TEXT,          -- caller id once claimed (a human's subject id, or the sim bot)
    ADD COLUMN claimed_at  TIMESTAMPTZ,
    ADD COLUMN sla_due_at  TIMESTAMPTZ;    -- set at enqueue time; dashboard urgency, not enforced yet

CREATE INDEX idx_analyst_reviews_unclaimed ON analyst_reviews(queue_status, assigned_to, created_at);
