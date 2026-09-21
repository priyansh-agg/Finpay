# FinPay — Complete Project Reference

A single document explaining what this project is, how every part works, how it was built, what
is real, and what is simulated. Written 2026-09-21, against commit `42cc8ce` on branch
`interview-sprint/week-1-correctness`.

Other documents, and what each is for:

| Document | Purpose |
|---|---|
| [`README.md`](../README.md) | Short public-facing overview and quick start |
| [`architecture-freeze.md`](architecture-freeze.md) | The six foundational design decisions, locked before building ("Doubts 1–6") |
| [`PHASES.md`](PHASES.md) | The original three-phase build plan and its exit criteria. **Historical** — its Phase-3 numbers predate the audit; see §7.4 |
| [`AUDIT.md`](AUDIT.md) | A full code + live-system audit, plus a dated remediation log of every fix |
| [`DESIGN.md`](DESIGN.md) | The UI design audit and a per-round redesign log |
| **This file** | Everything, tied together |

---

## 1. What FinPay is

A sandboxed payment gateway in the style of Razorpay or Cashfree, with a fraud-detection layer
on top. Merchants create orders; customers pay through a simulated processor; money moves
through a real double-entry ledger; and **every transaction is risk-scored before the processor
is ever called**.

The headline feature ("the USP") is **Auto-Clear**: most fraud flags are false positives, and
clearing them by hand burns analyst hours. FinPay adds a second layer that safely releases the
flagged transactions that are almost certainly legitimate, and — this is the part that matters —
measures itself honestly against a hidden ground truth that is specifically constructed so the
engine *can* be caught being wrong.

**Honest scope.** This is a learning and interview project, not a production gateway. Real
processor integration, PCI-DSS card handling, multi-currency settlement, disputes/chargebacks,
Kafka, Kubernetes and horizontal scaling are all deliberately out of scope. Analyst decisions and
fraud outcomes are simulated. See §12 for the full limitations list.

**Scale of the codebase:** ~8,400 lines of gateway Java, ~3,200 of gateway tests, ~2,000 of
dashboard TypeScript/CSS, ~570 of Python ML service. 18 database migrations, 47 commits,
102 automated tests.

---

## 2. Architecture

### 2.1 Shape

A **modular monolith**: one Spring Boot service with enforced module boundaries, plus a small
Python ML sidecar and a React dashboard. The choice is deliberate — with a two-person team, the
operational cost of Kafka and Kubernetes would have come out of correctness, so that budget went
into idempotency and ledger integrity instead.

```
                    ┌──────────── gateway (Spring Boot, modular monolith) ─────────────┐
 merchant ─X-API-Key▶│  auth ▸ rate-limit (Redis token bucket) ▸ router                 │
 dashboard ─JWT─────▶│                                                                 │
                    │  POST /v1/payments ─▶ IdempotencyService (INSERT..ON CONFLICT)    │
                    │      └▶ PaymentService: state machine ─▶ RiskEngine (SYNCHRONOUS) │
                    │             rules + ML + pgvector ─▶ ALLOW / REVIEW / BLOCK       │
                    │          ALLOW ─▶ simulator ─▶ AUTHORIZED                         │
                    │             score ≤ 6 → auto-capture   LOW/MED → HELD             │
                    │             HIGH → PENDING_REVIEW      CRITICAL → FAILED          │
                    │          capture/refund ─▶ double-entry ledger (append-only)      │
                    │          every state change ─▶ outbox_events (same transaction)   │
                    └───┬──────────────────┬─────────────────────┬────────────────────┘
                        │ poll             │ /predict            │ poll
              ┌─────────▼────────┐ ┌───────▼─────────┐ ┌─────────▼──────────┐
              │ WebhookDispatcher│ │ ml-service      │ │ ReviewProcessor    │
              │ HMAC sign, retry │ │ FinPay model    │ │ Auto-Clear Engine  │
              │ backoff, DEAD    │ │ Auto-Clear model│ │ + calibration loop │
              └──────────────────┘ │ Kaggle (offline)│ └────────────────────┘
                                   └─────────────────┘
   Postgres (+pgvector) · Redis · Ollama (optional) · GroundTruthResolver (delayed labels)
   · SettlementService (holdback + payout)
```

### 2.2 Stack

