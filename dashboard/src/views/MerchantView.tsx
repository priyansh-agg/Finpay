import { useState } from "react";
import { api, auth } from "../api";

export function MerchantView() {
  const [apiKey, setApiKey] = useState(auth.apiKey ?? "");
  const [log, setLog] = useState<any[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [amount, setAmount] = useState(45000);
  const [receipt, setReceipt] = useState("");
  const [link, setLink] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  // "direct pay" (server-to-server) form — kept for API testing
  const [payOrderId, setPayOrderId] = useState("");
  const [token, setToken] = useState("tok_valid");
  const [device, setDevice] = useState("dev-web-1");

  const [txns, setTxns] = useState<any[]>([]);

  function saveKey() { auth.apiKey = apiKey || null; push({ note: "API key saved locally" }); }
  function push(x: any) { setLog((l) => [{ t: new Date().toLocaleTimeString(), ...x }, ...l].slice(0, 40)); }

  async function onboard() {
    setErr(null);
    try {
      const r = await api("/v1/admin/merchants", {
        method: "POST",
        body: { name: "Sandbox " + Math.floor(Math.random() * 1e4), email: `s${Date.now()}@ex.com`, adminPassword: "sandbox123" },
      });
      setApiKey(r.apiKey.rawKey);
      auth.apiKey = r.apiKey.rawKey;
      push({ note: "merchant onboarded", merchantId: r.merchantId });
    } catch (e: any) { setErr(e.message); }
  }

  async function createLink() {
    setErr(null);
    setCopied(false);
    try {
      const order = await api<{ id: string }>("/v1/orders", {
        asMerchant: true,
        method: "POST",
        body: { customerRef: "customer_" + Date.now(), amountMinor: amount, currency: "INR", receipt: receipt || undefined },
      });
      const url = `${location.origin}/pay/${order.id}`;
      setLink(url);
      setPayOrderId(order.id);
      push({ note: "payment link created", orderId: order.id });
    } catch (e: any) { setErr(e.message); }
  }

  function copyLink() {
    if (!link) return;
    navigator.clipboard?.writeText(link).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); });
  }

  async function directPay() {
    setErr(null);
    try {
      let orderId = payOrderId;
      if (!orderId) {
        const order = await api<{ id: string }>("/v1/orders", {
          asMerchant: true, method: "POST",
          body: { customerRef: "cust_web", amountMinor: amount, currency: "INR" },
        });
        orderId = order.id;
      }
      const p = await api("/v1/payments", {
        asMerchant: true,
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: { orderId, method: "CARD", token, deviceFingerprint: device },
      });
      push({ payment: p });
    } catch (e: any) { setErr(e.message); }
  }

  async function refreshTransactions() {
    setErr(null);
    try {
      const r = await api<{ items: any[] }>("/v1/transactions?size=15", { asMerchant: true });
      setTxns(r.items);
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <>
      <div className="panel">
        <h2>Merchant credentials</h2>
        <div className="row">
          <div className="field" style={{ flex: 1, minWidth: 320 }}>
            <label>API key (X-API-Key)</label>
            <input value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="fp_live_..." />
          </div>
          <button className="ghost" onClick={saveKey}>Save</button>
          <button className="primary" onClick={onboard}>Onboard a sandbox merchant</button>
        </div>
      </div>

      <div className="panel">
        <h2>Create a payment link</h2>
        <p className="muted" style={{ marginTop: -6, marginBottom: 12 }}>
          Like Razorpay/Stripe Checkout: create an order, share the link — whoever opens it
          pays, no account or login needed on their side.
        </p>
        <div className="row">
          <div className="field" style={{ width: 160 }}><label>amount (minor)</label>
            <input type="number" value={amount} onChange={(e) => setAmount(+e.target.value)} /></div>
          <div className="field" style={{ width: 200 }}><label>receipt (optional)</label>
            <input value={receipt} onChange={(e) => setReceipt(e.target.value)} placeholder="INV-1042" /></div>
          <button className="primary" onClick={createLink}>Create link</button>
        </div>
        {link && (
          <div className="row" style={{ marginTop: 14 }}>
            <input readOnly value={link} style={{ flex: 1, minWidth: 280 }} onFocus={(e) => e.currentTarget.select()} />
            <button className="ghost" onClick={copyLink}>{copied ? "Copied" : "Copy"}</button>
            <a className="ghost" style={{ textDecoration: "none", display: "inline-block", padding: "7px 12px" }}
               href={link} target="_blank" rel="noreferrer">Open as customer ↗</a>
          </div>
        )}
        {err && <div className="err">{err}</div>}
      </div>

      <div className="panel">
        <h2>Transactions</h2>
        <div className="row" style={{ marginBottom: 10 }}>
          <button className="ghost" onClick={refreshTransactions}>Refresh</button>
          <span className="muted">Pulls GET /v1/transactions — shows payments made through any checkout link too.</span>
        </div>
        <table>
          <thead><tr><th>payment</th><th>status</th><th>risk</th><th>amount</th><th>created</th></tr></thead>
          <tbody>
            {txns.map((t) => (
              <tr key={t.id}>
                <td className="mono">{t.id.slice(0, 8)}</td>
                <td><span className={`pill ${t.status}`}>{t.status}</span></td>
                <td>{t.riskBucket ? <span className={`pill ${t.riskBucket}`}>{t.riskBucket}</span> : "-"}</td>
                <td>{(t.amountMinor / 100).toFixed(2)} {t.currency}</td>
                <td className="muted">{new Date(t.createdAt).toLocaleTimeString()}</td>
              </tr>
            ))}
            {txns.length === 0 && <tr><td colSpan={5} className="muted">click Refresh to load</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h2>Direct pay <span className="muted" style={{ fontWeight: 400 }}>(server-to-server / API testing)</span></h2>
        <div className="row">
          <div className="field" style={{ width: 220 }}><label>order id (blank = new order)</label>
            <input value={payOrderId} onChange={(e) => setPayOrderId(e.target.value)} placeholder="uuid" /></div>
          <div className="field" style={{ width: 190 }}><label>token</label>
            <select value={token} onChange={(e) => setToken(e.target.value)}>
              <option>tok_valid</option><option>tok_declined</option><option>tok_fraud</option>
              <option>tok_insufficient_funds</option><option>tok_expired</option>
            </select></div>
          <div className="field" style={{ width: 160 }}><label>device</label>
            <input value={device} onChange={(e) => setDevice(e.target.value)} /></div>
          <button className="primary" onClick={directPay}>Pay</button>
        </div>
      </div>

      <div className="panel">
        <h2>Activity (this browser tab)</h2>
        <table>
          <thead><tr><th>time</th><th>status</th><th>risk</th><th>detail</th></tr></thead>
          <tbody>
            {log.map((e, i) => (
              <tr key={i}>
                <td className="mono">{e.t}</td>
                <td>{e.payment ? <span className={`pill ${e.payment.status}`}>{e.payment.status}</span> : "-"}</td>
                <td>{e.payment ? <span className={`pill ${e.payment.riskBucket}`}>{e.payment.riskBucket} {e.payment.riskScore}</span> : "-"}</td>
                <td className="mono muted">{e.payment ? e.payment.id : e.note ?? JSON.stringify(e)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
