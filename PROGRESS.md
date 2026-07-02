# Sentinel — Progress Log

## Sprint 5 — Reliability + Eval Harness

### Day 32 (S5-D1) — Rehydration + reliability floor
- Done: cleared stale Sprint 4 state (Level 1 cleanup — 13 incidents force-transitioned to
  PARTIAL). Smoke test fired (fingerprint `d32-smoke-1`, `slow_query` mode, `orders-svc`).
  **Outcome C** — incident never reached terminal state within 28 min (deadline 600s). Sweeper
  fired correctly at t≈598s → AGGREGATING_PARTIAL → Synthesizer dispatched, but Synthesizer
  LLM call never completed. Root cause: qwen3:14b too slow on CPU-only hardware; all 7 LLM
  calls (6 specialists + Synthesizer) serialized through one Ollama process.
- Instrumentation added: `agents/app/metrics.py` — `sentinel_agent_timeouts_total` Counter +
  `sentinel_agent_llm_latency_seconds` Histogram wired into `_parse.py` except blocks. FastAPI
  `/metrics` endpoint exposed via `prometheus_client`. Orchestrator: `micrometer-registry-
  prometheus` added to `pom.xml`, `/actuator/prometheus` exposed in `application.yml`,
  `DeadlineSweeper` now increments `sentinel_deadline_breaches_total` on every sweep transition.
- Baseline highlights: 0 of 6 specialists completed before deadline; `latency_ms=0` in
  agent_traces for all agents (LLM timeouts); 1 deadline breach; DLQ empty.
  See `docs/RELIABILITY-BASELINE.md` for full numbers.
- Tests: TC-5.1.1 (deadline breach counter) added to `DeadlineSweeperTest.java`;
  TC-5.1.2 (agent timeout counter) added as `test_metrics.py` — both pass.
- Next: Day 33 — model ladder. `qwen2.5:3b` for 6 specialists, `qwen3:14b` for Synthesizer
  only. Per-agent HTTP timeouts. Target: p95 incident wall-clock under 300s.

## Sprint 4 — Knowledge Base, History, Topology, Runbook