| Layer | Choice | Notes |
|---|---|---|
| Gateway | Spring Boot 3.4 (Java 21), Spring Security, Spring Data JPA | JDK 21 pinned via Maven toolchain |
| Database | PostgreSQL 16 + `pgvector` | Flyway owns the schema (`ddl-auto: none`) |
| Cache / feature store | Redis 7 | Velocity counters, rate-limit buckets; degrades gracefully if down |
| Async | Postgres **transactional outbox** + polling worker | No broker, deliberately |
| ML sidecar | Python + FastAPI + scikit-learn | `/predict`, called synchronously with a 200 ms timeout |
| LLM explain | Spring AI + Ollama (local) | Optional; falls back to a template |
| Dashboard | React 18 + TypeScript + Vite, react-router, Recharts, Radix UI, lucide | See §9 |
| API docs | springdoc-openapi | `/swagger-ui/index.html`, `/v3/api-docs` |
| Observability | Micrometer → Prometheus, structured request log | See §11 |

### 2.3 Gateway modules

`gateway/src/main/java/com/finpay/gateway/`

| Module | Responsibility |
|---|---|
| `common/` | Typed error model, structured audit log, canonical-JSON request hashing |
| `auth/` | API-key + JWT authentication, security config, `CurrentCaller`, bootstrap admin |
| `identity/` | Merchants, API keys (hashed at rest), dashboard users, customers, device fingerprints |
| `merchant/` | Onboarding and admin provisioning |
| `order/` | Orders API |
| `payment/` | State machine, idempotency-wrapped create flow, processor simulator, payment-detail assembly |
| `idempotency/` | `Idempotency-Key` store, `INSERT … ON CONFLICT` claim |
| `ledger/` | Double-entry ledger and reconciliation |
| `refund/` | Partial and full refunds, concurrency-safe |
| `risk/` | `RiskGate` SPI, rule engine, Redis feature store, ML client, pgvector store |
| `outbox/` | Transactional outbox |
| `webhook/` | Outbox fan-out, HMAC-signed delivery, retry/dead-letter, demo receiver |
| `groundtruth/` | Hidden `actually_fraudulent` label, delayed resolver, false-clear reconciliation |
| `analyst/` | Review queue, reference analyst, Auto-Clear Engine, calibrator, LLM explain, metrics |
| `settlement/` | Payout batch with fraud holdback |
| `analytics/` | Overview roll-up for the console |
| `sim/` | Evaluation harness (`POST /v1/admin/simulate`) |
| `checkout/` | Hosted payment links |

---

## 3. The payment lifecycle

### 3.1 State machine

Twelve states, with an explicit `ALLOWED_TRANSITIONS` map. Every illegal transition throws
`InvalidStateTransitionException` — enforced in code, not by convention, and tested exhaustively.

```
CREATED → PENDING → RISK_EVALUATING
     RISK_EVALUATING ──BLOCK──▶ FAILED            (processor never called)
     RISK_EVALUATING ──REVIEW─▶ PENDING_REVIEW ──REJECT──▶ FAILED
     RISK_EVALUATING ──REVIEW─▶ PENDING_REVIEW ──CLEAR───▶ PROCESSING
     RISK_EVALUATING ──ALLOW──▶ PROCESSING
PROCESSING ─(simulated processor)─▶ AUTHORIZED | FAILED
AUTHORIZED ─ score ≤ auto-capture-max ─▶ CAPTURED     (the "auto-clear" path)
AUTHORIZED ─ LOW / MEDIUM ────────────▶ HELD          (funds reserved, merchant unpaid)
HELD ──analyst CLEAR──▶ CAPTURED
HELD ──analyst REJECT─▶ VOIDED
AUTHORIZED ────────────▶ VOIDED                       (e.g. HIGH risk, killed instantly)
CAPTURED ──────────────▶ REFUNDED                     (partial stays CAPTURED until full)
any non-terminal pre-AUTHORIZED ─▶ CANCELLED
```

### 3.2 Why authorization and capture are decoupled

This is the single most important design decision in the project (architecture-freeze Doubt 4).
"Auto-clear" means **auto-capture**, and capture is what actually moves money to the merchant.
By holding funds in `AUTHORIZED`/`HELD` rather than settling immediately:

- A wrong "clear" on a HELD payment costs nothing — the authorization is simply voided.
- The zero-false-auto-clear problem collapses from an abstract ML-threshold question into a
  concrete financial-mechanics one: only the narrow auto-capture bucket can ever lose money.
- `auto-capture-max` is deliberately tight (**6** on a 0–100 scale). Most LOW-risk payments are
  still HELD and routed through the review queue, where the Auto-Clear Engine — not a blanket
  rule — decides.

### 3.3 Idempotency

