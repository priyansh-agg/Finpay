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

/**
 * Initials in a tinted circle, for the places a bare customer ref used to sit on its own.
 * The hue is derived from the ref itself, so the same customer always reads the same colour
 * across screens — recognisable without being decorative, which is the only reason to show an
 * avatar for an entity that has no picture.
 */
export function Avatar({ label, size = 28 }: { label: string; size?: number }) {
  const words = label.replace(/[^a-zA-Z0-9]+/g, " ").trim().split(/\s+/).filter(Boolean);
  const initials = words.slice(0, 2).map((w) => w[0]).join("").toUpperCase() || "?";
  let hue = 0;
  for (let i = 0; i < label.length; i++) hue = (hue * 31 + label.charCodeAt(i)) % 360;
  return (
    <span
      className="avatar"
      title={label}
      style={{
        width: size, height: size, fontSize: Math.round(size * 0.38),
        background: `hsl(${hue} 60% 94%)`, color: `hsl(${hue} 45% 32%)`,
      }}
    >
      {initials}
    </span>
  );
}
