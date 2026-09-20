import { Construction } from "lucide-react";

export function ComingSoonView({ label }: { label: string }) {
  return (
    <div className="panel empty-state">
      <Construction size={28} strokeWidth={1.5} />
      <h2 style={{ margin: "14px 0 6px" }}>{label}</h2>
      <p className="muted">
        {label} isn't built out yet in this phase of the sprint — the resource is named in the
        navigation on purpose, so the product's shape stays honest about what exists.
      </p>
    </div>
  );
}
