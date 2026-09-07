import { useState } from "react";
import { auth, login } from "./api";
import { AnalystView } from "./views/AnalystView";
import { MerchantView } from "./views/MerchantView";

export function App() {
  const [role, setRole] = useState<string | null>(auth.role);
  const [tab, setTab] = useState<"analyst" | "merchant">(
    auth.role === "RISK_ANALYST" || auth.role === "SUPER_ADMIN" ? "analyst" : "merchant"
  );

  if (!role) return <Login onDone={(r) => { setRole(r); setTab(r === "MERCHANT_ADMIN" ? "merchant" : "analyst"); }} />;

  const canAnalyst = role === "RISK_ANALYST" || role === "SUPER_ADMIN";
  return (
    <>
      <header className="bar">
        <div>
          <h1>FinPay Console</h1>
          <span className="role">{role}</span>
        </div>
        <button className="ghost" onClick={() => { auth.clear(); setRole(null); }}>Sign out</button>
      </header>
      <div className="app">
        <nav className="tabs">
          {canAnalyst && (
            <button className={tab === "analyst" ? "active" : ""} onClick={() => setTab("analyst")}>
              Risk / Auto-Clear
            </button>
          )}
          <button className={tab === "merchant" ? "active" : ""} onClick={() => setTab("merchant")}>
            Merchant sandbox
          </button>
        </nav>
        {tab === "analyst" && canAnalyst ? <AnalystView /> : <MerchantView />}
      </div>
    </>
  );
}

function Login({ onDone }: { onDone: (role: string) => void }) {
  const [email, setEmail] = useState("admin@finpay.local");
  const [password, setPassword] = useState("admin12345");
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    try {
      const r = await login(email, password);
      auth.token = r.token;
      auth.role = r.role;
      onDone(r.role);
    } catch (e: any) {
      setErr(e.message);
    }
  }

  return (
    <div className="login panel">
      <h2>Sign in</h2>
      <form onSubmit={submit}>
        <div className="field"><label>Email</label>
          <input value={email} onChange={(e) => setEmail(e.target.value)} /></div>
        <div className="field" style={{ marginTop: 10 }}><label>Password</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></div>
        <button className="primary" style={{ marginTop: 14, width: "100%" }}>Sign in</button>
        {err && <div className="err">{err}</div>}
      </form>
      <p className="muted" style={{ fontSize: 12, marginTop: 14 }}>
        Default admin: admin@finpay.local / admin12345 (see BootstrapRunner).
      </p>
    </div>
  );
}
