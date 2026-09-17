import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";

export type Payment = {
  id: string; orderId: string; amountMinor: number; currency: string; method: string;
  status: string; riskDecision: string | null; riskBucket: string | null; riskScore: number | null;
  processorRef: string | null; failedReason: string | null;
  authorizedAt: string | null; capturedAt: string | null; createdAt: string;
};
type PageResult = { items: Payment[]; page: number; size: number; total: number };

const STATUSES = ["", "CREATED", "PENDING", "RISK_EVALUATING", "PENDING_REVIEW", "PROCESSING",
  "AUTHORIZED", "HELD", "CAPTURED", "REFUNDED", "FAILED", "CANCELLED", "VOIDED"];
const SIZE = 25;

/**
 * The Payments spine (interview sprint Day 11) — the audit's own C.1/C.3: real server-driven
 * filter + search + pagination on the primary object, replacing the fixed 15-row table with a
 * manual refresh button. Every row is clickable, addressable at /payments/:id.
 */
export function PaymentsView() {
  const [status, setStatus] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [q, setQ] = useState("");
  const [qInput, setQInput] = useState("");
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const params = new URLSearchParams({ page: String(page), size: String(SIZE) });
      if (status) params.set("status", status);
      if (from) params.set("from", from);
      if (to) params.set("to", to);
      if (q) params.set("q", q);
      const r = await api<PageResult>(`/v1/transactions?${params}`);
      setResult(r);
    } catch (e: any) {
      setErr(e.message);
    } finally {
      setLoading(false);
    }
  }, [status, from, to, q, page]);

  useEffect(() => { refresh(); }, [refresh]);

  function applySearch(e: React.FormEvent) {
    e.preventDefault();
    setPage(0);
    setQ(qInput.trim());
  }

  const totalPages = result ? Math.max(1, Math.ceil(result.total / SIZE)) : 1;

  return (
    <div className="panel">
      <h2>Payments</h2>
      <form className="row" style={{ marginBottom: 14 }} onSubmit={applySearch}>
        <div className="field" style={{ width: 260 }}>
          <label>search (payment or order id)</label>
          <input value={qInput} onChange={(e) => setQInput(e.target.value)} placeholder="a1b2c3d4…" />
        </div>
        <div className="field" style={{ width: 170 }}>
          <label>status</label>
          <select value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
            {STATUSES.map((s) => <option key={s} value={s}>{s || "any"}</option>)}
          </select>
        </div>
        <div className="field" style={{ width: 160 }}>
          <label>from</label>
          <input type="date" value={from} onChange={(e) => { setPage(0); setFrom(e.target.value); }} />
        </div>
        <div className="field" style={{ width: 160 }}>
          <label>to</label>
          <input type="date" value={to} onChange={(e) => { setPage(0); setTo(e.target.value); }} />
        </div>
        <button className="primary" type="submit">Search</button>
        {(status || from || to || q) && (
          <button type="button" className="ghost"
                  onClick={() => { setStatus(""); setFrom(""); setTo(""); setQ(""); setQInput(""); setPage(0); }}>
            Clear filters
          </button>
        )}
      </form>

      {err && <div className="err">{err}</div>}

      <table>
        <thead>
          <tr><th>payment</th><th>status</th><th>risk</th><th>amount</th><th>method</th><th>created</th></tr>
        </thead>
        <tbody>
          {result?.items.map((p) => (
            <tr key={p.id}>
              <td className="mono">
                <Link to={`/payments/${p.id}`} state={p}>{p.id.slice(0, 8)}</Link>
              </td>
              <td><span className={`pill ${p.status}`}>{p.status}</span></td>
              <td>{p.riskBucket ? <span className={`pill ${p.riskBucket}`}>{p.riskBucket}</span> : "-"}</td>
              <td>{(p.amountMinor / 100).toFixed(2)} {p.currency}</td>
              <td className="muted">{p.method}</td>
              <td className="muted">{new Date(p.createdAt).toLocaleString()}</td>
            </tr>
          ))}
          {!loading && result?.items.length === 0 && (
            <tr><td colSpan={6} className="muted">
              {status || from || to || q ? "no payments match these filters" : "no payments yet"}
            </td></tr>
          )}
          {loading && <tr><td colSpan={6} className="muted">loading…</td></tr>}
        </tbody>
      </table>

      {result && result.total > 0 && (
        <div className="row" style={{ marginTop: 12, justifyContent: "space-between" }}>
          <span className="muted" style={{ fontSize: 12 }}>
            {result.total} payment{result.total === 1 ? "" : "s"} — page {page + 1} of {totalPages}
          </span>
          <div className="row" style={{ gap: 6 }}>
            <button className="ghost small" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← prev</button>
            <button className="ghost small" disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}>next →</button>
          </div>
        </div>
      )}
    </div>
  );
}
