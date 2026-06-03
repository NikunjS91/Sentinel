#!/usr/bin/env bash
set -euo pipefail

PROM=${PROM:-http://localhost:9090}
LOKI=${LOKI:-http://localhost:3100}
GRAFANA=${GRAFANA:-http://localhost:3000}

echo "== Prometheus ready =="
curl -fsS "$PROM/-/ready" >/dev/null

echo "== Prometheus is scraping demo-app =="
result=$(curl -fsS "$PROM/api/v1/query?query=up%7Bjob%3D%22demo-app%22%7D" | \
  python3 -c "import json,sys; d=json.load(sys.stdin); print(d['data']['result'][0]['value'][1])")
[[ "$result" == "1" ]] || { echo "FAIL: demo-app up=$result (expected 1)"; exit 1; }

echo "== Loki ready =="
curl -fsS "$LOKI/ready" | grep -q ready

echo "== Grafana healthy =="
curl -fsS "$GRAFANA/api/health" | grep -q '"database"'

echo "== All observability checks passed =="
