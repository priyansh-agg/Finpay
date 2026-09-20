import { useEffect, useState } from "react";
import { formatMoney as formatAmount } from "./api";

type CheckoutView = {
  orderId: string;
  merchantName: string;
  amountMinor: number;
  currency: string;
  receipt: string | null;
  status: string;
};

type PaymentResult = {
  id: string;
  status: string;
  riskBucket: string | null;
  failedReason: string | null;
};

async function api<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...opts,
    headers: { "Content-Type": "application/json", ...(opts.headers ?? {}) },
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.message ?? `HTTP ${res.status}`);
  return data as T;
}

const TEST_TOKENS = [
  { value: "tok_valid", label: "Test card •••• 1111 (succeeds)" },
  { value: "tok_declined", label: "Test card •••• 0002 (declined)" },
  { value: "tok_insufficient_funds", label: "Test card •••• 0341 (insufficient funds)" },
];

/**
 * The customer-facing payment link — no account, no login. Mirrors Razorpay/Stripe hosted
 * checkout: the merchant shares a URL built from the order id, and whoever opens it can pay.
 * Card numbers are simulated test tokens since this is a sandbox, exactly like every real
 * gateway's test mode.
 */
export function CheckoutPage({ orderId }: { orderId: string }) {
  const [order, setOrder] = useState<CheckoutView | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [method, setMethod] = useState<"CARD" | "UPI">("CARD");
  const [token, setToken] = useState(TEST_TOKENS[0].value);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<PaymentResult | null>(null);
  const [payError, setPayError] = useState<string | null>(null);

  useEffect(() => {
    api<CheckoutView>(`/v1/checkout/${orderId}`).then(setOrder).catch((e) => setLoadError(e.message));
  }, [orderId]);

  async function pay() {
    setBusy(true);
    setPayError(null);
    try {
      const p = await api<PaymentResult>(`/v1/checkout/${orderId}/pay`, {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ method, token, deviceFingerprint: "checkout-" + navigator.userAgent.slice(0, 24) }),
      });
      setResult(p);
    } catch (e: any) {
      setPayError(e.message);
    } finally {
      setBusy(false);
    }
  }

  if (loadError) {
    return (
      <Shell>
        <div className="cx-error">This payment link is invalid or has expired.</div>
      </Shell>
    );
  }
  if (!order) {
    return (
      <Shell>
        <div className="cx-muted">Loading…</div>
      </Shell>
    );
  }

  if (result) {
    return (
      <Shell merchantName={order.merchantName}>
        <ResultView result={result} amount={formatAmount(order.amountMinor, order.currency)} />
      </Shell>
    );
  }

  return (
    <Shell merchantName={order.merchantName}>
      <div className="cx-amount">{formatAmount(order.amountMinor, order.currency)}</div>
      {order.receipt && <div className="cx-receipt">Receipt: {order.receipt}</div>}

      <div className="cx-tabs">
        <button className={method === "CARD" ? "cx-tab active" : "cx-tab"} onClick={() => setMethod("CARD")}>Card</button>
        <button className={method === "UPI" ? "cx-tab active" : "cx-tab"} onClick={() => setMethod("UPI")}>UPI</button>
      </div>

      <label className="cx-label">
        {method === "CARD" ? "Test card" : "Test UPI outcome"}
        <select className="cx-select" value={token} onChange={(e) => setToken(e.target.value)}>
          {TEST_TOKENS.map((t) => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </select>
      </label>

      <button className="cx-pay" disabled={busy} onClick={pay}>
        {busy ? "Processing…" : `Pay ${formatAmount(order.amountMinor, order.currency)}`}
      </button>
      {payError && <div className="cx-error">{payError}</div>}
      <div className="cx-sandbox-note">Sandbox mode — no real money moves. Card numbers above are test tokens.</div>
    </Shell>
  );
}

function ResultView({ result, amount }: { result: PaymentResult; amount: string }) {
  if (result.status === "CAPTURED") {
    return (
      <div className="cx-result cx-result-ok">
        <div className="cx-check">✓</div>
        <div className="cx-result-title">Payment successful</div>
        <div className="cx-muted">{amount} — payment {result.id.slice(0, 8)}</div>
      </div>
    );
  }
  if (result.status === "HELD" || result.status === "PENDING_REVIEW") {
    return (
      <div className="cx-result cx-result-pending">
        <div className="cx-check">⏳</div>
        <div className="cx-result-title">Payment received — under review</div>
        <div className="cx-muted">You'll be notified once it clears. Reference {result.id.slice(0, 8)}</div>
      </div>
    );
  }
  return (
    <div className="cx-result cx-result-fail">
      <div className="cx-check">✕</div>
      <div className="cx-result-title">Payment failed</div>
      <div className="cx-muted">{result.failedReason ?? "The payment could not be completed"}</div>
    </div>
  );
}

function Shell({ merchantName, children }: { merchantName?: string; children: React.ReactNode }) {
  return (
    <div className="cx-page">
      <div className="cx-card">
        {merchantName && <div className="cx-merchant">{merchantName}</div>}
        {children}
      </div>
      <div className="cx-footer">Secured by FinPay (sandbox)</div>
    </div>
  );
}
