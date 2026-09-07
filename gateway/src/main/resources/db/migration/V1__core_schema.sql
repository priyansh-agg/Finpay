-- FinPay core schema: identities, orders, payments, idempotency, audit.
-- Money is always (amount_minor BIGINT, currency VARCHAR(3)); never floats.

CREATE TABLE merchants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT        NOT NULL,
    email           TEXT        NOT NULL UNIQUE,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | SUSPENDED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Merchant API keys. The raw key (fp_live_xxx) is shown exactly once at creation;
-- we persist only an HMAC-SHA256 of it (peppered), plus a short prefix for display.
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    key_prefix      TEXT        NOT NULL,                    -- e.g. fp_live_a1b2c3
    key_hash        TEXT        NOT NULL UNIQUE,             -- HMAC-SHA256(pepper, raw)
    label           TEXT,
    role            TEXT        NOT NULL DEFAULT 'MERCHANT', -- MERCHANT | MERCHANT_ADMIN
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_keys_merchant ON api_keys(merchant_id);

-- Dashboard users (JWT auth). RISK_ANALYST / SUPER_ADMIN are NOT tied to a merchant.
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID        REFERENCES merchants(id),
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,                    -- bcrypt
    role            TEXT        NOT NULL,                    -- MERCHANT_ADMIN | RISK_ANALYST | SUPER_ADMIN
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE customers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    external_ref    TEXT        NOT NULL,                    -- merchant's own customer id
    email           TEXT,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),      -- drives "customer tenure"
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, external_ref)
);

-- Device fingerprints seen per customer; feeds the "new device" fraud rule.
CREATE TABLE devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID        NOT NULL REFERENCES customers(id),
    fingerprint     TEXT        NOT NULL,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, fingerprint)
);

CREATE TABLE orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    customer_id     UUID        NOT NULL REFERENCES customers(id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)     NOT NULL DEFAULT 'INR',
    status          TEXT        NOT NULL DEFAULT 'CREATED',  -- CREATED | PAID | CANCELLED
    receipt         TEXT,
    notes           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_merchant ON orders(merchant_id);

-- One payment per attempt-to-pay-an-order. State machine lives in code
-- (PaymentState + ALLOWED_TRANSITIONS); this column is just the current node.
CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID        NOT NULL REFERENCES orders(id),
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    customer_id     UUID        NOT NULL REFERENCES customers(id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency        VARCHAR(3)     NOT NULL,
    method          TEXT        NOT NULL,                    -- CARD | UPI
    payment_token   TEXT        NOT NULL,                    -- tok_xxx (never a real PAN)
    device_fingerprint TEXT,
    ip_address      TEXT,
    status          TEXT        NOT NULL DEFAULT 'CREATED',
    processor_ref   TEXT,
    risk_decision   TEXT,                                    -- ALLOW | REVIEW | BLOCK
    risk_bucket     TEXT,                                    -- LOW | MEDIUM | HIGH | CRITICAL
    risk_score      NUMERIC(5,2),
    failed_reason   TEXT,
    authorized_at   TIMESTAMPTZ,
    captured_at     TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,          -- JPA optimistic lock
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_merchant_created ON payments(merchant_id, created_at DESC);
CREATE INDEX idx_payments_customer_created ON payments(customer_id, created_at DESC);
CREATE INDEX idx_payments_status ON payments(status);

-- Append-only log of every state transition / processor interaction for a payment.
CREATE TABLE payment_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL REFERENCES payments(id),
    from_status     TEXT,
    to_status       TEXT        NOT NULL,
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_attempts_payment ON payment_attempts(payment_id, created_at);

-- Idempotency-Key store. UNIQUE(key) is the concurrency primitive:
-- INSERT ... ON CONFLICT DO NOTHING -> exactly one caller "owns" the request.
CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idem_key        TEXT        NOT NULL,
    merchant_id     UUID        NOT NULL REFERENCES merchants(id),
    endpoint        TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,                    -- sha256 of canonical body
    status          TEXT        NOT NULL DEFAULT 'PROCESSING', -- PROCESSING | COMPLETED | FAILED
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (idem_key)
);
CREATE INDEX idx_idem_expires ON idempotency_keys(expires_at);

-- who / what / when / before / after for every state-changing action.
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type      TEXT        NOT NULL,                    -- MERCHANT | USER | SYSTEM
    actor_id        TEXT,
    action          TEXT        NOT NULL,
    entity_type     TEXT        NOT NULL,
    entity_id       TEXT        NOT NULL,
    before_state    JSONB,
    after_state     JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id, created_at);