Every payment create requires an `Idempotency-Key`. The primitive is a unique index plus
`INSERT … ON CONFLICT DO NOTHING`: the winner proceeds, the loser blocks on the index and then
mirrors the winner's cached response. Keys are scoped to `(merchant_id, idem_key)` — a fix from
the audit; they were globally unique before, so one merchant could receive another's response.
A failed attempt is marked `FAILED` so the key becomes retryable, and an expired key (24 h TTL)
is taken over as a new request.

Proven by `IdempotencyConcurrencyTest`: 20 concurrent identical requests produce **exactly one**
payment.

---

## 4. Money and correctness

### 4.1 The ledger

Append-only double-entry. A database trigger rejects `UPDATE` and `DELETE` on `ledger_entries` —
immutability is enforced by Postgres, not by application discipline. Accounts:

| Account | Meaning |
|---|---|
| `CUSTOMER_CLEARING` | Funds in from the customer side |
| `MERCHANT_PAYABLE` | Accrued and owed to a merchant, not yet paid out |
| `MERCHANT_SETTLED` | Paid out in a settlement batch |
| `FRAUD_WRITEOFF` | Confirmed fraud, withheld from payout (added by the audit sprint) |
| `PLATFORM_FEES` | Reserved; currently unused |

`LedgerReconciliationTest` proves `SUM(debits) == SUM(credits)` after arbitrary capture/refund
sequences, and every transaction asserts balance at write time.

### 4.2 Settlement holdback

A settlement batch does **not** simply sweep a merchant's payable balance. A capture becomes
settlement-eligible only once its hidden ground truth has resolved as legitimate. A capture whose
ground truth resolves as fraud is **written off** to `FRAUD_WRITEOFF` — a real balanced ledger
transaction, not a deletion — before any payout.

This is deliberately payment-level rather than account-level, so the eligible amount nets out any
partial refund already issued; a refund mid-holdback cannot cause an over-payout.

Holdback is what finally closes the most serious hole the audit found: a *perfect* stealth-fraud
transaction carries no rule signal, scores ~0, and is auto-captured by the low-risk shortcut
without ever reaching the review queue or the Auto-Clear Engine. Nothing upstream of settlement
was ever going to catch it. Holdback catches it downstream, before the money leaves.

### 4.3 Refunds

Partial and full, serialised with `SELECT … FOR UPDATE` on the payment. Refund idempotency keys
are scoped per merchant (another audit fix), the key is checked *after* the payment lock, and a
same-key request for a different payment or amount is refused rather than silently mirrored.

---

## 5. Fraud detection

### 5.1 Three signals, combined

The risk gate runs **synchronously, before the processor call** — blocked fraud never costs a
processor round-trip.

```
final_score = 0.55 · rule_score + 0.40 · ml_score + 0.05 · vector_similarity
```

Bucketed on 0–100: `LOW ≤ 22`, `MEDIUM ≤ 50`, `HIGH ≤ 78`, `CRITICAL` above → decisions
`ALLOW` / `REVIEW` / `BLOCK`.

**Rules** (`RuleEngine`): velocity over 5 min and 1 h, amount deviation from the customer's
rolling average, new device, new IP, failed-attempt bursts, device→account rings. Each fired rule
is recorded individually in `risk_evaluations.rule_flags` with its code, weight and detail, so a
score is always attributable.

**ML** (`ml-service`): a logistic-regression model trained on synthetic transactions with injected
fraud patterns (ROC-AUC ≈ 0.89, PR-AUC ≈ 0.80). Called with a 200 ms timeout; if the sidecar is
unreachable the engine degrades to rules-only rather than failing the payment.

**pgvector** (`FraudVectorStore`): every confirmed fraud is encoded into a fixed 16-dimension
numeric feature vector and stored in `fraud_case_vectors` with an ivfflat cosine index. A new
transaction is encoded the same way and cosine-searched against that store; the mean similarity to
genuinely close cases (≥ 0.90, and only once the store holds ≥ 25 cases) becomes an extra signal.
This is the RAG pattern applied to structured feature vectors rather than text, and it does double
duty — a predictive signal independent of the classifier, and explainability, since the analyst
can be shown actual similar past cases.

### 5.2 The hidden ground truth

`fraud_ground_truth.actually_fraudulent` is the simulation's own label for whether a transaction
was really fraudulent. Rules governing it:

- **Never exposed** through any merchant- or analyst-facing API.
- **Evaluation only, never training.** Using it to train would be data leakage; the ML service
  deliberately does not select it (`ml-service/app/db.py`), and that guard is tested.
- **Resolves after a delay** (`resolve-after-seconds`, default 60 s), simulating a
  chargeback/dispute window.
