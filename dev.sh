#!/usr/bin/env bash
# Local dev launcher (no Docker needed). Requires: local Postgres 16+ w/ pgvector, local Redis,
# JDK 21 reachable via a Maven toolchain, Python 3.11+, Node 20+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

: "${PGURL:=postgresql://finpay:finpay@localhost:5432/finpay}"
: "${JDBC:=jdbc:postgresql://localhost:5432/finpay}"

echo "==> ensuring database + extension"
createdb finpay 2>/dev/null || true
psql -d finpay -c 'CREATE EXTENSION IF NOT EXISTS vector;' >/dev/null

echo "==> starting ml-service (:9000)"
( cd "$ROOT/ml-service"
  [ -d .venv ] || python3 -m venv .venv
  ./.venv/bin/pip -q install -r requirements.txt
  ARTIFACT_DIR="$ROOT/ml-service/artifacts" FINPAY_DB_URL="$PGURL" \
    ./.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 9000 ) &
ML_PID=$!

echo "==> starting gateway (:8080)"
( cd "$ROOT/gateway"
  FINPAY_ML_BASE_URL=http://127.0.0.1:9000 \
  SPRING_DATASOURCE_URL="$JDBC" SPRING_DATASOURCE_USERNAME=finpay SPRING_DATASOURCE_PASSWORD=finpay \
    ./mvnw -q spring-boot:run ) &
GW_PID=$!

echo "==> starting dashboard (:5173)"
( cd "$ROOT/dashboard" && npm install --silent && npm run dev ) &
UI_PID=$!

trap 'kill $ML_PID $GW_PID $UI_PID 2>/dev/null' EXIT
echo "==> up. dashboard http://localhost:5173  (admin@finpay.local / admin12345)"
wait
