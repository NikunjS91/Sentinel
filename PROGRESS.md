# Sentinel — Progress Log

## Sprint 1 — Foundation

### Day 9 — Aggregator & incident resolution
- Done: `AgentResult` Java record (camelCase wire format matching Python Pydantic aliases);
  `AggregatorListener` consumes `agent.results`, idempotent on `(incident_id, agent_name)`,
  records `AgentTrace`, walks `DISPATCHED → AGGREGATING → SYNTHESIZED → RESOLVED`, writes
  minimal `IncidentReport` (summary from LLM output), publishes to `incidents.synthesized`.
  Full Sprint-1 pipeline closes: POST alert → Kafka → classify → dispatch → LLM → trace →
  RESOLVED in ~5-15s end-to-end. Tests TC-1.9.1–1.9.5 pass; 30 Java tests green.
- Reused: `AgentTrace` and `IncidentReport` entities from `com.sentinel.incident` (existed
  from Day 2 schema); repositories placed in `com.sentinel.aggregate`. `cost_usd` set to
  `BigDecimal.ZERO` (computed Sprint 5).
- Known simplification: `state == DISPATCHED` one-agent guard; Sprint 3 replaces with
  multi-agent fan-in + deadline. Unparseable `agent.results` logged and skipped; proper
  DLQ is Sprint 5.
- Next: Day 10 — end-to-end test, CI integration (Java + Python), README, sprint demo.

### Day 8 — LLM abstraction & echo agent
- Done: `LLMClient` Protocol + normalized `LLMResponse`; `OllamaClient` (real, with
  60s explicit timeout, `stream=False`); `AnthropicClient`/`GroqClient` stubbed
  (`NotImplementedError`, land Sprint 5); `make_llm_client` factory selecting by
  `LLM_BACKEND`; `echo_agent` calls the LLM and wraps text/tokens/latency, failing into
  `status=error` result rather than throwing; worker routes `"echo"` to the agent and
  drops the Day-7 stub. Tests TC-1.8.1–1.8.4 pass; TC-1.8.3 (Ollama integration) runs
  locally with `qwen3:14b` and skips cleanly when Ollama is absent.
- Decision: `qwen3:14b` used as default model (`mistral` not installed locally). The spec
  model is advisory; the abstraction makes swapping trivial.
- Decision: Kafka worker integration tests (TC-1.7.2, TC-1.7.3) use `llm_backend=anthropic`
  (raises immediately, caught by echo_agent) to avoid a 14B model call in the Kafka
  plumbing tests. TC-1.8.3 owns the real LLM coverage.
- Seam noted: worker agent-routing is an `if` today; becomes a dict dispatch in Sprint 2.
- Next: Day 9 — Aggregator: orchestrator consumes agent.results, records a trace,
  resolves the incident.

### Day 7 — FastAPI agent service & Kafka worker
- Done: Python `agents/` service bootstrapped with FastAPI + aiokafka + Pydantic v2.
  `KafkaWorker` consumes `agent.tasks` (manual commit, `auto_offset_reset=earliest`),
  publishes a stub `AgentResult` to `agent.results`, routes malformed messages to
  `agent.tasks.dlq` with a `{"reason":..., "payload_b64":...}` envelope. `GET /health`
  returns 200 `{"status":"ok"}`. camelCase wire aliases (`incidentId`, `agentName`,
  `tokensUsed`, `latencyMs`) implemented via `Field(alias=...)` + `populate_by_name=True`.
  `ruff`, `mypy --strict`, and `pytest` all clean. TC-1.7.1–1.7.4 pass.
- Decision: Used `confluentinc/cp-kafka` (testcontainers default) — Apache Kafka
  KRaft image does not emit the startup log pattern that Python testcontainers waits for.
- Known simplification: `_stub_result()` returns a zero-token stub; real LLM call is Day 8.
- Next: Day 8 — LLM gateway: replace stub with real Ollama/Anthropic call.

