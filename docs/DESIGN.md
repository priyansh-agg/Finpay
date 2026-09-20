# FinPay Dashboard — Design Audit & Redesign Log

**Started:** 2026-09-20
**Method:** live comparison against real merchant dashboards (Razorpay, Stripe), plus a
literature review of what specifically makes AI-generated UI look generic ("AI slop") and how
to avoid it. Not a redesign from taste alone — every change below is either traceable to a
reference screenshot or to a named, sourced rule.

This log follows the same convention as [`AUDIT.md`](AUDIT.md): findings stay as written, a
remediation table tracks what changed and why. Round 1 is below; later rounds append, they
don't rewrite.

---

## Round 0 — why it read as "AI-generated," specifically

The old `styles.css` (100 lines, one file, no design tokens) against real references:

| Reference (what Razorpay/Stripe actually do) | FinPay had | Why it reads as unfinished |
|---|---|---|
| Light canvas, white cards, soft shadow for elevation | Dark theme (`#0f1115` / `#181b22`), flat 1px borders everywhere, zero shadow | This is [Tell 1 in the "unslop" checklist](https://www.claudecodehq.com/playbooks/unslop-ui) almost verbatim: untouched-default cards — border + radius + one bg-shift, no elevation, on *every* panel, stat, and input with no variation |
| A confident, named brand color | `--accent: #5b8cff` — a generic blue-violet with no stated reason, chosen the way a framework default gets chosen | Not technically in the banned indigo/violet hue band, but the *process* was the tell: no reference, no rationale, just "a blue that looks fine" |
| Icons everywhere — every nav item, every row affordance | Zero icons anywhere in the app. Nav items were plain text links | Razorpay's left rail pairs an icon with every single item (Home, Transactions, Settlements, Payment Links, Customers…); a text-only rail reads as a wireframe, not a shipped product |
| Real typographic hierarchy — big bold numbers, small caps labels, a stated type choice | System-font stack, 14px body everywhere, headers distinguished only by `text-transform: uppercase` at the same 14px | No display/body pairing, no distinctive numeral treatment for money — exactly [Tell 8, "generic fonts," and the missing tabular-numeral convention real fintech UIs use for amounts](https://www.claudecodehq.com/playbooks/unslop-ui) |
| Soft-tinted semantic badges (light bg + saturated text + a dot) | `.pill` used near-black tinted backgrounds (`#163a22`) barely distinguishable from the panel behind them, no border, no dot | Correct *idea* (tinted badge, not solid fill), wrong execution — too dark to read as "success," not "danger," at a glance |
| Generous padding, real whitespace | 18px panel padding, 14px card padding, 14px grid gap | Cramped enough that dense views (Risk console, Payment Detail) felt like a spreadsheet, not a product |
| A real login/brand moment | `.login { max-width: 340px; margin: 12vh auto; }`, no logo, on the same flat dark background as everything else | The first screen a user sees had zero brand presence |

**References actually visited, not assumed:**
- [razorpay.com/docs/payments/dashboard/home](https://razorpay.com/docs/payments/dashboard/home/) — the embedded dashboard screenshot (`payments-dashboard-in-home.jpg`, 2880×1674) is the primary reference: white top bar, light-lavender canvas, white left rail with icon+label nav grouped under small-caps section headers, active item = soft indigo-tinted pill background, AI-greeting card with rounded suggestion chips, KPI row with large bold numbers + small gray labels + a green "processed" indicator.
- [stripe.com/payments](https://stripe.com/payments) — confirmed the same underlying language from a second, independent real product: white canvas, one purple accent used sparingly, duotone icon set in a 4-column feature grid, thin 1px dividers instead of boxed cards, a genuine interactive-panel mock (payment-method allocation slider) with real borders/radii/badge chips, solid-fill primary buttons.
- [claudecodehq.com/playbooks/unslop-ui](https://www.claudecodehq.com/playbooks/unslop-ui) — the "AI slop" checklist actually applied below (tell numbers referenced in the table above are this source's own numbering).

---

## Round 1 — the token system + first full pass

**Direction chosen, and why:** switch the console itself (not just the marketing copy) to a
light theme matching the references directly — "start from a reference" is the checklist's own
first rule, and the reference is a payments dashboard, not a code editor. Every token below is
either sampled from a real object (see color) or picked for a stated, checkable reason (see
type).

### Color
- Neutral ramp is hand-tuned (`--n-0` … `--n-900`, a faint cool tint), not stock Tailwind
  slate/gray/zinc — the single most-cited "untouched default" tell.
- Primary `--p-500: #2f5fe0` — deliberately outside the indigo/violet band (hue ~255–280) the
  checklist calls out as "AI purple." FinPay's own hue is ~221 (blue, not violet).
- Semantic colors (`--good`/`--warn`/`--bad`/`--crit`) are soft-tint pairs (light background +
  saturated text + matching border), each with enough contrast to read at a glance — badges now
  carry a small dot indicator too, matching the Razorpay reference's green-dot "Processed"
  convention.

### Type
- **IBM Plex Sans** (UI) + **IBM Plex Mono** (money, ids) — a stated pairing, not an autopilot
  pick. Plex is IBM's own enterprise/developer-tool typeface, a deliberate fit for a technical
  fintech console; avoiding both failure modes the checklist names (the Inter/Geist "no-choice"
  default *and* the Instrument-Serif/Fraunces "tasteful-choice" default, neither of which suits
  a dense numeric admin UI anyway). Mono is applied specifically to money and ids — functional
  (tabular-figure alignment in a ledger), not decorative — never to running prose.
- `formatMoney()` (new, `src/api.ts`) replaced five different ad-hoc `(minor/100).toFixed(2)`
  call sites across `PaymentsView`/`PaymentDetailView`/`AnalystView`/`CheckoutPage` with one
  real-currency-symbol, grouped-decimal formatter (`₹2,499.00`, not `2499.00 INR`).

### Structure
- Small, intentional radius scale: 6 / 10 / 14px (controls / buttons / cards), pills stay fully
  round — a deliberate scale, not `rounded-full` applied everywhere.
- Soft, layered shadows (`--shadow-sm/md/lg`) replace flat-border-only elevation.
- Icons: added `lucide-react`; every left-rail item now pairs an icon with its label, matching
  the reference directly. A blue gradient logo mark ("F") appears in the header and on the
  login card — the product's first brand moment.
- `ComingSoonView` (the honest "not built yet" stub views) got a centered empty-state treatment
  with an icon instead of left-aligned text in a box — reads as a deliberate empty state, not
  an accidentally blank page.
- The hosted checkout page (`checkout.css`, customer-facing — this is what an actual payer
  sees) was re-pointed at the same token set instead of its own hand-rolled hex values, so the
  brand is now consistent from login through to the payment moment itself.
- Added a real favicon (an inline SVG matching the logo mark) — there was none before.

### Bugs caught building this (live-verified, not assumed)
- `body:has(.login) { background: ... radial-gradient(...) }` tiled visibly a second time below
  the fold on a short page (`background-repeat` defaults to `repeat`, and the login page's body
  is shorter than the viewport). Fixed with `no-repeat` + `min-height: 100vh` on `body`.
- `.logo-mark` was scoped as `header.bar .logo-mark`, so the *same* class on the login page (not
  inside `header.bar`) silently got none of its styling — rendered as unstyled inline text.
  Caught by inspecting `getComputedStyle` after the first screenshot didn't show a filled
  square; fixed by making `.logo-mark` a base rule.

### Verified live
Every route was loaded in-browser against real data from a running gateway + Postgres, not
just typechecked: Login, Payments (list, filters, real rows), Payment Detail (Overview, Ledger,
Risk tabs), Risk/Auto-Clear console (KPI cards, the scenario form, the review queue), Sandbox,
a `ComingSoonView` stub, and the hosted checkout page end to end against a real order. Console
checked clean on a fresh tab (no leftover errors from the redesign itself — the handful of
401/500 entries in the browser's buffered console are pre-login/pre-backend artifacts from
earlier in the session, confirmed stale against network-request timestamps).

**Files touched:** `dashboard/src/styles.css` (full rewrite), `dashboard/src/checkout.css`,
`dashboard/index.html` (fonts + favicon), `dashboard/src/App.tsx` (icons, logo mark, role
badge), `dashboard/src/api.ts` (`formatMoney`), `dashboard/src/CheckoutPage.tsx`,
`dashboard/src/views/PaymentsView.tsx`, `dashboard/src/views/PaymentDetailView.tsx`,
`dashboard/src/views/AnalystView.tsx` (money formatting), `dashboard/src/views/ComingSoonView.tsx`
(empty state). New dependency: `lucide-react`.

---

---

## Round 2 — the Overview screen, on real components

**Brief for this round:** widen the reference set, and stop hand-rolling things that good
libraries already solve.

### The reference list

| Site | Visited | What it contributed |
|---|---|---|
| [Razorpay dashboard](https://razorpay.com/docs/payments/dashboard/home/) | ✅ round 1 | The primary model: icon rail, light canvas, KPI-first home |
| [Stripe](https://stripe.com/payments) | ✅ round 1 | Independent confirmation of the same language; panel/badge/slider detailing |
| [Tremor](https://tremor.so) | ✅ round 2 | The KPI-card anatomy actually copied here: small grey label → oversized number → chart → legend list with right-aligned counts. Also settled the library question (below) |
| [Mercury](https://mercury.com) | ✅ round 2 | Marketing-led; confirmed the restrained-neutral + single-accent direction, little product UI exposed publicly |
| [Linear](https://linear.app) | ⚠️ round 2 | Rendered blank — heavy lazy-load/animation defeats a static screenshot. No signal taken from it rather than guessing |
| [Cashfree](https://cashfree.com) | ⚠️ round 2 | Captured mid-animation; direct Razorpay competitor, worth a revisit for its dashboard specifically |

### Library decision (the "don't build from scratch" brief)

- **Tremor — rejected, with a reason.** It is the closest thing to a drop-in fintech dashboard
  kit, but it is Tailwind-native, and this project has a hand-authored token stylesheet with no
  Tailwind. Adopting it meant a Tailwind migration for chart components — the expensive path.
- **Recharts — adopted.** It is what Tremor itself wraps, it is framework-CSS-agnostic, and it
  takes concrete colour values that can be pointed straight at the existing tokens.
- **Radix UI primitives — installed** for the menu work still on the backlog (unstyled and
  CSS-agnostic, so they inherit the token system rather than fighting it).
- **lucide-react** (round 1) continues as the icon set.

Net: one chart library and one primitive library, both chosen so the round-1 token system stays
the single source of styling truth. No component kit's default look was inherited — which is
the specific failure mode ("untouched shadcn/Tailwind defaults") the round-0 checklist names.

### What was built

The biggest structural gap from round 1's backlog: **there was no Overview screen.** A merchant
landed directly on a filterable table, which answers "find one payment" but never "how is the
account doing". Every reference dashboard opens on a roll-up instead. This also closes two
items the project's own audit already had open — **P2-1** ("no merchant overview — no balance,
volume, success rate, or attention queue") and **§I Phase 6 item 19** (`GET /v1/analytics/summary`).

- **Backend:** new `analytics` module — `AnalyticsService` + `AnalyticsController` serving
  `GET /v1/analytics/summary`. A merchant gets their own numbers; an analyst/admin gets the
  platform-wide roll-up, the same scoping rule `PaymentController.resolveViewable()` uses.
  Warm-up scenery is excluded from every figure, the same lesson Day 6 and Day 10 both learned.
  The 14-day series `LEFT JOIN`s off `generate_series` so an empty day is a zero, not a gap that
  makes the chart lie.
- **"Awaiting settlement" rather than "balance"** — deliberately computed from
  `settled_at IS NULL AND written_off_at IS NULL` on captured payments (the Day 9 holdback
  window) rather than from ledger account balances. It is the number a merchant actually wants
  ("what is owed to me but not yet sent"), and it avoids inventing a second, competing
  definition of balance alongside the ledger's.
- **Frontend:** `OverviewView` — a 4-up KPI row (tinted icon tiles, mono tabular figures), a
  Recharts area chart with a gradient fill and a custom money-formatted tooltip, and a
  status-breakdown legend list built on the Tremor pattern. New `.page-head` style gives every
  screen a real title; before this, nothing on any page was larger than a 12px all-caps panel
  label.
- Overview is now the default landing route for merchant-capable roles.

### Verified

- **102/102 backend tests green** (100 existing + 2 new).
- **Mutation check:** neutralising the warm-up filter across all 9 aggregate queries
  (`is_warmup = false` → `is_warmup IN (true, false)`) failed exactly
  `warmupScenery_isExcludedFromEveryNumber` and nothing else; restored byte-identical; green
  again.
- **Endpoint checked over HTTP** before any UI existed for it: 579 payments, 74.44% success,
  ₹296,524.48 awaiting settlement, a full 14-point series.
- **Live at 1440×950:** KPI row resolves 4-across, chart axes and gradient render, legend bars
  are proportional, zero console errors on a fresh tab.
- **Honest note:** the volume chart shows a single spike, because every row in the dev database
  was seeded within one session. That is the data being truthful, not the chart being broken —
  no synthetic back-dating was added to make the demo prettier.

---

---

## Round 3 — reuse the primitives, and fix what reuse exposes

Two backlog items from round 2, both about not writing things twice.

### Shared primitives

`Kpi` and `LegendRow` moved out of `OverviewView` into `src/components/stats.tsx`. The Risk
console needed the identical shapes, and the alternative was a copy. The breakdown row in
particular (dot → label → proportional bar → right-aligned figure) is the pattern lifted from
Tremor's own cards, and it is what makes a pile of counts read as a composition instead of
six unrelated tiles.

### The Risk console's fraud panel

Was six `Stat` tiles of equal visual weight — three percentages and three counts, with no
indication that the counts are parts of one whole. Now: three KPI cards for the rates, then a
breakdown list for the four outcomes, which sum to `totalFraud` so the bars are a real
composition. Caught-before-capture, caught-after-capture (the Day 9 write-off), still-pending,
and missed now visibly partition the fraud population.

The Auto-Clear panel above it was **deliberately left alone** this round — its eight tiles
carry carefully-worded semantics (the `null` review-reduction baseline, the false-auto-clear
denominator) that are worth more than the visual tidying, and restructuring them is a copy
review, not a CSS change. It stays on the backlog.

### Review queue actions → a real menu

Four ghost buttons crammed edge-to-edge in one table cell became a single `Actions ⌄` trigger
over a Radix `DropdownMenu`: a **Decide** group (Claim for me / Clear — legitimate / Reject —
fraud, the destructive one in red), a separator, then Explain this flag. Radix supplies the
behaviour (focus trap, keyboard nav, dismiss, portal); every visual comes from the existing
tokens, so nothing of the library's default look was inherited.

### Two defects this round surfaced — both found by looking, not by tests

1. **`text-transform: capitalize` on `.legend-label`.** Written in round 2 for the Overview's
   enum-shaped statuses (`PENDING_REVIEW`). The moment the same component was reused for the
   Risk console's prose labels, it rendered "Caught Before Capture (Review)" — Title Case on a
   sentence. Removed the CSS transform entirely and moved the formatting to the one call site
   that actually needs it (`sentenceCase` in `OverviewView`). The general lesson: presentation
   transforms belong with the caller that knows the shape of its data, not on a shared class.
2. **The volume chart rendered empty.** Initially looked like a regression from the `Kpi`
   refactor. It wasn't — diagnosed by inspecting the SVG rather than guessing: the area path
   was perfectly flat at the baseline *while the Y-axis domain had correctly scaled to
   ₹600,000*, which proved the data had arrived and only the drawing was missing. Recharts
   animates an `Area` up from the baseline on mount, so anything sampling the page mid-animation
   sees an empty chart. Re-reading the DOM a few seconds later showed it filled in, confirming
   it. Fixed with `isAnimationActive={false}` — a dashboard should draw its data immediately,
   and entry motion communicates nothing here (it is also precisely the decorative animation
   the round-0 checklist flags).

### Verified

- Typecheck clean; backend still 102/102 from earlier this round (no backend change in round 3).
- Radix menu opens, is styled by the token set, and shows the destructive action in red.
- Chart draws filled **1 second** after a hard reload with animation disabled — the earlier
  empty capture is no longer reproducible.
- Breakdown labels render as prose on the Risk console and as sentence-case status names on the
  Overview, from the same component.
- **Dev-only noise, not an app defect:** Vite's HMR WebSocket repeatedly fails to connect in
  this preview pane, flooding the console and meaning edits do not hot-apply — every
  verification here was done against a hard reload for that reason. A production build ships no
  such socket.

---

---

## Round 4 — the USP screen stops being a pile of tiles

### Auto-Clear panel → a composition

Round 3 deliberately left this one alone. Its eight equal-weight tiles were the last place on
the product where the most important screen looked like a debug readout: three of those tiles
were *parts of one whole* and nothing said so.

Now four KPI cards carry the headline facts (flagged, auto-clear rate, manual review removed,
false auto-clears with the ratcheted threshold as its subtitle), over a breakdown list for
where the flagged population actually went. Those four rows genuinely partition
`flaggedTotal` — verified against live data: 78 auto-cleared + 0 human + 609 demo bot + 2
pending = 689 flagged, exactly. The careful copy that justified deferring it (the `null`
review-reduction baseline, the false-auto-clear denominator) is preserved, moved into card
subtitles rather than dropped.

With both panels converted, `Stat` had no callers left and was deleted.

### Avatar

A customer ref used to sit as a bare monospace string. `Avatar` (in the shared `stats.tsx`)
renders initials in a circle whose hue is derived from the ref itself, so the same customer
reads the same colour on every screen — recognisable rather than decorative, which is the only
defensible reason to show an avatar for an entity that has no picture. Used on Payment Detail's
customer block.

**Honest limitation:** for machine-generated refs like `customer_1789823510785` the initials
come out as "C1". Deterministic and harmless, but it flatters real customer names far more than
simulator output.

### Verified

- Both screens confirmed live against real data (Risk console composition; avatar on Payment
  Detail), typecheck clean, and the console carries no application errors — only the known Vite
  HMR WebSocket noise documented in round 3.

---

## Backlog for the next round (not yet done)

- **`.stat` / `.grid` CSS may now be orphaned** — `Stat` is gone, but other views may still use
  those classes. Verify before deleting; do not assume.
- **KPI cards carry no trend** — no sparkline or period-over-period delta, which every
  reference dashboard shows. Needs a second time window from the summary endpoint.
- **Payments table has no row-level affordance** — no full-row click, no bulk select, no column
  sort (server-side sort isn't implemented either). Scoped for this round, cut to keep the
  change set honest.
- **The Overview chart has one series.** Captured volume alone; failed/refunded as comparison
  series would make it a real analytics view rather than a single line. Needs the daily query
  extended.
- **Dark mode** still not attempted (references are light-first); it needs its own token pass,
  not a naive invert.
- **Cashfree's actual dashboard** deserves a proper look as the closest regional competitor —
  the marketing page captured mid-animation in round 2 gave nothing usable.
