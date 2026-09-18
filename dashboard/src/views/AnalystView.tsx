import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

type Metrics = {
  flaggedTotal: number; pendingHuman: number; decided: number; autoCleared: number;
  humanReviewed: number; simulatedAnalystReviewed: number;
  autoClearRatePct: number; manualReviewReductionPct: number | null;
  autoClearsResolved: number; falseAutoClears: number; falseAutoClearRatePct: number; threshold: number;
};
type FraudMetrics = {
  totalFraud: number; totalLegit: number;
  fraudCaughtByReview: number; fraudCaughtByWriteOff: number; fraudCaught: number; fraudCaughtMinor: number;
  fraudMissed: number; fraudMissedMinor: number; fraudStillPending: number;
  legitWronglyRejected: number; legitCorrectlyCleared: number;
  recallPct: number | null; precisionPct: number | null; falsePositiveRatePct: number | null;
};
type Review = {
  id: string; paymentId: string; queueStatus: string; reviewerType: string | null;
  decision: string | null; reason: string | null; riskScore: number | null;
  features: any; createdAt: string; decidedAt: string | null;
  assignedTo: string | null; claimedAt: string | null;
};
type SimSummary = {
  runId: string; merchantId: string; seed: number;
  requestedDraws: number; requestedFraudRate: number;
  populationPayments: number; warmupPayments: number; legit: number; fraud: number;
  bucketCounts: Record<string, number>; statusCounts: Record<string, number>;
};
type BatchResult = { batchId: string; merchantCount: number; totalMinor: number; writtenOffCount: number; writtenOffMinor: number };

