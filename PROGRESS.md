# Sentinel — Progress Log

## Sprint 3 — Synthesis, Deadlines, UI

### Day 21 (S3-D1) — Synthesizer agent + two-stage dispatch
- Done: `SynthesizerFinding` typed model (summary, root_cause, recommended_action,
  confidence, dissenting_notes, contributing_agents) added to `agents/app/agents/types.py`.
  `agents/app/prompts/synthesizer.txt` replaced with real weighting-guidance prompt
  (two template variables, `.replace()` not `.format()`, explicit dissent instructions).
  `agents/app/agents/synthesizer.py` — meta-agent using shared `call_with_retry` +
  `AgentContext` (Day-18/19 pattern); contributing_agents populated post-hoc if LLM forgets.
  Registered in `AGENTS` dict — edit one of Day-19 contract's two edits for a specialist
  (see architecture note below).
  Java: `SynthesizerTaskBuilder` reads `agent_traces`, deserializes output strings,
  builds specialist_findings payload; `Dispatcher.dispatchSynthesizer` separate from
  `dispatch` (does NOT touch expected_agents); `AgentTraceRepository` gains
  `findByIncidentId` and `countByIncidentIdAndAgentNameNot`.
  `AggregatorListener` refactored into two-stage logic: `handleSpecialistResult` →
  AGGREGATING + dispatch Synthesizer when specialist count reaches expected; 
  `handleSynthesizerResult` → write report from Synthesizer output + SYNTHESIZED + RESOLVED
  (guarded by `state == AGGREGATING` to prevent double-resolution on redelivery).
  Report now populated from Synthesizer's summary/root_cause/recommended_action/confidence,
  not the last specialist's message.
  Tests: TC-3.1.1-3.1.3 (Python unit), TC-3.1.4-3.1.5 (Java integration),
  TC-3.1.6 (`SprintThreeE2ETest` e2e). All pre-existing tests updated to reflect
  two-stage dispatch (await AGGREGATING before sending Synthesizer result; trace count 4).
  Final: 42 Java tests + 36 Python tests (non-infra), all green. ruff + mypy strict clean.
- Architecture note: The Day-19 "two edits" contract holds for SPECIALISTS (parallel
  siblings — Topology, History, Runbook in Sprint 4 will each be two edits).
  The Synthesizer is a META-AGENT: it needs orchestration code specific to its role
  (two-stage dispatch, SynthesizerTaskBuilder, handleSynthesizerResult). That's a new
  capability, not a contract violation. Future meta-agents (a final-validator, an auditor)
  will similarly need their own orchestration code.
- Next: Day 22 — deadline + PARTIAL handling. Synthesizer today waits forever for all
  specialists; Day 22 adds a per-incident deadline that forces synthesis with what's
  available, marking the incident PARTIAL if a specialist timed out.

## Sprint 2 — Demo App & First Agents

### Day 20 (S2-D10) — Sprint 2 close
- Done: `SprintTwoE2ETest.java` (TC-2.11.1) — three-agent flow end-to-end: alert POST,
  await DISPATCHED + expected_agents = ["echo","log_analyzer","metrics"], send three
  AgentResults in non-canonical order (metrics/echo/log_analyzer), await RESOLVED,
  assert three traces + one report.
  `agents/app/tests/test_swarm_asymmetry.py` (TC-2.11.2) — architectural thesis codified:
  Log Analyzer confidence 0.15 vs Metrics agent confidence 0.88 on slow_query; assertion
  `metric_confidence - log_confidence > 0.5` (observed delta 0.73, threshold conservative).
  `AggregatorIntegrationTest` regression fixed: `buildDispatchedIncident()` now sets
  `expectedAgents = ["echo","log_analyzer","metrics"]`; TC-2.10.3 explicitly clears to []
  to test the safety guard — all 9 aggregator tests pass.
  Demo artifact: `docs/demos/SPRINT-2-SWARM-ASYMMETRY.md` — full slow_query demo script
  with SQL query showing divergent findings.
  README updated: Sprint 2 status, agent table with ✅/🔜, What's built table, test counts
  (~40 Java + 37 Python + 8 demo-app), Getting Started for 8-service stack.
  Docs updated: `00-PROJECT-SETUP-REPORT.md` sprint status, `01-OVERVIEW-AND-ARCHITECTURE.md`
  agent table, `04-SPRINT-2-DEMO-APP-AND-AGENTS.md` completion banner.
  `docs/retrospectives/SPRINT-02.md` written — honest account of what went well, what was
  hard (format/replace, Kafka divergence, Loki schema, aggregator regression), decisions,
  known simplifications, riskiest unknown (Synthesizer prompt).
