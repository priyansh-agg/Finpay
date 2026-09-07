-- Phase 2/3: pgvector similar-fraud retrieval, analyst review queue, auto-clear engine.

CREATE EXTENSION IF NOT EXISTS vector;

-- Structured numeric feature vectors of CONFIRMED fraud cases only.
-- Populated after fraud_ground_truth resolves actually_fraudulent = true.
CREATE TABLE fraud_case_vectors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL UNIQUE REFERENCES payments(id),
    feature_vector  vector(16)  NOT NULL,
    fraud_type      TEXT,
    confirmed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_vectors_ivfflat ON fraud_case_vectors
    USING ivfflat (feature_vector vector_cosine_ops) WITH (lists = 20);

-- Review queue. A flagged payment (decision = REVIEW) creates one row here.
CREATE TABLE analyst_reviews (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL REFERENCES payments(id),
    queue_status    TEXT        NOT NULL DEFAULT 'PENDING',   -- PENDING | DECIDED
    reviewer_type   TEXT,                                     -- REFERENCE_ANALYST | HUMAN | AUTO_CLEAR
    decision        TEXT,                                     -- CLEAR | REJECT
    reason          TEXT,
    features_at_review JSONB    NOT NULL DEFAULT '{}'::jsonb,  -- feature snapshot used for the decision
    risk_score_at_review NUMERIC(5,2),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ
);
CREATE INDEX idx_analyst_reviews_queue ON analyst_reviews(queue_status, created_at);
CREATE INDEX idx_analyst_reviews_payment ON analyst_reviews(payment_id);

-- Auto-Clear Engine decisions (Phase 3 USP). Separate classifier: "would the
-- reference analyst clear this?" Only auto-clears above a high, tunable threshold.
CREATE TABLE autoclear_decisions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL REFERENCES payments(id),
    review_id       UUID        REFERENCES analyst_reviews(id),
    confidence      NUMERIC(6,5) NOT NULL,   -- P(analyst clears)
    threshold       NUMERIC(6,5) NOT NULL,
    auto_cleared    BOOLEAN     NOT NULL,
    top_features    JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- [{feature, contribution}]
    model_version   TEXT,
    -- set later by the ground-truth reconciler; TRUE here = a false auto-clear (critical bug)
    later_confirmed_fraud BOOLEAN,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_autoclear_payment ON autoclear_decisions(payment_id);
CREATE INDEX idx_autoclear_cleared ON autoclear_decisions(auto_cleared, created_at DESC);

-- LLM explanations cached per payment (Spring AI + Ollama).
CREATE TABLE risk_explanations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL UNIQUE REFERENCES payments(id),
    explanation     TEXT        NOT NULL,
    model           TEXT,
    similar_cases   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
