import { useState } from "react";
import { BrowserRouter, Routes, Route, Navigate, NavLink } from "react-router-dom";
import {
  Receipt, ShoppingBag, Undo2, Landmark, Users, FlaskConical, ShieldHalf,
} from "lucide-react";
import { auth, login } from "./api";
import { AnalystView } from "./views/AnalystView";
import { PaymentsView } from "./views/PaymentsView";
import { PaymentDetailView } from "./views/PaymentDetailView";
import { SandboxView } from "./views/SandboxView";
import { ComingSoonView } from "./views/ComingSoonView";

function LogoMark() {
  return <span className="logo-mark">F</span>;
}

export function App() {
  const [role, setRole] = useState<string | null>(auth.role);

  if (!role) return <Login onDone={setRole} />;

  return (
    <BrowserRouter>
      <Shell role={role} onSignOut={() => { auth.clear(); setRole(null); }} />
    </BrowserRouter>
  );
}

const MERCHANT_STUBS = ["Orders", "Refunds", "Settlements", "Customers"];
const STUB_ICONS: Record<string, React.ReactNode> = {
  Orders: <ShoppingBag size={17} />,
  Refunds: <Undo2 size={17} />,
  Settlements: <Landmark size={17} />,
  Customers: <Users size={17} />,
};

/**
 * The navigation shell (interview sprint Day 11). Replaces the two-button flat tab bar with a
 * left rail that mirrors the resource model — Payments, Orders, Refunds, Settlements,
 * Customers, Risk — so the product's domain is legible before you click anything (the audit's
 * §G.2 recommendation). Every item is a real route, even the ones this phase hasn't built out:
 * a resource the object model names is addressable and says so honestly, rather than a greyed
 * label that gives no feedback on click.
 */
function Shell({ role, onSignOut }: { role: string; onSignOut: () => void }) {
  const canAnalyst = role === "RISK_ANALYST" || role === "SUPER_ADMIN";
  const canMerchant = role === "MERCHANT" || role === "MERCHANT_ADMIN" || role === "SUPER_ADMIN";

  return (
    <>
      <header className="bar">
        <div className="brand">
          <LogoMark />
          <div>
            <h1>FinPay Console</h1>
          </div>
          <span className="role">{role.replaceAll("_", " ")}</span>
        </div>
        <button className="ghost" onClick={onSignOut}>Sign out</button>
      </header>
      <div className="app">
        <nav className="rail">
          {canMerchant && (
            <>
              <div className="section">Merchant</div>
              <RailLink to="/payments" icon={<Receipt size={17} />}>Payments</RailLink>
              {MERCHANT_STUBS.map((label) => (
                <RailLink key={label} to={`/${label.toLowerCase()}`} icon={STUB_ICONS[label]}>{label}</RailLink>
              ))}
              <RailLink to="/sandbox" icon={<FlaskConical size={17} />}>Sandbox</RailLink>
            </>
          )}
          {canAnalyst && (
            <>
              <div className="section">Fraud</div>
              <RailLink to="/risk" icon={<ShieldHalf size={17} />}>Risk / Auto-Clear</RailLink>
            </>
          )}
        </nav>
        <main className="main">
          <Routes>
            <Route path="/payments" element={canMerchant ? <PaymentsView /> : <Navigate to="/risk" replace />} />
            <Route path="/payments/:id" element={canMerchant ? <PaymentDetailView /> : <Navigate to="/risk" replace />} />
            {MERCHANT_STUBS.map((label) => (
              <Route key={label} path={`/${label.toLowerCase()}`}
                     element={canMerchant ? <ComingSoonView label={label} /> : <Navigate to="/risk" replace />} />
            ))}
            <Route path="/sandbox" element={canMerchant ? <SandboxView /> : <Navigate to="/risk" replace />} />
            <Route path="/risk" element={canAnalyst ? <AnalystView /> : <Navigate to="/payments" replace />} />
            <Route path="*" element={<Navigate to={canMerchant ? "/payments" : "/risk"} replace />} />
          </Routes>
        </main>
      </div>
    </>
  );
}

function RailLink({ to, icon, children }: { to: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <NavLink to={to} className={({ isActive }) => (isActive ? "active" : "")}>
      {icon}
      {children}
    </NavLink>
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
      <LogoMark />
      <h2 style={{ marginTop: 14 }}>Sign in to FinPay</h2>
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
