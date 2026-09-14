import { useCallback, useEffect, useState } from "react";
import { api } from "../api";

type Metrics = {
  flaggedTotal: number; decided: number; autoCleared: number; referenceReviewed: number;
  autoClearRatePct: number; manualReviewReductionPct: number | null;
  autoClearsResolved: number; falseAutoClears: number; falseAutoClearRatePct: number; threshold: number;
};
type Review = {
  id: string; paymentId: string; queueStatus: string; reviewerType: string | null;
  decision: string | null; reason: string | null; riskScore: number | null;
  features: any; createdAt: string; decidedAt: string | null;
};

export function AnalystView() {
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [queue, setQueue] = useState<Review[]>([]);
  const [status, setStatus] = useState<"PENDING" | "DECIDED">("PENDING");
  const [err, setErr] = useState<string | null>(null);
  const [explains, setExplains] = useState<Record<string, any>>({});
  const [simCount, setSimCount] = useState(300);

  const refresh = useCallback(async () => {
    try {
      const [m, q] = await Promise.all([
        api<Metrics>("/v1/analyst/metrics/autoclear"),
        api<Review[]>(`/v1/analyst/reviews?status=${status}&limit=60`),
      ]);
      setMetrics(m); setQueue(q); setErr(null);
    } catch (e: any) { setErr(e.message); }
  }, [status]);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, [refresh]);

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
    try { await api("/v1/admin/simulate", { method: "POST", body: { count: simCount, fraudRate: 0.12 } }); }
    catch (e: any) { setErr(e.message); }
    setTimeout(refresh, 1500);
  }

  return (
    <>
      <div className="panel">
        <h2>Auto-Clear Engine — impact</h2>
        {metrics && (
          <div className="grid">
            <Stat n={metrics.flaggedTotal} l="transactions flagged for review" />
            <Stat n={metrics.autoCleared} l="auto-cleared (no human)" good />
            <Stat n={metrics.referenceReviewed} l="sent to an analyst" />
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
            &ldquo;Manual review volume removed&rdquo; needs a real human-reviewed baseline to divide by, which the
            review queue doesn&rsquo;t have yet — every review is currently decided automatically.
          </p>
        )}
      </div>

      <div className="panel">
        <h2>Generate traffic (SUPER_ADMIN)</h2>
        <div className="row">
          <div className="field" style={{ width: 140 }}>
            <label>transactions</label>
            <input type="number" value={simCount} onChange={(e) => setSimCount(+e.target.value)} />
          </div>
          <button className="primary" onClick={runSim}>Run simulation</button>
          <span className="muted">12% fraud, injected as realistic patterns.</span>
        </div>
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
            <tr><th>payment</th><th>risk</th><th>reason</th><th>decided by</th><th>outcome</th><th></th></tr>
          </thead>
          <tbody>
            {queue.map((r) => (
              <tr key={r.id}>
                <td className="mono">{r.paymentId.slice(0, 8)}</td>
                <td>{r.riskScore?.toFixed(1) ?? "-"}</td>
                <td className="muted" style={{ maxWidth: 320 }}>{r.reason}</td>
                <td>{r.reviewerType ? <span className={`pill ${r.reviewerType}`}>{r.reviewerType}</span> : "-"}</td>
                <td>{r.decision ? <span className={`pill ${r.decision}`}>{r.decision}</span> : "-"}</td>
                <td style={{ whiteSpace: "nowrap" }}>
                  {r.queueStatus === "PENDING" ? (
                    <>
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