- **Authored only by the simulation harness.** Originally a card token (`tok_fraud`) planted the
  label — and that token was selectable on the public checkout page, so anyone could label a clean
  payment as fraud. The audit removed it; real traffic is recorded as `LIVE`.

---

## 6. The Auto-Clear engine (the USP)

### 6.1 How it decides

A payment that lands in review becomes an `analyst_reviews` row with a feature snapshot. Three
things then act on it:

1. **The reference analyst** — a fixed, deterministic ruleset producing *silver labels*
   (architecture-freeze Doubt 3). It never sees ground truth.
2. **The Auto-Clear classifier** — a separate model trained on those silver labels, answering a
   different question from the fraud model: *"would the reference analyst clear this?"*
3. **`ReviewDecider`** — auto-clears only when **every** check agrees: bucket ∈ {LOW, MEDIUM},
   no prior confirmed fraud for the customer, the reference analyst concurs, and model confidence
   ≥ the calibrated threshold. It is a **proposer only**: it either auto-clears, or leaves the
   review genuinely `PENDING` for a human. It never decides *for* a human.

### 6.2 The calibration loop

Every auto-clear is reconciled against the ground truth once that resolves. A single confirmed
false auto-clear **ratchets the confidence threshold up** (never down) and logs an `ERROR`. The
threshold lives in `system_config` and is read on every call, so the ratchet reaches every
instance rather than one JVM's cache.

### 6.3 The human path

`POST /v1/analyst/reviews/{id}/claim` makes ownership real — a second analyst cannot work a
claimed review. A demo-only `SimulatedAnalystWorker` drains whatever stays unclaimed after a
configurable delay so the queue moves with nobody at the keyboard, but its decisions are tagged
`SIMULATED_ANALYST`, **never** `HUMAN`, and are explicitly excluded from the manual-review-
reduction baseline.

### 6.4 How effectiveness is measured

Scored against the hidden ground truth, optionally scoped to one simulation run:

- **Recall** = fraud caught / all actual fraud, where "caught" covers both a review rejecting it
  before capture **and** a settlement write-off catching it after capture but before payout.
- **Precision** = fraud caught / everything blocked.
- **False-positive rate** = legitimate traffic wrongly rejected / all actual legitimate traffic.

A plain simulated processor decline is deliberately **excluded** from "caught" — it is not the
fraud pipeline's doing, and counting it would credit catches nobody made. Warm-up scenery is
excluded from every figure.

---

## 7. What is honest about the numbers

This deserves its own section, because the project's most interesting finding is about its own
measurement.

### 7.1 The original claim

The pre-audit measurement (still recorded in `PHASES.md`) was: ~2,200 flagged transactions,
~25% auto-cleared, **zero false auto-clears**.

### 7.2 Why that claim was hollow

The audit (§D.C) found that all four injected fraud patterns tripped a rule *by design*, and the
"regular" legitimate pattern never did. So no simulated transaction was ever actually eligible to
be auto-cleared-and-wrong, or wrongly rejected. "Zero false auto-clears" was **structural, not
earned** — a property of a population that could not produce the failure the claim was about.

### 7.3 What was built to fix it

Two new populations the engine genuinely cannot see through:

- **`stealthRate`** — fraud disguised well enough to carry *no rule signal at all*. This is the
  only population over which "zero false auto-clears" means anything.
- **`fraudShapedLegitRate`** — genuine customers whose real behaviour trips a rule anyway. This is
  the population "review reduction" and the false-positive rate are actually about.

### 7.4 What the numbers look like now

With the ML sidecar live and those populations present, **false auto-clears do occur** — a
Day-15 run produced 3–6 per scenario, and the calibrator ratcheted the threshold from its 0.80
floor up to 0.999 in response. That is the system working, not failing.

A representative run (80 transactions, 50% stealth, 30% fraud-shaped-legit):

| Stage | Recall | Fraud missed |
|---|---|---|
| Right after ground truth resolves | 80% | 5 |
| After a settlement batch runs | **100%** | **0** (5 caught by write-off) |

The gap between those two rows is the whole argument: review alone genuinely misses fraud, and
**the ledger, not the model, is what actually protects the money**.

`README.md` was corrected during the audit sprint to state this. `PHASES.md` still carries the
old figure and is kept as a historical record.

---

## 8. API surface

All routes are versioned under `/v1`. Roles: `MERCHANT`, `MERCHANT_ADMIN`, `RISK_ANALYST`,
`SUPER_ADMIN`.

