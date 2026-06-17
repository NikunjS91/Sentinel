# Sprint 3 Demo — Human-in-the-Loop Flow

Sprint 3's demo moment: a real SRE reviewing an AI-generated diagnosis on a live dark dashboard, editing the recommended action, and watching labeled training data get captured in Postgres.

---

## Prerequisites

```bash
docker compose up -d       # 8 services healthy
cd orchestrator && ./mvnw spring-boot:run   # port 8080
cd agents && uvicorn app.main:app --port 8001
cd ui && npm install && npm run dev          # port 5173
```

---

## Demo script (~3 minutes)

### Beat 1 — Show the empty dashboard (10s)

Open `http://localhost:5173`.

Point out:
- The **"live"** green indicator in the top-right (SSE connection active)
- The **filter bar**: state, service, decision dropdowns + search box
- "Every filter updates the URL — you can bookmark filtered views."

---

### Beat 2 — Trigger a real incident (15s)

```bash
# Put the demo app into slow_query mode
curl -s -X POST http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"slow_query"}'

# Generate some traffic so logs and metrics have signal
for i in $(seq 1 15); do
  curl -s -X POST http://localhost:8090/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"SKU-001","quantity":1,"totalUsd":9.99}' >/dev/null
done

sleep 30

# Fire the alert
curl -s -X POST http://localhost:8080/alerts \
  -H 'Content-Type: application/json' \
  -d @sample-alert.json
```

Narration: *"Demo app is misbehaving. Alert fired."*

---

### Beat 3 — Watch the dashboard update live (45s)

On the dashboard, watch the new incident row. The state pill cycles:

```
RECEIVED → CLASSIFIED → DISPATCHED → AGGREGATING → SYNTHESIZED → RESOLVED
```

No page refresh. SSE pushes every state transition in real time.

Narration: *"Three specialists running in parallel — Log Analyzer, Metrics, Echo. The Synthesizer receives their findings and combines them. The state machine is explicit: every transition is audited in Postgres."*

---

### Beat 4 — Expand the incident (15s)

Click the incident row to expand it. Point out:

- **Summary** — the Synthesizer's one-line diagnosis
- **Root cause** — the concrete finding
- **Recommended action** — what to do next
- **Confidence** — e.g. 82%
- **Dissent notes** — "log_analyzer low confidence; metrics agent high confidence with concrete p95 evidence." This is the swarm asymmetry from Sprint 2, now surfaced explicitly.

---

### Beat 5 — The human-in-the-loop moment (30s)

*"But the AI doesn't have the final say. As the SRE reviewing this, I can see the recommended action is too generic."*

Click **Edit**. Change the recommended action from the AI's generic text to something specific:

> "Check the slow_query_log table for queries over 500ms in the last hour. Cross-reference with the deploy at 14:23."

Add a brief reason if prompted. Click **Save**.

The incident row now shows the **"EDITED"** decision chip.

---

### Beat 6 — Show the labeled data being captured (20s)

```bash
docker compose exec postgres psql -U sentinel -d sentinel -c "
SELECT
  recommended_action         AS ai_original,
  edited_recommended_action  AS human_edit,
  human_decision,
  human_decided_at
FROM incident_reports
ORDER BY created_at DESC
LIMIT 1;
"
```

*"The AI's original recommendation is preserved. The human edit is stored separately in `edited_recommended_action`. Every operator action since Day 24 is generating labeled training data for Sprint 5's prompt-tuning eval harness."*

---

### Beat 7 — Demonstrate filter bookmarkability (20s)

In the filter bar, set **Decision** to **"Edited"**.

Watch the URL update to `?decision=edited`.

*"Bookmark this URL — it's the SRE's queue of all reports where the AI got it almost right but needed a human touch. Decision=undecided is the working queue."*

---

### Beat 8 — Closing (10s)

*"Twenty-six days. From an empty folder to this: three specialist agents in parallel, a Synthesizer combining their findings with explicit dissent notes, per-incident deadlines routing incomplete runs through a PARTIAL state, a live dashboard, and human decisions captured as labeled training data. Sprint 4 adds Topology, History, and Runbook agents."*

---

## What to verify in Postgres after the demo

```sql
-- Final incident state
SELECT id, state, severity, created_at FROM incidents ORDER BY created_at DESC LIMIT 1;

-- All four agent traces (echo, log_analyzer, metrics, synthesizer)
SELECT agent_name, status, tokens_used, output->>'confidence' AS confidence
FROM agent_traces
WHERE incident_id = '<paste id from above>'
ORDER BY created_at;

-- Human decision with labeled training data
SELECT summary, recommended_action, edited_recommended_action,
       human_decision, human_decision_reason, human_decided_at
FROM incident_reports
WHERE incident_id = '<paste id from above>';

-- Audit trail
SELECT event_type, actor, created_at FROM audit_log
WHERE incident_id = '<paste id from above>'
ORDER BY created_at;
```

---

## Fallback: if SSE connection drops during demo

Watch the **"live"** indicator. If it turns red ("reconnecting..."), refresh the page — the REST hydration re-fetches the current list and SSE re-establishes automatically.