- Sprint 2 final score: 3 specialist agents in parallel, demo app + full observability
  stack, tool layer, prompt registry, dispatch refactor, swarm asymmetry codified.
  Test counts: ~40 Java + 37 Python + 8 demo-app, all green.
- Sprint 3 starts: Synthesizer + deadline + PARTIAL handling + live UI. The placeholder
  Synthesizer prompt is already in the registry from Day 16.

### Day 19 (S2-D9) — Multi-agent dispatch refactor
- Done: `AgentContext` frozen dataclass in `agents/app/agents/_context.py`; uniform
  agent signature `(task, ctx: AgentContext)` across echo, log_analyzer, metrics_agent;
  `AGENTS` dict + `AgentFn` type alias + `known_agents()` in `_registry.py`; worker
  `_handle` collapsed to single dict-dispatch branch with one `prompt_registry` guard.
  Java: V2 Flyway migration adds `incidents.expected_agents JSONB NOT NULL DEFAULT '[]'`;
  `Incident` entity gains `expectedAgents: List<String>` with `@JdbcTypeCode(SqlTypes.JSON)`;
  `Dispatcher` now constructor-injects `IncidentRepository`, saves `expectedAgents` before
  Kafka send; `AggregatorListener` resolves when `traceCount >= expectedCount` where
  `expectedCount = inc.getExpectedAgents().size()` (with `expectedCount > 0` guard).
  Tests: TC-2.10.1 (dispatcher records expected_agents), TC-2.10.2 (aggregator uses
  dynamic count, resolves 2-agent incident at 2 traces), TC-2.10.3 (empty expected_agents
  never resolves). All prior Python (34 non-Docker) + Java tests pass unchanged.
- Decisions: `AgentContext` is a frozen dataclass (not Pydantic) — holds non-serializable
  objects (LLM client, prompt registry). `expected_agents` is JSONB on the incident row;
  `List.copyOf()` at the dispatcher boundary prevents caller mutation.
- Added-agent contract verified: writing a new agent requires exactly two edits —
  (1) register in Python `AGENTS` dict, (2) append to `List.of(...)` in
  `ClassifierListener.java`. No threshold to update, no aggregator change.
- Next: Day 20 — Sprint 2 close (integration tests, demo, retrospective).

### Day 18 (S2-D8) — Metrics agent
- Done: `agents/app/agents/_parse.py` — shared `call_with_retry`/`try_parse`
  helpers generic over Pydantic model (`T = TypeVar("T", bound=BaseModel)`);
  `LLMStats` accumulates tokens + latency across both LLM calls; `log_analyzer.py`
  refactored to use shared helpers (Day-17 tests TC-2.8.1–2.8.4 pass unchanged —
  proof of behavior-preserving extraction); `MetricsAgentFinding` typed model added
  to `agents/app/agents/types.py`; `metrics_agent.py` composes three PromQL queries
  (p95 latency, error rate, heap) + `slo_violations` into a bounded `_MetricsSummary`,
  substitutes into prompt via `.replace()` (NOT `.format()` — Day-17 lesson),
  returns structured `AgentResult` with `prompt_version`; worker has third `elif`
  branch for `"metrics"` (deliberate ahead of Day-19 dict-dispatch refactor); Java
  dispatcher publishes 3 tasks per incident (`echo`, `log_analyzer`, `metrics`);
  aggregator resolves at `traceCount >= 3`.
  Tests: TC-2.9.1 (4 subtests), TC-2.9.2 (2 subtests) in `test_parse_helper.py`;
  TC-2.9.3–TC-2.9.6 in `test_metrics_agent.py`; TC-2.9.7 in
  `ClassifierDispatchIntegrationTest.java`. All existing Day-17 tests updated for
  three-agent flow (`sendThreeResults`, trace count 3).
