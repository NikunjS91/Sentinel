# Sentinel

**An AI agent swarm that triages production incidents in seconds instead of minutes.**

When a system breaks at 3 AM, an on-call engineer normally spends 20–40 minutes digging through logs, metrics, dashboards, and old tickets just to *understand* the problem. Sentinel replaces that first half-hour with a team of specialized AI agents that investigate in parallel and hand the engineer a single, clear diagnosis with a recommended fix.

> **Project status:** Sprint 2 complete. Three specialist agents (Log Analyzer, Metrics, Echo) run in parallel on every incident. Swarm asymmetry demonstrated on `slow_query`: Metrics agent 0.88 confidence, Log Analyzer 0.15 — same incident, different specialists, different answers. ~40 Java tests, 37 Python tests, 8 demo-app tests, all green. Real two-language CI on every PR. See [What's built](#whats-built) for the current state and [Roadmap](#roadmap) for the full plan.

---

## The problem

Modern incident response forces a tired human to be a fast detective. They open five tools, scroll thousands of log lines, cross-check graphs, search past incidents, and find the right runbook — all under pressure, all before they can even start fixing anything.

A single large AI model handling all of that does it poorly: too many concerns, shallow reasoning, no specialization.

## The approach

Sentinel uses a **swarm of specialist agents**, each narrow and good at one job, working in parallel — like a hospital ER team where the nurse, radiologist, lab tech, and specialist all work on one patient simultaneously instead of one after another.

Five investigators each produce a fragment of the picture; a sixth agent combines them into one answer:

| Agent | Job | Example finding | Status |
|---|---|---|---|
| **Log Analyzer** | Scans error logs around the incident window | "200 DB timeout errors in the last 5 minutes" | ✅ Sprint 2 |
| **Metrics** | Reads metrics and checks SLO violations | "p95 latency 0.82s exceeds SLO — DB query latency" | ✅ Sprint 2 |
| **Synthesizer** | Combines all findings into one report | Final diagnosis + recommended fix + confidence | 🔜 Sprint 3 |
| **Topology** | Maps service dependencies and recent deploys | "A deploy hit this service 10 minutes ago" | 🔜 Sprint 4 |
| **History** | Searches past incidents for similar patterns | "We saw this in March — bad DB query" | 🔜 Sprint 4 |
| **Runbook** | Matches the incident to documented playbooks | "Runbook exists — step 1: roll back" | 🔜 Sprint 4 |

The result, delivered in seconds: *what broke, why, whether it's been seen before, and what to do — with a confidence score.* A human reviews it on a live dashboard and approves or edits.

---

## Architecture

Sentinel is built as a hybrid system across two runtimes, connected by a durable event log:

- **Control Plane — Spring Boot (Java):** alert ingestion, classification, dispatch, orchestration, budget governance, result aggregation. Stateless and horizontally scalable.
- **Agent Plane — FastAPI (Python):** the agent swarm, LLM gateway, and tool layer. Sandboxed and least-privilege.
- **Event Log — Kafka:** durable, partitioned by incident, the source of truth for events. Decouples the planes so a slow or failed agent never blocks the system.

```mermaid
flowchart TB
    subgraph Sources["Alert Sources"]
        AM(Alertmanager)
        DD(Datadog)
        PD(PagerDuty)
        DA(Demo App)
        SG(Synthetic Gen)
    end

    subgraph CP["Control Plane · Spring Boot (Java)"]
        direction TB
        GW[API Gateway]
        ING[Ingestion\nDedupe · Correlate]
        SM[Incident State Machine\nRECEIVED → DISPATCHED → SYNTHESIZED]
        DISP[Dispatcher /\nBudget Governor]
        AGG[Aggregator /\nReconciler]
        SSE[SSE Endpoint]
    end

    subgraph KAFKA["Kafka Event Log  (partitioned by incident_id)"]
        direction LR
        T1[incidents.raw]
        T2[agent.tasks]
        T3[agent.results]
        T4[incidents.synthesized]
        T5[audit.events]
        T6[agent.tasks.dlq]
    end

    subgraph AP["Agent Plane · FastAPI (Python)"]
        direction TB
        LLM[LLM Gateway\nQueue · Circuit Breaker · Fallback]
        subgraph Agents["Agent Workers"]
            A1[Log Analyzer]
            A2[Metrics]
            A3[Topology]
            A4[History]
            A5[Runbook]
            A6[Synthesizer]
        end
    end

    subgraph Data["State & Data"]
        PG[(PostgreSQL\n+ pgvector)]
        RD[(Redis\nCache · Rate Limits)]
        S3[(S3\nTranscripts · Replay)]
    end

    subgraph UI["Dashboard · React + Vite"]
        DASH[Live Incident View\nHuman Approval Gate]
    end

    Sources -->|webhook| GW
    GW --> ING --> SM --> DISP
    DISP -->|agent.tasks| T2
    T2 --> LLM
    LLM <--> Agents
    Agents -->|agent.results| T3
    T3 --> AGG --> SSE
    SSE -->|SSE stream| DASH
    AGG -->|incidents.synthesized| T4
    SM <--> PG
    AGG <--> PG
    CP <--> RD
    AP --> S3
```

### Design principles

- **Idempotent ingestion** — duplicate alerts are deduplicated; alert storms collapse into a single incident.
- **Durable incident state** — the incident lifecycle is an explicit state machine persisted in Postgres, so any orchestrator replica can resume any incident after a crash.
- **Graceful degradation** — if an agent times out, the system proceeds with a `PARTIAL` result and flags the gap rather than blocking.
- **Bounded cost** — token budgets are enforced per-incident, per-tenant, and globally, with a kill switch.
- **Resilient LLM calls** — a dedicated LLM gateway adds queueing, circuit breakers, retries, and a model fallback ladder.
- **Self-observability** — Sentinel runs its own isolated telemetry stack so a customer outage never blinds Sentinel itself.
- **Extensible swarms** — agents are registered by capability, so new swarm types can be added without rewriting the orchestrator.

---

## Tech stack

| Layer | Technology |
|---|---|
| Control plane | Java, Spring Boot |
| Agent plane | Python, FastAPI |
| Event bus | Apache Kafka |
| Storage | PostgreSQL (+ pgvector), Redis, S3-compatible object store |
| LLM backends | Ollama (local, dev) · Anthropic / Groq API (production) |
| Observability | Prometheus, Loki, Grafana, OpenTelemetry |
| Frontend | React, Vite, Tailwind, Server-Sent Events |
| Infrastructure | Docker Compose (dev), Kubernetes (production) |
| CI/CD | GitHub Actions |

---

## What's built

> Sprint 2 complete — ~40 Java tests · 37 Python tests · 8 demo-app tests · full three-agent pipeline end-to-end

| Component | Status | Details |
|---|---|---|
| Monorepo structure | ✅ Sprint 1 | All directories, Docker Compose, real CI |
| Docker Compose stack | ✅ Sprint 1 | Postgres 16, Redis 7, Apache Kafka — all healthy |
| Postgres schema | ✅ Sprint 1 | Flyway V1+V2: incidents, agent_traces, incident_reports, audit_log, prompt_versions, expected_agents |
| JPA entities + repos | ✅ Sprint 1 | All entities, Hibernate 6, `IncidentRepository` with dedup query |
| Kafka topics | ✅ Sprint 1 | 6 topics provisioned on startup; Kafka health indicator |
| Alert ingestion | ✅ Sprint 1 | `POST /alerts` — validates, deduplicates (SHA-256), persists `RECEIVED`, publishes to `incidents.raw` |
| Incident state machine | ✅ Sprint 1 | Explicit transitions `RECEIVED→CLASSIFIED→DISPATCHED→AGGREGATING→SYNTHESIZED→RESOLVED`, all audited |
| Classifier + Dispatcher | ✅ Sprint 1 | Consumes `incidents.raw`, assigns severity, publishes `AgentTask` to `agent.tasks` |
| Python agent service | ✅ Sprint 1 | FastAPI + aiokafka; `KafkaWorker` with DLQ; LLM abstraction (Ollama/Anthropic/Groq) |
| Echo agent + LLM layer | ✅ Sprint 1 | `OllamaClient` (real), stub backends; `echo_agent` wraps LLM response in `AgentResult` |
| Aggregator | ✅ Sprint 1 | Consumes `agent.results`, records trace, resolves incident, publishes to `incidents.synthesized` |
| CI | ✅ Sprint 1 | Java (mvn verify) + Python (ruff/mypy/pytest) + demo-app jobs run in parallel on every PR |
| Demo app | ✅ Sprint 2 | Spring Boot breakable service — 3 endpoints, 4 failure modes, Prometheus + structured-JSON logs |
| Observability stack | ✅ Sprint 2 | Prometheus, Loki, Promtail, Grafana — containerized, datasource-provisioned, queryable |
| Tool layer | ✅ Sprint 2 | `query_logs` (Loki/LogQL) + `query_metrics` + `slo_violations` (Prometheus/PromQL) — async, fixture-mode |
| Prompt registry | ✅ Sprint 2 | Versioned, immutable, Postgres-synced; `.txt` files loaded at startup |
| Log Analyzer agent | ✅ Sprint 2 | LLM agent scanning logs around incident; defensive JSON parsing with retry |
| Metrics agent | ✅ Sprint 2 | LLM agent reading 3 PromQL queries + SLO violations; calibrated confidence |
| Three-agent dispatch | ✅ Sprint 2 | Dict-based registry; `expected_agents` per incident; aggregator resolves dynamically |
| Swarm asymmetry | ✅ Sprint 2 | TC-2.11.2 codifies: Metrics 0.88 confidence vs Log Analyzer 0.15 on slow_query |
| React dashboard | 🔜 Sprint 3 | SSE-powered live incident view with human approval gate |

---

## Getting started

> Prerequisites: Java 21, Python 3.11, Docker, [Ollama](https://ollama.ai) with `mistral` pulled (`ollama pull mistral`).

```bash
# Clone
git clone https://github.com/NikunjS91/Sentinel.git
cd Sentinel

# Start infrastructure (Postgres 16, Redis 7, Kafka, Prometheus, Loki, Grafana, Promtail, MinIO — 8 services)
docker compose up -d
docker compose ps   # confirm all 8 services healthy

# Terminal A — control plane (port 8080)
cd orchestrator && ./mvnw spring-boot:run

# Terminal B — agent plane (port 8001)
cd agents && python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
uvicorn app.main:app --port 8001

# Terminal C — fire an alert and watch the three-agent pipeline resolve it
curl -i -X POST http://localhost:8080/alerts \
  -H 'Content-Type: application/json' \
  -d @sample-alert.json
# → 201 Created  (new incident, dispatches echo + log_analyzer + metrics)
# → Same curl again → 200 OK (deduplicated — same incident ID returned)

# Wait ~45s for the three LLM calls, then inspect
docker compose exec postgres psql -U sentinel -d sentinel \
  -c "SELECT id, state, severity FROM incidents;"
# state should be RESOLVED

docker compose exec postgres psql -U sentinel -d sentinel \
  -c "SELECT agent_name, status, tokens_used, output->>'confidence' AS confidence FROM agent_traces;"

docker compose exec postgres psql -U sentinel -d sentinel \
  -c "SELECT summary FROM incident_reports;"

# Optional: inject a slow_query failure and observe swarm asymmetry
curl -s -X POST http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' -d '{"mode":"slow_query"}'
# Then fire alerts and observe Log Analyzer ≈0.15 confidence vs Metrics ≈0.88
# See docs/demos/SPRINT-2-SWARM-ASYMMETRY.md for the full demo script

# Run all tests (requires Docker)
cd orchestrator && ./mvnw verify             # ~40 Java tests
cd ../agents && ruff check . && mypy . && pytest  # 37 Python tests
cd ../demo-app && mvn verify                 # 8 demo-app tests
```

> Day-plan docs are in `docs/`. `sample-alert.json` is at the repo root.

---

## Roadmap

The project is built in six two-week sprints, scoped as **Phase 1** of a longer vision.

### Phase 1 — Diagnosis Swarm (MVP)

- [x] **Sprint 1** — Foundation: full pipeline end-to-end ✅ — alert ingestion, state machine, classifier/dispatcher, Python agent service, LLM abstraction (Ollama), aggregator, 31+9 tests, real CI
- [x] **Sprint 2** — Demo app + full observability stack, Log Analyzer + Metrics agents, tool layer, prompt registry, dict dispatch, swarm asymmetry codified ✅ — ~40+37+8 tests, three-agent fan-in, CI-gated
- [ ] **Sprint 3** — Synthesizer agent, parallel dispatch, partial-result handling, live React dashboard with SSE
- [ ] **Sprint 4** — Topology, History (pgvector), and Runbook agents
- [ ] **Sprint 5** — Production hardening: circuit breakers, budgets, evaluation harness, prompt versioning
- [ ] **Sprint 6** — Kubernetes manifests, CI/CD pipeline, documentation, demo

### Beyond Phase 1

- **Remediation Swarm** — a second swarm (rollback, scaling, traffic-shifting, verification agents) that begins fixing the incident in parallel with diagnosis, behind a human-approval safety gate.
- **Integrations** — adapters for Datadog, New Relic, PagerDuty, GitHub, and Kubernetes.
- **Collective intelligence** — anonymized incident-pattern learning so a fix discovered in one environment helps resolve similar incidents elsewhere.

---

## Why this project exists

Sentinel is a portfolio and learning project exploring multi-agent AI systems applied to a real, hard operations problem: incident response. It is designed around production-grade concerns — reliability, failure handling, observability, cost control, and security — rather than a happy-path demo.

## License

MIT — see [LICENSE](LICENSE).

## Author

**Nikunj Shetye** — [LinkedIn](https://www.linkedin.com/in/nikunj-shetye) · [GitHub](https://github.com/NikunjS91)
