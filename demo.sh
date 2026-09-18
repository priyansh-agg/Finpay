#!/usr/bin/env bash
# End-to-end narrated walkthrough of FinPay. Assumes the gateway is on :8080
# (and, for the fraud/auto-clear parts, the ML sidecar on :9000). Start both with ./dev.sh.
set -euo pipefail
B="${FINPAY_BASE:-http://localhost:8080}"
j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }
hr() { printf '\n\033[1;36m── %s\033[0m\n' "$1"; }

hr "1. Sign in as the platform admin"
ADMIN=$(curl -s -X POST $B/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@finpay.local","password":"admin12345"}' | j '["token"]')
echo "got a JWT"

hr "2. Onboard a merchant (returns the raw API key ONCE)"
ONB=$(curl -s -X POST $B/v1/admin/merchants -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"Acme Coffee","email":"acme'"$RANDOM"'@example.com","adminPassword":"acme12345"}')
KEY=$(echo "$ONB" | j '["apiKey"]["rawKey"]')
echo "merchant id: $(echo "$ONB" | j '["merchantId"]')"
echo "api key:     ${KEY:0:18}…  (hashed at rest — a DB leak doesn't expose it)"

hr "3. Configure a webhook (HMAC-signed, retried, dead-lettered)"
SINK="$B/v1/webhooks/receiver/demo?secret=REPLACED"
WH=$(curl -s -X POST $B/v1/webhooks/config -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"url":"'"$SINK"'","events":["payment.*","refund.*","risk.*"]}')
SECRET=$(echo "$WH" | j '["secret"]')
curl -s -X DELETE "$B/v1/webhooks/config/$(echo "$WH" | j '["id"]')" -H "X-API-Key: $KEY" >/dev/null
SINK="$B/v1/webhooks/receiver/demo?secret=$SECRET"
curl -s -X POST $B/v1/webhooks/config -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"url":"'"$SINK"'","events":["payment.*","refund.*"]}' >/dev/null
echo "webhook -> local receiver, secret ${SECRET:0:14}…"

order() { curl -s -X POST $B/v1/orders -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"customerRef":"'"$1"'","amountMinor":'"$2"',"currency":"INR"}' | j '["id"]'; }
pay() { curl -s -X POST $B/v1/payments -H "X-API-Key: $KEY" -H "Idempotency-Key: $1" -H 'Content-Type: application/json' \
  -d '{"orderId":"'"$2"'","method":"CARD","token":"'"$3"'","deviceFingerprint":"'"${4:-dev-1}"'"}'; }

hr "4. A brand-new customer's payment is AUTHORISED but HELD (can't be confidently low-risk yet)"
O1=$(order cust_alice 45000)
P4=$(pay "idem-$(date +%s%N)" "$O1" tok_valid)
echo "$P4" | python3 -m json.tool
P4ID=$(echo "$P4" | j '["id"]')
echo "…the merchant can capture it after a look:"
curl -s -X POST "$B/v1/payments/$P4ID/capture" -H "X-API-Key: $KEY" | j '["status"]'

hr "5. Idempotency: fire the SAME key twice -> one payment"
O2=$(order cust_bob 60000)
K="idem-race-$(date +%s)"
A=$(pay "$K" "$O2" tok_valid | j '["id"]')
Bp=$(pay "$K" "$O2" tok_valid | j '["id"]')
[ "$A" = "$Bp" ] && echo "both calls returned the same payment: $A" || { echo "MISMATCH"; exit 1; }

hr "6. A declined card -> FAILED (processor path)"
pay "idem-$(date +%s%N)" "$(order cust_carol 25000)" tok_declined | j '["status"]'

hr "7. Partial refund of the captured payment from step 4 -> double-entry reversal"
curl -s -X POST "$B/v1/payments/$P4ID/refund" -H "X-API-Key: $KEY" -H 'Idempotency-Key: rf-1' \
  -H 'Content-Type: application/json' -d '{"amountMinor":15000,"reason":"partial"}' | j '["status"]'

hr "8. Reset simulation state -> a clean, reproducible slate for what follows"
curl -s -X POST $B/v1/admin/simulate/reset -H "Authorization: Bearer $ADMIN" | python3 -m json.tool

hr "9. Generate a scenario with fraud no rule can see (stealthRate) and legit traffic that trips one anyway (fraudShapedLegitRate)"
RUN=$(curl -s -X POST $B/v1/admin/simulate -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"count":80,"fraudRate":0.3,"stealthRate":0.5,"fraudShapedLegitRate":0.3,"seed":42}')
echo "$RUN" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(" population:",d["populationPayments"],"warm-up:",d["warmupPayments"],"buckets:",d["bucketCounts"])'
RUNID=$(echo "$RUN" | j '["runId"]')
echo "run id: $RUNID"

hr "10. Force this run's ground truth (the simulated dispute window) closed right now"
curl -s -X POST "$B/v1/admin/simulate/$RUNID/resolve" -H "Authorization: Bearer $ADMIN" >/dev/null
echo "resolved"

hr "11. Fraud detection effectiveness for this run, right after Resolve — some fraud is CAPTURED but not yet written off"
curl -s "$B/v1/analyst/metrics/fraud?runId=$RUNID" -H "Authorization: Bearer $ADMIN" | python3 -m json.tool

hr "12. The USP — Auto-Clear impact for this run (analyst view, scoped by ?runId=)"
curl -s "$B/v1/analyst/metrics/autoclear?runId=$RUNID" -H "Authorization: Bearer $ADMIN" | python3 -m json.tool

hr "13. Run a settlement batch — a capture only pays out once its ground truth resolves legitimate; confirmed fraud is written off instead"
curl -s -X POST $B/v1/settlements/run -H "Authorization: Bearer $ADMIN" | python3 -m json.tool

hr "14. Same fraud metrics, same run, after settlement — fraud missed should have dropped: settlement caught what capture alone did not"
curl -s "$B/v1/analyst/metrics/fraud?runId=$RUNID" -H "Authorization: Bearer $ADMIN" | python3 -m json.tool

hr "15. Explain a flagged, decided transaction from this run (LLM if Ollama is up, else template)"
PID=$(curl -s "$B/v1/analyst/reviews?status=DECIDED&limit=50" -H "Authorization: Bearer $ADMIN" | \
  python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["paymentId"] if d else "")')
if [ -n "$PID" ]; then
  curl -s "$B/v1/risk/$PID/explain" -H "Authorization: Bearer $ADMIN" | python3 -m json.tool
else
  echo "no decided reviews yet — the review scheduler hasn't caught up, skipping."
fi

hr "16. Prometheus business metrics"
curl -s $B/actuator/prometheus | grep -E '^finpay_(payments|risk_decisions|autoclear)' | sort

hr "done — open the dashboard at http://localhost:5173, pick \"this run only\" to see exactly what just happened"
