# FinPay — Payment Gateway + Fraud Detection Engine

A sandboxed payment gateway (à la Cashfree/Razorpay) where merchants create orders,
customers "pay" through a simulated processor, money moves through a **real double-entry
ledger**, and every transaction is **risk-scored before the processor is touched**.

Built as a **modular monolith** (one Spring Boot service, enforced module boundaries) with a
small Python ML sidecar. The design decisions and their rationale are frozen in
[`docs/architecture-freeze.md`](docs/architecture-freeze.md); the phased build plan is in
[`docs/PHASES.md`](docs/PHASES.md).

> **Honest scope:** this is a learning/interview project by a 2-person team. Real processor
> integration, PCI-DSS card handling, multi-currency settlement, disputes/chargebacks,
> Kafka/K8s, and horizontal scaling are **deliberately out of scope**. Analyst decisions and
> fraud outcomes in Phase 3 are **simulated** against a hidden ground-truth label the
> transaction generator controls — see Phase 3 notes.

---

## Stack

| Layer | Choice |
|---|---|
| Gateway | Spring Boot 3.4 (Java 21), Spring Security (API keys + JWT), Spring Data JPA |
| DB | PostgreSQL 16 + `pgvector` (similar-fraud retrieval) |
| Cache / feature store | Redis 7 |
| Async | Postgres **transactional outbox** + polling worker (no broker) |
| ML sidecar | Python + FastAPI + scikit-learn, `/predict` called by the gateway |
| LLM explain | Spring AI + Ollama (local, no API cost) |
| Dashboard | React + TypeScript (Vite) |
| API docs | springdoc-openapi → `/swagger-ui`, `/v3/api-docs` |
| Observability | Micrometer → `/actuator/prometheus`, structured request log |

## Architecture

```
                        ┌───────────── gateway (Spring Boot, modular monolith) ─────────────┐
  merchant ─X-API-Key─▶ │  auth ▸ rate-limit(Redis token bucket) ▸ router                   │
  dashboard ─JWT──────▶ │                                                                  │
                        │  POST /v1/payments ─▶ IdempotencyService (INSERT..ON CONFLICT)    │
                        │        └▶ PaymentService: state machine ──▶ RiskEngine (SYNC)     │
                        │                 rules + ML + pgvector ─▶ ALLOW / REVIEW / BLOCK   │
                        │              ALLOW ─▶ simulator ─▶ AUTHORIZED                     │
                        │                 LOW≤k → auto-capture   LOW/MED → HELD             │
                        │                 HIGH → PENDING_REVIEW   CRIT → FAIL               │
                        │              capture/refund ─▶ double-entry ledger (append-only)  │
                        │              every state change ─▶ outbox_events (same tx)        │
                        └───┬───────────────────────┬──────────────────────┬───────────────┘
                            │ poll                  │ /predict             │ poll
                   ┌────────▼─────────┐    ┌─────────▼──────────┐   ┌───────▼───────────────┐
                   │ WebhookDispatcher│    │ ml-service (FastAPI)│   │ ReviewProcessor       │
                   │ HMAC sign, retry,│    │ FinPay model (live) │   │ reference analyst OR  │
                   │ backoff, DEAD    │    │ Auto-Clear model    │   │ Auto-Clear Engine     │
                   └──────────────────┘    │ Kaggle model (demo) │   │ + calibration loop    │
                                           └────────────────────┘   └───────────────────────┘
        Postgres (+pgvector) · Redis · Ollama (optional) · GroundTruthResolver (delayed labels)
```

## Repo layout

```
gateway/        Spring Boot modular monolith
  src/main/java/com/finpay/gateway/
    common/       errors, audit, canonical JSON
    auth/         API-key + JWT auth, security config, bootstrap admin
    identity/     merchants, api_keys, users, customers, devices
    merchant/     onboarding / admin provisioning
    order/        orders API
    payment/      state machine, idempotency-wrapped create flow, simulator
    idempotency/  Idempotency-Key store + INSERT..ON CONFLICT claim
    ledger/       double-entry ledger + reconciliation
    refund/       partial/full refunds, concurrency-safe
    risk/         RiskGate SPI, rule engine, Redis feature store, ML client, pgvector store
    outbox/       transactional outbox
    webhook/      outbox fan-out, HMAC-signed delivery, retry/dead-letter, demo receiver
    groundtruth/  hidden actually_fraudulent label + delayed resolver + false-clear reconcile
    analyst/      review queue, reference analyst, Auto-Clear Engine, calibrator, LLM explain
    settlement/   nightly-style payout batch: sweeps merchant payables to MERCHANT_SETTLED
    sim/          Phase-3 evaluation harness (POST /v1/admin/simulate)
ml-service/     Python FastAPI: FinPay-native model (live) + Auto-Clear model (live) + Kaggle demo
dashboard/      React + TS: merchant sandbox + risk-analyst / auto-clear console
docs/           architecture freeze, phase plan + measured results
```

## Running locally

Prereqs: Docker (Postgres + Redis + services) **or** a local Postgres 16+ with `pgvector`
and a local Redis. JDK 21 (the build pins to it via a Maven toolchain).

```bash
cp .env.example .env
docker compose up --build          # postgres, redis, ml-service, gateway, dashboard
```

Or run the whole stack against local infra (Postgres + Redis) with one script:

```bash
./dev.sh        # gateway :8080, ml-service :9000, dashboard :5173
./demo.sh       # narrated end-to-end walkthrough against a running gateway
```

