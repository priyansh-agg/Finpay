# FinPay — Doubts Resolved (Architecture Freeze)

All 6 open doubts from the project plan, resolved and locked. Tech stack + pgvector use-case included at the end.

---

## Doubt 1 — Simulated payment processor mechanics

**Decision: Deterministic + configurable simulator.** Random-only is bad for demos (non-reproducible results in front of an interviewer).

- **Deterministic token map** for demo/testing:
  - `tok_valid` → `AUTHORIZED`
  - `tok_declined` → `FAILED`
  - `tok_fraud` → `AUTHORIZED but flagged`
  - `tok_insufficient_funds` → `FAILED`
- **Probabilistic mode** (config toggle: `DETERMINISTIC` vs `PROBABILISTIC`) for load-testing / realistic volume — configurable success rates per method (e.g. 92% CARD, 88% UPI).
- Both CARD and UPI implement a common `PaymentSimulator` interface, so swapping in a real processor (Razorpay/Stripe) later is a drop-in — good "production-mindset" talking point for interviews.

---

## Doubt 2 — `actually_fraudulent` visibility

**Decision: Completely hidden from merchant and normal dashboard, with delayed resolution.**

- Never exposed via any merchant-facing or standard dashboard API.
- Lives only in an **internal evaluation table**, accessible only to offline analytics/ML training pipeline.
- To be more realistic: don't set this label immediately — simulate it resolving after a delay (like a real chargeback/dispute window). This also feeds directly into Doubt 4's solution.

---

## Doubt 3 — Reference analyst behavior

