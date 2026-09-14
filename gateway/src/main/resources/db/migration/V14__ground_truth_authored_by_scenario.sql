-- Fraud ground truth is authored by whoever generated the transaction, not inferred from
-- the card token it happened to carry.
--
-- Until now PaymentService planted actually_fraudulent from "tok_fraud".equals(token). That
-- token was an option on the PUBLIC checkout page, so any tester could attach a hidden fraud
-- label to a payment with a perfectly clean signal. The auto-clear engine then cleared it —
-- correctly, on the evidence — and the ground-truth reconciler booked a false auto-clear.
-- The zero-false-auto-clear claim was one click away from being broken.
--
-- Now:
--   intended_class   LEGIT | FRAUD — what the transaction's author says it is
--   label_source     SIMULATION  — authored by the simulation harness, the only author of fraud
--                    LIVE        — real API / checkout traffic; no author, assumed legitimate
--                    LEGACY_TOKEN — rows labelled by the old token rule, kept for provenance
--   scenario_pattern the generator's name for the pattern (velocity_abuse, regular, ...)
--   disguise_level   0..1, how much the transaction looks like the OTHER class
--                    (0 = looks exactly like what it is; stealth fraud / fraud-shaped legit → 1)
--
-- injected_fraud is replaced by intended_class; the backfill keeps every existing label.
ALTER TABLE fraud_ground_truth
    ADD COLUMN intended_class   TEXT,
    ADD COLUMN label_source     TEXT,
    ADD COLUMN scenario_pattern TEXT,
    ADD COLUMN disguise_level   NUMERIC(4,3);

UPDATE fraud_ground_truth
   SET intended_class   = CASE WHEN injected_fraud THEN 'FRAUD' ELSE 'LEGIT' END,
       label_source     = 'LEGACY_TOKEN',
       scenario_pattern = fraud_type;

ALTER TABLE fraud_ground_truth
    ALTER COLUMN intended_class SET NOT NULL,
    ALTER COLUMN label_source   SET NOT NULL,
    ADD CONSTRAINT chk_gt_intended_class CHECK (intended_class IN ('LEGIT', 'FRAUD')),
    ADD CONSTRAINT chk_gt_label_source   CHECK (label_source IN ('SIMULATION', 'LIVE', 'LEGACY_TOKEN')),
    ADD CONSTRAINT chk_gt_disguise_level CHECK (disguise_level IS NULL OR disguise_level BETWEEN 0 AND 1),
    DROP COLUMN injected_fraud;

CREATE INDEX idx_ground_truth_class_source ON fraud_ground_truth (label_source, intended_class);