On first boot a `SUPER_ADMIN` dashboard user is created: `admin@finpay.local` / `admin12345`
(override with `FINPAY_BOOTSTRAP_ADMIN_*`). **Change it for anything non-local.**
API reference: `http://localhost:8080/swagger-ui/index.html`.

## Quick tour (curl)

```bash
# 1. login as admin -> JWT
TOKEN=$(curl -s localhost:8080/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@finpay.local","password":"admin12345"}' | jq -r .token)

# 2. onboard a merchant -> raw API key (shown once)
curl -s localhost:8080/v1/admin/merchants -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme","email":"acme@example.com","adminPassword":"acme12345"}'

# 3. create an order, then pay it (Idempotency-Key required)
curl -s localhost:8080/v1/orders -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"customerRef":"cust_1","amountMinor":50000,"currency":"INR"}'
curl -s localhost:8080/v1/payments -H "X-API-Key: $KEY" -H 'Idempotency-Key: abc123' \
  -H 'Content-Type: application/json' \
  -d '{"orderId":"<id>","method":"CARD","token":"tok_valid"}'
```

Simulator tokens (deterministic mode): `tok_valid`, `tok_declined`,
`tok_insufficient_funds`, `tok_expired`. There is deliberately no fraud token — a card token
only decides what the processor answers. Fraud in the sandbox is authored by the simulation
harness (`POST /v1/admin/simulate`), which carries its own hidden ground-truth label.

## Checkout links (Razorpay/Stripe-style)

The merchant creates an order and gets a shareable link — `/pay/{orderId}` — that anyone can
open and pay, **no customer account or login**, exactly like a real hosted checkout. The
order id is the capability token; `GET/POST /v1/checkout/{orderId}` are `permitAll` but
rate-limited by caller IP. Try it in the dashboard: **Merchant sandbox → Create a payment
link** → "Open as customer" → pay with a test card → back in the merchant tab, **Refresh**
the Transactions panel to watch it land (and, if it was held for review, settle).

## Phase 3 — the point of the project

Most fraud flags are false positives, and analysts burn hours clearing them. FinPay adds a
second layer that **safely auto-clears the flagged transactions that are almost certainly
legitimate** and proves it with a number.

- Funds are **authorised but held** unless the risk is confidently low or a review clears it
  (auth/capture decoupling, like Stripe/Razorpay) — so a wrong "clear" costs nothing.
- A rule-based **reference analyst** produces silver labels; a **separate** classifier learns
  "would that analyst clear this?" and auto-clears only when *every* check agrees (bucket,
  no prior fraud, reference analyst concurs, ML confidence ≥ a calibrated threshold).
- Every auto-clear is reconciled against the hidden `actually_fraudulent` label once it
  resolves. **A single false auto-clear ratchets the threshold up and logs an error.**

Measured over ~2,200 flagged transactions in simulation: **~25% auto-cleared with zero false
auto-clears.** Watch it live: `POST /v1/admin/simulate` then the dashboard's Risk / Auto-Clear tab.

## What to say in interviews

- **"Why a monolith?"** — With 2 people, the operational cost of Kafka/K8s would have come out
  of correctness. We spent that budget on idempotency and ledger integrity instead. Modules
  have enforced boundaries (separate routers/services, no cross-module DB access).
- **"How do you know idempotency works?"** — 20 concurrent requests with the same
  `Idempotency-Key` create exactly one payment; it's an automated test. The primitive is a
  unique index + `INSERT … ON CONFLICT DO NOTHING` — the loser blocks on the index and then
  mirrors the winner's cached response.
- **"How do you know money can't disappear?"** — The ledger is append-only double-entry; a DB
  trigger rejects `UPDATE`/`DELETE` on entries. A reconciliation query proves
  `SUM(debits) == SUM(credits)` after any sequence of captures and refunds — also a test.
- **"Is the ML real?"** — The live model trains on synthetic transactions with injected fraud
  *patterns*; we're explicit about that. A separate offline model demonstrates methodology on
  the real Kaggle dataset. Production would need a labelled internal dataset and a feedback
  loop from confirmed chargebacks.
- **(lead with this) "What's the actual insight?"** — Fraud *flagging* isn't the hard part —
  most flags are false positives, and that burns analyst hours. We built a second layer that
  safely auto-clears the high-confidence false positives, measured against a simulated ground
  truth, with **zero false auto-clears** and a **~20–25% cut in manual review volume**. The
  safety comes from financial mechanics (funds are held, not settled, until confidently
  cleared) plus a calibration loop, not just an ML threshold.
- **"What's simulated / what would change for production?"** — The reference analyst and the
  `actually_fraudulent` label. Real deployment swaps in actual analyst feedback + confirmed
  chargebacks, adds drift monitoring (analyst behaviour and fraud both shift), and a
  human-in-the-loop escalation path.

## Status

**All three phases implemented and verified.** 29 automated tests green: 20-way idempotency
race, refund concurrency, ledger reconciliation + append-only enforcement, exhaustive state
machine, rule engine, reference analyst, webhook retry→dead-letter→recovery, rate limiter,
and the Phase-3 auto-clear flow + calibration. See [`docs/PHASES.md`](docs/PHASES.md) for the
per-item checklist, the measured Phase-3 numbers, and the honest limitations.

## Tests

```bash
cd gateway && ./mvnw test          # needs local Postgres 'finpay_test' + Redis
```