- Sprint-2 coupling note: dispatch list and aggregator threshold remain hardcoded
  together (now at 3). Day 19 replaces with an expected-agent-set per incident.
- Next: Day 19 — multi-agent dispatch refactor: dict-based worker routing,
  expected-agent-set per incident, remove hardcoded threshold coupling.

### Day 17 (S2-D7) — Log Analyzer agent
- Done: `log_analyzer` agent in `agents/app/agents/log_analyzer.py` — calls
  `query_logs` (Day 14), fetches `log_analyzer` prompt from registry (Day 16),
  calls LLM (Day 8), returns structured `AgentResult` with `prompt_version`;
  `LogAnalyzerFinding` typed model in `agents/app/agents/types.py`; defensive
  JSON parse (`_try_parse`) strips code fences, finds first `{…}`, validates
  with Pydantic; one retry with nudge on parse failure; `_LLMStats` accumulates
  tokens + latency across both calls; fallback returns `confidence=0.0`;
  `KafkaWorker` routes `log_analyzer` tasks via `elif`; prompt substitution uses
  `.replace("{log_summary}", ...)` (not `str.format()`) to avoid KeyError on
  the JSON examples in the prompt body.
  Java: `ClassifierListener` dispatches `["echo", "log_analyzer"]` per incident;
  `AggregatorListener` resolves only when `traceCount >= 2`; `countByIncidentId`
  added to `AgentTraceRepository`. Existing tests (TC-1.9.2–1.9.4, TC-1.10.1)
  updated for two-result flow. New tests TC-2.8.7–2.8.8 (Java) and TC-2.8.1–2.8.5
  (Python, 5 pass incl. live Ollama). 30 Python tests total.
- Known coupling (Sprint-2 simplification): aggregator `traceCount >= 2`
  matches the two-agent dispatch list exactly. Day 19 replaces with a proper
  "expected agent set" per incident.
- Next: Day 18 — Metrics agent. Same shape over `query_metrics` + `slo_violations`.

### Day 16 (S2-D6) — Prompt registry
- Done: `agents/app/prompts/{log_analyzer,metrics_agent,synthesizer}.txt`;
  `PromptRegistry` loader (SHA-256:12 hashes, immutable, sorted glob load);
  `upsert_prompt_versions` writes loaded prompts to `prompt_versions` (Day-2
  table) with `ON CONFLICT (version) DO NOTHING`; lifespan integration in
  `main.py` — registry loaded before Kafka worker, attached to `app.state` and
  `worker.prompt_registry`; `/health` now reports `prompts_loaded`. `asyncpg`
  added to deps; `asyncpg.*` added to mypy `ignore_missing_imports` override.
  Tests TC-2.7.1–2.7.5: 4 pass, TC-2.7.5 skips when Postgres absent. `ruff` +
  `mypy --strict` clean on 26 source files. 25 Python tests total.
- Design: one `{placeholder}` per prompt; registry immutable after load (prompt
  change = service restart = new hash = new row, old row preserved as history).
  Postgres shared with orchestrator — same `database_url`, no intermediary API.
  Synthesizer prompt is a placeholder for Sprint 3; Log Analyzer and Metrics
  agent prompts are ready for Days 17–18.
