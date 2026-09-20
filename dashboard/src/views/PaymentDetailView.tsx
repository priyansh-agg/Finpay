import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, formatMoney } from "../api";
import { Avatar } from "../components/stats";
import type { Payment } from "./PaymentsView";

type Timeline = { fromStatus: string | null; toStatus: string; detail: string | null; createdAt: string }[];
type LedgerEntry = { accountType: string | null; ownerType: string | null; ownerId: string | null; direction: string; amountMinor: number; currency: string };
type LedgerTx = { id: string; txType: string; memo: string | null; createdAt: string; entries: LedgerEntry[] };
type Delivery = { endpointId: string; status: string; attempts: number; lastStatusCode: number | null; lastError: string | null; deliveredAt: string | null };
type OutboxEvent = { id: string; eventType: string; status: string; attempts: number; createdAt: string; publishedAt: string | null; deliveries: Delivery[] };
type AuditEntry = { seq: number; actorType: string; actorId: string | null; action: string; beforeState: any; afterState: any; createdAt: string };
type AutoClearDecision = { reviewId: string | null; confidence: number; threshold: number; autoCleared: boolean; modelVersion: string | null; laterConfirmedFraud: boolean | null; createdAt: string };
type CustomerView = { id: string; externalRef: string; email: string | null; firstSeenAt: string; capturedCount: number; capturedSumMinor: number; rollingAvgMinor: number };
type RefundView = { id: string; paymentId: string; amountMinor: number; currency: string; status: string; reason: string | null; createdAt: string };
type RiskView = { paymentId: string; decision: string; bucket: string; finalScore: number; ruleScore: number | null; mlScore: number | null; vectorSimilarity: number | null; ruleFlags: any; features: any; latencyMs: number | null };

const TABS = ["Overview", "Risk", "Ledger", "Refunds", "Webhooks", "Timeline", "Audit"] as const;
type Tab = typeof TABS[number];

/**
 * Payment Detail (interview sprint Day 12) — tabbed facets assembled from the read endpoints
 * this day added (the audit's §C.2: "4 of ~10 facets are retrievable and 0 are assembled").
 * The overview now fetches for real (GET /v1/payments/{id} works for every viewing role as of
 * this day), so a direct reload or shared link works — Day 11's router-state-only fallback is
 * gone.
 */
export function PaymentDetailView() {
  const { id } = useParams();
  const [payment, setPayment] = useState<Payment | null>(null);
  const [customer, setCustomer] = useState<CustomerView | null>(null);
  const [autoClear, setAutoClear] = useState<AutoClearDecision[] | null>(null);
  const [tab, setTab] = useState<Tab>("Overview");
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    setErr(null);
    Promise.all([
      api<Payment>(`/v1/payments/${id}`),
      api<CustomerView>(`/v1/payments/${id}/customer`).catch(() => null),
      api<AutoClearDecision[]>(`/v1/payments/${id}/autoclear`).catch(() => []),
    ]).then(([p, c, ac]) => { setPayment(p); setCustomer(c); setAutoClear(ac); })
      .catch((e) => setErr(e.message));
  }, [id]);

  if (err) {
    return (
      <div className="panel">
        <h2>Payment {id?.slice(0, 8)}</h2>
        <div className="err">{err}</div>
        <Link to="/payments">← Back to Payments</Link>
      </div>
    );
  }
  if (!payment) return <div className="panel"><h2>Payment {id?.slice(0, 8)}</h2><p className="muted">loading…</p></div>;

  return (
    <div className="panel">
      <h2>Payment {payment.id.slice(0, 8)}</h2>
      <div className="subtabs">
        {TABS.map((t) => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>{t}</button>
        ))}
      </div>

      {tab === "Overview" && <Overview payment={payment} customer={customer} autoClear={autoClear} />}
      {tab === "Risk" && <RiskTab paymentId={payment.id} />}
      {tab === "Ledger" && <LedgerTab paymentId={payment.id} />}
      {tab === "Refunds" && <RefundsTab paymentId={payment.id} />}
      {tab === "Webhooks" && <WebhooksTab paymentId={payment.id} />}
      {tab === "Timeline" && <TimelineTab paymentId={payment.id} />}
      {tab === "Audit" && <AuditTab paymentId={payment.id} />}

      <Link to="/payments" style={{ display: "inline-block", marginTop: 14 }}>← Back to Payments</Link>
    </div>
  );
}

