# Sprint 2 Retrospective — Demo App & First Agents

## What was delivered

- A breakable demo Spring Boot service (3 endpoints, 4 failure modes: slow_query,
  memory_leak, downstream_timeout, high_error_rate — full Prometheus + structured-JSON
  instrumentation).
- The full observability stack: Prometheus, Loki, Promtail, Grafana —
  containerized, datasource-provisioned, queryable.
- The tool layer: query_logs (Loki/LogQL) and query_metrics + slo_violations
  (Prometheus/PromQL) — async, bounded, fixture-mode, skip-guarded.
- The prompt registry: versioned, immutable, Postgres-synced.
- Two real specialist agents: Log Analyzer (TC-2.8.x), Metrics agent (TC-2.9.x).
- The dispatch refactor: dict dispatch in Python, expected_agents per
  incident in Java. Adding a new agent is now 2 edits.
- The architectural thesis test: TC-2.11.2 codifies the slow_query
  asymmetry — Metrics agent 0.88 confidence vs Log Analyzer 0.15.
- ~40 Java tests, 37 Python tests, 8 demo-app tests. Ruff + mypy --strict
  clean throughout.

## What went well

- The rule-of-three discipline held. Two branches → no refactor. Three
  branches → still no refactor (deepened duplication deliberately).
  Day 19's dict-dispatch landed cleanly because the duplication was visible
  and the refactor was earned, not anticipatory.
- The "tool before agent" sequencing paid off. Days 14–15's tools are
  used unmodified by Days 17–18's agents — no rework.
- The fixture/live/skip-guard pattern from Day 8 stayed consistent
  through Days 14, 15, 17, 18. CI doesn't need Ollama, Loki, or
  Prometheus; local dev gets real signal from fixture mode.
- The AgentContext frozen dataclass pattern keeps non-serializable
  dependencies (LLM client, prompt registry) out of agent signatures
  without resorting to global state or dependency injection frameworks.
- Testcontainers made Java integration tests reliable. Real Postgres and
  real Kafka in every test — no mocks that diverge from production.

## What was hard

- **str.format() vs .replace() on prompt templates.** JSON examples in
  prompt bodies contain literal `{...}`. Using `.format()` on a prompt
  string that includes a JSON snippet causes a `KeyError`. Found during
  Day 17 (log_analyzer prompt), fixed immediately, applied as a project
  rule from Day 18 forward: always use `.replace()` for prompt variable
  substitution.
- **Kafka image divergence.** The Java and Python test containers used
  different Kafka images at various points. Standardized on `apache/kafka:3.7.0`
  across all tests. Worth documenting once rather than rediscovering each time.
- **Loki 3.2.0 schema change.** `ring.kind` was renamed to
  `ring.kvstore.store`. The error message was opaque. Documented in the
  Compose config comment.
- **Grafana health-check whitespace.** The `wget` health-check command
  required precise whitespace handling. Documented.
- **Coupling between dispatcher list and aggregator threshold (Days 17–18).**
  Hardcoding `traceCount >= 3` in the aggregator while hardcoding three agents
  in the dispatcher was a deliberate Sprint-2 simplification. Acceptable when
  the same commit changes both. Resolved in Day 19 with `expected_agents` per
  incident.
- **AggregatorIntegrationTest missing expected_agents.** After Day 19's
  dynamic expected-agents refactor, the test helper `buildDispatchedIncident()`
  created incidents with empty `expected_agents`, which the safety check
  `if (expectedCount > 0)` treated as "never resolve". The 4 affected tests
  failed silently at timeout. Fixed on Day 20 by setting the standard three
  agents in the helper and explicitly clearing them in TC-2.10.3.

## Decisions made

- Demo app is in-memory (no DB) to keep failure modes clean and deterministic.
  A real DB introduces variability that complicates the failure mode signals.
- Metric naming convention: `<entity>_<action>_<unit>` (e.g., `demo_order_create_duration_seconds`).
  Day 15's metrics tool and Day 18's metrics agent consume it without modification.
- Promtail over the Loki Docker driver: no plugin install, all config in-repo,
  works in CI without elevated Docker permissions.
- Demo app containerized; orchestrator and agents stay on the host through
  Sprint 5 for IDE iteration speed (hot reload, debugger attach).
- One `TOOL_MODE` env var controls both tools (Loki + Prometheus). Single flip
  to switch the entire tool layer between fixture and live modes.
- `AgentContext` as a frozen dataclass (not Pydantic). Holds non-serializable
  objects that Pydantic can't validate. The frozen constraint prevents accidental
  mutation during agent execution.
- Compose stack not added to CI (Day 13): observability_smoke.sh is the pre-merge
  manual gate. Sprint 5's eval harness will run the full stack in CI.
- `expected_agents` stored on the incident row at dispatch time rather than
  computed at aggregation time. This decouples dispatcher from aggregator —
  the aggregator reads what it needs without knowing what was dispatched.

## Known simplifications (deliberate, addressed later)

- No Synthesizer yet. The system records three traces and resolves the
  incident, but doesn't *combine* the findings. Sprint 3 Day 1.
- No deadline / PARTIAL handling. If an agent never responds, the incident
  never resolves. Sprint 3 Day 2.
- No live UI. Status is in the database. Sprint 3 Day 4 onward.
- The slow_query asymmetry test (TC-2.11.2) uses calibrated fake LLM
  responses. Sprint 5's eval harness validates the same asymmetry
  against the real LLM at scale.
- Three SLO thresholds in `slo_violations` are dev numbers. Sprint 5
  introduces real per-service SLO config.
- Aggregator does not yet have a DLQ for unparseable agent.results
  messages (Day 9's known simplification, still open). Sprint 5.

## The single riskiest unknown going into Sprint 3

The Synthesizer prompt. Combining three findings (one low-confidence log
result, one high-confidence metrics result, one echo acknowledgement) into
a single calibrated diagnosis is genuinely hard prompt engineering. The
placeholder prompt exists from Day 16 — Day 21's job is to make it work.
Sprint 5's eval harness is the final validator. The quality of the Synthesizer
output is what turns "a swarm that produces three separate answers" into
"a system that produces one better answer than any single agent could."

## What I would do differently

- Document the `.format()` vs `.replace()` lesson at Day 17 the moment it
  was discovered, not at the sprint retrospective. The Day-18 agent would
  have caught it immediately.
- Write the swarm asymmetry test (TC-2.11.2) at Day 18, not Day 20. It's
  a load-bearing assertion on the architectural thesis and would have caught
  any Day-19 refactor that accidentally homogenized agent behavior.
- Set `expected_agents` on the test helper from the start. The Day-20
  AggregatorIntegrationTest fix was mechanical but time-consuming to diagnose
  (timeout failures with no clear root cause in the error message).
- Consider a single shared Python package for `AgentTask` / `AgentResult`
  contracts so the Java side imports the schema from one place. Today both
  sides maintain their own record/model. Likely Sprint 5 when the wire-format
  formalizes around the eval harness.

## Sprint 3 prep — one decision to make before starting

Where does the Synthesizer live? Same `agents/` package, registered in the
`AGENTS` dict, dispatched as a fourth agent? Or a separate consumer of
`agent.results` that builds a unified report after aggregation? The dispatch
refactor (Day 19) makes either work; Sprint 3 Day 1 picks one and commits.
