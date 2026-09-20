/**
 * The two card/row primitives the Overview and Risk screens share (design round 3). Both were
 * first written inline on the Overview screen; the Risk console needed the identical shapes, so
 * they moved here rather than being copied — the breakdown-list pattern in particular (dot →
 * label → proportional bar → right-aligned figure) is lifted from Tremor's own dashboard cards
 * and is the thing that makes a pile of counts read as a composition.
 */

export function Kpi({ icon, tone = "grey", label, value, sub }: {
  icon?: React.ReactNode;
  tone?: "blue" | "green" | "amber" | "violet" | "grey";
  label: string;
  value: React.ReactNode;
  sub?: string;
}) {
  return (
    <div className="kpi">
      <div className="kpi-head">
        {icon && <span className={`kpi-icon ${tone}`}>{icon}</span>}
        <span className="kpi-label">{label}</span>
      </div>
      <div className="kpi-value">{value}</div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </div>
  );
}

/** One row of a breakdown list. {@code total} only drives the bar width, never the figure shown. */
export function LegendRow({ color, label, value, total }: {
  color: string;
  label: React.ReactNode;
  value: number;
  total: number;
}) {
  const pct = total > 0 ? (value / total) * 100 : 0;
  return (
    <li>
      <span className="legend-dot" style={{ background: color }} />
      <span className="legend-label">{label}</span>
      <span className="legend-bar">
        <span style={{ width: `${value > 0 ? Math.max(2, pct) : 0}%`, background: color }} />
      </span>
      <span className="legend-value num">{value.toLocaleString()}</span>
    </li>
  );
}