### Day 31 (S4-D5) — Sprint 4 close
- Done: `SprintFourE2ETest` TC-4.5.1 — 6-agent flow (echo + log_analyzer + metrics + history +
  topology + runbook) → Synthesizer → RESOLVED + KB write-back delta assertion (exactly 1 new
  `past_incident` row). TC-4.5.2 — knowledge feedback loop (resolve A → embed → resolve B →
  History finds A); skip-guarded with `Assumptions.assumeTrue("fixture".equals(TOOL_MODE))` for
  determinism. `docs/demos/SPRINT-4-KNOWLEDGE-LOOP.md` captured as permanent demo artifact.
  README refreshed (6-agent count, Runbook ✅, test counts, roadmap Sprint 4 checked).
  `docs/01-OVERVIEW-AND-ARCHITECTURE.md`, `docs/00-PROJECT-SETUP-REPORT.md`, and
  `docs/06-SPRINT-4-REMAINING-AGENTS.md` updated. Sprint-4 retrospective written —
  CI-red incident (PR #28 wrong base branch, corrected via PR #29) included as named lesson.
  Post-merge `main` CI verified green via GitHub Actions UI. `sprint-4` git tag pushed.
- Sprint 4 final: 6 specialist agents + Synthesizer, pgvector knowledge base with 3 tables,
  self-learning feedback loop (resolved incidents → `past_incident` rows → vector search picks
  them up within 30s). ~85 Java tests, ~60 Python tests, 8 demo-app tests. Architecture: 4
  planes, 9+ Compose services.
- Process improvement: post-merge `main` CI verification is now part of every sprint close.
- Sprint 5 starts: the eval harness. Plus per-severity deadlines, `alert_name` backfill, and
  DLQ hardening from the Sprint 2-3-4 collected simplifications.

### Day 30 (S4-D4) — Runbook agent
- Done: `runbook` agent queries `/kb/runbooks?q=<query>&limit=5` (PostgreSQL full-text search
  via `to_tsvector`/`plainto_tsquery`), asks the LLM whether any returned runbook genuinely
  applies, and extracts the most relevant steps. Registered via Day-19 contract (exactly 2 edits:
  `AGENTS` dict in `_registry.py` + `ClassifierListener` dispatcher list now
  `List.of("echo","log_analyzer","metrics","history","topology","runbook")`). `_build_query`
  joins service + alert_name + symptoms_summary for FTS. Post-guard fills `matched_runbooks`
  from raw candidates when LLM returns an empty list. No embedder needed (FTS, not vector
  search). `RunbookMatch` + `RunbookFinding` types added to `agents/types.py`. Prompt template
  `runbook.txt` instructs calibrated confidence: 0.0 = no match, 0.5 = partial, 0.8+ = strong.
  All 6 full-pipeline Java tests updated to dispatch and receive 6 specialists. Day-19 contract
  verified: `expected_agents` count ticks from 5 to 6 automatically via `getExpectedAgents().size()`.
- Tests: 5 new Python (TC-4.4.1–4.4.5 all pass — happy path, no candidates, unparseable LLM,
  timeout, agent_name). Prompt registry test updated for 6th prompt. `ruff` and `mypy` both clean.
- Spec: `docs/Day30/DAY-30-RUNBOOK-AGENT.md`

### Day 29 (S4-D3) — Topology agent
- Done: `topology` agent fetches service-graph neighbors from `/kb/topology/{service}`,
  flattens `{outgoing: [...], incoming: [...]}` into a typed `list[TopologyNeighbor]`,
  then asks the LLM to reason about failure propagation (outgoing = potential causes,
  incoming = downstream victims). Empty topology (no neighbors) skips the LLM entirely
  and returns a graceful fallback. Registered via Day-19 contract (exactly 2 edits:
  `AGENTS` dict in `_registry.py` + `ClassifierListener` dispatcher list now
  `List.of("echo","log_analyzer","metrics","history","topology")`). Prompt uses `.replace()`
  (not `.format()`) due to JSON schema literal braces. Post-guard fills `neighbors` from
  the _fetch_topology result when LLM returns an empty list. `TopologyNeighbor` +
  `TopologyFinding` types added to `agents/types.py`. No KB write-back (topology is
  reference data; `kb_links` is not updated by agent activity). All 6 full-pipeline Java
  tests updated to dispatch and receive 5 specialists (echo, log_analyzer, metrics,
  history, topology).
- Tests: 5 new Python (TC-4.3.1–4.3.5 all pass — happy path, empty topology, timeout,
  post-guard, direction test). Prompt registry test updated for 5th prompt. `ruff` and
  `mypy` both clean. Java compiles clean (`mvn compile test-compile`).

### Day 28 (S4-D2) — History agent + KB write-back
- Done: `history` agent vector-similarity search against past incidents. Registered with
  Day-19 contract (exactly 2 edits: `AGENTS` dict in `_registry.py` + `ClassifierListener`
  dispatcher list now `List.of("echo","log_analyzer","metrics","history")`). Agent embeds
  the current incident's query text via `ctx.embedder`, POSTs to `/kb/search` (5 s timeout),
  renders `history.txt` prompt with `.replace()` (not `.format()` — JSON schema has literal
  braces), calls LLM, and post-guards empty `matched_incidents` from the LLM by repopulating
  from KB candidates. `HistoryFinding` + `MatchedIncident` types added to `agents/types.py`.
  `EmbeddingBackfillTask` asyncio periodic task (30 s interval) polls `kb_documents` for NULL
  embeddings and fills them in via asyncpg; wired into FastAPI lifespan. `KbWriter` (Java)
  inserts resolved incidents as `past_incident` rows in `kb_documents` — wrapped in try/catch
  (KB write failure is non-fatal); called ONLY in `AGGREGATING → RESOLVED` branch, NOT in
  the `AGGREGATING_PARTIAL → PARTIAL` branch. `AggregatorListener` constructor-injected with
  `KbWriter`. All 6 full-pipeline Java tests updated to dispatch and receive 4 specialists
  (echo, log_analyzer, metrics, history). `orchestrator_url` added to `agents/settings.py`.
- Tests: 4 new Java (TC-4.2.6: KB write on RESOLVED; TC-4.2.7: no KB write on PARTIAL;
  all via Testcontainers). 5 new Python (TC-4.2.1–4.2.4 pass; TC-4.2.5 skip-guarded —
  requires DATABASE_URL). Prompt registry test updated for 4th prompt. All green.

### Day 27 (S4-D1) — Knowledge-base infrastructure
- Done: Shared data layer for the three new Sprint-4 agents (History, Topology, Runbook).
  V5 Flyway migration: `CREATE EXTENSION IF NOT EXISTS vector`, `knowledge_base` schema,
  `kb_documents` (vector(384) + IVFFlat cosine index), `kb_links` (topology adjacency,
  UNIQUE tuple constraint), `kb_runbooks` (GIN FTS + tags GIN). Seed SQL: 5 past incidents,
  5 topology links, 4 runbooks — all idempotent (`ON CONFLICT DO NOTHING`). JPA entities
  `KbDocument`, `KbLink`, `KbRunbook` (`@JdbcTypeCode(SqlTypes.ARRAY)` for tags).
  Repositories: `KbDocumentRepository` (native pgvector `<=>` query), `KbLinkRepository`
  (Spring Data derived), `KbRunbookRepository` (native GIN FTS). `KnowledgeBaseSeeder`
  (`@PostConstruct`, disabled via `sentinel.kb.seed-on-startup=false`). `KbController`:
  `GET/POST /kb/search`, `GET /kb/topology/{service}`, `GET /kb/runbooks?q=`. Python
  embedding abstraction: `EmbeddingClient` ABC + `FixtureEmbedding` (SHA-256 deterministic,
  384-dim) + `SentenceTransformerEmbedding` (lazy all-MiniLM-L6-v2) + factory (default
  `embedding_backend=fixture`). `AgentContext` extended with `embedder` field. Worker and
  main wired. `backfill_embeddings.py` script. Postgres image upgraded to
  `pgvector/pgvector:pg16` everywhere (docker-compose + all 13 Testcontainers files).
- Tests: 78 Java (73 existing + 5 new TC-4.1.1–4.1.5; all green), 2 Python (TC-4.1.6
  fixture determinism passes; TC-4.1.7 sentence_transformer skipped — not installed in CI).

## Sprint 3 — Synthesis, Deadlines, UI

### Day 26 (S3-D6) — Sprint 3 close
- Done: `SprintThreeE2ETest` extended with TC-3.6.1 (full pipeline: alert → three
  specialists → Synthesizer → RESOLVED → human accepts; asserts 204 NO_CONTENT, ACCEPTED
  stored uppercase, HUMAN_ACCEPTED in audit log) and TC-3.6.2 (deadline path: incident
  written directly in DISPATCHED with past deadline → one specialist reports → sweeper
  fires → AGGREGATING_PARTIAL → Synthesizer → PARTIAL → human rejects; asserts REJECTED
  + reason persisted). `AuditLogRepository.findByIncidentId(UUID)` added.
  Demo artifact: `docs/demos/SPRINT-3-HUMAN-IN-LOOP.md` — 8-beat demo script with
  commands for triggering real incident, watching live SSE, expanding incident detail,
  editing recommended action, showing labeled data, demonstrating filter bookmarkability.
  README refreshed: Sprint-3 status, Synthesizer ✅, 4-plane architecture description,
  getting-started includes UI step (Node 18+), design principles updated (graceful
  degradation ✅ Sprint 3), What's built table updated with all Sprint-3 components,
  test counts ~73+42+8. Sprint-3 retrospective: `docs/retrospectives/SPRINT-03.md`.
- Tests: 73 Java (all non-StateMachineUnitTest pass; StateMachineUnitTest is pre-existing
  Java-26/Mockito gap — passes on CI with Java 21), 42 Python, 8 demo-app.
- Architecture notes:
  - TC-3.6.2 builds Incident directly in DISPATCHED with past deadline, bypassing
    natural dispatch. Sweeper picks it up within 5s. Sprint 4 should use a Spring
    test profile with short deadline instead.
  - `humanDecision` values stored uppercase (ACCEPTED/REJECTED/EDITED) in DB; tests
    assert uppercase.
  - `HumanDecisionController.accept()` returns 204 NO_CONTENT (spec said 200 — corrected).
  - `Map.of()` rejects null; TC-3.6.2 uses `""` for empty `root_cause` in synthesizer result.
- Sprint 3 final score: Synthesizer + deadlines + PARTIAL + live UI + human-in-the-loop
  + filters. Architecture: 4 planes (orchestrator, agents, dashboard, demo-app),
  8 Compose services, ~123 tests across two languages + UI build gate.
- Next: Sprint 4 — Topology + History (pgvector) + Runbook agents. Each a two-edit
  addition per the Day-19 contract. Open decision: shared knowledge-base infrastructure
  day first, or build incrementally.

### Day 25 (S3-D5) — Filters & search
- Done: `GET /incidents` now accepts `state` (comma list), `service`, `decision`
  (undecided/accepted/rejected/edited), `q` (case-insensitive free-text across
  summary/root_cause/recommended_action), `limit` (1-100), and `before` (cursor).
  Response shape changed from `List<Map>` to `ListResponse{items, nextBefore}`.
  `IncidentSpecifications` builds the dynamic JPA Criteria predicate with AND semantics;
  LEFT JOIN to `incident_reports` for decision/q — active incidents (no report yet) still
  appear in state filters. `@OneToOne(mappedBy="incident")` added to Incident; matching
  read-only `@OneToOne @JoinColumn(insertable=false, updatable=false)` on IncidentReport —
  existing UUID field (`incidentId`) untouched. `IncidentRepository` extends
  `JpaSpecificationExecutor<Incident>`. `IncidentListController` uses `PageRequest + findAll`
  for cursor-based pagination; `IllegalArgumentException` → 400 via `@ExceptionHandler`.
  `decision=undecided` uses `isNull(humanDecision)` — covers both no-report (LEFT JOIN null)
  and report-without-decision. `decision` filter values uppercased before DB comparison
  (DB stores ACCEPTED/REJECTED/EDITED). Invalid `state` name → 400 via `parseStateOrThrow`.
  UI: `FilterBar` component (state/service/decision selects + search input + Clear button);
  `services` derived via `useMemo` from loaded incidents. URL sync via `replaceState` on
  filter change; hydrated from `window.location.search` on mount. SSE merge stays permissive
  — state/service checked at event level, decision/q deferred to REST refresh. `useEffect`
  re-subscribes SSE on filter change so `matchesFilter` sees current filter. "Load older"
  button appends next page using `nextBefore` cursor; disappears when `nextBefore` is null.
  `contracts/incident-list-api.md` documents wire format. TC-3.3.4 updated to new response
  shape (`{items, nextBefore}`).
- Tests: TC-3.5.1-3.5.8 (8 new Java integration tests); TC-3.3.4 updated. All 71 tests
  pass on CI (Java 21).
- Architecture notes:
  - Incident entity uses `source` not `service`; Specifications uses `root.get("source")`.
  - LEFT JOIN mandatory — `JoinType.INNER` silently drops active incidents.
  - Cursor pagination one-way (newest→older); Sprint 6 may add two-way if needed.
  - Permissive SSE merge — accepts events that might match; next REST refresh corrects.
  - No router library; URL synced manually via `replaceState`.
- Next: Day 26 — Sprint 3 close.

### Day 24 (S3-D4) — Human-in-the-loop accept / reject / edit
- Done: `V4__human_decision_columns.sql` — adds `human_decision_reason`, `human_decided_at`,
  `edited_summary`, `edited_root_cause`, `edited_recommended_action` to `incident_reports`;
  partial index on `human_decision` for non-null rows.
  `IncidentReport` entity — 5 new fields + getters/setters; AI originals (`summary`,
  `root_cause`, `recommended_action`) never overwritten on edit, preserving labeled training
  data for Sprint 5 eval harness.
  `AuditWriter.record(UUID, String, String, String detail)` — new overload with JSONB detail;
  existing `write()` delegates to it with null detail (no callers changed).
  `com.sentinel.humanloop` package:
  - `HumanDecisionRequests` — `AcceptRequest`, `RejectRequest(reason)`, `EditRequest(summary,
    rootCause, recommendedAction)` inner records.
  - `ReportNotFoundException` — `@ResponseStatus(404)`.
  - `AlreadyDecidedException` — `@ResponseStatus(409)`.
  - `HumanDecisionService` — `@Transactional` `accept()`, `reject()`, `edit()` methods;
    `findAndGuard()` raises 404 if no report, 409 if already decided;
    publishes `incident.human_decision` SSE event on every decision;
    audit-logs `HUMAN_ACCEPTED`, `HUMAN_REJECTED`, `HUMAN_EDITED` with JSONB detail.
  - `HumanDecisionController` — `POST /incidents/{id}/accept` (204),
    `POST /incidents/{id}/reject` (204), `PATCH /incidents/{id}/report` (204); all CORS-gated.
  `IncidentListController.toListItem` — adds `human_decision` and `human_decision_reason`
  from report lookup (null-safe).
  `pom.xml` — `httpclient5` test dependency so `TestRestTemplate` supports PATCH.
  UI: `types.ts` adds `HumanDecisionEvent` + `SentinelEvent` union; `IncidentListItem` gains
  `human_decision`/`human_decision_reason`. `useIncidentStream` exports `IncidentWithReport`,
  handles `incident.human_decision` SSE event via `mergeDecisionEvent`. `App.tsx` —
  `IncidentDetail` shows decision banner (✓/✗/✎) when decided; shows Accept/Reject/Edit
  action buttons when terminal + undecided; reject uses `window.prompt`; edit shows inline
  form writing to `edited_*` columns; error display on non-OK response. `App.css` — new
  `.actions`, `.edit-form`, `.decision-banner`, `.error`, `.decision-chip` rules.
  Tests: TC-3.4.1 (accept 204 + fields set), TC-3.4.2 (reject 204 + reason stored),
  TC-3.4.3 (edit 204 + edited_* columns set), TC-3.4.4 (double-accept → 409),
  TC-3.4.5 (accept then reject → 409), TC-3.4.6 (accept no-report → 404),
  TC-3.4.7 (edit verifies AI originals preserved + edited_* columns set — labeled data guard).
  Final: 63 Java tests (56 + 7 new), 42 Python tests (unchanged), all green on CI (Java 21).
  `StateMachineUnitTest` fails locally on Java 26 (pre-existing Mockito JVM restriction;
  passes on CI Java 21).
- Next: Day 25 — Sprint 3 close + topology/history/runbook agents (Sprint 4).

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
- Next: Day 24 — human-in-the-loop.

### Day 23 (S3-D3) — SSE & UI shell
- Done: `IncidentEventPublisher` with `CopyOnWriteArrayList<SseEmitter>` (lock-free
  broadcast, per-subscriber lifecycle hooks on completion/timeout/error);
  `IncidentStreamController` at `GET /incidents/stream` with `@CrossOrigin` per-endpoint
  to `localhost:5173`; `GET /incidents` (list, newest-first, configurable limit) and
  `GET /incidents/{id}` (detail with report + traces) in `IncidentListController`;
  `IncidentRepository.findRecent` JPQL query; `IncidentReportRepository.findByIncidentId`.
  `IncidentStateMachine.transition` calls `events.publish(stateChangedEvent)` after every
  state change — `incident.state_changed` for non-terminal, `incident.completed` (report:
  null) for terminal. `AggregatorListener.handleSynthesizerResult` publishes a second
  `incident.completed` with populated report (summary, root_cause, recommended_action,
  confidence, dissenting_notes, contributing_agents from synthesizer trace output).
  `IngestService.handle` publishes `incident.state_changed` for the initial RECEIVED state
  so the UI shows the incident immediately on POST.
  React app at `ui/` (Vite 7 + TS, zero extra dependencies): `types.ts`, `App.tsx`,
  `useIncidentStream.ts`, `App.css`. Initial REST hydration + SSE merge preserves non-null
  report when state_changed re-fires after completed. SSE merge deduplicates by
  `incident_id`. Monospace dark theme. CI grows to 4 parallel jobs.
  `contracts/sse-events-schema.md` documents wire format.
  Tests: TC-3.3.1-3.3.6 (Java integration); UI tests deferred to Sprint 6.
  Final: 56 Java tests (50 + 6 new), 42 Python tests, all green.
- Architecture notes:
  - Two events fire on terminal transitions (state machine's null-report, then aggregator's
    populated-report). React merge keeps non-null. Sprint 5 may refactor.
  - `alert_name` is null in events (Sprint 3 placeholder; Sprint 4 adds the field).
  - No auth on endpoints. Sprint 6.
  - UI testing deferred (needs stub server or recording infra).

### Day 22 (S3-D2) — Deadline sweeper + PARTIAL terminal state
- Done: `V3__deadline_index.sql` — partial index on `deadline_at` for sweeper query
  (column already existed in V1; V3 is index-only).
  `IncidentState` — added `AGGREGATING_PARTIAL` and `SYNTHESIZED_PARTIAL`; `PARTIAL` was
  already in enum but with wrong transitions; `isTerminal()` now covers RESOLVED, PARTIAL,
  and FAILED. Full ALLOWED map in `IncidentStateMachine` replaced (old had `PARTIAL →
  SYNTHESIZED` and `AGGREGATING → PARTIAL` which are semantically wrong).
  `IncidentDeadlineConfig` — `@ConfigurationProperties(prefix="sentinel.incident")` for
  `default-deadline-seconds` (default 60); `application.yml` adds `sweeper-interval-ms`.
  `Dispatcher.dispatch` — injects `IncidentDeadlineConfig`, sets `deadline_at` at dispatch
  time (before saving incident).
  `IncidentRepository.findOverdueActive` — JPQL query with `@Query` + `@Param` on state IN
  (DISPATCHED, AGGREGATING) and `deadline_at <= :now`.
  `DeadlineSweeper` — `@Scheduled(fixedDelayString)`, `@Transactional sweep()`;
  `progressOverdue` acts only on DISPATCHED: transitions to AGGREGATING_PARTIAL, builds
  payload via `SynthesizerTaskBuilder.buildPayload`, computes `missing_specialists` =
  expectedAgents minus reported traces, adds `partial: true`, calls `dispatchSynthesizer`.
  `AggregatorListener.handleSynthesizerResult` — branches on AGGREGATING (→ SYNTHESIZED →
  RESOLVED) vs AGGREGATING_PARTIAL (→ SYNTHESIZED_PARTIAL → PARTIAL); both publish to
  `incidents.synthesized`; unexpected state logs warn and returns.
  `synthesizer.txt` — partial-input guidance section appended (missing agents → lower
  confidence, name them in dissenting_notes, don't invent findings).
  `synthesizer.py` — single fallback refactored into `_build_fallback(payload, agent_names)`
  that distinguishes zero-findings (no-data summary, confidence 0.0, empty contributing)
  from parse-failure (partial contributing agents preserved).
  Tests: TC-3.2.1-3.2.4, 3.2.9 (Java — DeadlineSweeperTest + AggregatorIntegrationTest
  TC-3.2.4 and TC-3.2.5); TC-3.2.6, TC-3.2.7 (Python — test_synthesizer_partial.py);
  StateMachineUnitTest updated to cover AGGREGATING_PARTIAL, SYNTHESIZED_PARTIAL in
  TC-1.5.3 and add PARTIAL to TC-1.5.4 terminal list.
  Final: 50 Java tests + 42 Python tests (non-infra), all green. ruff + mypy strict clean.

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