| Endpoint | Method | Who |
|---|---|---|
| `/v1/auth/login` · `/v1/auth/me` | POST · GET | public · authenticated |
| `/v1/admin/merchants` · `/v1/admin/analysts` | POST | SUPER_ADMIN |
| `/v1/orders` · `/v1/orders/{id}` | POST · GET | merchant |
| `/v1/payments` | POST | merchant (`Idempotency-Key` required) |
| `/v1/payments/{id}` | GET | any role; merchants see only their own (404, never 403) |
| `/v1/payments/{id}/capture` | POST | merchant |
| `/v1/payments/{id}/timeline` · `/ledger` · `/webhooks` · `/audit` · `/autoclear` · `/customer` | GET | any role, same scoping |
| `/v1/payments/{paymentId}/refund` | POST · GET | write merchant-only; read any role |
| `/v1/transactions?page&size&status&from&to&q` | GET | merchant-scoped, or all for an analyst |
| `/v1/analytics/summary` | GET | merchant sees own; analyst/admin see platform-wide |
| `/v1/risk/{paymentId}` | GET | merchant sees decision/bucket/score only; analyst sees all |
| `/v1/risk/{paymentId}/explain` | GET | RISK_ANALYST / SUPER_ADMIN |
| `/v1/analyst/reviews` · `/reviews/{id}` | GET | analyst |
| `/v1/analyst/reviews/{id}/claim` · `/decide` | POST | analyst |
| `/v1/analyst/metrics/autoclear?runId&since` · `/metrics/fraud?runId` | GET | analyst |
| `/v1/settlements` · `/v1/settlements/run` | GET merchant · POST SUPER_ADMIN | |
| `/v1/webhooks/config` · `/config/{id}` | POST/GET · DELETE | merchant |
| `/v1/webhooks/receiver/{name}` | POST · GET | demo receiver |
| `/v1/checkout/{orderId}` · `/{orderId}/pay` | GET · POST | **public**, rate-limited by IP |
| `/v1/admin/simulate` · `/{runId}/resolve` · `/reset` | POST | SUPER_ADMIN |

A merchant requesting a payment that is not theirs gets **404, not 403** — existence is not
leaked.

---

## 9. The dashboard

React 18 + TypeScript + Vite, routed with react-router. Two surfaces share the build: the
merchant/analyst console, and a customer-facing hosted checkout at `/pay/{orderId}` (routed by
pathname in `main.tsx`).

**Routes:** Overview (default landing) · Payments · Payment Detail · Orders\* · Refunds\* ·
Settlements\* · Customers\* · Sandbox · Risk/Auto-Clear.  \*named in the navigation on purpose but
not built out — an honest empty state rather than a greyed label.

- **Overview** — KPI row (awaiting settlement, captured volume, success rate, needs attention), a
  Recharts 14-day volume chart, and a status breakdown.
- **Payments** — server-driven filter, free-text id search, time range, pagination.
- **Payment Detail** — seven tabs: Overview, Risk, Ledger, Refunds, Webhooks, Timeline, Audit.
- **Risk / Auto-Clear** — Auto-Clear impact and fraud-effectiveness panels (KPI cards over
  proportional breakdowns), full scenario controls, the run lifecycle (run → resolve → settle),
  an all-time/this-run scope toggle, and the review queue with a Radix action menu.
- **Sandbox** — API key, payment links, direct server-to-server pay.

### Design system