**Decision: Rule-based deterministic analyst** (not manual labeling — doesn't scale for a solo project and kills reproducibility).

```
IF risk_score >= 80          → REJECT
IF risk_score <= 20          → CLEAR (auto)
IF 20 < risk_score < 80:
    IF customer_tenure > 90 days
       AND prior_fraud_flags == 0
       AND amount < customer_avg_amount * 2   → CLEAR
    ELSE                                       → REJECT
```

- These analyst rules generate the **training labels** (silver labels) for the Phase-3 ML model.
- `actually_fraudulent` (ground truth) is used **only for evaluation, never for training** — using it in training would be data leakage and make the model look unrealistically good.

---

## Doubt 4 — "Zero false auto-clear" constraint

**Decision: Separate authorization from capture — this becomes the actual enforcement mechanism, not just an offline calibration target.** Instead of settling funds to the merchant immediately, the gateway authorizes (reserves funds) first, and capture only happens based on the risk tier.

```
Customer
   ↓
Payment Gateway
   ↓
Payment Authorization   (funds reserved, NOT yet transferred to merchant)
   ↓
Fraud Detection Model   (risk scoring)
   ↓
┌──────────────┬──────────────────┬───────────────┐
│   Low Risk   │   Medium Risk    │   High Risk   │
│              │                  │               │
│   Capture    │  Hold + Review   │    Decline    │
└──────────────┴──────────────────┴───────────────┘
                       ↓
                 Fraud Investigation
                       ↓
              ┌────────┴────────┐
              ↓                 ↓
           Legitimate          Fraud
              ↓                 ↓
           Capture          Cancel/Reverse
```

**Why this genuinely solves zero-false-auto-clear (not just approximates it):**

- "Auto-clear" now specifically means **auto-capture** — and that only happens for the **Low Risk** bucket.
- Medium Risk transactions are never captured without investigation — funds stay in `AUTHORIZED`/held state, merchant never receives money. If one of these later turns out fraudulent, there's no real loss — the authorization is simply voided/reversed.
- High Risk = declined immediately (never authorized, or authorized and voided instantly).
- So the constraint collapses to a much smaller, tractable problem: **calibrate the Low Risk threshold so that its historical false-negative rate is ~0** (offline validation batch, threshold tuning — same idea as before, just now scoped only to the auto-capture bucket instead of the whole system).

**Updated FinPay lifecycle (extends Doubt 6's state machine):**

```
CREATED → PENDING → Risk Engine
                         │
        ┌────────────────┼─────────────────┐
        ↓                ↓                 ↓
   Low Risk         Medium Risk         High Risk
        ↓                ↓                 ↓
   AUTHORIZED      AUTHORIZED           DECLINED
   → CAPTURED      (HELD / PENDING_REVIEW) (never authorized,
   (auto)                ↓                  or authorized
                   Fraud Investigation       + voided instantly)
                         ↓
                 ┌───────┴───────┐
                 ↓               ↓
            Legitimate         Fraud
                 ↓               ↓
             CAPTURED     CANCELLED/VOIDED
                          (or REFUNDED if
                           already captured —
                           should be rare)
```

**Important nuance:** if a transaction was already `CAPTURED` (low-risk auto-capture path) and later turns out `actually_fraudulent = true` (delayed ground truth, per Doubt 2), that's a genuine false auto-clear — and it's exactly the metric the offline calibration process is minimizing. With this design, that failure mode is only possible for the Low Risk bucket, since Medium/High are already held or declined.

**Interview framing:** "I decoupled authorization from capture, similar to real payment gateways like Stripe/Razorpay. This turns 'zero false auto-clear' from an abstract ML-threshold problem into a concrete financial-mechanics problem — funds are only released when risk is confidently low or a manual investigation clears the transaction. The loss-bearing 'auto-clear' path is restricted to a calibrated low-risk bucket."

---

## Doubt 5 — Kaggle features vs FinPay features

**Decision: Option C + A combined.** Pure feature-transformation (Option B) isn't practical — Kaggle's V1–V28 are anonymized PCA components that can't be reverse-mapped to synthetic transaction features.

Build **two separate model artifacts**:

1. **Kaggle model** (standalone, on the real Credit Card Fraud dataset) — proves ML methodology: class imbalance handling (SMOTE/class weights), precision/recall/AUC reporting. This is the "ML competency demo," offline only.
2. **FinPay-native model** — trained on synthetic transactions using FinPay's actual features: velocity, amount deviation, device/IP mismatch, customer history, failed attempts. Inject realistic fraud patterns into the generator (e.g. "burst of 5 transactions in 2 min from a new device"). Keep it interpretable — logistic regression or gradient boosting (XGBoost/LightGBM). This is the model that's actually **live in the system**.

Label both clearly in docs so there's no confusion about "which model is actually running."

---

## Doubt 6 — Payment lifecycle + fraud gate point

**Decision: Risk check happens *before* the processor call** (saves cost + blocks fraud attempts before they ever hit "payment processing").

```
POST /payments
      ↓
   CREATED
      ↓
   PENDING
      ↓
  Risk Engine (ALLOW / REVIEW / BLOCK)
      │
      ├── BLOCK  → FAILED (processor never called)
      │
      ├── REVIEW → PENDING_REVIEW
      │              ↓
      │        Reference Analyst
      │              ↓
      │        CLEAR → (resume below)   REJECT → FAILED
      │
      └── ALLOW  ↓
             PROCESSING (simulated processor call)
                  ↓
         AUTHORIZED / FAILED
                  ↓ (if AUTHORIZED)
               CAPTURED
```

- `BLOCK` = immediate `FAILED`, processor is never touched.
- `REVIEW` = holds at `PENDING_REVIEW`; analyst decision resumes or kills the flow.
- Capture auto-triggers right after `AUTHORIZED` for MVP simplicity. (A separate `CAPTURE_PENDING` state can be added later if delayed capture needs to be demoed.)

**Note on "Section 14":** confirmed missing in the source doc (numbering jumps 13 → 15). Not an assumption gap — it's a genuine gap in the original document. Ignore it.

---

## Tech Stack (locked)

**Frontend**
- React (Zustand/Redux Toolkit if the dashboard state gets complex)
- Axios/fetch for API calls; React Query for server-state caching (useful for risk dashboard polling)

**Backend**
- **Spring Boot** — core REST API, payment lifecycle orchestration, state machine
- **Spring Security** — role-based auth (JWT). `ROLE_MERCHANT` vs `ROLE_RISK_ANALYST`/`ROLE_ADMIN` — this is what actually enforces Doubt 2 (hiding `actually_fraudulent` from merchants)
- **Spring AI** — powers an LLM-based "explain this flag" feature for the risk analyst dashboard, using Ollama as the local model backend
- **Ollama** — local LLM serving; no external API cost dependency (good interview point)
- **pgvector (PostgreSQL)** — similar-fraud-case retrieval for risk scoring + explainability (full use-case below — this was previously vague and has now been made concrete)
- **PostgreSQL** — primary relational store (transactions, states, hidden ground-truth table in a restricted-access schema/table)

**ML piece (Phase 3)**
- Separate **Python FastAPI** service (consistent with the existing Algo Trading Platform project) exposing a `/predict` endpoint that Spring Boot's risk engine calls. Keeping it separate from Java avoids awkward scikit-learn/XGBoost training pipelines inside Spring Boot.

---

## pgvector — Concrete Use Case (Similar Fraud Case Retrieval)

**Problem it solves:** A plain ML model just outputs a score (e.g. "0.87 fraud probability") with no good "why" beyond feature importance — weak explainability. pgvector fixes this with a retrieval-based approach, the same RAG pattern used for documents, applied here to fraud cases.

### Architecture Flow

```
New Transaction
      ↓
Feature Extraction (velocity, amount deviation, device fingerprint,
                     IP reputation, time-of-day, customer tenure,
                     failed-attempt count, etc.)
      ↓
Encode into fixed-dimension vector (embedding)
      ↓
pgvector similarity search (cosine distance)
against store of CONFIRMED FRAUD transaction vectors
      ↓
Top-K similar past fraud cases retrieved
      ↓
   ┌──────────────────────┬───────────────────────────┐
   ↓                      ↓
Feed as extra signal   Feed retrieved cases into
to Risk Engine          Spring AI + Ollama LLM
(similarity_score       → generates natural-language
 as one more feature)     explanation for analyst dashboard
```

### What gets embedded

Not text embedding (unlike IntelliHire AI) — a **structured numeric feature vector**:

```
vector = [
  normalized_amount,
  amount_deviation_from_customer_avg,
  velocity_last_5min,
  velocity_last_1hr,
  device_is_new (0/1),
  ip_is_new (0/1),
  ip_risk_score,
  customer_tenure_days,
  failed_attempts_last_24hr,
  hour_of_day_sin, hour_of_day_cos,   // cyclical encoding
  ...
]
```
Fixed dimension (~12-16), normalized/scaled, stored as a `vector` column in Postgres via pgvector.

### Storage

```sql
CREATE TABLE fraud_case_vectors (
    id UUID PRIMARY KEY,
    transaction_id UUID REFERENCES transactions(id),
    feature_vector vector(16),
    confirmed_at TIMESTAMP,
    fraud_type VARCHAR(50)  -- e.g. 'velocity_abuse', 'stolen_card', 'account_takeover'
);

CREATE INDEX ON fraud_case_vectors USING ivfflat (feature_vector vector_cosine_ops);
```

Only **confirmed fraud cases** (`actually_fraudulent = true`, after the delayed ground-truth resolution from Doubt 2) get inserted here — directly tied to the Doubt 2 / Doubt 4 design.

### Query at inference time

```sql
SELECT transaction_id, fraud_type,
       1 - (feature_vector <=> $1) AS similarity
FROM fraud_case_vectors
ORDER BY feature_vector <=> $1
LIMIT 5;
```

### How it's actually used

1. **As a risk signal:** `avg_similarity_to_known_fraud` becomes an additional feature fed into the FinPay-native ML model (Doubt 5) — retrieval-augmented risk scoring.
2. **As explainability:** Risk analyst dashboard shows "This transaction is 89% similar to 3 past confirmed fraud cases (type: velocity_abuse)." Spring AI + Ollama converts this into natural language: *"Flagged due to unusually high transaction velocity from a new device, matching a pattern seen in 3 prior fraud cases."*

### Interview framing

"I applied the RAG pattern I used for text in IntelliHire AI to structured fraud-feature-vectors instead — retrieval-augmented risk scoring. This gives two benefits: similarity becomes an additional predictive signal independent of the classifier, and explainability improves because the analyst sees actual similar past cases instead of just a black-box score."

This reuses the same embed → store → retrieve → augment pattern across two resume projects (text RAG in IntelliHire, feature-vector RAG here), which demonstrates architectural consistency.
