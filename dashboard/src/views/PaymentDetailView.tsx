import { Link, useLocation, useParams } from "react-router-dom";
import type { Payment } from "./PaymentsView";

/**
 * Payment detail (interview sprint Day 11 — the spine only). A real payment-detail view with
 * tabbed facets (risk / ledger / webhooks / refunds / timeline) is Day 12's job — it needs read
 * endpoints that don't exist yet for most of those facets (see the audit's §C.2), and
 * `GET /v1/payments/{id}` itself 403s for a RISK_ANALYST/SUPER_ADMIN caller today since it's
 * merchant-role-gated. For now the route exists and is addressable — the point of the spine —
 * and renders whatever the list already had for this row, passed as router state, rather than
 * pretending to assemble a fuller view than the backend can support yet.
 */
export function PaymentDetailView() {
  const { id } = useParams();
  const location = useLocation();
  const payment = location.state as Payment | undefined;

  if (!payment) {
    return (
      <div className="panel">
        <h2>Payment {id?.slice(0, 8)}</h2>
        <p className="muted">
          This view only has what the Payments list already fetched for this row — reloading the
          page, or opening this link directly, loses that. A real single-payment endpoint every
          role can read is Day 12's job.
        </p>
        <Link to="/payments">← Back to Payments</Link>
      </div>
    );
  }

  return (
    <div className="panel">
      <h2>Payment {payment.id.slice(0, 8)}</h2>
      <div className="kv">
        <div className="k">Status</div><div><span className={`pill ${payment.status}`}>{payment.status}</span></div>
        <div className="k">Amount</div><div>{(payment.amountMinor / 100).toFixed(2)} {payment.currency}</div>
        <div className="k">Method</div><div>{payment.method}</div>
        <div className="k">Order</div><div className="mono">{payment.orderId}</div>
        <div className="k">Risk bucket</div>
        <div>{payment.riskBucket ? <span className={`pill ${payment.riskBucket}`}>{payment.riskBucket}</span> : "-"}</div>
        <div className="k">Risk decision</div><div>{payment.riskDecision ?? "-"}</div>
        <div className="k">Risk score</div><div>{payment.riskScore ?? "-"}</div>
        <div className="k">Processor ref</div><div className="mono">{payment.processorRef ?? "-"}</div>
        <div className="k">Failed reason</div><div>{payment.failedReason ?? "-"}</div>
        <div className="k">Created</div><div>{new Date(payment.createdAt).toLocaleString()}</div>
        <div className="k">Authorized</div><div>{payment.authorizedAt ? new Date(payment.authorizedAt).toLocaleString() : "-"}</div>
        <div className="k">Captured</div><div>{payment.capturedAt ? new Date(payment.capturedAt).toLocaleString() : "-"}</div>
      </div>
      <p className="muted" style={{ fontSize: 12, marginTop: 14 }}>
        Customer, fraud rules triggered, ledger impact, webhook events, refunds and audit trail
        aren't assembled here yet — Day 12 adds the read endpoints for those facets.
      </p>
      <Link to="/payments">← Back to Payments</Link>
    </div>
  );
}