function Overview({ payment, customer, autoClear }: { payment: Payment; customer: CustomerView | null; autoClear: AutoClearDecision[] | null }) {
  const latest = autoClear && autoClear.length > 0 ? autoClear[0] : null;
  return (
    <>
      <div className="kv">
        <div className="k">Status</div><div><span className={`pill ${payment.status}`}>{payment.status}</span></div>
        <div className="k">Amount</div><div className="num">{formatMoney(payment.amountMinor, payment.currency)}</div>
        <div className="k">Method</div><div>{payment.method}</div>
        <div className="k">Order</div><div className="mono">{payment.orderId}</div>
        <div className="k">Processor ref</div><div className="mono">{payment.processorRef ?? "-"}</div>
        <div className="k">Failed reason</div><div>{payment.failedReason ?? "-"}</div>
        <div className="k">Created</div><div>{new Date(payment.createdAt).toLocaleString()}</div>
        <div className="k">Authorized</div><div>{payment.authorizedAt ? new Date(payment.authorizedAt).toLocaleString() : "-"}</div>
        <div className="k">Captured</div><div>{payment.capturedAt ? new Date(payment.capturedAt).toLocaleString() : "-"}</div>
      </div>

      <h2 style={{ marginTop: 20 }}>Customer</h2>
      {customer ? (
        <div className="kv">
          <div className="k">Ref</div>
          <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
            <Avatar label={customer.externalRef} size={26} />
            <span className="mono">{customer.externalRef}</span>
          </div>
          <div className="k">Email</div><div>{customer.email ?? "-"}</div>
          <div className="k">First seen</div><div>{new Date(customer.firstSeenAt).toLocaleString()}</div>
          <div className="k">Captures</div>
          <div>{customer.capturedCount} totalling <span className="num">{formatMoney(customer.capturedSumMinor, payment.currency)}</span> (avg <span className="num">{formatMoney(customer.rollingAvgMinor, payment.currency)}</span>)</div>
        </div>
      ) : <p className="muted">no customer profile</p>}

      <h2 style={{ marginTop: 20 }}>Auto-Clear</h2>
      {latest ? (
        <div className="kv">
          <div className="k">Outcome</div>
          <div><span className={`pill ${latest.autoCleared ? "AUTO_CLEAR" : "REJECT"}`}>{latest.autoCleared ? "AUTO-CLEARED" : "declined"}</span></div>
          <div className="k">Confidence / threshold</div><div>{latest.confidence.toFixed(4)} / {latest.threshold.toFixed(2)}</div>
          <div className="k">Model</div><div className="mono">{latest.modelVersion ?? "-"}</div>
          <div className="k">Later confirmed fraud</div>
          <div>{latest.laterConfirmedFraud == null ? "not yet resolved" : (latest.laterConfirmedFraud ? "YES — false auto-clear" : "no")}</div>
        </div>
      ) : <p className="muted">never evaluated by the Auto-Clear proposer</p>}
    </>
  );
}

function RiskTab({ paymentId }: { paymentId: string }) {
  const [risk, setRisk] = useState<RiskView | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<RiskView>(`/v1/risk/${paymentId}`).then(setRisk).catch((e) => setErr(e.message)); }, [paymentId]);
  if (err) return <div className="err">{err}</div>;
  if (!risk) return <p className="muted">loading…</p>;
  return (
    <div className="kv">
      <div className="k">Decision</div><div>{risk.decision}</div>
      <div className="k">Bucket</div><div><span className={`pill ${risk.bucket}`}>{risk.bucket}</span></div>
      <div className="k">Final score</div><div>{risk.finalScore}</div>
      <div className="k">Rule score</div><div>{risk.ruleScore ?? "-"}</div>
      <div className="k">ML score</div><div>{risk.mlScore ?? "-"}</div>
      <div className="k">Vector similarity</div><div>{risk.vectorSimilarity ?? "-"}</div>
      <div className="k">Rule flags</div><div className="mono">{risk.ruleFlags ? JSON.stringify(risk.ruleFlags) : "-"}</div>
      <p className="muted" style={{ fontSize: 12, gridColumn: "1 / -1", marginTop: 4 }}>
        A merchant sees decision/bucket/score only — the fields above show "-" either because
        you're viewing as a merchant, or because the value is genuinely absent (e.g. no ML
        sidecar reachable).
      </p>
    </div>
  );
}

