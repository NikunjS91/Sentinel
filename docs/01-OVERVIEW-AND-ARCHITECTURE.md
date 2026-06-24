# 01 — Overview & Architecture

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## 1.1 The problem

When a production system breaks, an on-call engineer spends 20–40 minutes as a fast detective: opening logs, metrics, dashboards, deploy history, and past tickets just to *understand* the problem before they can fix it. This is slow, error-prone, and happens at the worst times.

A single large AI model handling all of this does it poorly — too many concerns, shallow reasoning, no specialization.

## 1.2 The solution

Sentinel uses a **swarm of specialist AI agents**. Each agent is narrow and good at one job. They investigate in parallel and a final agent synthesizes their findings into one diagnosis with a confidence score. A human reviews and approves.

The mental model is a hospital ER team: nurse, radiologist, lab tech, specialist, and ER doctor all work one patient simultaneously, not in sequence.

## 1.3 The six agents

| Agent | Job | Reads from | Status |
|---|---|---|---|
| Log Analyzer | Find error patterns near the incident window | Loki (LogQL) | ✅ Sprint 2 |
| Metrics | Detect SLO violations and correlated metric anomalies | Prometheus (PromQL) | ✅ Sprint 2 |
| Synthesizer | Combine all findings into one report | The other agents' outputs | ✅ Sprint 3 |
| Topology | Map affected service to dependencies via service graph | `/kb/topology` (kb_links) | ✅ Sprint 4 |
| History | Find similar past incidents via vector similarity | Postgres + pgvector (kb_documents) | ✅ Sprint 4 |
| Runbook | Match incident to documented playbook via FTS | Postgres FTS (kb_runbooks) | ✅ Sprint 4 |

## 1.4 Architecture summary

Three planes connected by a durable event log.

- **Control Plane — Spring Boot (Java).** Ingestion, classification, dispatch, orchestration, budget governance, aggregation. Stateless, horizontally scalable.
- **Agent Plane — FastAPI (Python).** The agent swarm, LLM gateway, tool layer. Sandboxed, least-privilege.
- **Event Log — Kafka.** Durable, partitioned by `incident_id`. The source of truth for events; decouples the planes.

```
                          ┌──────────────────────┐
 Alert sources  ───────▶   │   API Gateway        │
 (Alertmanager,            │   Ingestion Service  │  dedupe, correlate
  Datadog, demo app,       └──────────┬───────────┘
  synthetic generator)                │ publish
                                       ▼
                          ┌────────────────────────────┐
                          │   KAFKA  (event log)        │
                          │  partitioned by incident_id │
                          └───┬───────────────────┬─────┘
                consume       │                   │      consume
                  ┌───────────▼─────────┐   ┌─────▼──────────────┐
                  │ CONTROL PLANE       │   │ AGENT PLANE        │
                  │ Spring Boot (Java)  │   │ FastAPI (Python)   │
                  │ - Orchestrator      │   │ - Agent Workers    │
                  │ - State Machine     │   │ - LLM Gateway      │
                  │ - Dispatcher        │   │ - Tool Layer       │
                  │ - Budget Governor   │   │ - 6 agents         │
                  │ - Aggregator        │   └────────────────────┘
                  └─────────┬───────────┘
                            │
        ┌───────────────────┼────────────────────────┐
        ▼                   ▼                        ▼
   PostgreSQL           Redis                  S3 / object store
   (source of truth)   (cache, rate            (transcripts,
   + pgvector           limits, idempotency)    replay artifacts)

   React dashboard ◀── SSE ── Control Plane    (live agent activity)
```

## 1.5 Tech stack

| Layer | Choice | Why |
|---|---|---|
| Control plane | Java 21, Spring Boot 3.x | Stack credibility for finance/SRE roles; strong Kafka support |
| Agent plane | Python 3.11, FastAPI | Mature AI ecosystem; async-native for LLM streaming |
| Event bus | Apache Kafka | Durable, ordered-per-key, decouples planes |
| Relational store | PostgreSQL 16 + pgvector | Incident state of truth; vector search for History agent |
| Cache / locks | Redis 7 | Rate limits, idempotency keys, ephemeral state |
| Object store | MinIO (dev) / S3 (prod) | Cheap immutable storage for transcripts |
| LLM (dev) | Ollama + Mistral 7B | Free, offline, fast iteration |
| LLM (prod) | Anthropic API / Groq | Quality answers when it matters |
| Observability | Prometheus, Loki, Grafana, OpenTelemetry | The telemetry the agents read + Sentinel's own |
| Frontend | React 18, Vite, Tailwind, SSE | Live dashboard |
| Infra | Docker Compose (dev), Kubernetes (prod) | Local stack, then real deployment |
| CI/CD | GitHub Actions | Lint, test, build, run eval harness |

## 1.6 Repository layout

A monorepo. This layout is created in Sprint 1 Day 1 and stays stable.

