-- Risk evaluations, transactional outbox, webhook delivery, hidden ground truth.

CREATE TABLE risk_evaluations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id        UUID        NOT NULL REFERENCES payments(id),
    rule_flags        JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- [{code, weight, detail}]
    rule_score        NUMERIC(5,2) NOT NULL DEFAULT 0,
    ml_score          NUMERIC(5,2),                              -- 0-100, from ml-service /predict
    ml_model_version  TEXT,
    vector_similarity NUMERIC(5,4),                              -- avg cosine sim to known fraud
    final_score       NUMERIC(5,2) NOT NULL,
    bucket            TEXT        NOT NULL,                       -- LOW | MEDIUM | HIGH | CRITICAL
    decision          TEXT        NOT NULL,                       -- ALLOW | REVIEW | BLOCK
    latency_ms        INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_eval_payment ON risk_evaluations(payment_id);
CREATE INDEX idx_risk_eval_bucket ON risk_evaluations(bucket, created_at DESC);

-- Transactional outbox: written in the SAME tx as the state change it describes.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  TEXT        NOT NULL,   -- PAYMENT | REFUND | RISK
    aggregate_id    UUID        NOT NULL,
    event_type      TEXT        NOT NULL,   -- payment.captured | payment.failed | risk.flagged | refund.succeeded ...
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING | PUBLISHED | DEAD
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_dispatch ON outbox_events(status, next_attempt_at);

CREATE TABLE webhook_endpoints (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    url             TEXT        NOT NULL,
    secret          TEXT        NOT NULL,   -- shared secret for HMAC-SHA256 signature
    events          TEXT[]      NOT NULL DEFAULT '{}',
    active          BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints(merchant_id);

-- One row per (outbox_event, endpoint) delivery attempt-chain.
CREATE TABLE webhook_deliveries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id     UUID        NOT NULL REFERENCES webhook_endpoints(id),
    outbox_event_id UUID        NOT NULL REFERENCES outbox_events(id),
    event_type      TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING | DELIVERED | FAILED | DEAD
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status_code INT,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    UNIQUE (endpoint_id, outbox_event_id)
);
CREATE INDEX idx_webhook_deliveries_dispatch ON webhook_deliveries(status, next_attempt_at);

-- HIDDEN ground truth (Doubt 2). Restricted table: no merchant-facing API ever reads it.
-- actually_fraudulent resolves after a delay to mimic a chargeback/dispute window.
CREATE TABLE fraud_ground_truth (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id          UUID        NOT NULL UNIQUE REFERENCES payments(id),
    actually_fraudulent BOOLEAN,                       -- NULL until resolved
    fraud_type          TEXT,                          -- velocity_abuse | stolen_card | account_takeover
    injected_fraud      BOOLEAN     NOT NULL DEFAULT false,  -- what the simulator planted
    resolve_at          TIMESTAMPTZ NOT NULL,
    resolved            BOOLEAN     NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ground_truth_resolve ON fraud_ground_truth(resolved, resolve_at);