function LedgerTab({ paymentId }: { paymentId: string }) {
  const [txs, setTxs] = useState<LedgerTx[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<LedgerTx[]>(`/v1/payments/${paymentId}/ledger`).then(setTxs).catch((e) => setErr(e.message)); }, [paymentId]);
  if (err) return <div className="err">{err}</div>;
  if (!txs) return <p className="muted">loading…</p>;
  if (txs.length === 0) return <p className="muted">no ledger activity yet</p>;
  return (
    <>
      {txs.map((tx) => (
        <div key={tx.id} style={{ marginBottom: 16 }}>
          <div className="row" style={{ marginBottom: 6 }}>
            <strong>{tx.txType}</strong>
            <span className="muted" style={{ fontSize: 12 }}>{new Date(tx.createdAt).toLocaleString()}</span>
          </div>
          <table>
            <thead><tr><th>account</th><th>direction</th><th style={{ textAlign: "right" }}>amount</th></tr></thead>
            <tbody>
              {tx.entries.map((e, i) => (
                <tr key={i}>
                  <td>{e.accountType ?? "-"}{e.ownerType === "MERCHANT" ? " (merchant)" : ""}</td>
                  <td>{e.direction}</td>
                  <td className="num" style={{ textAlign: "right" }}>{formatMoney(e.amountMinor, e.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </>
  );
}

function RefundsTab({ paymentId }: { paymentId: string }) {
  const [refunds, setRefunds] = useState<RefundView[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<RefundView[]>(`/v1/payments/${paymentId}/refund`).then(setRefunds).catch((e) => setErr(e.message)); }, [paymentId]);
  if (err) return <div className="err">{err}</div>;
  if (!refunds) return <p className="muted">loading…</p>;
  if (refunds.length === 0) return <p className="muted">no refunds</p>;
  return (
    <table>
      <thead><tr><th style={{ textAlign: "right" }}>amount</th><th>status</th><th>reason</th><th>created</th></tr></thead>
      <tbody>
        {refunds.map((r) => (
          <tr key={r.id}>
            <td className="num" style={{ textAlign: "right" }}>{formatMoney(r.amountMinor, r.currency)}</td>
            <td><span className={`pill ${r.status}`}>{r.status}</span></td>
            <td className="muted">{r.reason ?? "-"}</td>
            <td className="muted">{new Date(r.createdAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function WebhooksTab({ paymentId }: { paymentId: string }) {
  const [events, setEvents] = useState<OutboxEvent[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<OutboxEvent[]>(`/v1/payments/${paymentId}/webhooks`).then(setEvents).catch((e) => setErr(e.message)); }, [paymentId]);
  if (err) return <div className="err">{err}</div>;
  if (!events) return <p className="muted">loading…</p>;
  if (events.length === 0) return <p className="muted">no webhook events yet</p>;
  return (
    <>
      {events.map((e) => (
        <div key={e.id} style={{ marginBottom: 16 }}>
          <div className="row" style={{ marginBottom: 6 }}>
            <strong className="mono">{e.eventType}</strong>
            <span className={`pill ${e.status === "PUBLISHED" ? "CAPTURED" : e.status === "DEAD" ? "FAILED" : "PENDING"}`}>{e.status}</span>
            <span className="muted" style={{ fontSize: 12 }}>{new Date(e.createdAt).toLocaleString()}</span>
          </div>
          {e.deliveries.length === 0 ? <p className="muted" style={{ fontSize: 12 }}>no subscribed endpoint</p> : (
            <table>
              <thead><tr><th>endpoint</th><th>status</th><th>attempts</th><th>last code</th></tr></thead>
              <tbody>
                {e.deliveries.map((d, i) => (
                  <tr key={i}>
                    <td className="mono">{d.endpointId.slice(0, 8)}</td>
                    <td><span className={`pill ${d.status === "DELIVERED" ? "CAPTURED" : d.status === "DEAD" ? "FAILED" : "PENDING"}`}>{d.status}</span></td>
                    <td>{d.attempts}</td>
                    <td>{d.lastStatusCode ?? "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      ))}
    </>
  );
}

function TimelineTab({ paymentId }: { paymentId: string }) {
  const [entries, setEntries] = useState<Timeline | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<Timeline>(`/v1/payments/${paymentId}/timeline`).then(setEntries).catch((e) => setErr(e.message)); }, [paymentId]);
  if (err) return <div className="err">{err}</div>;
  if (!entries) return <p className="muted">loading…</p>;
  return (
    <table>
      <thead><tr><th>from</th><th>to</th><th>detail</th><th>when</th></tr></thead>
      <tbody>
        {entries.map((e, i) => (
          <tr key={i}>
            <td>{e.fromStatus ?? "-"}</td>
            <td><span className={`pill ${e.toStatus}`}>{e.toStatus}</span></td>
            <td className="muted">{e.detail ?? "-"}</td>
            <td className="muted">{new Date(e.createdAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function AuditTab({ paymentId }: { paymentId: string }) {
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api<AuditEntry[]>(`/v1/payments/${paymentId}/audit`).then(setEntries).catch((e) => setErr(e.message)); }, [paymentId]);
  if (err) return <div className="err">{err}</div>;
  if (!entries) return <p className="muted">loading…</p>;
  return (
    <table>
      <thead><tr><th>action</th><th>actor</th><th>after</th><th>when</th></tr></thead>
      <tbody>
        {entries.map((e) => (
          <tr key={e.seq}>
            <td>{e.action}</td>
            <td className="muted">{e.actorType}{e.actorId ? `: ${e.actorId}` : ""}</td>
            <td className="mono muted" style={{ maxWidth: 320 }}>{e.afterState ? JSON.stringify(e.afterState) : "-"}</td>
            <td className="muted">{new Date(e.createdAt).toLocaleString()}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