```
sentinel/
├── README.md
├── docker-compose.yml
├── .github/
│   └── workflows/
│       └── ci.yml
├── docs/
│   └── (this report)
├── orchestrator/                  # Spring Boot (Java)
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/sentinel/
│       │   ├── SentinelApplication.java
│       │   ├── ingest/            # alert intake, dedupe
│       │   ├── incident/          # state machine, entities
│       │   ├── dispatch/          # task fan-out
│       │   ├── aggregate/         # result fan-in
│       │   ├── budget/            # cost governor
│       │   ├── stream/            # SSE endpoints
│       │   └── config/            # Kafka, DB config
│       └── test/java/com/sentinel/
├── agents/                        # FastAPI (Python)
│   ├── pyproject.toml
│   └── app/
│       ├── main.py
│       ├── worker.py              # Kafka consumer loop
│       ├── llm/                   # LLM gateway, model ladder
│       ├── tools/                 # PromQL, LogQL, git, vector
│       ├── agents/                # one module per agent
│       ├── prompts/               # versioned prompt files
│       └── tests/
├── ui/                            # React + Vite
│   ├── package.json
│   └── src/
├── demo-app/                      # Sprint 2: the breakable service
│   └── ...
├── synthetic/                     # Sprint 2: incident generator
│   └── generate.py
├── eval/                          # Sprint 5: evaluation harness
│   └── run_eval.py
└── infra/
    ├── k8s/                       # Sprint 6: manifests
    └── grafana/                   # dashboards
```

## 1.7 The incident lifecycle (state machine)

Every incident moves through these states. The state lives in Postgres so any orchestrator replica can resume after a crash.

```
RECEIVED ──▶ CLASSIFIED ──▶ DISPATCHED ──▶ AGGREGATING ──┬──▶ SYNTHESIZED ──▶ RESOLVED
                                                          │
                                                          └──▶ PARTIAL ──▶ SYNTHESIZED
   any state ──▶ FAILED   (on unrecoverable error; always a terminal escape hatch)
```

| State | Meaning |
|---|---|
| RECEIVED | Alert ingested, deduplicated, persisted |
| CLASSIFIED | Severity assigned, agent set chosen |
| DISPATCHED | Agent tasks published to Kafka |
| AGGREGATING | Waiting for / collecting agent results |
| PARTIAL | Deadline hit with some agents missing |
| SYNTHESIZED | Final report produced |
| RESOLVED | Human reviewed and closed |
| FAILED | Unrecoverable error — terminal |

## 1.8 Core data model (created in Sprint 1)

Five tables. Columns shown are the essential ones; you will add more.

```sql
-- incidents: the unit of work and its lifecycle
incidents(
  id UUID PK,
  idempotency_key TEXT UNIQUE,     -- dedupe duplicate alerts
  source TEXT,                     -- alertmanager | demo | synthetic
  severity TEXT,                   -- p1..p4
  state TEXT,                      -- the state machine value
  raw_alert JSONB,                 -- original payload
  created_at, updated_at TIMESTAMPTZ
)

-- agent_traces: one row per agent run, for replay and audit
agent_traces(
  id UUID PK,
  incident_id UUID FK,
  agent_name TEXT,
  prompt_version TEXT,
  input JSONB, output JSONB,
  tokens_used INT, cost_usd NUMERIC,
  latency_ms INT, status TEXT,     -- ok | timeout | error
  created_at TIMESTAMPTZ
)

-- incident_reports: the Synthesizer's final output
incident_reports(
  id UUID PK,
  incident_id UUID FK,
  summary TEXT, root_cause TEXT,
  recommended_action TEXT,
  confidence NUMERIC,
  human_decision TEXT,             -- approved | edited | rejected
  created_at TIMESTAMPTZ
)

-- audit_log: every state transition and human action
audit_log(
  id UUID PK,
  incident_id UUID FK,
  event_type TEXT, detail JSONB,
  actor TEXT,                      -- system | <agent> | <user>
  created_at TIMESTAMPTZ
)

-- prompt_versions: versioned prompts for reproducibility
prompt_versions(
  version TEXT PK,                 -- content hash
  agent_name TEXT, body TEXT,
  created_at TIMESTAMPTZ
)
```

## 1.9 Kafka topics (created in Sprint 1)

| Topic | Produced by | Consumed by | Purpose |
|---|---|---|---|
| `incidents.raw` | Ingestion | Orchestrator | New incidents needing classification |
| `agent.tasks` | Orchestrator | Agent workers | One message per agent job |
| `agent.results` | Agent workers | Orchestrator | Agent findings |
| `incidents.synthesized` | Orchestrator | UI/BFF | Final reports ready to show |
| `audit.events` | All | Audit consumer | Append-only audit stream |
| `agent.tasks.dlq` | Kafka | Manual / alerting | Dead-letter for poison messages |

All topics are partitioned by `incident_id` so all events for one incident stay ordered.

Continue to `02-ENVIRONMENT-AND-CONVENTIONS.md`.
