# FinPay — Implementation Audit (Phase 0: understand & assess)

**Date:** 2026-09-10
**Method:** full code trace of `gateway/`, `ml-service/`, `dashboard/` + a live run of the
Docker stack (1,000-transaction simulation + a hand-crafted adversarial payment).
**Scope:** audit only. No production code changed. The simulations I ran left rows in the
local Postgres (including one deliberately-produced false auto-clear — see §D).

## Remediation log

The findings below are left as written, as a record of what the audit found. This log
tracks what has since been fixed and the test that proves each fix.

| Finding | Status | Fix | Proven by |
|---|---|---|---|
| P0-2 idempotency key global, not per merchant | ✅ fixed | `V10` composite unique `(merchant_id, idem_key)` | `IdempotencyScopeAndRetryTest` |
| P0-5 failed attempt left no trace | ✅ fixed — **severity corrected**, see F2.4 | `FAILED` marker written after rollback; key retryable | `IdempotencyScopeAndRetryTest` |
| P0-3 one order could take two payments | ✅ fixed | order locked `FOR UPDATE`; live-payment check; `V11` partial unique index | `OrderPaymentExclusivityTest` |
| P0-4 refund webhooks silently dropped | ✅ fixed | `V12` `outbox_events.merchant_id` column; unroutable events dead-lettered, never marked `PUBLISHED` | `WebhookFanOutCoverageTest` |
| Scheduler vs `TRUNCATE` deadlock (found while fixing) | ✅ fixed | schedulers off under the test profile; tests drive workers directly | 4 consecutive clean suite runs |
| Redis feature-store drift across test runs (§E.2, found in the tests too) | ✅ fixed | test fixtures wipe `finpay:*` | same |
| **New — missed by the audit:** refund idempotency key globally unique and looked up without the merchant — merchant B could receive merchant A's refund and B's own refund never ran; same-key concurrent refunds raced into a 500 | ✅ fixed | `V13` `(merchant_id, idem_key)` on refunds; key checked after the payment lock; different payment/amount refused | `RefundIdempotencyTest` |
| **New:** idempotency TTL stored but never enforced | ✅ fixed | expired keys are taken over as new requests | `IdempotencyScopeAndRetryTest` |
| Test blind spot (§A.7): DB suites ran under `phase1` with the allow-all risk gate | ✅ fixed | one test base on the full stack; ML pinned to a closed port for determinism; `phase1` kept alive by a single smoke test | full suite green against the real engine |
| **New:** test profile overrode `auto-capture-max` (15 vs production's 6), so the checkout test asserted a first-time customer is CAPTURED — production HOLDS them | ✅ fixed | override removed; test now pins HELD for a new customer, CAPTURED for a returning one | `CheckoutFlowTest` |
| **New:** auto-clear threshold cached per JVM — a ratchet reached only the instance whose calibrator ran it; the rest kept auto-clearing at the proven-unsafe value | ✅ fixed | `system_config` read on every call; ratchet is one atomic `GREATEST(...)` upsert | `AutoClearFlowTest` |
| P0-1 ground truth coupled to `tok_fraud` — a false auto-clear reachable from the public checkout | ✅ fixed | `V14`: the label is authored only by the simulation harness (`intended_class`, `label_source`, `scenario_pattern`, `disguise_level`); `tok_fraud` removed from the processor and from both UIs; real traffic recorded as `LIVE` | `GroundTruthLabellingTest` |
| **New:** every fraud label hardcoded as `velocity_abuse` — pgvector cases and analyst explanations misnamed three of the four fraud patterns | ✅ fixed | fraud type is the scenario's real pattern name | `GroundTruthLabellingTest` |
| P1-2 `manualReviewReductionPct` a literal copy of `autoClearRatePct` | ✅ fixed | returns `null` — no real human-reviewed baseline exists yet to divide by (sprint Day 8) — instead of a fabricated number; dashboard shows "—" with an explanation | `AutoClearMetricsService` |
| F2.7 payment created/failed/held/voided and review decisions never audited | ✅ fixed | `audit.record(...)` at each; a single `REVIEW_DECIDED` call covers every review outcome (auto-clear, reference analyst, human), whoever decided | `PaymentAuditTrailTest` |
| **New, found while fixing the above:** ordering `audit_logs` by `created_at` silently reverses same-instant rows — several audit rows from one request share one transaction timestamp | ✅ fixed | `V15` adds a real gapless `seq` column; ordering is by `seq`, never `created_at` | `PaymentAuditTrailTest` (caught its own bug on first write — see remediation notes below) |

---

## Executive summary

FinPay is **much more real than it looks**. The payment lifecycle, the double-entry ledger,
the idempotency race primitive, concurrency-safe refunds, the rule+ML risk engine, pgvector
retrieval, the transactional outbox, delayed ground-truth resolution, the Auto-Clear engine
and its calibration loop are all genuinely implemented and backed by the database — not mock
data. 29 tests exist and the core financial-correctness ones are meaningful.

It **does not feel like a serious payment gateway** for three structural reasons, none of
them visual:

1. **There is almost no product surface.** The entire dashboard is 3 screens (`Login`,
   `AnalystView`, `MerchantView`) plus a checkout page — ~630 lines. There is no overview,
   no payment detail, no orders/customers/refunds/settlements/webhooks/API-keys UI, and
   most of those don't have read endpoints either. A merchant who logs in cannot answer
   "how much money moved," "what needs my attention," or "what happened to this payment."

2. **The Auto-Clear USP is not convincingly demonstrated.** The mechanism is well-designed
   in code, but (a) on a fresh 1,000-txn run it auto-cleared **12.4%** of the queue, not the
   ~25% the README claims; (b) "zero false auto-clears" is **structural, not earned** — the
   simulator's fraud always trips a high risk bucket *and* a reference-analyst hard rule, so
   no fraudulent transaction is ever in a position to be auto-cleared; and (c) I produced a
   **real false auto-clear in one attempt** through the normal UI (§D-A), because the hidden
   `actually_fraudulent` label is planted purely from `token == "tok_fraud"`, decoupled from
   whether the transaction carries any fraud signal.

3. **There is no human in the loop, and the "manual review reduction" metric is not a
   comparison against one.** A scheduled job drains the review queue every 4 seconds and
   auto-decides everything with the rule-based reference analyst. `manualReviewReductionPct`
   is *literally a copy* of `autoClearRatePct` (`AutoClearMetricsService` returns the same
   value twice). The reference analyst also **rejected 49% of legitimate flagged
   transactions** in my run — a poor oracle for the silver labels the Auto-Clear model
   trains on.

The right next phase is: fix a short list of correctness bugs, turn the simulator into a
controllable scenario engine, make the review loop real, make Auto-Clear's safety *earned*,
add the missing read endpoints + a payment-detail view, then do the dedicated design pass.

---

# SECTION A — Current architecture

## A.1 Stack & shape

| Layer | Implementation |
|---|---|
| Gateway | Spring Boot 3.4.5, Java 21, modular monolith under `com.finpay.gateway.*` |
| Persistence | PostgreSQL 16 + `pgvector`, Flyway migrations **V1–V9**, JPA/Hibernate (`ddl-auto: none`) |
| Cache / feature store | Redis 7 via `StringRedisTemplate` (`FeatureStore`) |
| Async | Postgres `outbox_events` + `@Scheduled` pollers (no broker) |
| ML sidecar | Python FastAPI (`ml-service/`), scikit-learn logistic regression |
| LLM | Spring AI + Ollama (`RiskExplainer`); **not running** in the stack → template fallback |
| Dashboard | React 18 + Vite, no router lib, no state lib; `main.tsx` switches on `window.location.pathname` |
| Auth | JWT (dashboard users) + peppered-HMAC API keys (merchants); `FinPayAuthFilter` → `FinPayPrincipal` + `ROLE_*` |
| Docs | springdoc OpenAPI at `/swagger-ui`; **27 operations** across 15 paths |

## A.2 Module map (what each package actually does)

- **`auth/`** — `AuthController` (`POST /v1/auth/login` → JWT), `FinPayAuthFilter` resolves
  `Bearer <jwt>` / `ApiKey` / `X-API-Key`, `BootstrapRunner` creates the `SUPER_ADMIN`
  (`admin@finpay.local` / `admin12345`) on first boot, `SecurityConfig` (`@EnableMethodSecurity`,
  stateless, `permitAll` on `/v1/checkout/**`, `/v1/webhooks/receiver/**`, docs, health).
- **`identity/`** — `Merchant`, `ApiKey` (`revoked_at` column exists, **never used**), `User`
  (one `MERCHANT_ADMIN` per merchant, created at onboard), `Customer` (`first_seen_at` drives
  tenure), `Device` (per-customer fingerprints).
- **`merchant/`** — `AdminController` (`SUPER_ADMIN` only: `POST /v1/admin/merchants`,
  `/analysts`), `MerchantService.onboard` (merchant + admin user + one API key, returned once).
- **`order/`** — `OrderController` (`POST /v1/orders`, `GET /v1/orders/{id}` — **no list
  endpoint**), `OrderService` (lazily creates the `Customer`).
- **`payment/`** — the core. `PaymentService.createPayment` runs the whole flow (see A.3),
  `PaymentState` enum + `ALLOWED_TRANSITIONS` map + `assertCanTransitionTo`, `PaymentController`
  (`POST /v1/payments` idempotent, `GET /{id}`, `POST /{id}/capture`), `TransactionsController`
  (`GET /v1/transactions?page&size&status` — merchant-scoped or all-for-analyst),
  `PaymentAttempt` (append-only transition log — **no endpoint exposes it**),
  `simulator/ConfigurablePaymentSimulator` (deterministic `tok_*` map + probabilistic mode).
- **`idempotency/`** — `IdempotencyService.run(...)` wraps the work supplier;
  `IdempotencyKeyRepository.tryClaim` = `INSERT ... ON CONFLICT (idem_key) DO NOTHING`.
- **`ledger/`** — `LedgerService` posts `CAPTURE` / `REFUND` / `SETTLEMENT` journal entries
  (`LedgerTransaction` + ≥2 balanced `LedgerEntry`), `assertBalanced` per tx, `reconcile()`
  (`SUM(debit) == SUM(credit)` + unbalanced-tx scan). Append-only enforced by a DB trigger
  (`finpay_block_mutation` on `ledger_entries`, V2).
- **`refund/`** — `RefundService.refund` — `findByIdForUpdate` on the payment row serialises
  concurrent refunds; idempotent on `idem_key` (unique column); posts the ledger reversal;
  flips payment → `REFUNDED` when fully refunded. **No processor call — refunds always succeed
  instantly.**
- **`risk/`** — `RiskEngine implements RiskGate` (`@Profile("!phase1")`): `FeatureExtractor`
  (Redis velocity + Postgres tenure/rolling-avg), `RuleEngine` (8 rules, weighted),
  `MlClient` → `POST /predict` (200 ms timeout, degrades to rules-only), `FraudVectorStore`
  (16-dim encode, `ivfflat` cosine, only trusted at ≥25 confirmed cases). Weighted score
  → bucket (`low/medium/high-max` = 22/50/78) → decision (`LOW/MEDIUM`→ALLOW, `HIGH`→REVIEW,
  `CRITICAL`→BLOCK). Persists `risk_evaluations` (rule flags + full feature snapshot + latency).
  `AllowAllRiskGate` (`@Profile("phase1")`) is the placeholder.
- **`groundtruth/`** — `GroundTruthService.plant` (called inside the payment tx;
  `injected = "tok_fraud".equals(token)`), `GroundTruthResolver` (`@Scheduled` 5 s: once
  `resolve_at` = created + 60 s passes, sets `actually_fraudulent = injected_fraud`, embeds
  confirmed fraud into pgvector, reconciles `autoclear_decisions.later_confirmed_fraud`).
- **`analyst/`** — `ReviewQueue.enqueue` (writes a `PENDING analyst_reviews` row),
  `ReviewProcessor` (`@Scheduled` every 4 s, drains 50 at a time), `ReviewDecider.decide`
  (per-item, own tx: consult `AutoClearClient` + `ReferenceAnalyst`, auto-clear only if
  **every** gate agrees, always write a shadow `autoclear_decisions` row),
  `ReferenceAnalyst` (deterministic CLEAR/REJECT rules — the silver-label oracle),
  `AutoClearClient` → `POST /autoclear/predict`, `AutoClearPolicy` (threshold in
  `system_config`, floor `0.80`), `AutoClearCalibrator` (`@Scheduled` 15 s: any confirmed
  false auto-clear ratchets the threshold **up**, logs `ERROR`), `AutoClearMetricsService`
  (SQL aggregates), `AnalystController` (`/v1/analyst/reviews`, `/reviews/{id}/decide`,
  `/metrics/autoclear`), `RiskController` (`/v1/risk/{id}`, `/{id}/explain`), `RiskExplainer`.
- **`outbox/` + `webhook/`** — `OutboxService.publish` (`Propagation.MANDATORY` — same tx as
  the state change), `WebhookDispatcher` (`@Scheduled` 2 s: `fanOut()` one delivery per
  subscribed endpoint then mark `PUBLISHED`; `deliver()` hands due deliveries to
  `WebhookSender`), `WebhookSender.attempt` (HMAC-SHA256 sig, exponential backoff, `DEAD`
  after 8 attempts), `WebhookConfigController` (`/v1/webhooks/config`),
  `WebhookReceiverController` (local demo sink, can simulate outages).
- **`settlement/`** — `SettlementService` (`@Scheduled` every 5 min): sweeps each merchant's
  `MERCHANT_PAYABLE` balance → `settlements` row + `SETTLEMENT` ledger tx → `MERCHANT_SETTLED`
  + `settlement.paid` webhook + audit row. `GET /v1/settlements`, `POST /v1/settlements/run`.
- **`sim/`** — `SimulationController` (`POST /v1/admin/simulate`, `SUPER_ADMIN`),
  `SimulationService.run(count, fraudRate, seed)` (see §E).
- **`common/`** — `ApiException` + `GlobalExceptionHandler` (typed error envelope),
  `AuditService`/`AuditLog` (write-only; **no read endpoint**), `CanonicalJson` (for the
  idempotency request hash), `RateLimiter` (Redis token bucket, Lua) + `RateLimitFilter`,
  `FinPayMetrics` (6 Prometheus meters), `RequestLogFilter` (one structured line per request).

## A.3 The create-payment flow (`PaymentService.createPayment`, traced line by line)

```
POST /v1/payments  ──▶ IdempotencyService.run(key, merchantId, "POST /v1/payments", body)   [one @Transactional]
                        └─ tryClaim: INSERT ... ON CONFLICT (idem_key) DO NOTHING
                           winner (1 row) ──▶ createPayment(cmd)   [Propagation.MANDATORY — same tx]
                              order = findByIdAndMerchantId; require status == "CREATED"     ← NO row lock (§F)
                              Payment(CREATED) → PENDING → RISK_EVALUATING
                              risk = riskGate.evaluate(ctx)         ← rules + ML + pgvector, persists risk_evaluations
                              seenDevice(p); plantGroundTruth(p)    ← injected = token == "tok_fraud"
                              decision == BLOCK   → fail(p,"risk_blocked")  → FAILED   (processor never called)
                              decision == REVIEW  → PENDING_REVIEW; reviewQueue.enqueue; outbox "risk.flagged"
                              decision == ALLOW   → PROCESSING → simulator.process(...)
                                 DECLINED → fail(p, reason) → FAILED
                                 AUTHORIZED → AUTHORIZED; authorizedAt = now
                                    autoCapture = risk.autoCaptureEligible && bucket==LOW && finalScore <= 6
                                      true  → doCapture (ledger CAPTURE, order→PAID, stats) ; outbox "payment.captured"
                                      else if bucket in {LOW,MEDIUM} → HELD ; reviewQueue.enqueue ; outbox "payment.held"
                                      else (HIGH/CRITICAL authorised) → VOIDED ; outbox "payment.voided"
                           loser (0 rows) ──▶ read the row; same hash → mirror cached response ; different hash → 422
```

HTTP status mapping (`PaymentController`): `FAILED/VOIDED` → 200, `PENDING_REVIEW/HELD` → 202,
else 201.

## A.4 The flagged → review → auto-clear flow

```
HELD or PENDING_REVIEW payment ──▶ analyst_reviews row (PENDING, feature snapshot, risk score)
ReviewProcessor @Scheduled(4s) ──▶ for each PENDING (batch 50): ReviewDecider.decide(id)   [own tx]
   eval        = latest risk_evaluations row → RiskFeatures
   priorFraud  = fraud_ground_truth.customerHasConfirmedFraud(customerId)
   pred        = AutoClearClient.predictClearProbability(features, riskScore)   → clear_confidence
   verdict     = ReferenceAnalyst.decide(riskScore, features, priorFraud)       → CLEAR | REJECT
   autoCleared = bucket in {LOW,MEDIUM} AND !priorFraud AND verdict==CLEAR
                 AND pred.isPresent() AND confidence >= AutoClearPolicy.threshold()
   autoCleared → paymentService.resolveReview(true, "AUTO_CLEAR")   → (PENDING_REVIEW→PROCESSING→AUTHORIZED)→CAPTURED
   else        → paymentService.resolveReview(verdict==CLEAR, "REFERENCE_ANALYST")
   always      → write autoclear_decisions{confidence, threshold, auto_cleared, top_features, model_version}
GroundTruthResolver @Scheduled(5s) ──▶ resolve_at passed → actually_fraudulent = injected_fraud
   for each autoclear_decisions row on that payment where auto_cleared:
       later_confirmed_fraud = actually_fraudulent   (TRUE here = a FALSE AUTO-CLEAR)
AutoClearCalibrator @Scheduled(15s) ──▶ any (auto_cleared AND later_confirmed_fraud) →
       policy.ratchetUp(max_bad_confidence + 0.02) ; log ERROR
```

`AnalystController.decide` (`POST /v1/analyst/reviews/{id}/decide`) is the **human** path
(`reviewer_type = "HUMAN"`). It exists but races the 4-second scheduler and is not used by
any automated flow.

## A.5 ml-service

- `main.py` — FastAPI; on startup trains `FinPayModel` (or loads the artifact) and
  `AutoClearModel`; a **daemon thread retrains Auto-Clear every 60 s** from the DB.
- `finpay_model.py` — logistic regression, `StandardScaler` pipeline, trained on
  `synthetic.generate()` (8,000 rows, ~5.5% fraud injected as *patterns*). Live metrics from
  the running stack: `roc_auc ≈ 0.891`, `pr_auc ≈ 0.801`.
- `autoclear_model.py` — logistic regression on **silver labels** pulled from
  `analyst_reviews` where `reviewer_type='REFERENCE_ANALYST'` (`db.fetch_reference_analyst_decisions`,
  which deliberately does **not** select `actually_fraudulent`). Cold-start returns
  `clear_confidence = 0.0` until ≥40 samples & ≥8/class. Live: trained on 501 samples
  (129 clear / 372 reject), train accuracy 0.974.
- `kaggle_model.py` — offline only; **`data/creditcard.csv` not present**, so this endpoint
  reports `dataset_present: false`. Not in the request path (correct, and documented).
- `db.py` — `psycopg` direct to the same Postgres. `autoclear_eval_against_ground_truth()`
  powers `GET /autoclear/evaluation`.

## A.6 Dashboard (complete inventory)

| File | Lines | What it renders |
|---|---|---|
| `main.tsx` | 14 | pathname regex → `<CheckoutPage>` or `<App>` |
| `App.tsx` | 75 | `<Login>` + a 2-button tab bar (`Risk / Auto-Clear`, `Merchant sandbox`) |
| `views/AnalystView.tsx` | 148 | 4-stat KPI grid, "Generate traffic" form, review-queue table (polls every 4 s) |
| `views/MerchantView.tsx` | 181 | API-key box, "create payment link", transactions table (manual Refresh), "direct pay" form, client-only activity log |
| `CheckoutPage.tsx` | 167 | customer-facing hosted checkout (`/pay/{orderId}`), test-card picker, result screen |
| `api.ts` | 42 | `fetch` wrapper, token/apiKey in `localStorage` |
| `styles.css` / `checkout.css` | 3.8 K / 2.4 K | one dark theme + one light checkout theme |

No routing, no data-fetching library, no component library, no charts.

## A.7 Tests (10 classes)

| Test | Profile | Proves |
|---|---|---|
| `IdempotencyConcurrencyTest` | `phase1` | 20 concurrent identical keys → exactly 1 payment; reused key + different body → reject |
| `RefundConcurrencyTest` | `phase1` | 15 concurrent partial refunds → exactly 10 succeed, ledger balanced; double full refund deduped |
| `LedgerReconciliationTest` | `phase1` | balanced after arbitrary capture/refund; `UPDATE`/`DELETE` on `ledger_entries` blocked; double capture rejected |
| `PaymentStateTransitionTest` | (unit) | every illegal `(from,to)` throws, every legal one doesn't; happy + review paths walkable |
| `SettlementTest` | `phase1` | payable → 0, ledger balanced, per-merchant settlement rows |
| `WebhookDeliveryTest` | `phase1` | down endpoint → retries → `DEAD`; recovered endpoint delivers with `verified=true` |
| `CheckoutFlowTest` | `phase1` | link view + pay without auth; unknown order → 404; decline visible |
| `RuleEngineTest` | (unit) | each rule fires on its pattern; score capped at 100 |
| `ReferenceAnalystTest` | (unit) | clears a false positive; rejects high score / prior fraud / velocity / deviation / new-device-new-IP; `decide` has no ground-truth param |
| `AutoClearFlowTest` | `test` (full stack) | adverse pattern → HELD → rejected by reference analyst (not auto-cleared, ML absent); confirmed false auto-clear ratchets the threshold |

**Gap:** every DB test except `AutoClearFlowTest` runs under the `phase1` profile with
`AllowAllRiskGate` — so idempotency, refunds, ledger, webhooks and checkout are **never
tested against the real risk engine**. Tests require a local `finpay_test` Postgres + Redis
(Testcontainers is a dependency but unused).

---

# SECTION B — Product gap analysis

## B.1 Navigation / information architecture

| Concept | Backend | UI | Verdict |
|---|---|---|---|
| Overview / home | `LedgerService.merchantPayableBalance` exists, **not exposed** | none | **MISSING** |
| Payments | `GET /v1/transactions`, `GET /v1/payments/{id}` | 5-column table, 15 rows, manual refresh | **PARTIALLY IMPLEMENTED** |
| Orders | `POST/GET /v1/orders` (no list) | none | **MISSING** (UI) |
| Transactions | `GET /v1/transactions` paginated | table without pagination/filter/search | **PARTIALLY IMPLEMENTED** |
| Refunds | `POST/GET /v1/payments/{id}/refund` (per-payment only) | none | **MISSING** (UI + list endpoint) |
| Customers | `Customer` + `Device` entities, `customer_payment_stats` | none | **MISSING** (API + UI) |
| Settlements | `GET /v1/settlements`, `POST /run` | none | **PARTIALLY IMPLEMENTED** |
| Developers / API keys | key returned once at onboard; `revoked_at` unused | raw text box in `MerchantView` | **MISSING** (lifecycle API + UI) |
| Webhooks | `/v1/webhooks/config` CRUD, demo receiver | none | **PARTIALLY IMPLEMENTED** (no delivery-log endpoint, no UI) |
| Risk / Fraud | `GET /v1/risk/{id}`, `/explain`, analyst queue | `AnalystView` (analyst only) | **PARTIALLY IMPLEMENTED** |
| Analytics | `AutoClearMetricsService`, Prometheus | 4 stat tiles (analyst only) | **MISSING** (merchant), partial (analyst) |
| Settings | — | none | **MISSING** |
| Team / access control | `users.role`, `api_keys.role` | none | **MISSING** |
| "Onboard sandbox merchant" (in `MerchantView`) | calls `POST /v1/admin/merchants` (`SUPER_ADMIN`) | button | **MISLEADING** — only works because you're logged in as admin; a real `MERCHANT_ADMIN` gets 403 |
| `WebhookReceiverController` | permitAll demo sink | — | **UNNECESSARY** in the product surface (fine as a dev tool, but it's a permitAll POST endpoint in the deployed app) |

## B.2 Merchant mental model — can a logged-in merchant answer these?

| Question | Answer today | Why |
|---|---|---|
| How much money is moving? | **No** | no balance/volume endpoint or screen; `merchantPayableBalance` is internal-only |
| How many payments succeeded / failed? | **Barely** | the transaction table shows status per row; no counts, no rates, no time range |
| What payments need my attention? | **No** | `HELD` / `AUTHORIZED` payments are not surfaced as a work queue; `POST /{id}/capture` exists but nothing lists what's capturable |
| Which orders / customers are involved? | **No** | order id is on the payment view but not resolvable; customers have no API |
| What happened to transaction X? | **No** | there is no payment-detail view; `payment_attempts` (the lifecycle log) has no endpoint |
| Why was a payment blocked / flagged? | **Partial** | `GET /v1/risk/{id}` returns `decision + bucket + score` to a merchant (rules/features are analyst-only); nothing in the merchant UI calls it |
| What was refunded? | **Partial** | `GET /v1/payments/{id}/refund` works; no UI, no account-wide refund list |
| What is happening operationally? | **No** | Prometheus only; no in-product health/activity view |

## B.3 What exists only as UI / mock / placeholder

- **`MerchantView` "Activity (this browser tab)"** — pure client state, lost on refresh.
- **"Onboard a sandbox merchant"** — admin endpoint behind a merchant-looking button.
- **Kaggle model** — code complete, dataset absent, never in the path (correctly documented).
- **LLM explanations** — Ollama isn't in the compose stack, so `RiskExplainer` always returns
  the deterministic template; the dashboard "explain" button shows `model: "template"`.
- **`api_keys.revoked_at`, `PLATFORM_FEES` ledger account, `PaymentSimulator.processorFlagged`,
  `PaymentState.CANCELLED`** — all present in the schema/model, all dead (no code path sets or
  reads them).
- **`AutoClearMetricsService.compute(threshold, since)`** — the per-run `since` scoping is
  implemented but the dashboard never passes `since`, so every number is cumulative across
  all runs + warm-ups since the DB was created.

---

# SECTION C — Payment-gateway UX audit (why it doesn't feel serious)

This is a **structural** critique. Colours and spacing are downstream.

## C.1 It reads as a generic admin panel, not a payments platform

| Signal | Current | What a payments platform does |
|---|---|---|
| **Navigation** | 2 buttons (`Risk / Auto-Clear`, `Merchant sandbox`) in a flat top bar | a persistent left rail that *is* the object model — Payments, Orders, Refunds, Disputes, Settlements, Customers, Developers, Risk — so the product's domain is legible before you click anything |
| **Landing** | login → whichever tab your role defaults to | a money-first overview: current balance, next settlement, captured vs refunded volume, success rate, "needs attention" |
| **Primary object** | a 5-column row in a table that isn't clickable | a payment is an addressable entity (`/payments/pay_xxx`) with a canonical detail view |
| **Hierarchy** | every block is the same `.panel` (same bg, same border, same 14px uppercase-grey `<h2>`) | KPIs, work queues, forms and reference tables are visually different weights |
| **Density** | one font size, one row height, generous padding everywhere | operational tables are dense and scannable; detail views are airy |
| **Status language** | pills exist for `LOW/MEDIUM/HIGH/CRITICAL/CAPTURED/FAILED/...` but the vocabulary is mixed (risk buckets and payment states share the pill component and colour space) | payment state, risk bucket, and settlement state are three distinct visual systems |
| **Empty / loading / error** | `"click Refresh to load"`, `"queue empty"`, raw `err.message` in a red `<div>` | first-run guidance, skeletons, typed error states with recovery actions |
| **Search / filter** | none anywhere | every list has time range + status + free-text (payment id, order id, customer, amount) |

## C.2 Transaction / payment detail — the biggest single gap

There is **no payment detail view at all**. The pieces exist but are scattered across
endpoints and tables and never assembled:

| Facet | Where the data is | Retrievable today? |
|---|---|---|
| payment id / order id / amount / method / status / timestamps | `GET /v1/payments/{id}` (`PaymentView`) | **yes** |
| customer | `payments.customer_id` → no customer endpoint | **no** |
| risk score / bucket / decision | `GET /v1/risk/{id}` | yes (merchant: score/bucket/decision only) |
| fraud rules triggered | `risk_evaluations.rule_flags` — analyst-only in `GET /v1/risk/{id}` | analyst only |
| Auto-Clear status | `autoclear_decisions` table | **no endpoint** |
| ledger impact | `ledger_transactions` where `reference_id = paymentId` | **no endpoint** |
| webhook events | `webhook_deliveries` / `outbox_events` | **no endpoint** |
| refunds | `GET /v1/payments/{id}/refund` | yes |
| audit trail | `audit_logs` where `entity_id = paymentId` | **no endpoint** |
| lifecycle / state history | `payment_attempts` where `payment_id = ...` | **no endpoint** |

So **4 of ~10 facets are retrievable and 0 are assembled**. This is the number-one reason
the product feels thin: you cannot inspect a single payment and understand what happened to
it.

## C.3 Per-problem breakdown (current → why weak → what platforms do → correction → when)

1. **No navigation shell / object model.**
   *Current:* two role tabs. *Weak:* the domain (payments, orders, refunds, settlements,
   customers, webhooks) is invisible; nothing is deep-linkable. *Platforms:* a left rail that
   mirrors the API resources. *Correction:* add a router (URL-addressable entities) + a nav
   shell whose items map 1:1 to resources that actually have endpoints. *When:* **now**
   (structural; unblocks detail views).

2. **No payment-detail view.**
   *Current:* rows aren't clickable. *Weak:* can't answer "what happened to this payment."
   *Platforms:* list → entity → tabbed facets (overview / risk / ledger / webhooks / refunds
   / timeline). *Correction:* 5 read endpoints (§H) + one detail component. *When:* **now**.

3. **No merchant overview.**
   *Current:* land on a form. *Weak:* no sense of money movement or what needs action.
   *Platforms:* balance + volume + success rate + attention queue. *Correction:* expose
   `merchantPayableBalance` + a `GET /v1/analytics/summary`; add an overview screen. *When:*
   **now** (product), visual polish **deferred**.

4. **Mixed status vocabulary / one panel style.**
   *Current:* `.panel` for everything; pills share a colour space across risk & state.
   *Weak:* no visual priority; a KPI looks like a form. *Platforms:* distinct treatments for
   KPI / queue / table / form; separate chip systems. *Correction:* define 3 chip families
   (payment state, risk bucket, settlement) + component roles. *When:* **defer** to design
   phase (but decide the taxonomy now).

5. **No list ergonomics (filter / search / pagination / sort).**
   *Current:* fixed 15 rows, manual refresh. *Weak:* unusable at real volume (my test run
   created 1,712 payments). *Platforms:* server-driven filter + cursor pagination on every
   list. *Correction:* extend `GET /v1/transactions` params, add the controls. *When:*
   **now** (functional), styling **deferred**.

6. **Empty / loading / error states are ad-hoc.**
   *Current:* literal strings, raw error messages. *Weak:* looks unfinished, leaks
   internals. *Platforms:* consistent skeletons + typed empty/error components. *When:*
   **defer** (but stop rendering raw `err.message` — small now).

7. **Analyst console conflates "auto-decided" with "needs a human."**
   *Current:* the queue table mixes reference-analyst auto-decisions, auto-clears and the
   (unused) human buttons; the KPI "sent to an analyst" counts rule-engine output. *Weak:*
   the USP story is muddled — you can't see "work removed from humans" because there's no
   human baseline. *Correction:* split *pending human queue* from *decision log*; relabel;
   scope metrics per simulation run. *When:* **now** (this is the USP surface).

---

# SECTION D — Auto-Clear USP audit (most important)

## D.1 Does the code demonstrate "safely auto-clear high-confidence legitimate flagged transactions"?

**Mechanically: yes.** **Convincingly: no.** Details below.

### A. Ground truth — exists, but the label is not tied to signal

- `fraud_ground_truth` table (V3): `actually_fraudulent` (NULL until resolved), `fraud_type`,
  `injected_fraud`, `resolve_at`, `resolved`. Hidden — no merchant-facing endpoint reads it;
  `RiskController` explicitly never serves it. **This part is well designed.**
- Resolution is delayed (`created_at + 60 s`, `GroundTruthResolver`), mimicking a chargeback
  window. Confirmed fraud is embedded into pgvector. **Also good.**
- **The problem:** the label is planted as
  `PaymentService.plantGroundTruth` → `injected = "tok_fraud".equals(p.getPaymentToken())`.
  It has **nothing to do with the transaction's features**. In `SimulationService` this is
  masked because fraud is always dealt as `tok_fraud` *after* building an adverse pattern
  (velocity burst / high value / deviation / failed-attempt burst). But `tok_fraud` is a
  **selectable option in the dashboard "Direct pay" form and in the customer checkout page**
  (`CheckoutPage.tsx` → "Test card •••• 9995 (flagged as suspicious)").

- **Demonstrated false auto-clear (I ran this against the live stack):**
  1. Onboarded a merchant, built a normal history for one customer (4× `tok_valid`, ₹500,
     same device `clean-dev-1`, same IP `55.55.55.55`).
  2. Paid once more with `token = tok_fraud`, amount ₹520, **same device, same IP, same
     customer**.
  3. Result: `risk_score = 14.12`, bucket `LOW`, status `HELD` → queued for review.
  4. `ReviewDecider` (Auto-Clear model already trained from the earlier sim): reference
     analyst says CLEAR (no adverse pattern), ML `clear_confidence = 0.960 ≥ 0.80` →
     **auto-cleared → CAPTURED** (₹520 moved to `MERCHANT_PAYABLE` in the ledger).
  5. 60 s later `GroundTruthResolver` set `actually_fraudulent = true` →
     `autoclear_decisions.later_confirmed_fraud = true` → **false auto-clear #1**.
  6. `AutoClearCalibrator` ratcheted the threshold **0.80 → 0.980** and logged `ERROR`.

  The "zero false auto-clears" guarantee broke on the **first** deliberately-clean fraudulent
  payment through the normal UI. The calibrator "works", but only *after* the money is
  captured (and, once a settlement batch runs, paid out).

  **Fix direction:** the scenario/simulator must own the label — `injected_fraud` should be
  a property of a generated scenario row that *also* determines the feature pattern, and
  `tok_fraud` should either be restricted to the simulation harness or force an adverse
  feature pattern when used.

### B. Analyst simulation — partial

- `ReferenceAnalyst.decide(riskScore, features, priorFraud) → CLEAR | REJECT` is
  deterministic, pattern-aware, never sees `actually_fraudulent` (compile-time-checked by a
  test). Its verdict **is persisted**: `analyst_reviews.decision`, `reviewer_type =
  'REFERENCE_ANALYST'`, and it's the training signal in `db.fetch_reference_analyst_decisions`.
- **A human review path exists but never runs.** `POST /v1/analyst/reviews/{id}/decide`
  writes `reviewer_type = 'HUMAN'`, but `ReviewProcessor` drains the queue every 4 s and
  `ReviewDecider` auto-decides every item. There is no assignment, no claim, no SLA, no
  "hold for human." In my 1,000-txn run: **501 reference-analyst decisions, 0 human
  decisions.**
- **The reference analyst is a weak oracle.** In that run it saw **392 legitimate flagged
  payments** and **REJECTED 192 of them (49%)** — i.e. it voids real customer money on half
  of all false positives. A silver-label source that is wrong 49% of the time on the
  positive class makes the Auto-Clear model both conservative and hard to evaluate.

### C. Fraud detection vs Auto-Clear prediction — genuinely separated ✅

This distinction **is** reflected in the code, cleanly:

| | Fraud model | Auto-Clear model |
|---|---|---|
| Question | "is this suspicious?" | "would the reference analyst clear this flagged txn?" |
| Endpoint | `POST /predict` | `POST /autoclear/predict` |
| Artifact | `finpay_model.joblib` | `autoclear_model.joblib` |
| Training data | synthetic transactions w/ injected patterns | `analyst_reviews` silver labels (**no** `actually_fraudulent` — verified in `db.py` + a test) |
| Extra feature | — | `risk_score` |

`ReviewDecider` requires **all four** gates: `bucket ∈ {LOW, MEDIUM}` ∧ no prior confirmed
fraud ∧ reference analyst concurs ∧ `confidence ≥ calibrated threshold`. Solid multi-gate
design.

**But in practice the reference-analyst hard gate does all the safety work** and the ML
threshold is a marginal filter — and because the simulator's fraud always trips *both* a
high bucket *and* a reference rule, **0 of 180 injected-fraud transactions ever reached
auto-clear eligibility** in my run. The ML gate's "zero false" is never stress-tested by the
simulator.

### D. Safety constraint — measured, but post-hoc and cumulative

- `autoclear_decisions.later_confirmed_fraud`, reconciled by `GroundTruthResolver`.
- `AutoClearCalibrator` ratchets the threshold up (never down), logs `ERROR`, persists to
  `system_config`.
- Prometheus `finpay_autoclear_false_clears_total`; dashboard "false auto-clears" tile
  (turns red if > 0).
- **Weaknesses:** (1) enforcement is *after capture* — for the LOW-bucket auto-capture path
  and the auto-clear path, funds are already in `MERCHANT_PAYABLE` and a settlement batch
  will pay them out before the 60 s ground-truth window necessarily closes; (2) the counter
  and the ratcheted threshold are **cumulative forever** — my one test left the dashboard
  showing `1` false auto-clear and threshold `0.98048` permanently, with no reset; (3) it's
  never exercised because the sim can't produce "fraud that looks legit."

### E. Metrics — what exists vs what's claimed

| Metric | Exists? | Notes |
|---|---|---|
| total transactions | **no** | `SimSummary.total` is returned by `/simulate` but not shown; `Metrics` has no field |
| flagged transactions | yes | `flaggedTotal` = `count(DISTINCT payment_id)` in `analyst_reviews` |
| manually reviewed | **misleading** | `referenceReviewed` shown as "sent to an analyst" — it's rule-engine output |
| auto-cleared | yes | `autoCleared` |
| auto-clear rate | yes | `autoCleared / decided` |
| manual review reduction | **duplicate** | `AutoClearMetricsService` returns `round(autoClearRate), round(autoClearRate)` — the *same value twice* (`manualReviewReductionPct == autoClearRatePct`) |
| false-positive rate | **no** | ground truth isn't joined into the merchant/analyst metric at all |
| false auto-clear count | yes | `falseAutoClears` |
| fraud caught | **no** | |
| precision / recall | **no** (for the live pipeline) | only reported for the *fraud model on its synthetic test set*, in `/models` |

Live numbers from a fresh `count:1000, fraudRate:0.12, seed:42` run:

```
flaggedTotal      572      (of 1,712 payments actually created — see §E on warm-ups)
decided           572
autoCleared        71
referenceReviewed 501
autoClearRatePct   12.41
manualReviewReductionPct  12.41   ← identical, by construction
falseAutoClears     0            ← structural: 0 of 180 injected-fraud txns were auto-clear-eligible
threshold           0.80
```

Ground-truth split of the review population (my run):

| | entered review | auto-cleared | reference CLEAR | rejected |
|---|---|---|---|---|
| legitimate (`injected_fraud = false`) | 392 | 71 | 129 | **192** |
| injected fraud (`injected_fraud = true`) | 180 | **0** | 0 | 180 |

## D.2 Verdict

The **plumbing** for the USP is all there and is a legitimate design. What's missing to make
it *convincing*:

1. Ground truth that is a property of the generated scenario, not of a token string.
2. A scenario generator that produces **fraud that looks legitimate** and **legit that looks
   fraudulent** as separate, tunable streams — so "zero false auto-clear" is *earned* under
   pressure, not a side effect of perfectly separable classes.
3. A real human-review baseline so "manual review reduction" is a true comparison.
4. A better reference analyst (or an explicit acknowledgement of its error rate) so the
   silver labels mean something.
5. Per-run metrics (precision, recall, FP rate, review reduction, fraud caught, false
   auto-clears) with the `since` scoping the code already supports, plus a reset.
6. Pre-settlement holdback on auto-cleared funds until the ground-truth window closes, so a
   detected false auto-clear is recoverable.

---

# SECTION E — Simulation audit

## E.1 Can we generate the scenarios we need?

| Scenario | Supported? | How |
|---|---|---|
| successful payment | ✅ | `tok_valid` |
| failed / declined payment | ✅ | `tok_declined`, `tok_insufficient_funds`, `tok_expired` |
| pending payment | ⚠️ | no explicit "stay pending"; `RISK_EVALUATING`/`PROCESSING` are transient |
| authorization (without capture) | ✅ | `HELD` and `AUTHORIZED` states are reachable |
| capture | ⚠️ | auto only at `finalScore ≤ 6`; manual via `POST /{id}/capture`; no delayed-capture demo |
| cancellation | ❌ | `CANCELLED` state exists, **no endpoint transitions to it** |
| partial / full refund | ✅ | `POST /v1/payments/{id}/refund` (+ tests) |
| fraud | ⚠️ | only as 4 pre-baked patterns in `SimulationService.fraudTxn`; not composable |
| false positive | ⚠️ | emerges as a side effect (legit txns that trip rules); **not controllable** |
| high-risk transaction | ⚠️ | via the fraud patterns; no "risk level" knob |
| suspicious velocity | ✅ (generator) | `fraudTxn` pattern 0 (7 warm-ups + burst); not a user knob |
| unusual amount | ✅ (generator) | patterns 1 & 2 |
| new device | ✅ (generator) | patterns 1 & 3 |
| repeated failures | ✅ (generator) | pattern 3 (`tok_declined` burst) |
| multiple accounts on one device | ✅ | `synthetic.py` `device_ring`; Redis `distinctAccountsForDevice` |

## E.2 Are the scenarios deterministic and reproducible?

**Partially.** `SimulationService.run(count, fraudRate, seed)` threads `seed` into
`new Random(seed)` for the generation choices. But **everything downstream is un-seeded:**

- **Redis state persists across runs.** `resetVelocity` clears the velocity/failed ZSETs,
  but the **IP-seen sets** (`finpay:ip:<customer>`) and **device→accounts sets**
  (`finpay:dev:<fp>`) are 30-day TTL and never reset — run 2 sees run 1's devices/IPs.
- **Wall-clock everywhere.** Velocity windows use `System.currentTimeMillis()`;
  `resolve_at = now + 60 s`. Results depend on *when* you read them.
- **The Auto-Clear model retrains every 60 s** from whatever is in the DB — same seed,
  different model depending on timing and accumulated history.
- **Metrics are cumulative and never reset.** Each run adds to `analyst_reviews`,
  `autoclear_decisions`, `fraud_ground_truth`; the dashboard shows the running total.
- **Warm-up transactions inflate everything.** For `count:1000` the harness also creates
  `3 × poolSize` normal warm-ups + `7–8` per fraud session — my run produced **1,712
  payments for a "1,000" request**, all scored, captured and counted in `flaggedTotal`.
- **A confirmed fraud on a simulated customer poisons that customer** for the rest of the
  run (`priorFraud` gate) — mitigated by a fresh customer pool per run, but pool refs are
  `sim-<seed>-cust-<i>` so re-running the same seed reuses them.

## E.3 How the simulator *should* be structured

A **scenario spec** object, not positional args:

```
SimulationScenario {
  seed
  window:            { durationMinutes, txnsPerMinute }
  legit:             { count, amountDistribution, deviceReuse, ipReuse }
  falsePositives:    { rate }            // legit txns deliberately given fraud-shaped features
  trueFraud:         { rate, patterns: [VELOCITY, NEW_DEVICE_HIGH_VALUE, DEVIATION, FAILED_BURST, RING],
                       stealthRate }     // fraction of fraud crafted to look legitimate
  cohorts:           { highRiskMerchants[], highRiskCustomers[] }
  attacks:           [ { type: VELOCITY_ATTACK, atMinute, fromDevice, count } ]
}
```

Requirements:

- **Ground truth is authored by the scenario.** Each generated txn carries its intended
  `isFraud` + `fraudType`; the feature pattern is derived from that, and `tok_fraud` is
  reserved for the harness. `stealthRate` explicitly creates fraud with near-legit features
  — that's the population that tests "zero false auto-clear."
- **Deterministic downstream.** Run the sim against an isolated feature store (in-memory,
  seeded) or namespace every Redis key by run id and flush it at the end; make the
  ground-truth window either instantaneous-on-demand (`POST /simulate/{run}/resolve`) or
  virtual-clock.
- **Isolation + provenance.** A `simulation_runs` row; every payment/review/decision tagged
  with `run_id`; warm-ups tagged and excluded from metrics; `GET /v1/analyst/metrics/autoclear?run=<id>`.
- **Reset.** `POST /v1/admin/simulate/reset` truncates sim data + re-seeds `system_config`.

---

# SECTION F — Data / financial correctness

## F.1 Solid (verified in code and/or tests)

- **Payment state machine** — `EnumMap` of allowed transitions + `assertCanTransitionTo`
  called on every write; `PaymentStateTransitionTest` is exhaustive over all `(from, to)`.
- **Idempotency race** — `INSERT ... ON CONFLICT (idem_key) DO NOTHING`; loser blocks on the
  unique index then mirrors the winner's cached response; `IdempotencyConcurrencyTest`
  (20-way) passes.
- **Concurrent refunds** — `PaymentRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`)
  serialises them; `succeededTotal + amount ≤ capturedAmount` can't be raced;
  `RefundConcurrencyTest` proves exactly-10-of-15 and a balanced ledger.
- **Ledger append-only** — DB trigger `finpay_block_mutation` rejects `UPDATE`/`DELETE` on
  `ledger_entries`; tested.
- **Ledger balance** — `assertBalanced` per journal tx + `reconcile()` global check; tested
  after arbitrary capture/refund sequences and after settlement.
- **Outbox atomicity** — `OutboxService.publish` is `Propagation.MANDATORY`, so the event
  and the state change share a transaction.
- **Double-capture / double-ledger-post** — `LedgerService.postCapture` refuses if a
  `PAYMENT`-referenced tx already exists; `capture()` refuses non-`AUTHORIZED/HELD` payments.

## F.2 Bugs / correctness gaps

### F2.1 — Idempotency key is global, not scoped to `(merchant, endpoint)` — **P0**

`tryClaim` conflicts on `idem_key` alone (`V1`: `UNIQUE (idem_key)`). `merchant_id` and
`endpoint` are stored but not part of the key. Consequences:

- Two merchants that both send `Idempotency-Key: order-42` collide: the second either gets
  the first merchant's cached `PaymentView` (data leak / wrong response) or a 422.
- `POST /v1/payments` and `POST /v1/checkout/{id}/pay` share the store with different
  `endpoint` values but the same key space.

Real gateways scope idempotency by `(merchant_id, key)`. Fix: composite unique
`(merchant_id, idem_key)` (or `(merchant_id, endpoint, idem_key)`) + matching `ON CONFLICT`.

### F2.2 — No lock on the order in `createPayment` → double payment for one order — **P0**

`createPayment` does `orders.findByIdAndMerchantId(...)` then `if (!"CREATED".equals(status))`.
Two requests with **different** idempotency keys against the same `CREATED` order both pass
the check and both create a `Payment`. Only `doCapture` → `orderService.markPaid` flips the
order, and only for the one that captures; the other payment is orphaned in `HELD`/`AUTHORIZED`
against a now-`PAID` order. Fix: `SELECT ... FOR UPDATE` the order row (or a unique partial
index `payments(order_id) WHERE status NOT IN (terminal…)`).

### F2.3 — Refund (and all `REFUND`-aggregate) webhooks are never delivered — **P0**

`WebhookDispatcher.fanOut` derives the target merchant from `payload.merchantId`
(`merchantIdOf` reads `node.get("merchantId")`). `RefundService`'s `refund.succeeded` payload
is `{refundId, paymentId, amountMinor, currency, fullyRefunded}` — **no `merchantId`** — so
`merchantIdOf` returns `null`, no deliveries are created, and the outbox row is still marked
`PUBLISHED`. `payment.*`, `risk.flagged` and `settlement.paid` payloads all include
`merchantId` and are fine. Fix: add `merchantId` to the refund payload (and add a test that
asserts a delivery row is created per event type).

### F2.4 — A failed attempt leaves no trace; `FAILED` was unreachable — **P1**

> **Corrected 2026-09-10 after verifying against a running suite.** The original wording of
> this finding claimed the row "stays `PROCESSING`" and returns "409 forever". That is not
> what happens, and the code comment at `IdempotencyService:74` that asserted it was also
> wrong. Recorded here rather than quietly edited, because the difference matters.

`IdempotencyService.run` was `@Transactional` and `tryClaim` joined that transaction. When
`work.get()` threw, the whole transaction rolled back — **including the claim row**. So the
row did not persist as `PROCESSING`; it vanished entirely, and a retry with the same key
re-claimed and re-ran. The practical behaviour was therefore *correct*, and less severe than
originally reported.

What was genuinely wrong:

- `IdempotencyKey.Status.FAILED` was **never written by any code path**, making both the
  `case FAILED ->` branch and the `STALE_AFTER` staleness check dead code — the `FAILED`
  branch told callers to "use a new key", which is the wrong answer anyway (see below).
- A failure left **no record at all**, so nothing could distinguish "this key failed" from
  "this key has never been seen".
- The comment in the source documented behaviour the code did not have.

**Fixed.** `run` now owns its transaction programmatically so the ordering can be expressed:
roll back first, *then* write a `FAILED` marker from a separate transaction. Doing it the
obvious way — a `REQUIRES_NEW` write from inside the failing transaction — self-deadlocks,
because the new transaction waits on the old one's uncommitted insert of the same key while
the old one waits for the call to return. On a later retry the marker is taken over
(`FAILED → PROCESSING`) and the work runs again: an attempt that rolled back had no effect,
so the same key *must* be retryable. Telling the client to pick a new key fails them in
exactly the situation idempotency keys exist for — an ambiguous outcome where they cannot
know whether the original applied.

### F2.5 — Checkout auto-generates an idempotency key → not idempotent for the payer — **P1**

`CheckoutController.pay`: `key = (idemKey == null || blank) ? "checkout-auto-" + UUID.randomUUID() : idemKey`.
The dashboard's `CheckoutPage` *does* send a random `Idempotency-Key` per click, but a
double-submit / retry from the browser generates a **new** UUID each time → two payments for
one checkout. Fix: derive a stable key from the order id (+ a client nonce), or require the
header.

### F2.6 — `CUSTOMER_CLEARING` has no funding side → economically incomplete — **P2**

Capture posts `DEBIT CUSTOMER_CLEARING / CREDIT MERCHANT_PAYABLE`. Nothing ever credits
`CUSTOMER_CLEARING` (there's no "customer paid in" / PSP-inbound entry), so its balance is
`-(total captured)` forever. `reconcile()`'s global `SUM(debit) == SUM(credit)` still holds
because every journal is internally balanced, but no account represents "cash the platform
holds," and `PLATFORM_FEES` is never touched. Acceptable for an MVP but should be stated;
ideally add a customer/PSP source account so the model closes.

### F2.7 — Audit coverage is partial and unreadable — **P2**

`AuditService.record` is called for capture, refund, onboard, settlement. **Not** for:
payment created/failed/held/voided, risk decision, or **any review decision** (analyst
CLEAR/REJECT and auto-clears write only the `analyst_reviews` / `autoclear_decisions` rows,
no `audit_logs` entry). There is **no endpoint** to read `audit_logs` at all.

### F2.8 — Frontend vs backend truth

Mostly fine — `AnalystView` and `MerchantView` read real endpoints. Two caveats: (1) the
analyst KPIs are cumulative across every sim run + warm-ups and are presented as a result;
(2) `MerchantView`'s "Activity" table is browser-tab-local state, not backend-derived.

---

# SECTION G — UX / IA recommendations (structural only — no visual redesign)

1. **Add a router and make entities URL-addressable** (`/payments/:id`, `/orders/:id`,
   `/settlements/:id`). Prerequisite for everything else.
2. **Introduce a navigation shell that mirrors the resource model** — only include items
   that have (or will get in this phase) real endpoints: Overview, Payments, Orders, Refunds,
   Settlements, Customers, Developers (API keys + Webhooks), Risk. Drop the two-role tab
   switch; drive the visible nav from the caller's role.
3. **Build one canonical Payment Detail view** with tabbed facets (Overview / Risk / Ledger /
   Webhooks / Refunds / Timeline). Requires the read endpoints in §H.
4. **Merchant Overview**: balance (expose `merchantPayableBalance`), captured vs refunded
   volume, success/failure counts over a time range, and a **"needs attention"** list
   (`HELD` / `AUTHORIZED` / `PENDING_REVIEW`). Numbers from a new `GET /v1/analytics/summary`.
5. **Developers area**: API-key lifecycle (list / create / reveal-once / revoke — needs
   endpoints; `revoked_at` already in the schema), webhook endpoints + a **delivery log**
   (`webhook_deliveries` per endpoint / per payment), and an event-type catalogue.
6. **Analyst console**: separate the **pending-human queue** from the **auto-decision log**;
   stop labelling reference-analyst output "sent to an analyst"; add a **run selector** and
   use the `?since=` / `?run=` scoping the backend already supports; show the full metric set
   (§D-E) including precision / recall / FP-rate / fraud-caught.
7. **One status-chip system per concept** (payment state ≠ risk bucket ≠ settlement state).
   Decide the taxonomy now; colour it in the design phase.
8. **Standardise empty / loading / error states**; stop rendering raw `err.message`.
9. **Defer to the design phase:** typography scale, spacing system, panel hierarchy, density,
   colour, responsive behaviour, the component library.

---

# SECTION H — Prioritized backlog

### P0 — fundamentally broken (correctness / the USP's core claim)

| # | Finding | Evidence |
|---|---|---|
| P0-1 | Ground-truth `actually_fraudulent` planted purely from `token == "tok_fraud"`, decoupled from signal → **false auto-clear reachable through the normal UI** (demonstrated) | `PaymentService.plantGroundTruth`; `CheckoutPage.tsx` test-card list; live repro in §D-A |
| P0-2 | Idempotency key not scoped to `(merchant, endpoint)` → cross-merchant / cross-endpoint collisions | `IdempotencyKeyRepository.tryClaim`, `V1` `UNIQUE (idem_key)` |
| P0-3 | No lock on the order row in `createPayment` → two payments for one order | `PaymentService.createPayment` L89-93 |
| P0-4 | `refund.succeeded` (and all `REFUND` events) never delivered — payload has no `merchantId` | `RefundService.refund` payload vs `WebhookDispatcher.merchantIdOf` |
| P0-5 | A failed attempt leaves no trace — `FAILED` unreachable, its branch and the staleness check dead code (**severity corrected**, see F2.4) | `IdempotencyService.run` catch block |

### P1 — product / flow problem

| # | Finding |
|---|---|
| P1-1 | No human-in-the-loop: `ReviewProcessor` auto-decides the whole queue in 4 s; the `HUMAN` decide endpoint never fires in practice |
| P1-2 | `manualReviewReductionPct` is a literal copy of `autoClearRatePct` (`AutoClearMetricsService` returns the value twice) — there is no baseline it's reduced *from* |
| P1-3 | Reference analyst rejected **49% of legitimate flagged transactions** in a standard run → weak silver-label oracle |
| P1-4 | "Zero false auto-clears" is structural, not earned — 0/180 injected-fraud txns were ever auto-clear-eligible; the simulator can't produce stealth fraud |
| P1-5 | Metrics cumulative across all runs + warm-ups, never reset, `?since` scoping unused by the UI |
| P1-6 | Simulator not reproducible — un-seeded Redis IP/device sets, wall-clock windows, 60 s model retrain, warm-ups inflate counts (1,000 → 1,712 payments) |
| P1-7 | Checkout auto-generates an idempotency key → a payer double-submit creates two payments |
| P1-8 | No payment-detail view + 5 read endpoints missing (timeline, ledger-by-payment, webhook-deliveries, autoclear-decision, audit-by-entity) |
| P1-9 | No API-key lifecycle (list / rotate / revoke) — a merchant can't self-serve credentials |
| P1-10 | No UI for Orders / Customers / Refunds (account-wide) / Settlements / Webhooks |

### P2 — important UX problem

| # | Finding |
|---|---|
| P2-1 | No merchant overview — no balance, volume, success rate, or attention queue |
| P2-2 | No filter / search / pagination / sort on the transactions list (unusable at real volume) |
| P2-3 | LLM explanations always fall back to template (Ollama not in the stack) |
| P2-4 | `audit_logs` has no read endpoint; analyst decisions aren't audited at all |
| P2-5 | "Onboard a sandbox merchant" in `MerchantView` calls a `SUPER_ADMIN` endpoint |
| P2-6 | Raw `err.message` rendered to users; ad-hoc empty/loading strings |
| P2-7 | `CUSTOMER_CLEARING` unfunded; `PLATFORM_FEES` never used |

### P3 — visual polish

Typography scale, spacing system, panel/KPI hierarchy, chip taxonomy & colour, hover/active
states, responsive layout.

### DEFERRED — design-phase work

Full visual system, navigation shell styling, dark/light theming, table density, component
library, motion, the checkout page's visual brand.

---

# SECTION I — Proposed next build (exact sequence)

> Order = product correctness → payment-flow correctness → risk/Auto-Clear correctness →
> information architecture → UX → visual polish. Each phase ends with tests.

### Phase 1 — Correctness fixes (P0), ~small

1. **Idempotency scoping** — composite unique `(merchant_id, idem_key)` (migration V10) +
   `ON CONFLICT (merchant_id, idem_key)`; add a test: same key, two merchants → two payments.
2. **Order lock** — `SELECT ... FOR UPDATE` on the order in `createPayment`; test:
   concurrent payments on one order → exactly one non-terminal payment.
3. **Idempotency failure recovery** — on exception, mark the row `FAILED` in a
   `REQUIRES_NEW` tx (or delete the claim); test: failing work → retry with same key
   succeeds.
4. **Refund webhook payload** — add `merchantId`; test: `refund.succeeded` creates a
   `webhook_deliveries` row and delivers `verified=true`.
5. **Ground-truth from scenario** — `injected_fraud` becomes a field the sim writes based on
   the generated scenario; `tok_fraud` restricted to the sim harness (or forces an adverse
   feature pattern); test: a "stealth fraud" scenario row is labelled fraud regardless of
   token.

### Phase 2 — Controllable scenario simulator

6. `SimulationScenario` spec (§E.3) + `simulation_runs` table (migration V11); every
   payment / review / decision tagged `run_id`; warm-ups flagged and excluded from metrics.
7. Deterministic execution — namespace Redis keys by `run_id` and flush on completion (or a
   seeded in-memory `FeatureStore` for sim); on-demand ground-truth resolution
   (`POST /v1/admin/simulate/{run}/resolve`).
8. `POST /v1/admin/simulate/reset` (truncate sim data, re-seed `system_config`).
9. Separate tunable streams: `legit`, `falsePositives.rate`, `trueFraud.rate`,
   `trueFraud.stealthRate`, `attacks[]`, `cohorts`.

### Phase 3 — Real fraud-review loop

10. Persistent human queue: `PENDING → CLAIMED(analyst, at) → DECIDED`; a
    `POST /reviews/{id}/claim`; the scheduler only auto-decides items **not claimed** and
    only after a configurable grace period (or only the auto-clear-eligible subset).
11. Baseline semantics: "all flagged → human" is the denominator; **review reduction =
    auto-cleared / flagged-eligible** (a distinct number from auto-clear rate).
12. Improve `ReferenceAnalyst` (tighten the reject rules against the FP population) **or**
    surface its measured error rate as a first-class caveat in the metrics.

### Phase 4 — Auto-Clear made honest

13. Scenario generator emits stealth fraud + fraud-shaped legit; the auto-clear evaluation
    runs specifically over the stealth-fraud + FP population.
14. Per-run metrics: total, flagged, auto-cleared, review reduction, FP rate, fraud caught,
    precision, recall, **false auto-clears** — all scoped by `run_id`, all on the dashboard
    with a run selector.
15. Pre-settlement holdback: auto-cleared / low-risk-auto-captured funds are not swept by a
    settlement batch until the payment's ground-truth window has closed (or cap the
    outstanding auto-cleared exposure).
16. Calibration + threshold scoped per run (or resettable) so a demo starts clean.

### Phase 5 — Read endpoints + Payment Detail

17. `GET /v1/payments/{id}/timeline` (from `payment_attempts`),
    `GET /v1/payments/{id}/ledger` (journal txns by `reference_id`),
    `GET /v1/payments/{id}/webhooks` (deliveries), `GET /v1/payments/{id}/autoclear`,
    `GET /v1/audit?entityType=PAYMENT&entityId=...`.
18. One `PaymentDetail` component assembling all facets (behind the new router).

### Phase 6 — Merchant surface

19. `GET /v1/analytics/summary` (balance, volume, counts, attention queue); Overview screen.
20. API-key lifecycle endpoints + Developers screen; webhook endpoints + delivery-log
    screen.
21. Orders / Refunds / Settlements / Customers list screens (add the missing list
    endpoints).

### Phase 7 — Metrics surface & polish

22. Analyst console split (pending-human queue vs decision log), run selector, full metric
    set, fraud-caught + precision/recall.
23. Filter / search / pagination on all lists; standardised empty/loading/error components.

### Phase 8 — Dedicated design pass

24. Navigation shell, visual system (typography, spacing, colour, chip taxonomy), states,
    responsive, checkout brand. **Only after 1–7 land.**

---

## Appendix — commands used for the live checks

```bash
# full stack
cd /Users/priyanshagarwal/finPay && docker compose up --build -d

# 1,000-txn simulation (seed 42, 12% fraud)
TOKEN=$(curl -s localhost:8080/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@finpay.local","password":"admin12345"}' | jq -r .token)
curl -s localhost:8080/v1/admin/simulate -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"count":1000,"fraudRate":0.12,"seed":42}'
curl -s localhost:8080/v1/analyst/metrics/autoclear -H "Authorization: Bearer $TOKEN"
curl -s localhost:9000/models ; curl -s localhost:9000/autoclear/evaluation

# ground-truth / auto-clear reconciliation
docker compose exec postgres psql -U finpay -d finpay -c "SELECT ... FROM autoclear_decisions ..."
```

The false auto-clear repro (§D-A) is reproducible: build a clean history for one customer,
then submit one `tok_fraud` payment for a normal amount on the same device/IP.
