-- A simulation run becomes a first-class, queryable record instead of parameters that vanish
-- once the HTTP request returns.
--
-- Today a "1,000-transaction" run silently creates ~1,700 payments: customer-history seeding
-- (3 per simulated customer) and the warm-up transactions inside each fraud pattern (a
-- velocity burst needs 7 clean payments first to push the rule past its threshold) are
-- indistinguishable, in the payments table, from the population the caller actually asked
-- for. Every metric computed from that table -- flagged count, auto-clear rate, anything --
-- is silently diluted by transactions nobody asked to have scored.
--
-- simulation_runs records the scenario as it was requested (verbatim, so a run is literally
-- reproducible from its own row) and, once finished, a summary of what actually happened.
-- run_id and is_warmup on payments (and run_id on analyst_reviews, which inherits it from the
-- payment that queued them) let every later query separate "the population" from "the scenery
-- built to produce it".
CREATE TABLE simulation_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    seed            BIGINT      NOT NULL,
    scenario        JSONB       NOT NULL,   -- the request that started this run, verbatim
    created_by      TEXT,                    -- caller identity (subjectId(), e.g. "user:<id>")
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    summary         JSONB                    -- SimSummary, written once the run finishes
);
CREATE INDEX idx_simulation_runs_merchant ON simulation_runs (merchant_id, started_at DESC);

ALTER TABLE payments
    ADD COLUMN run_id    UUID REFERENCES simulation_runs(id),
    ADD COLUMN is_warmup BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX idx_payments_run ON payments (run_id) WHERE run_id IS NOT NULL;

ALTER TABLE analyst_reviews
    ADD COLUMN run_id UUID REFERENCES simulation_runs(id);
CREATE INDEX idx_analyst_reviews_run ON analyst_reviews (run_id) WHERE run_id IS NOT NULL;
