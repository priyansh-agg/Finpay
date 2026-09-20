import { useEffect, useState } from "react";
import {
  Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import { TrendingUp, TriangleAlert, Wallet, Percent } from "lucide-react";
import { api, formatMoney } from "../api";
import { Kpi } from "../components/stats";

type DailyPoint = { date: string; capturedMinor: number; count: number };
type StatusSlice = { status: string; count: number };
type Summary = {
  awaitingSettlementMinor: number;
  capturedVolumeMinor: number;
  capturedCount: number;
  totalPayments: number;
  successRatePct: number | null;
  heldCount: number;
  pendingReviewCount: number;
  failedCount: number;
  daily: DailyPoint[];
  statusCounts: StatusSlice[];
};

/** Status → the same semantic colours the badges use, as concrete values for SVG/inline use. */
const STATUS_COLOR: Record<string, string> = {
  CAPTURED: "#16824a",
  REFUNDED: "#0f7490",
  HELD: "#2650c4",
  PENDING_REVIEW: "#8a5c07",
  AUTHORIZED: "#2650c4",
  FAILED: "#b3261e",
  VOIDED: "#8c1717",
};
const NEUTRAL = "#848aa3";
const PRIMARY = "#2f5fe0";

/** PENDING_REVIEW -> "Pending review". Done here, not in CSS, so prose labels elsewhere in the
 *  same list component aren't Title-Cased word by word. */
const sentenceCase = (s: string) => {
  const t = s.replaceAll("_", " ").toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
};

/**
 * The Overview screen (design round 2). Every reference dashboard — Razorpay's home, Stripe's,
 * Mercury's — opens on a roll-up: what you're owed, what moved, what needs a human. FinPay
 * used to land the merchant straight on a filterable table, which answers "find one payment"
 * but never "how is the account doing".
 */
export function OverviewView() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api<Summary>("/v1/analytics/summary").then(setSummary).catch((e) => setErr(e.message));
  }, []);

  if (err) return <div className="panel"><div className="err">{err}</div></div>;
  if (!summary) return <div className="panel"><p className="muted">loading…</p></div>;

  const attention = summary.heldCount + summary.pendingReviewCount;
  const chartData = summary.daily.map((d) => ({
    label: new Date(`${d.date}T00:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" }),
    captured: d.capturedMinor / 100,
    count: d.count,
  }));
  const hasTraffic = summary.totalPayments > 0;

  return (
    <>
      <div className="page-head">
        <h1>Overview</h1>
        <p>Balance, volume and anything waiting on a human — across every payment this account has taken.</p>
      </div>

      <div className="kpi-row">
        <Kpi
          icon={<Wallet size={16} />} tone="blue" label="Awaiting settlement"
          value={formatMoney(summary.awaitingSettlementMinor, "INR")}
          sub="captured, not yet paid out"
        />
        <Kpi
          icon={<TrendingUp size={16} />} tone="green" label="Captured volume"
          value={formatMoney(summary.capturedVolumeMinor, "INR")}
          sub={`${summary.capturedCount.toLocaleString()} of ${summary.totalPayments.toLocaleString()} payments`}
        />
        <Kpi
          icon={<Percent size={16} />} tone="violet" label="Success rate"
          value={summary.successRatePct == null ? "—" : `${summary.successRatePct}%`}
          sub={summary.successRatePct == null ? "no payments yet" : "captured over all attempts"}
        />
        <Kpi
          icon={<TriangleAlert size={16} />} tone={attention > 0 ? "amber" : "grey"} label="Needs attention"
          value={attention.toLocaleString()}
          sub={`${summary.heldCount} held · ${summary.pendingReviewCount} in review`}
        />
      </div>

      <div className="panel">
        <h2>Captured volume — last 14 days</h2>
        {hasTraffic ? (
          <div className="chart-wrap">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={chartData} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <defs>
                  <linearGradient id="volumeFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={PRIMARY} stopOpacity={0.22} />
                    <stop offset="100%" stopColor={PRIMARY} stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e5ee" />
                <XAxis dataKey="label" tickLine={false} axisLine={false}
                       tick={{ fontSize: 11, fill: "#676d87" }} />
                <YAxis tickLine={false} axisLine={false} width={62}
                       tick={{ fontSize: 11, fill: "#676d87" }}
                       tickFormatter={(v: number) => `₹${v.toLocaleString()}`} />
                <Tooltip content={<VolumeTooltip />} cursor={{ stroke: "#ccd1e0" }} />
                {/* Drawn immediately, not animated in. Recharts grows an Area up from the
                    baseline on mount, so anything that samples the page mid-animation — a
                    screenshot, a slow frame, a background tab — sees an empty chart. Entry
                    motion communicates nothing here; determinism does. */}
                <Area type="monotone" dataKey="captured" stroke={PRIMARY} strokeWidth={2}
                      fill="url(#volumeFill)" isAnimationActive={false} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <p className="muted">
            No payments yet — take one from <strong>Sandbox</strong>, or generate a scenario from
            the Risk console, and this fills in.
          </p>
        )}
      </div>

      <div className="panel">
        <h2>Payments by status</h2>
        {summary.statusCounts.length === 0 ? (
          <p className="muted">nothing to break down yet</p>
        ) : (
          <ul className="legend-list">
            {summary.statusCounts.map((s) => (
              <li key={s.status}>
                <span className="legend-dot" style={{ background: STATUS_COLOR[s.status] ?? NEUTRAL }} />
                <span className="legend-label">{sentenceCase(s.status)}</span>
                <span className="legend-bar">
                  <span style={{
                    width: `${Math.max(2, (s.count / summary.totalPayments) * 100)}%`,
                    background: STATUS_COLOR[s.status] ?? NEUTRAL,
                  }} />
                </span>
                <span className="legend-value num">{s.count.toLocaleString()}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}

function VolumeTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  const point = payload[0].payload;
  return (
    <div className="chart-tip">
      <div className="chart-tip-date">{label}</div>
      <div className="chart-tip-value">{formatMoney(Math.round(point.captured * 100), "INR")}</div>
      <div className="chart-tip-sub">{point.count} payment{point.count === 1 ? "" : "s"}</div>
    </div>
  );
}
