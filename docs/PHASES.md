# FinPay — Phased Build Plan & Status

Three phases. **Phase 3 is the USP** ("we measurably cut analyst review hours without
letting real fraud through"), so it leads the résumé bullet and the README.

---

## Cross-cutting (plan §10–11)

- **Rate limiting** (§10) — Redis token bucket per caller (`RateLimiter` Lua eval, atomic),
  added to the security chain after auth. Over-limit → 429 + `Retry-After` + `X-RateLimit-*`
  headers. Fails open if Redis is down. Tested (`RateLimitTest`).
- **API keys** hashed at rest (HMAC-SHA256, peppered), shown once. **Webhook payloads** HMAC-SHA256
  signed. **RBAC** via `@PreAuthorize` (MERCHANT / MERCHANT_ADMIN / RISK_ANALYST / SUPER_ADMIN).
  Pydantic-equivalent validation (Jakarta Bean Validation) on every request body. Structured
  audit log for state-changing actions.
- **Observability** (§11) — one structured request line per call (`finpay.request`) with a
  `requestId` in MDC + `X-Request-Id` header; ECS JSON under the container profile.
  `/actuator/prometheus`: `finpay_payments_total{result}`,
  `finpay_risk_decisions_total{decision,bucket}`, `finpay_risk_latency_seconds` (p50/p95/p99),
  `finpay_webhook_deliveries_total{status}`, `finpay_autoclear_decisions_total{auto_cleared}`,
  `finpay_autoclear_false_clears_total`.
- **Checkout links** — `com.finpay.gateway.checkout` — Razorpay/Stripe-style hosted payment
  links. `GET/POST /v1/checkout/{orderId}` are `permitAll` (the order id is the capability
  token) and rate-limited by caller IP. `dashboard/src/CheckoutPage.tsx` is the customer-facing
  page (routed by pathname in `main.tsx`, `/pay/{orderId}`), separate light theme, test-card
  picker, success/held/failed screens. `CheckoutFlowTest` covers view/pay/decline/404.
- **API docs** — springdoc-openapi at `/swagger-ui/index.html` and `/v3/api-docs` (25 paths).
- `GET /v1/transactions?page&size&status` — paginated history (merchant-scoped, or all for an analyst).
- **Settlement batch** (plan V1 stretch) — `SettlementService` sweeps each merchant's accrued
  `MERCHANT_PAYABLE` balance into a payout on a schedule: a `settlements` row + a balanced
  `SETTLEMENT` ledger transaction to `MERCHANT_SETTLED`, a `settlement.paid` webhook, an audit
  entry. `GET /v1/settlements` (merchant) / `POST /v1/settlements/run` (admin). Tested
  (`SettlementTest`) — payable → 0, ledger stays balanced.

---

## Phase 0 — Scaffold ✅ done

- [x] Monorepo: `gateway/` (Spring Boot), `ml-service/` (FastAPI), `dashboard/` (React), `docker-compose.yml`
- [x] JDK 21 pinned via Maven toolchain (host has JDK 26)
- [x] Flyway migrations V1–V8
- [x] `PaymentState` + `ALLOWED_TRANSITIONS` map with `assertCanTransitionTo`
- [x] Security: API-key auth (HMAC-hashed at rest, shown once) + JWT for dashboard, RBAC roles
- [x] Global error model, structured audit log, canonical-JSON hasher

---

## Phase 1 — Payment Gateway Core ✅ done & tested

| Area | Status |
|---|---|
| Merchant onboarding + API keys + dashboard users | ✅ |
| Orders API | ✅ |
| Payment state machine | ✅ |
| Idempotency: `idempotency_keys` unique index + `INSERT … ON CONFLICT DO NOTHING` | ✅ |
| Double-entry ledger, append-only (DB trigger blocks UPDATE/DELETE) + reconciliation | ✅ |
| Refunds/cancellations, `SELECT … FOR UPDATE`, partial + full | ✅ |
| Deterministic simulator (`tok_*`) + probabilistic mode | ✅ |
| Transactional outbox | ✅ |
| Hidden `actually_fraudulent` ground truth planted at payment time | ✅ |

**Exit criteria — all met, all automated (`gateway/src/test`, 29 tests green):**

- [x] 20 concurrent identical `Idempotency-Key` requests → **exactly 1 payment** (`IdempotencyConcurrencyTest`)
- [x] Concurrent refunds never over-refund; ledger stays balanced (`RefundConcurrencyTest`)
- [x] `SUM(debits) == SUM(credits)` after arbitrary capture/refund sequences (`LedgerReconciliationTest`)
- [x] Every illegal state transition throws `InvalidStateTransitionException` (`PaymentStateTransitionTest`)
- [x] `ledger_entries` UPDATE/DELETE rejected by a DB trigger (tested)

---

## Phase 2 — Fraud Detection ✅ done

| Area | Status |
|---|---|
| Rule engine (velocity 5m/1h, amount deviation, new device/IP, failed-attempt burst, device ring) | ✅ `RuleEngine`, tested |
| Redis feature store (velocity counters, TTL'd, degrades gracefully) | ✅ `FeatureStore` |
| **Kaggle model** — offline demo, class-imbalance handling, PR/AUC report | ✅ `ml-service/app/kaggle_model.py` (needs `data/creditcard.csv`) |
| **FinPay-native model** — LIVE, logistic regression on synthetic feature patterns | ✅ `ml-service/app/finpay_model.py`, ROC-AUC ≈ 0.89 / PR-AUC ≈ 0.80 |
| Synchronous risk gate BEFORE the processor call (Doubt 6) | ✅ `RiskEngine implements RiskGate` |
| Combined score `w₁·rule + w₂·ml + w₃·vector` → LOW/MEDIUM/HIGH/CRITICAL → ALLOW/REVIEW/BLOCK | ✅ |
| pgvector similar-fraud retrieval, similarity as a risk signal | ✅ `FraudVectorStore` (`vector(16)`, ivfflat cosine) |
| Webhooks: transactional outbox → fan-out → HMAC-SHA256 signed delivery, exponential backoff, dead-letter | ✅ `WebhookDispatcher` + `WebhookSender` |
| Delayed `actually_fraudulent` resolution + embedding of confirmed fraud into pgvector | ✅ `GroundTruthResolver` |

**Exit criteria:**

- [x] Every payment gets a `final_score` + bucket synchronously (latency logged; ~2–5 ms in practice)
- [x] Rules are individually attributable — `risk_evaluations.rule_flags` records code + weight + detail per fired rule
- [x] README states plainly the live model trains on synthetic data and the Kaggle model is an offline methodology demo
- [x] Webhook delivery survives a downed endpoint: retries with backoff, then DEAD, then a
      recovered endpoint delivers with a verifiable signature — automated (`WebhookDeliveryTest`)

---

## Phase 3 — Analyst Hour Reduction (the USP) ✅ done

| Component | Status |
|---|---|
| Review queue — a REVIEW/HELD payment becomes a `PENDING` `analyst_reviews` row with a feature snapshot | ✅ `ReviewQueue` |
| Rule-based **reference analyst** → silver labels (Doubt 3, pattern-aware, deterministic, never sees ground truth) | ✅ `ReferenceAnalyst`, tested |
| Auth/capture decoupling (Doubt 4): score ≤ auto-capture-max → auto-capture; LOW/MEDIUM → HELD; HIGH → REVIEW; CRITICAL → BLOCK | ✅ `PaymentService` |
| **Auto-Clear Engine** — separate classifier "would the reference analyst clear this?", trained on silver labels in the ML sidecar | ✅ `ml-service/app/autoclear_model.py`, `AutoClearClient` |
| Auto-clear only when EVERY check agrees: bucket ∈ {LOW,MEDIUM}, no prior confirmed fraud, reference analyst concurs, ML confidence ≥ calibrated threshold | ✅ `ReviewDecider` |
| **Calibration loop** — every auto-clear reconciled vs resolved ground truth; a confirmed false auto-clear ratchets the confidence threshold up (never down) and logs `ERROR` | ✅ `AutoClearCalibrator` + `AutoClearPolicy` (`system_config`) |
| LLM "explain this flag" — Spring AI + Ollama over the rule flags + retrieved pgvector cases, cached, template fallback | ✅ `RiskExplainer` |
| Risk-analyst dashboard — queue, decide, before/after volume, false-auto-clear rate, threshold | ✅ `dashboard/` + `AnalystController` |
| Evaluation harness — generates realistic traffic; fraud injected as *patterns*, only signal-bearing txns tagged | ✅ `SimulationService` (`POST /v1/admin/simulate`) |

**Measured result (calibration phase + measurement phase, ~2000 flagged transactions):**

| Metric | Value |
|---|---|
| Transactions flagged for review | ~2,200 |
| **Auto-cleared without a human** | ~530 (**≈ 24–27%** of the flagged queue) |
| **False auto-clears** (auto-cleared, later confirmed fraud) | **0** |
| Confidence threshold | held at its 0.80 floor — the calibrator never needed to intervene in the final config |

Résumé line: *"Built a secondary auto-clear layer that safely released ~1 in 4 flagged
transactions with **zero false auto-clears** against a simulated ground truth, cutting manual
review volume by ~25%."*

**Exit criteria:**

- [x] Dashboard panel shows review volume before/after + false-auto-clear rate (0)
- [x] `actually_fraudulent` is used for EVALUATION ONLY — `ml-service/app/db.py` deliberately does not select it for training (data-leakage guard, verified in `ReferenceAnalystTest`)
- [x] README documents how "analyst ground truth" is simulated and what a real deployment would need

---

## V1 stretch list (plan "V1, weeks 9-10") — all done

- ML risk scoring layered on rules — ✅ Phase 2
- Webhook delivery + retry — ✅ Phase 2
- Settlement batch simulation — ✅ `settlement/` module
- Admin risk dashboard — ✅ `dashboard/` + `AnalystController`

## Explicitly deferred (stated for maturity, per the plan)

Real payment-processor integration · multi-currency settlement · disputes/chargebacks ·
PCI-DSS card vaulting · horizontal-scaling infra · Kafka · Kubernetes · a labelled internal
fraud dataset with a chargeback feedback loop · Grafana (Prometheus metrics are exported;
the dashboard is left to the reader).

---

## Honest limitations (say these out loud)

- Analyst decisions and fraud outcomes are **simulated**. The reference analyst is a fixed
  ruleset; `actually_fraudulent` is a hidden label the generator controls and resolves after
  a delay. A real deployment replaces both with actual analyst feedback and confirmed
  chargebacks, plus drift monitoring (analyst behaviour and fraud patterns both shift).
- The live fraud model trains on synthetic transactions. The Kaggle model demonstrates
  methodology on real data but is not in the request path.
- Single Postgres, single Redis, in-process workers. No Kafka/K8s — a deliberate call for a
  2-person team (we spent that budget on idempotency + ledger integrity).

---

## Local dev notes

- Host has local Postgres 18 + pgvector and local Redis (brew services); the gateway runs
  against those directly when Docker isn't up. `docker compose up --build` runs the full stack.
- `SPRING_PROFILES_ACTIVE=phase1` runs the gateway with the allow-all risk gate + no-op
  review queue (Phase 1 in isolation). Without it, the full Phase 2/3 engine is active and
  the ML sidecar should be reachable (it degrades gracefully if not).