### Day 6 — Classifier & dispatcher
- Done: AgentTask message record (wire contract decided: camelCase — incidentId, agentName,
  service, payload — documented in contracts/agent-task-schema.md); rule-based
  IncidentClassifier (severity p1-p4, never throws); Dispatcher publishes keyed AgentTask
  messages and is built for many agents; ClassifierListener — the first Kafka consumer —
  drives RECEIVED→CLASSIFIED→DISPATCHED and dispatches the echo task, with an idempotency
  guard against at-least-once redelivery. All operations in one @Transactional on
  onRawIncident to prevent partial re-inserts under concurrent test cleanup. Tests
  TC-1.6.1–1.6.4 pass. All 25 tests green.
- Decision: AgentTask wire format is camelCase — recorded in contracts/agent-task-schema.md.
- Known simplification: listener logs+skips a missing incident; proper DLQ is Sprint 5.
- Next: Day 7 — FastAPI agent service: the Python plane, a Kafka consumer for agent.tasks.

### Day 5 — Incident state machine
- Done: IncidentState enum (8 states, terminal flag); IllegalStateTransitionException;
  IncidentStateMachine with the legal-transition table, canTransition check, and a
  transactional transition() that persists + audits. Incident entity state field
  migrated to the enum; Day-4 code (IngestService, IngestController, SchemaIntegrationTest)
  updated to match. Unit tests TC-1.5.1–1.5.5 and integration test TC-1.5.6 pass.
  All 21 tests green.
- Blocked: (none)
- Next: Day 6 — Classifier & dispatcher: consume incidents.raw, assign severity,
  drive RECEIVED->CLASSIFIED->DISPATCHED, publish to agent.tasks.

### Day 4 — Alert ingestion & idempotency
- Done: POST /alerts with AlertRequest validation; SHA-256 idempotency key from
  identity fields (source|service|alertName|fingerprint); IngestService dedups,
  persists a RECEIVED incident, writes an audit row, publishes the incident id to
  incidents.raw; AuditWriter component + AuditLogRepository wired; clean 400 on
  invalid payloads via IngestExceptionHandler. Integration tests TC-1.4.1–1.4.4
  pass. All 9 tests green.
- Note: Kafka send after DB commit — known gap (message lost if send fails);
  addressed in Sprint 5 hardening.
- Blocked: (none)
- Next: Day 5 — Incident state machine: explicit, validated, persisted,
  audited state transitions.

### Day 1 — Setup
- Done: monorepo structure, docker-compose, CLAUDE.md, .gitignore,
  .env.example, placeholder CI. Stack comes up healthy. CI green.
- Blocked: (none)
- Next: Day 4 — Alert ingestion: POST /alerts, idempotency-key dedup, persist a RECEIVED incident, publish to incidents.raw.

### Day 3 — Kafka topics & orchestrator skeleton
- Done: spring-kafka added; six topics provisioned automatically on startup via
  NewTopic beans; KafkaTopicConfig holds topic-name constants; custom Kafka
  health indicator wired into /actuator/health; integration tests TC-1.3.1 and
  TC-1.3.2 pass with a Testcontainers Kafka. All 5 tests green.
- Blocked: (none)
- Next: Day 4 — Alert ingestion: POST /alerts, idempotency-key dedup, persist a
  RECEIVED incident, publish to incidents.raw.

### Day 2 — Database schema
- Done: Spring Boot project created; Flyway wired in; V1 migration creates the
  5 core tables; JPA entities validate against the schema; IncidentRepository
  added; integration tests (TC-1.2.1, TC-1.2.2) pass.
- Note: Docker 29.x requires API ≥ 1.44 — set `api.version=1.44` in Surefire
  argLine to unblock Testcontainers on this machine.
- Blocked: (none)
- Next: Day 3 — Kafka topics and the orchestrator skeleton (Kafka config,
  topic provisioning, health check including Kafka).
