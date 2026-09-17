export function ComingSoonView({ label }: { label: string }) {
  return (
    <div className="panel">
      <h2>{label}</h2>
      <p className="muted">
        {label} isn't built out yet in this phase of the sprint — the resource is named in the
        navigation on purpose, so the product's shape stays honest about what exists.
      </p>
    </div>
  );
}
