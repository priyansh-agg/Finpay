-- Small key/value store for runtime-tunable policy (e.g. the auto-clear confidence
-- threshold, which the calibrator raises if a false auto-clear is ever detected).
CREATE TABLE system_config (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seeded low; the calibrator only ever ratchets it upward. The effective value at runtime
-- is max(this, finpay.autoclear.threshold).
INSERT INTO system_config (key, value) VALUES ('autoclear.threshold', '0.50');
