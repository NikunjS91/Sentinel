# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

Early active development. Sprint 1 has not yet started. The `code/` directory is empty. The `docs/files.zip` contains the full 6-sprint engineering plan.

## What Sentinel is

An AI agent swarm for production incident triage. When an alert fires, six specialist agents investigate in parallel (logs, metrics, topology, history, runbook) and a Synthesizer combines their findings into a single diagnosis with a confidence score. A human reviews the result on a live dashboard.

## Architecture: three planes

```
Alert sources → API Gateway → Kafka (event log) → Control Plane (Java) + Agent Plane (Python)
                                                           ↓
                                                   Postgres · Redis · S3
                                                   React dashboard (SSE)
```

- **`orchestrator/`** — Spring Boot (Java 21): alert ingestion, dedup, incident state machine, dispatcher, budget governor, aggregator, SSE endpoints.
- **`agents/`** — FastAPI (Python 3.11): six agent workers, LLM gateway, PromQL/LogQL/pgvector tools, versioned prompts.
- **`ui/`** — React 18 + Vite + Tailwind: live dashboard consuming SSE.
- **`demo-app/`** — breakable service emitting realistic telemetry (Sprint 2).
- **`synthetic/`** — incident generator (`generate.py`) for local testing (Sprint 2).
- **`eval/`** — evaluation harness (`run_eval.py`) scoring swarm against known incidents (Sprint 5).
- **`infra/`** — `k8s/` manifests and Grafana dashboards (Sprint 6).

## Incident lifecycle (state machine persisted in Postgres)

```
RECEIVED → CLASSIFIED → DISPATCHED → AGGREGATING ┬→ SYNTHESIZED → RESOLVED
                                                  └→ PARTIAL → SYNTHESIZED
any state → FAILED  (terminal)
```

## Kafka topics (all partitioned by `incident_id`)

| Topic | Flow |
|---|---|
| `incidents.raw` | Ingestion → Orchestrator |
| `agent.tasks` | Orchestrator → Agent workers |
| `agent.results` | Agent workers → Orchestrator |
| `incidents.synthesized` | Orchestrator → UI/BFF |
| `audit.events` | All producers → Audit consumer |
| `agent.tasks.dlq` | Dead-letter queue |

## Commands

All three application services run from the IDE against the Docker Compose stack.

```bash
# Start infrastructure (Postgres, Redis, Kafka, Prometheus, Loki, Grafana, MinIO)
docker compose up -d

# Control plane
cd orchestrator && ./mvnw spring-boot:run

# Run orchestrator tests (add -pl orchestrator for monorepo root)
./mvnw test -pl orchestrator

# Run a single orchestrator test class
./mvnw test -pl orchestrator -Dtest=IncidentStateMachineTest

# Agent plane
cd agents && uvicorn app.main:app --reload

# Agent tests
cd agents && pytest

# Run a single agent test file
cd agents && pytest app/tests/test_log_analyzer.py -v

# UI
cd ui && npm install && npm run dev

# Lint/format Python
cd agents && ruff check . && ruff format .
```

Ollama runs natively on the host (not in Compose) for GPU access: `ollama pull mistral`.

## Environment variables

Copy `.env.example` → `.env` (gitignored). Key variables:

```
DATABASE_URL=postgresql://sentinel:sentinel@localhost:5432/sentinel
KAFKA_BOOTSTRAP=localhost:9092
REDIS_URL=redis://localhost:6379
LLM_BACKEND=ollama            # ollama | anthropic | groq
OLLAMA_HOST=http://localhost:11434
OLLAMA_MODEL=mistral
ANTHROPIC_API_KEY=            # empty until Sprint 5
INCIDENT_TOKEN_BUDGET=20000
```

## Coding conventions

### Java (orchestrator)
- Package root: `com.sentinel`. One package per concern: `ingest`, `incident`, `dispatch`, `aggregate`, `budget`, `stream`, `config`.
- Constructor injection only — no field `@Autowired`.
- Records for DTOs and Kafka events; classes for entities and services.
- Every external call (Kafka, DB, HTTP) must have an explicit timeout.
- Format with Spring Java Format plugin.

### Python (agents)
- Package: `app/`. One module per agent under `app/agents/`.
- Type hints everywhere; keep `mypy` clean.
- `ruff` for lint and format.
- All I/O must be `async` — no blocking calls inside the worker loop.
- Pydantic models for every inbound and outbound message.

### Commits
Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`. One branch per ticket, named `feat/S{sprint}-D{day}-{slug}`.

## Testing

Four levels: unit (JUnit 5 / pytest), integration (Testcontainers for real Postgres + Kafka), contract (shared JSON schema validation), and end-to-end (full pipeline script). Prefer unit and integration tests; end-to-end only for the critical happy path. Testcontainers is the standard for integration tests — do not mock Postgres or Kafka.

The coverage rule: every `MUST` ticket must have tests that would fail if the feature broke.

## LLM backend

Dev uses Ollama + Mistral 7B (free, offline). Prod uses Anthropic API or Groq. The LLM gateway in `app/llm/` abstracts the backend and adds queueing, circuit breakers, retries, and a model fallback ladder. `ANTHROPIC_API_KEY` stays empty locally until Sprint 5.