- Next: Day 17 — Log Analyzer agent. Uses `prompt_registry.get("log_analyzer")`
  + `query_logs` + LLM gateway, returns a structured `AgentResult` with
  `prompt_version` populated.

### Day 15 (S2-D5) — Tool layer: PromQL query tool
- Done: `agents/app/tools/metrics.py` — `query_metrics` async function; `MetricResult`,
  `SeriesSummary`, `SLOViolation` Pydantic models; `_call_prometheus` (Prometheus seconds
  float, not nanoseconds); `_summarize` collapses each series to min/max/avg/last/p95
  (NaN-filtered, even p95 on sorted values); `slo_violations` checks latency p95 (500ms),
  5xx error rate (5%), and heap usage (300MB) against Day-12 failure-mode thresholds;
  `_FIXTURES` keyed by PromQL substring mirror the three failure-mode symptom shapes.
  `Settings` extended with `prometheus_url` and `metric_query_timeout_s` (reuses
  `tool_mode` from Day 14 — one env var governs both tools).
  Tests TC-2.6.1–TC-2.6.6: 5 pass, TC-2.6.5 skips when Prometheus absent. `ruff` +
  `mypy --strict` clean on 23 source files.
- Decisions: summary stats over raw points caps LLM token spend; naive per-series p95 in
  summarizer vs `histogram_quantile` in PromQL (two different things); `last` is last
  non-NaN value (not `values[-1]`); SLO thresholds are dev numbers calibrated to Day-12
  failure modes — Sprint 5 introduces real SLO config.
- Pattern locked in: every external-service integration test gets a skip-guard.
  TC-2.6.5 follows Day-8 / Day-14 shape.
- Next: Day 16 — Log Analyzer agent. Uses `query_logs`, calls the LLM, returns a
  structured finding.

### Day 14 (S2-D4) — LogQL query tool
- Done: `agents/app/tools/logs.py` — `query_logs` async entry point; `LogQueryResult`
  Pydantic model (query, line_count, sampled_lines, top_levels, top_messages, time_range,
  truncated, status, error); `_call_loki` hits Loki `/loki/api/v1/query_range` with
  nanosecond timestamps; `_summarize` applies even-spaced sampling (`log_sample_limit`),
  level counting, and 80-char message fingerprinting; `_FIXTURES` / `_fixture` for offline
  dev (`tool_mode="fixture"`); hard 5s timeout via `settings.log_query_timeout_s`;
  never raises (TimeoutException → `status="timeout"`, bare Exception → `status="error"`).
  Settings extended with `loki_url`, `tool_mode`, `log_sample_limit`, `log_query_timeout_s`.
  Tests TC-2.5.1–TC-2.5.5: 4 pass, TC-2.5.4 skips when Loki absent. `ruff` + `mypy
  --strict` clean on 21 source files.
- Design: fixture mode mirrors `LLM_BACKEND` pattern from Day 8 — all offline tests use it,
  no mocking required. `_summarize` is a pure function so TC-2.5.3 tests it directly without
  settings or network.
- Next: Day 15 — PromQL metrics tool (`query_metrics`) for the Metrics agent.

### Day 13 (S2-D3) — Observability stack
- Done: Prometheus, Loki, Grafana, Promtail added to `docker-compose.yml`; demo-app
  containerized (new `Dockerfile`) and joined the Compose network so Prometheus scrapes
  it by service DNS (`demo-app:8090`). Config files under `infra/`: Prometheus scrape
  config (5s interval, histogram percentiles), Loki single-node filesystem store
  (schema v13, 7-day retention), Promtail Docker SD with JSON pipeline stage promoting
  `level` + `service` to indexed Loki labels, Grafana datasources provisioned from
  file (no click-ops). `observability_smoke.sh` passes (TC-2.3.1). Loki config
  fixed: `ring.kvstore.store: inmemory` (Loki 3.2.0 changed the field path from
  `ring.kind`). Manual Grafana verification: PromQL `up{job="demo-app"}` == 1,
  LogQL `{service="demo-app"}` returns structured log lines.