export function AnalystView() {
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [fraudMetrics, setFraudMetrics] = useState<FraudMetrics | null>(null);
  const [queue, setQueue] = useState<Review[]>([]);
  const [status, setStatus] = useState<"PENDING" | "DECIDED">("PENDING");
  const [err, setErr] = useState<string | null>(null);
  const [explains, setExplains] = useState<Record<string, any>>({});

  // Scenario controls (interview sprint Day 13) — every knob SimulationScenario actually
  // supports, not just a transaction count. Rates are edited as whole percentages.
  const [count, setCount] = useState(300);
  const [fraudRatePct, setFraudRatePct] = useState(12);
  const [stealthRatePct, setStealthRatePct] = useState(0);
  const [fraudShapedLegitRatePct, setFraudShapedLegitRatePct] = useState(0);
  const [seed, setSeed] = useState("");
  const [simBusy, setSimBusy] = useState(false);

  // The lifecycle of the last run this tab started: run -> resolve -> settle. Nothing here
  // persists across a reload — it's a demo aid, not a record; SimulationRun rows are the record.
  const [lastRun, setLastRun] = useState<SimSummary | null>(null);
  const [lastBatch, setLastBatch] = useState<BatchResult | null>(null);
  const [scope, setScope] = useState<"all" | "run">("all");

  const runIdQuery = scope === "run" && lastRun ? `?${new URLSearchParams({ runId: lastRun.runId })}` : "";

  const refresh = useCallback(async () => {
    try {
      const [m, fm, q] = await Promise.all([
        api<Metrics>(`/v1/analyst/metrics/autoclear${runIdQuery}`),
        api<FraudMetrics>(`/v1/analyst/metrics/fraud${runIdQuery}`),
        api<Review[]>(`/v1/analyst/reviews?status=${status}&limit=60`),
      ]);
      setMetrics(m); setFraudMetrics(fm); setQueue(q); setErr(null);
    } catch (e: any) { setErr(e.message); }
  }, [status, runIdQuery]);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, [refresh]);

  async function claim(id: string) {
    try { await api(`/v1/analyst/reviews/${id}/claim`, { method: "POST" }); refresh(); }
    catch (e: any) { setErr(e.message); }
  }
  async function decide(id: string, decision: "CLEAR" | "REJECT") {
    await api(`/v1/analyst/reviews/${id}/decide`, { method: "POST", body: { decision, reason: "manual" } });
    refresh();
  }
  async function explain(paymentId: string) {
    const e = await api(`/v1/risk/${paymentId}/explain`);
    setExplains((s) => ({ ...s, [paymentId]: e }));
  }

  async function runSim() {
    setErr(null);
    setSimBusy(true);
    setLastBatch(null);
    try {
      const summary = await api<SimSummary>("/v1/admin/simulate", {
        method: "POST",
        body: {
          count,
          fraudRate: fraudRatePct / 100,
          stealthRate: stealthRatePct / 100,
          fraudShapedLegitRate: fraudShapedLegitRatePct / 100,
          seed: seed.trim() === "" ? undefined : Number(seed),
        },
      });
      setLastRun(summary);
      setScope("run");
    } catch (e: any) { setErr(e.message); }
    setSimBusy(false);
    setTimeout(refresh, 1000);
  }

  async function resolveRun() {
    if (!lastRun) return;
    setErr(null);
    setSimBusy(true);
    try {
      const summary = await api<SimSummary>(`/v1/admin/simulate/${lastRun.runId}/resolve`, { method: "POST" });
      setLastRun(summary);
    } catch (e: any) { setErr(e.message); }
    setSimBusy(false);
    refresh();
  }

  async function runSettlement() {
    setErr(null);
    setSimBusy(true);
    try {
      const batch = await api<BatchResult>("/v1/settlements/run", { method: "POST" });
      setLastBatch(batch);
    } catch (e: any) { setErr(e.message); }
    setSimBusy(false);
    refresh();
  }

  async function resetSim() {
    setErr(null);
    try { await api("/v1/admin/simulate/reset", { method: "POST" }); }
    catch (e: any) { setErr(e.message); }
    refresh();
  }

  return (
    <>
      {lastRun && (
        <div className="panel" style={{ paddingBottom: 12 }}>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span className="muted" style={{ fontSize: 12 }}>
              Metrics below scoped to: run <span className="mono">{lastRun.runId.slice(0, 8)}</span> (seed {lastRun.seed})
            </span>
            <div className="row" style={{ gap: 6 }}>
              <button className={scope === "all" ? "primary small" : "ghost small"} onClick={() => setScope("all")}>all time</button>
              <button className={scope === "run" ? "primary small" : "ghost small"} onClick={() => setScope("run")}>this run only</button>
            </div>
          </div>
        </div>
      )}

      <div className="panel">
        <h2>Auto-Clear Engine — impact</h2>
        {metrics && (
          <div className="grid">
            <Stat n={metrics.flaggedTotal} l="transactions flagged for review" />
            <Stat n={metrics.autoCleared} l="auto-cleared (no human)" good />
            <Stat n={metrics.pendingHuman} l="pending — awaiting a human" />
            <Stat n={metrics.humanReviewed} l="reviewed by a human" />
            <Stat n={metrics.simulatedAnalystReviewed} l="reviewed by the demo bot (not a human)" />
            <Stat
              n={metrics.manualReviewReductionPct == null ? "—" : `${metrics.manualReviewReductionPct}%`}
              l="manual review volume removed"
              good={metrics.manualReviewReductionPct != null}
            />
            <Stat
              n={metrics.falseAutoClears}
              l={`false auto-clears (of ${metrics.autoClearsResolved} resolved)`}
              bad={metrics.falseAutoClears > 0}
              good={metrics.falseAutoClears === 0}
            />
            <Stat n={metrics.threshold} l="auto-clear confidence threshold (calibrated)" />
          </div>
        )}
        <p className="muted" style={{ fontSize: 12, marginTop: 12 }}>
          Ground truth (<span className="mono">actually_fraudulent</span>) is simulated and resolves after a delay,
          then reconciled against every auto-clear. A single false auto-clear ratchets the threshold up.
        </p>
        {metrics?.manualReviewReductionPct == null && (
          <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
            &ldquo;Manual review volume removed&rdquo; needs a real human-reviewed baseline to divide by — decide at
            least one review below (not just via the demo bot) to make this a real number.
          </p>
        )}
      </div>

      <div className="panel">
        <h2>Fraud detection effectiveness</h2>
        {fraudMetrics && (
          <div className="grid">
            <Stat
              n={fraudMetrics.recallPct == null ? "—" : `${fraudMetrics.recallPct}%`}
              l={`recall — fraud caught (${fraudMetrics.fraudCaught} of ${fraudMetrics.totalFraud})`}
              good={fraudMetrics.recallPct != null && fraudMetrics.recallPct >= 90}
            />
            <Stat
              n={fraudMetrics.precisionPct == null ? "—" : `${fraudMetrics.precisionPct}%`}
              l="precision — of everything blocked, how much was real fraud"
              good={fraudMetrics.precisionPct != null && fraudMetrics.precisionPct >= 90}
            />
            <Stat
              n={fraudMetrics.falsePositiveRatePct == null ? "—" : `${fraudMetrics.falsePositiveRatePct}%`}
              l={`false-positive rate (${fraudMetrics.legitWronglyRejected} of ${fraudMetrics.totalLegit} legit)`}
              bad={fraudMetrics.falsePositiveRatePct != null && fraudMetrics.falsePositiveRatePct > 20}
            />
            <Stat n={fraudMetrics.fraudCaughtByReview} l="fraud caught before capture (review)" good />
            <Stat n={fraudMetrics.fraudCaughtByWriteOff} l="fraud caught after capture (settlement write-off)" good />
            <Stat
              n={fraudMetrics.fraudMissed}
              l={`fraud missed — reached the merchant (₹${(fraudMetrics.fraudMissedMinor / 100).toLocaleString()})`}
              bad={fraudMetrics.fraudMissed > 0}
              good={fraudMetrics.fraudMissed === 0}
            />
          </div>
        )}
        <p className="muted" style={{ fontSize: 12, marginTop: 12 }}>
          Scored against the hidden ground truth (<span className="mono">actually_fraudulent</span>) — evaluation
          only, never a decision input. "Caught" covers both a review rejecting it before capture and a settlement
          write-off catching it after capture but before payout (the closer for the auto-capture bypass a perfect
          stealth-fraud transaction can otherwise ride straight through).
        </p>
      </div>

      <div className="panel">
        <h2>Generate traffic (SUPER_ADMIN)</h2>
        <div className="row">
          <div className="field" style={{ width: 120 }}>
            <label>transactions</label>
            <input type="number" value={count} onChange={(e) => setCount(+e.target.value)} />
          </div>
          <div className="field" style={{ width: 110 }}>
            <label>fraud %</label>
            <input type="number" min={0} max={100} value={fraudRatePct} onChange={(e) => setFraudRatePct(+e.target.value)} />
          </div>
          <div className="field" style={{ width: 110 }}>
            <label>stealth %</label>
            <input type="number" min={0} max={100} value={stealthRatePct} onChange={(e) => setStealthRatePct(+e.target.value)} />
          </div>
          <div className="field" style={{ width: 150 }}>
            <label>fraud-shaped-legit %</label>
            <input type="number" min={0} max={100} value={fraudShapedLegitRatePct}
                   onChange={(e) => setFraudShapedLegitRatePct(+e.target.value)} />
          </div>
          <div className="field" style={{ width: 120 }}>
            <label>seed (optional)</label>
            <input value={seed} onChange={(e) => setSeed(e.target.value)} placeholder="random" />
          </div>
          <button className="primary" onClick={runSim} disabled={simBusy}>Run simulation</button>
          <button className="ghost" onClick={resetSim} disabled={simBusy}>Reset threshold + Redis</button>
        </div>
        <p className="muted" style={{ fontSize: 12, marginTop: 10 }}>
          <strong>stealth</strong>: fraud with no rule signal — the only population "zero false auto-clears" means
          anything over. <strong>fraud-shaped-legit</strong>: genuine customers who trip a rule anyway — what
          "review reduction" and the false-positive rate above are actually about. Same seed + same rates ⇒ same
          numbers, once threshold/Redis are reset.
        </p>

        {lastRun && (
          <div style={{ marginTop: 16, paddingTop: 14, borderTop: "1px solid var(--border)" }}>
            <div className="row" style={{ justifyContent: "space-between", marginBottom: 10 }}>
              <span>
                Run <span className="mono">{lastRun.runId.slice(0, 8)}</span> — {lastRun.populationPayments} population
                + {lastRun.warmupPayments} warm-up ({lastRun.legit} legit / {lastRun.fraud} fraud drawn)
              </span>
              <div className="row" style={{ gap: 6 }}>
                <button className="ghost small" onClick={resolveRun} disabled={simBusy}>Resolve now</button>
                <button className="ghost small" onClick={runSettlement} disabled={simBusy}>Run settlement batch</button>
              </div>
            </div>
            <div className="muted" style={{ fontSize: 12 }}>
              statuses: {Object.entries(lastRun.statusCounts).map(([k, v]) => `${k} ${v}`).join(", ") || "—"}
            </div>
            {lastBatch && (
              <p className="muted" style={{ fontSize: 12, marginTop: 8 }}>
                Settlement batch: {lastBatch.merchantCount} merchant(s) paid ₹{(lastBatch.totalMinor / 100).toLocaleString()};{" "}
                <strong>
                  {lastBatch.writtenOffCount} confirmed-fraud payment(s) written off (₹{(lastBatch.writtenOffMinor / 100).toLocaleString()})
                </strong> before they could pay out.
              </p>
            )}
          </div>
        )}
      </div>

      <div className="panel">
        <h2>
          Review queue
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value as any)}
            style={{ width: 160, marginLeft: 12, display: "inline-block" }}
          >
            <option value="PENDING">Pending</option>
            <option value="DECIDED">Decided</option>
          </select>
        </h2>
        {err && <div className="err">{err}</div>}
        <table>
          <thead>
            <tr><th>payment</th><th>risk</th><th>reason</th><th>claimed by</th><th>decided by</th><th>outcome</th><th></th></tr>
          </thead>
          <tbody>
            {queue.map((r) => (
              <tr key={r.id}>
                <td className="mono">{r.paymentId.slice(0, 8)}</td>
                <td>{r.riskScore?.toFixed(1) ?? "-"}</td>
                <td className="muted" style={{ maxWidth: 320 }}>{r.reason}</td>
                <td className="mono">{r.assignedTo ?? "-"}</td>
                <td>{r.reviewerType ? <span className={`pill ${r.reviewerType}`}>{r.reviewerType}</span> : "-"}</td>
                <td>{r.decision ? <span className={`pill ${r.decision}`}>{r.decision}</span> : "-"}</td>
                <td style={{ whiteSpace: "nowrap" }}>
                  {r.queueStatus === "PENDING" ? (
                    <>
                      {!r.assignedTo && <button className="ghost small" onClick={() => claim(r.id)}>claim</button>}{" "}
                      <button className="ghost small" onClick={() => decide(r.id, "CLEAR")}>clear</button>{" "}
                      <button className="ghost small" onClick={() => decide(r.id, "REJECT")}>reject</button>{" "}
                    </>
                  ) : null}
                  <button className="ghost small" onClick={() => explain(r.paymentId)}>explain</button>
                  {explains[r.paymentId] && (
                    <details className="explain" open>
                      <summary>{explains[r.paymentId].model}</summary>
                      <div className="muted">{explains[r.paymentId].text}</div>
                    </details>
                  )}
                </td>
              </tr>
            ))}
            {queue.length === 0 && <tr><td colSpan={6} className="muted">queue empty</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Stat({ n, l, good, bad }: { n: React.ReactNode; l: string; good?: boolean; bad?: boolean }) {
  return (
    <div className={`stat ${good ? "good" : ""} ${bad ? "bad" : ""}`}>
      <div className="n">{n}</div>
      <div className="l">{l}</div>
    </div>
  );
}
