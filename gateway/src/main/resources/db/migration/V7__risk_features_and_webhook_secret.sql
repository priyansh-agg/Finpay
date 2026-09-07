-- Phase 2: store the feature snapshot used for each risk evaluation (explainability + Phase 3).
ALTER TABLE risk_evaluations ADD COLUMN features JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Track failed-then-success bursts: mark when a payment attempt failed for a customer.
CREATE TABLE customer_payment_stats (
    customer_id      UUID PRIMARY KEY REFERENCES customers(id),
    rolling_avg_minor  BIGINT NOT NULL DEFAULT 0,
    captured_count     BIGINT NOT NULL DEFAULT 0,
    captured_sum_minor BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