Rebuilt over four rounds against real references (Razorpay's and Stripe's actual dashboards,
Tremor's card anatomy) and a sourced checklist of what makes AI-generated UI recognisable. Key
decisions: a light canvas with white cards; a hand-tuned neutral ramp rather than stock Tailwind
greys; a brand blue deliberately picked *outside* the indigo/violet "AI purple" band; IBM Plex
Sans + IBM Plex Mono, with Mono reserved for money and ids where tabular alignment is functional;
a small intentional radius scale; soft layered shadows. Charts use Recharts, menus use Radix
primitives — chosen because both are CSS-agnostic, so the token system stays the only source of
styling truth and no component kit's default look is inherited.

Full rationale and the per-round log, including defects found and fixed, is in
[`DESIGN.md`](DESIGN.md).

---

## 10. ML service

`ml-service/` — Python FastAPI, three model artifacts, clearly labelled so there is no confusion
about which one is live:

| File | Role |
|---|---|
| `finpay_model.py` | **LIVE.** Trained on synthetic transactions with FinPay's real features |
| `autoclear_model.py` | **LIVE.** "Would the reference analyst clear this?", trained on silver labels |
| `kaggle_model.py` | **Offline only.** Methodology demo on the real Kaggle credit-card dataset — class-imbalance handling, PR/AUC reporting. Not in the request path |
| `features.py` · `synthetic.py` · `db.py` | Feature extraction, generator, DB access (deliberately never selects the ground-truth column) |

Kept as a separate service because scikit-learn training pipelines inside Spring Boot would be
awkward, and because it mirrors a real deployment boundary.

---

## 11. Security and observability

**Security.** API keys are HMAC-SHA256 hashed with a pepper and shown exactly once. JWT for
dashboard users. RBAC through `@PreAuthorize`. Jakarta Bean Validation on every request body. A
Redis token-bucket rate limiter (atomic Lua, 120 capacity / 40 refill per second by default) sits
in the security chain after auth, returns 429 with `Retry-After` and `X-RateLimit-*`, and **fails
open** if Redis is down. Webhook payloads are HMAC-SHA256 signed. Every state-changing action is
written to a structured audit log with a gapless `seq` ordering column — ordering by `created_at`
silently reversed same-instant rows, which the audit caught.

**Observability.** One structured line per request (`finpay.request`) with a `requestId` in MDC
and an `X-Request-Id` header; ECS JSON under the container profile. Prometheus at
`/actuator/prometheus`:

```
finpay_payments_total{result}          finpay_risk_decisions_total{decision,bucket}
finpay_risk_latency_seconds            finpay_webhook_deliveries_total{status}
finpay_autoclear_decisions_total{auto_cleared}
finpay_autoclear_false_clears_total
```

---

## 12. Simulation and evaluation

`POST /v1/admin/simulate` generates realistic traffic against a dedicated simulation merchant.

| Knob | Meaning |
|---|---|
| `count` | Requested transaction draws |
| `fraudRate` | Share of draws that are fraud |
| `stealthRate` | Share of that fraud carrying **no rule signal at all** |
| `fraudShapedLegitRate` | Share of legitimate traffic that trips a rule anyway |
| `seed` | Reproducibility |

**Provenance.** Every call is recorded as a `simulation_runs` row, and every payment and review it
creates is tagged with that run id and a `is_warmup` flag. Warm-up scenery — the customer history
and pattern warm-ups the generator needs in order to *produce* a population — is excluded from
every metric. Before this, a "1,000-transaction" run silently created ~1,712 payments and counted
them all.

**Reproducibility.** Every identifier a run mints (customer external refs, device fingerprints) is
scoped by the run's own id, not the scenario seed, so two runs with the same seed can never
inherit each other's Redis state. Three pieces of state are deliberately global and are cleared by
`POST /v1/admin/simulate/reset`: the Redis feature store, the auto-clear threshold ratchet, and
the pgvector confirmed-fraud case store.

**On-demand resolution.** `POST /v1/admin/simulate/{runId}/resolve` forces a run's review queue and
ground-truth window closed immediately, so a demo never waits on a wall clock.

---

## 13. Testing

**102 tests across 27 classes**, all integration-weighted and running against a real Postgres and
Redis (`finpay_test`). There is no mocking of the database.

Representative coverage: 20-way idempotency race · refund concurrency · ledger reconciliation and
DB-enforced append-only · exhaustive state-machine transitions · rule engine · reference analyst ·
webhook retry→dead-letter→recovery · rate limiter · the Auto-Clear flow and calibration · a real
human-reviewable queue · settlement holdback and fraud write-off · run-scoped precision/recall/FPR
· transaction search/filter/pagination · the full Payment Detail read path · the Overview roll-up.

### The discipline

Beyond "tests pass", the audit sprint held two rules:

1. **Failing test first.** Every fix has a regression test that was demonstrated to fail before
   the fix landed.
2. **Mutation check.** Where the bug was in logic rather than markup, the fix was reverted, the
   specific test confirmed to fail *with a clear diagnostic*, the file restored and verified
   byte-identical with `diff -q`, and the suite re-run green. A test that does not fail when you
   break the thing it covers is not a test.

Live verification was treated as a first-class technique, not a sanity check — and it earned its
place: the three most serious bugs in the whole sprint were found by running the finished feature,
not by the suite (§14.2).

---

## 14. How it was built

### 14.1 Phases 0–3 (the original build)

- **Phase 0** — scaffold, migrations, state machine, security, error model.
- **Phase 1** — payment gateway core: merchants, orders, state machine, idempotency, ledger,
  refunds, simulator, outbox.
- **Phase 2** — fraud detection: rule engine, Redis feature store, ML sidecar, pgvector retrieval,
  webhooks, delayed ground truth.
- **Phase 3** — the USP: review queue, reference analyst, Auto-Clear engine, calibration loop, LLM
  explain, analyst dashboard, evaluation harness.

### 14.2 The 15-day audit sprint

A full code-trace and live-system audit ([`AUDIT.md`](AUDIT.md)) was run against the finished
build, then worked off day by day. It found 5 P0 correctness bugs, a dozen-plus P1/P2 product and
measurement gaps, and — notably — **several bugs the audit itself missed and only live verification
caught**:

| Day | Work |
|---|---|
| 1–5 | P0 fixes: idempotency scoped per merchant, order locked against double payment, refund webhooks routed by a real column, ground truth taken away from card tokens, audit trail with gapless ordering |
| 6 | Simulation provenance — run ids, warm-up tagging |
| 7 | The two hard populations (stealth, fraud-shaped-legit) and true run isolation. **Found:** stealth fraud bypasses review entirely via the low-risk auto-capture shortcut |
| 8 | A review queue with a real human in it. **Found (worst bug of the sprint):** `SimulatedAnalystWorker.drain()` called its own `@Transactional` method, so Spring's proxy never applied — money moved while the review row silently never persisted. Every test passed, because tests called the injected proxy from outside |
| 9 | Settlement holdback + `FRAUD_WRITEOFF`, closing Day 7's finding end to end |
| 10 | Precision/recall/FPR against ground truth. **Found:** `resolveDue()` is a global 100-row-capped sweep, so any real-sized run left ground truth silently unresolved |
| 11 | Router, navigation shell, Payments spine with real filter/search/pagination |
| 12 | Payment Detail — every facet the audit found missing, assembled |
| 13 | Run-scoped metrics and full scenario controls in the UI |
| 14 | Rewrote `demo.sh`. **Found:** `reset()` promised same-seed reproducibility but never cleared the pgvector case store, so run 2 silently inherited run 1's confirmed fraud |
| 15 | Full dry run with the ML sidecar live; corrected the README's misleading headline claim |

### 14.3 Four design rounds

Covered in §9 and logged in [`DESIGN.md`](DESIGN.md): the token system and reference-driven
rebuild; the Overview screen and its backing endpoint; shared card primitives plus the Radix menu;
and the Auto-Clear panel's conversion from eight tiles to a composition.

---

## 15. Running it

Prerequisites: Docker, **or** a local PostgreSQL 16+ with `pgvector` and a local Redis. JDK 21 is
pinned via a Maven toolchain.

```bash
docker compose up --build
```

Services: postgres (`pgvector/pgvector:pg16`), redis (`7-alpine`), ml-service (`:9000`), gateway
(`:8080`), dashboard (`:5173`).

Or, against local infrastructure:

```bash
./dev.sh
```

Then a narrated end-to-end walkthrough — onboard, hold/capture, idempotency, decline, partial
refund, reset, a stealth/fraud-shaped-legit scenario, resolve, fraud metrics, settlement batch,
fraud metrics again, explain, Prometheus:

```bash
./demo.sh
```

On first boot a `SUPER_ADMIN` is created: `admin@finpay.local` / `admin12345` (override with
`FINPAY_BOOTSTRAP_ADMIN_*`). **Change it for anything non-local.** API reference at
`http://localhost:8080/swagger-ui/index.html`.

Tests (needs a local `finpay_test` database and Redis):

```bash
cd gateway && ./mvnw test
```

> **Note:** `docker compose up --build` was **not** verifiable in the environment used for the
> final dry run — the Docker daemon was unavailable, so every live check in this project used the
> `dev.sh` local-process path. The compose file's pinned versions match what is documented here,
> but a genuine cold-start-from-clone rehearsal is the one thing that has not been done.

---

## 16. Configuration reference

All overridable by environment variable; defaults shown.

| Key | Default | Meaning |
|---|---|---|
| `finpay.risk.rule-weight` / `ml-weight` / `vector-weight` | 0.55 / 0.40 / 0.05 | Score blend |
| `finpay.risk.low-max` / `medium-max` / `high-max` | 22 / 50 / 78 | Bucket boundaries (0–100) |
| `finpay.risk.auto-capture-max` | **6** | Only at/below this score is a payment auto-captured |
| `finpay.risk.ml-timeout-ms` | 200 | Sidecar timeout; degrades to rules-only |
| `finpay.autoclear.threshold` | 0.80 | Confidence floor; the calibrator ratchets it up, never down |
| `finpay.groundtruth.resolve-after-seconds` | 60 | Simulated dispute window |
| `finpay.settlement.interval-ms` | 300000 | Demo sweep every 5 min (a real gateway runs nightly) |
| `finpay.ratelimit.capacity` / `refill-per-second` | 120 / 40 | Redis token bucket per caller |
| `finpay.idempotency.ttl-hours` | 24 | After which a key is reusable |
| `finpay.simulator.mode` | `DETERMINISTIC` | Or `PROBABILISTIC` (92% CARD, 88% UPI) |
| `finpay.simulated-analyst.latency-seconds` | 20 | Demo bot's response delay |
| `finpay.analyst.review-sla-minutes` | 30 | Dashboard urgency only |

Simulator tokens: `tok_valid`, `tok_declined`, `tok_insufficient_funds`, `tok_expired`. There is
deliberately **no fraud token** — a card token only decides what the processor answers.

`SPRING_PROFILES_ACTIVE=phase1` runs the gateway with an allow-all risk gate and a no-op review
queue, for exercising Phase 1 in isolation.

---

## 17. Database schema

Eighteen Flyway migrations, which read as the project's history:

| Migration | What it added |
|---|---|
| `V1` | Core schema — identities, orders, payments |
| `V2` | Ledger and refunds |
| `V3` | Risk, webhooks, outbox |
| `V4` | pgvector `fraud_case_vectors`, analyst reviews, auto-clear decisions |
| `V5`–`V6` | Platform ledger accounts, uniqueness |
| `V7` | Risk features, webhook secret |
| `V8` | `system_config` (where the auto-clear threshold lives) |
| `V9` | Settlements |
| `V10` | **Audit fix** — idempotency scoped to `(merchant, key)` |
| `V11` | **Audit fix** — one live payment per order (partial unique index) |
| `V12` | **Audit fix** — `outbox_events.merchant_id` for routing |
| `V13` | **Audit fix** — refund keys scoped to merchant |
| `V14` | **Audit fix** — ground truth authored by scenario, not card token |
| `V15` | **Audit fix** — audit log gapless `seq` total ordering |
| `V16` | Simulation runs and per-payment provenance |
| `V17` | Analyst review human workflow (assignment, claim, SLA) |
| `V18` | Settlement holdback (`settled_at`, `settlement_id`, `written_off_at`) |

---

## 18. Limitations — say these out loud

- **Analyst decisions and fraud outcomes are simulated.** The reference analyst is a fixed
  ruleset; `actually_fraudulent` is a hidden label the generator controls and resolves on a timer.
  A real deployment replaces both with actual analyst feedback and confirmed chargebacks, plus
  drift monitoring, since analyst behaviour and fraud patterns both shift.
- **The live fraud model trains on synthetic data.** The Kaggle model demonstrates methodology on
  real data but is not in the request path.
- **Single Postgres, single Redis, in-process workers.** No Kafka, no Kubernetes — a deliberate
  trade for a two-person team.
- **`docker compose` is unverified** in the most recent environment (see §15).
- **Explicitly out of scope:** real processor integration, multi-currency settlement, disputes and
  chargebacks, PCI-DSS card vaulting, horizontal scaling, a labelled internal fraud dataset with a
  chargeback feedback loop, Grafana dashboards (Prometheus metrics are exported; the dashboard is
  left to the reader).

---

## 19. Talking points

- **Why a monolith?** With two people, the operational cost of Kafka and Kubernetes would have
  come out of correctness. That budget went into idempotency and ledger integrity. Modules have
  enforced boundaries.
- **How do you know idempotency works?** 20 concurrent requests with the same key create exactly
  one payment — an automated test. The primitive is a unique index plus
  `INSERT … ON CONFLICT DO NOTHING`.
- **How do you know money can't disappear?** The ledger is append-only double-entry and a DB
  trigger rejects `UPDATE`/`DELETE`. Reconciliation proves debits equal credits after any sequence
  of captures and refunds.
- **Is the ML real?** The live model trains on synthetic transactions with injected fraud
  patterns — stated plainly. A separate offline model demonstrates methodology on the real Kaggle
  dataset. Production would need a labelled internal dataset and a chargeback feedback loop.
- **What's the actual insight?** Fraud *flagging* isn't the hard part — most flags are false
  positives, and that burns analyst hours. The second layer safely auto-clears high-confidence
  false positives, measured against a ground truth specifically constructed so the engine can be
  caught being wrong. When it *is* wrong, the safety net isn't "the model never errs" — it's
  financial mechanics: funds stay held until confidently cleared, a false clear ratchets the
  threshold immediately, and settlement holdback writes off confirmed fraud before payout even if
  every upstream check missed it. Recall reaches 100% only *after* holdback runs, and that gap is
  the whole point.
- **What would you do differently?** The measurement lesson. The first version reported "zero
  false auto-clears" over a population that was structurally incapable of producing one. Building
  populations that can genuinely defeat the engine was worth more than any additional feature.
