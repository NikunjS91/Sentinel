# Sprint 2 Demo — Swarm Asymmetry on slow_query

**The core architectural thesis:** Two specialist agents investigate the same incident and reach *different* conclusions. This is not a bug — it is the design working as intended.

---

## The failure mode: slow_query

The demo app exposes an admin endpoint to inject failure modes. When `slow_query` is active, every database operation is artificially slowed. Crucially, **all requests still succeed** — the slow query failure produces no errors, just elevated latency.

This is a class of failure where:
- **Logs are silent** — no ERROR lines, no exceptions, only INFO-level request completions
- **Metrics are loud** — p95 latency climbs from ~30ms to ~800ms, crossing the SLO threshold

---

## How to reproduce

### Prerequisites

```bash
docker compose up -d
# Terminal A
cd orchestrator && ./mvnw spring-boot:run
# Terminal B
cd agents && source .venv/bin/activate && uvicorn app.main:app --port 8001
```

### Step 1 — Inject slow_query

```bash
curl -s -X POST http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"slow_query"}'

for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8090/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"SKU-001","quantity":1,"totalUsd":9.99}'
done
```

### Step 2 — Observe the divergence in Grafana

Open `http://localhost:3000` (Grafana, admin/admin).

- **Metrics panel:** `histogram_quantile(0.95, rate(demo_order_create_duration_seconds_bucket[5m]))` — p95 climbs from 30ms to ~800ms
- **Loki panel:** `{service="demo-app", level="ERROR"}` — **empty result**

This is the asymmetry in raw data: metrics detect the failure, logs don't.

### Step 3 — Fire an alert

```bash
curl -s -X POST http://localhost:8080/alerts \
  -H 'Content-Type: application/json' \
  -d @sample-alert.json
```

### Step 4 — Wait for the three agents (~45s)

The orchestrator dispatches `echo`, `log_analyzer`, and `metrics` in parallel. Each runs independently. The aggregator waits for all three before resolving.

### Step 5 — Query the divergent findings

```sql
docker compose exec postgres psql -U sentinel -d sentinel -c "
  SELECT agent_name,
         output->>'confidence'          AS confidence,
         output->>'most_likely_symptom' AS log_finding,
         output->>'most_likely_cause'   AS metric_finding
  FROM agent_traces
  WHERE incident_id = (SELECT id FROM incidents ORDER BY created_at DESC LIMIT 1)
    AND agent_name IN ('log_analyzer', 'metrics');
"
```

**Expected output (representative from Day-18 manual e2e):**

```
 agent_name  | confidence |        log_finding         |        metric_finding
-------------+------------+----------------------------+-------------------------------
 log_analyzer| 0.15       | null                       | null
 metrics     | 0.88       | null                       | downstream DB query latency
```

---

## What this proves

| Agent | Sees | Conclusion | Confidence |
|---|---|---|---|
| Log Analyzer | Only INFO-level `order_created` lines | No clear pattern | 0.15 |
| Metrics agent | p95 latency 0.82s, SLO threshold 0.50s | Downstream DB query latency | 0.88 |

**Same incident. Same time window. Two specialists. Two answers.**

The Metrics agent is right. The Log Analyzer is not wrong — it correctly reports that logs offer no signal. That difference in confidence (>0.5) is what the Synthesizer will use in Sprint 3 to weight the findings and produce a single calibrated diagnosis.

If both agents always returned the same confidence, there would be no value in having two. The asymmetry **is the value**.

---

## The architectural thesis in code

`TC-2.11.2` in `agents/app/tests/test_swarm_asymmetry.py` codifies this permanently:

```python
assert metric_confidence - log_confidence > 0.5
```

This test uses fixture mode (same slow_query metric signature) and calibrated fake LLM responses mirroring the Day-18 manual e2e. The threshold (0.5) is conservative — the real observed delta is ~0.73. If this test ever goes red, the swarm has lost its differentiating value.
