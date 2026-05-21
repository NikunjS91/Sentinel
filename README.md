# Sentinel

**An AI agent swarm that triages production incidents in seconds instead of minutes.**

When a system breaks at 3 AM, an on-call engineer normally spends 20–40 minutes digging through logs, metrics, dashboards, and old tickets just to *understand* the problem. Sentinel replaces that first half-hour with a team of specialized AI agents that investigate in parallel and hand the engineer a single, clear diagnosis with a recommended fix.

> **Project status:** Early active development. The architecture and roadmap are defined; the codebase is being built sprint by sprint. This README describes the target system — see the [Roadmap](#roadmap) for what currently exists.

---

## The problem

Modern incident response forces a tired human to be a fast detective. They open five tools, scroll thousands of log lines, cross-check graphs, search past incidents, and find the right runbook — all under pressure, all before they can even start fixing anything.

A single large AI model handling all of that does it poorly: too many concerns, shallow reasoning, no specialization.

## The approach

Sentinel uses a **swarm of specialist agents**, each narrow and good at one job, working in parallel — like a hospital ER team where the nurse, radiologist, lab tech, and specialist all work on one patient simultaneously instead of one after another.

Five investigators each produce a fragment of the picture; a sixth agent combines them into one answer:

| Agent | Job | Example finding |
|---|---|---|
| **Log Analyzer** | Scans error logs around the incident window | "200 DB timeout errors in the last 5 minutes" |
| **Metrics** | Reads metrics and checks SLO violations | "Memory climbing fast — looks like a leak" |
| **Topology** | Maps service dependencies and recent deploys | "A deploy hit this service 10 minutes ago" |
| **History** | Searches past incidents for similar patterns | "We saw this in March — bad DB query" |
| **Runbook** | Matches the incident to documented playbooks | "Runbook exists — step 1: roll back" |
| **Synthesizer** | Combines all findings into one report | Final diagnosis + recommended fix + confidence |

The result, delivered in seconds: *what broke, why, whether it's been seen before, and what to do — with a confidence score.* A human reviews it on a live dashboard and approves or edits.

---

## Architecture

Sentinel is built as a hybrid system across two runtimes, connected by a durable event log:

- **Control Plane — Spring Boot (Java):** alert ingestion, classification, dispatch, orchestration, budget governance, result aggregation. Stateless and horizontally scalable.
- **Agent Plane — FastAPI (Python):** the agent swarm, LLM gateway, and tool layer. Sandboxed and least-privilege.
- **Event Log — Kafka:** durable, partitioned by incident, the source of truth for events. Decouples the planes so a slow or failed agent never blocks the system.

```
Alert sources                Control Plane (Java)           Agent Plane (Python)
─────────────                ────────────────────           ────────────────────
Alertmanager  ─┐             API Gateway                    Agent Workers (autoscaled)
Datadog       ─┤             Ingestion (dedupe, correlate)    ├─ Log Analyzer
PagerDuty     ─┼──webhook──▶ Orchestrator (N replicas)        ├─ Metrics
Demo app      ─┤             Incident State Machine           ├─ Topology
Synthetic gen ─┘             Dispatcher / Budget Governor     ├─ History
                             Aggregator / Reconciler          ├─ Runbook
                                    │                         └─ Synthesizer
                                    │                                │
                                    ▼          KAFKA          ◀───────┘
                             ◀── durable event log, partitioned by incident ──▶

State & data:  Postgres (source of truth + pgvector)  ·  Redis (cache, rate limits,
idempotency)  ·  S3 (transcripts, replay artifacts)  ·  Secrets manager

Cross-cutting:  OpenTelemetry self-observability  ·  circuit breakers / retries /
dead-letter queues  ·  trust boundaries & multi-tenant isolation  ·  human approval gate
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

## Getting started

> Setup instructions will be finalized as Sprint 1 lands. Expected flow:

```bash
# Clone the repository
git clone https://github.com/NikunjS91/sentinel.git
cd sentinel

# Start the local stack (Postgres, Redis, Kafka, Prometheus, Loki, Grafana, Ollama)
docker compose up -d

# Run the control plane
cd orchestrator && ./mvnw spring-boot:run

# Run the agent plane
cd agents && uvicorn app.main:app --reload

# Run the dashboard
cd ui && npm install && npm run dev
```

A demo app and a synthetic incident generator are included so the full pipeline can be exercised locally without a real production environment.

---

## Roadmap

The project is built in six two-week sprints, scoped as **Phase 1** of a longer vision.

### Phase 1 — Diagnosis Swarm (MVP)

- [ ] **Sprint 1** — Foundation: monorepo, Docker Compose, Postgres schema, Kafka topics, Spring Boot + FastAPI skeletons, LLM abstraction layer
- [ ] **Sprint 2** — Demo app emitting realistic telemetry, synthetic incident generator, Log Analyzer + Metrics agents
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
