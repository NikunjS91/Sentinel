# Sprint 4 Demo — Knowledge-Augmented Diagnosis

**Sprint 4 · Demo artifact · Knowledge base, History, Topology, Runbook agents**

This is the Sprint 4 demo: a 6-agent swarm whose three knowledge-augmented specialists
(History, Topology, Runbook) reference a live PostgreSQL knowledge base to produce
richer, evidence-backed findings — and where every resolved incident teaches the system
to recognize the same pattern faster next time.

---

## Pre-demo setup

```bash
# Stack running
docker compose up -d

# Terminal A — orchestrator
cd orchestrator && ./mvnw spring-boot:run

# Terminal B — agents
cd agents && uvicorn app.main:app --reload

# Terminal C — demo app
cd demo-app && ./mvnw spring-boot:run

# Terminal D — UI
cd ui && npm run dev
```

---

## Beat 1 — Show the knowledge base before any incidents (0:00–0:15)

```sql
SELECT count(*), source_type
FROM knowledge_base.kb_documents
GROUP BY source_type;
```

Expected output before any live incidents:
```
 count | source_type
-------+--------------
     5 | past_incident
     4 | runbook
```

Narration: *"This is what the system knows before today. Five seeded incidents from our
history, four operational runbooks. When an incident fires, three of our six agents will
query this table directly."*

---

## Beat 2 — Trigger a memory-leak incident (0:15–0:30)

```bash
# Put demo-app into memory_leak mode
curl -s -X POST http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"memory_leak"}' >/dev/null

# Generate load to trigger the alert
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8090/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"SKU-001","quantity":1,"totalUsd":9.99}' >/dev/null
done
```

```bash
# Fire the alert manually
curl -s -X POST http://localhost:8080/alerts \
  -H 'Content-Type: application/json' \
  -d '{
    "source": "prometheus",
    "service": "demo-app",
    "alertName": "HighMemoryUsage",
    "fingerprint": "demo-memory-leak-001",
    "severity": "critical",
    "labels": {"env": "demo"},
    "annotations": {"description": "Heap usage > 90% for 5 minutes"}
  }'
```

Narration: *"Demo app is misbehaving. I'm triggering a HighMemoryUsage alert directly.
Watch the dashboard."*

---

## Beat 3 — Dashboard: 6-agent dispatch (0:30–1:30)

Open `http://localhost:5173`. Watch the incident appear and progress through states.

State progression: `RECEIVED → CLASSIFIED → DISPATCHED → AGGREGATING → RESOLVED`

Narration while watching:
*"Six specialists dispatch in parallel. Three you've seen before: Echo (metadata extraction),
Log Analyzer (LogQL queries), Metrics (PromQL queries). Three are new this sprint:*

- *History searches our past incidents using vector similarity — it embeds the current
  incident's description and finds the semantically closest past cases.*
- *Topology reads our service-graph: which services does demo-app call, which services
  call it? Then it reasons about failure-propagation direction.*
- *Runbook does a full-text search over our operational playbooks — 'do we have a
  documented procedure for this?'*

*All six findings flow into the Synthesizer, which combines them into a single report
with a confidence score and explicit dissent notes where agents disagreed."*

---

## Beat 4 — Expand the resolved incident (1:30–2:30)

Click the incident in the dashboard. Show the synthesized summary, root cause, confidence.

Then show the three knowledge-agent contributions directly in psql:

```sql
-- History agent: what past incidents matched?
SELECT
  at.agent_name,
  at.output::jsonb -> 'matched_incidents' AS matches
FROM agent_traces at
WHERE at.incident_id = '<paste-id>'
  AND at.agent_name = 'history';
```

Expected: the seeded "Memory leak after deploy" incident surfaces as a top match.

```sql
-- Topology agent: which services were implicated?
SELECT
  at.output::jsonb -> 'neighbors' AS neighbors,
  at.output::jsonb -> 'likely_implicated' AS implicated
FROM agent_traces at
WHERE at.incident_id = '<paste-id>'
  AND at.agent_name = 'topology';
```

Expected: postgres and redis appear as outgoing dependencies (likely causes).

```sql
-- Runbook agent: which playbook matched?
SELECT
  at.output::jsonb -> 'matched_runbooks' AS runbooks
FROM agent_traces at
WHERE at.incident_id = '<paste-id>'
  AND at.agent_name = 'runbook';
```

Expected: "Memory leak triage" runbook surfaces with high confidence.

Narration: *"The system reached its conclusion from three different epistemic angles:
history, topology, and documented procedure. The Synthesizer then reasoned about whether
these angles agreed or disagreed. If Topology says 'postgres is likely implicated' and
History says 'last time this was a JVM leak, not a DB issue,' the Synthesizer captures
that tension in the dissent notes."*

---

## Beat 5 — The feedback-loop moment (2:30–3:15)

```sql
-- Count past_incidents BEFORE the second incident
SELECT count(*) FROM knowledge_base.kb_documents WHERE source_type = 'past_incident';
```

Accept the first incident (human decision), then fire a second memory-leak alert:

```bash
curl -s -X POST http://localhost:8080/alerts \
  -H 'Content-Type: application/json' \
  -d '{
    "source": "prometheus",
    "service": "demo-app",
    "alertName": "HighMemoryUsage",
    "fingerprint": "demo-memory-leak-002",
    "severity": "critical",
    "labels": {"env": "demo"},
    "annotations": {"description": "Heap still elevated after pod restart"}
  }'
```

Wait ~30 seconds for the EmbeddingBackfillTask to populate the vector for the first incident.
Then check the History agent trace on the second incident:

```sql
SELECT
  at.output::jsonb -> 'matched_incidents'
FROM agent_traces at
WHERE at.incident_id = '<second-incident-id>'
  AND at.agent_name = 'history';
```

Expected: the list now includes **both** the seeded incident AND the just-resolved first incident.

```sql
-- Confirm the KB grew
SELECT count(*) FROM knowledge_base.kb_documents WHERE source_type = 'past_incident';
-- Was N, now N+1
```

Narration: *"The system is growing its own institutional memory. The first incident resolved
30 seconds ago. Its synthesized diagnosis — summary, root cause, recommended action — was
written to the knowledge base as a new past incident. The next time a similar alert fires,
the History agent finds it.*

*No model retraining. No schema changes. No human curation. Just an embedding write and
the next vector search picks it up. Every incident makes the next diagnosis a little more
informed."*

---

## Beat 6 — Closing (3:15–3:30)

*"Thirty-one days. From an empty folder to a six-agent diagnostic swarm with a working
feedback loop. Java orchestrator, Python agents, React dashboard, Kafka event log,
pgvector knowledge base. Four CI jobs. One process lesson: always verify post-merge
main CI before calling a sprint done."*

---

## Key numbers

| Metric | Sprint 4 final |
|--------|----------------|
| Specialist agents | 6 (echo, log_analyzer, metrics, history, topology, runbook) |
| Java tests | ~85 |
| Python tests | ~60 |
| Demo-app tests | 8 |
| CI jobs | 4 |
| KB tables | 3 (kb_documents, kb_links, kb_runbooks) |
| Seed incidents | 5 |
| Seed runbooks | 4 |