- Decisions: Promtail over the Loki Docker driver plugin (no install, all in-repo).
  Demo-app containerized; orchestrator + agents stay on the host through Sprint 5.
  NOT added to CI — Compose stack excluded until Sprint 5 eval harness.
- Next: Day 14 — LogQL query tool for the Log Analyzer agent so it can read Loki.

### Day 12 (S2-D2) — Failure modes
- Done: `FailureMode` enum + admin endpoints (`GET/POST /admin/failure-mode`);
  `MemoryLeak`, `DownstreamSimulator`, `SlowQuery` components wired into
  `OrderController.create` (leak per request → slow-query stock read → downstream
  call → decrement) and `InventoryController.snapshot` (via `SlowQuery`). New
  metrics: `demo_failure_mode` gauge (ordinal), `demo_leak_objects` gauge,
  `downstream_call_latency` timer, `downstream_timeouts_total` counter,
  `db_query_latency` timer. `NONE` reset clears leaked buffers. Tests TC-2.2.1–2.2.5
  pass; CI still three parallel jobs, 8 demo-app tests green.
- Design: each mode has a distinct symptom shape — `memory_leak` moves heap only;
  `downstream_timeout` spikes latency AND emits ERROR logs with
  `dependency=payment-gateway`; `slow_query` is the silent failure (metrics only,
  no distinctive logs). Pedagogically distinct for agents.
- Next: Day 13 — wire Prometheus/Loki/Grafana into Docker Compose so the demo
  app's telemetry is stored and queryable by agents.

### Day 11 (S2-D1) — Demo app skeleton
- Done: `demo-app/` Spring Boot service at :8090; `OrderStore` + `Inventory` in memory;
  `OrderController` (`GET/POST /orders`) and `InventoryController` (`GET /inventory`) with
  Micrometer counters (`orders_created_total`, `orders_rejected_total`) and timer
  (`orders_create_latency`); structured JSON logs via `logstash-logback-encoder` — every
  log line is a real JSON object with `service`, `level`, `msg`, and `kv()` fields as
  top-level attributes; `/actuator/prometheus` exposes `orders_created_total`,
  `orders_rejected_total`, and `http.server.requests` histograms; tests TC-2.1.1–2.1.3
  pass; CI now runs three parallel jobs (orchestrator, agents, demo-app).
- Decision: demo-app holds state in memory (no DB) — keeps Day-12's failure modes clean
  and surface area small.
- Decision: metric names follow `<entity>_<action>_<unit>` convention so Day-14's Metrics
  agent PromQL queries are clean.
- Fixed spec bug: `Inventory.decrement()` in the spec referenced a lambda variable `curr`
  outside its scope; replaced with a correct lock-free compare-and-set loop.
- Next: Day 12 — toggleable failure modes (memory leak, downstream timeout, slow query)
  so agents on Day 13–14 have something to find.

## Sprint 1 — Foundation

### Day 10 — Sprint 1 close
- Done: `SprintOneE2ETest` (TC-1.10.1) — full pipeline automated in one test: POST alert →
  await DISPATCHED → publish canned AgentResult → await RESOLVED → assert trace + report
  exist. 31 Java tests green. Real CI workflow replacing the Day-1 placeholder: two parallel
  jobs (`orchestrator` with `mvn verify`, `agents` with ruff/mypy/pytest). `sample-alert.json`
  added at repo root. README updated to reflect Sprint 1 complete state with accurate
  getting-started commands. `docs/retrospectives/SPRINT-01.md` written.
- Sprint 1 status: **COMPLETE** — 31 Java tests, 9 Python tests, full pipeline end-to-end,
  real CI, documented retrospective.
- Next: Sprint 2 — demo app emitting realistic telemetry, Log Analyzer + Metrics agents.

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
