# Sprint 1 Retrospective — Foundation

## What was delivered

- The complete one-agent incident pipeline: alert → classify → dispatch → LLM → trace → report → resolved, end to end.
- 31 Java tests, 9 Python tests, all green; real two-language CI (Java + Python jobs in parallel on every PR).
- The hybrid architecture proven in code: Java control plane, Python agent plane, Kafka between them, Postgres as source of truth.
- Explicit incident state machine (8 states, terminal flag, full audit trail on every transition).
- LLM abstraction layer: `LLMClient` Protocol, `OllamaClient` (real), `AnthropicClient`/`GroqClient` (stubbed for Sprint 5).

## What went well

- **Day-plan discipline worked.** Every day had a verifiable end state and every day landed it. No half-finished days carried forward as silent debt.
- **Test discipline held.** Every MUST ticket has tests that would fail if the feature broke. The Testcontainers integration tests caught real bugs at merge time, not at debug time.
- **Wire-format contract paid off.** The Day-6 decision to document camelCase aliases in `contracts/agent-task-schema.md` made Days 7–9 zero-friction. No schema drift across the Java/Python boundary.
- **The abstraction seams proved their value immediately.** `LLMClient` Protocol meant swapping `mistral` → `qwen3:14b` was a one-line settings change. The `if task.agent_name == "echo"` router is already shaped for the Sprint-2 `dict` dispatch.

## What was hard

- **Kafka `seekToEnd()` is lazy.** In TC-1.6.2, the consumer positioned at the "end" but `seekToEnd()` defers the seek to poll time — meaning the immediately-following message was missed. Fix: use `endOffsets() + seek()` synchronously before the action under test. A subtle Kafka gotcha worth remembering.
- **Day-5 entity type ripple.** Changing `Incident.state` from `String` to `IncidentState` enum cascaded into Day-4's `IngestService` and test fixtures. `mvn verify` caught it, but small type changes ripple farther than expected.
- **Race condition in Day-6 tests.** `ClassifierListener`'s state machine called `em.merge()`, which re-inserted an incident between `@BeforeEach` DELETE and the test body. Fix required two changes together: `@Transactional` on the listener method (atomic commit) and `TRUNCATE incidents CASCADE` in `@BeforeEach` (acquires ACCESS EXCLUSIVE lock, waits for in-flight transactions). Neither alone was sufficient.
- **Kafka image mismatch.** Java testcontainers uses `apache/kafka:3.7.0` (KRaft); Python testcontainers' `KafkaContainer` was built for `confluentinc/cp-kafka` and waits for a Confluent-specific startup log line. Spent debugging time before finding the fix: `KafkaContainer()` with the default Confluent image in Python.

## Decisions made

- **Hybrid wire format:** camelCase JSON on the bus, snake_case in Python via Pydantic `Field(alias=...)` + `populate_by_name=True`. Recorded in `contracts/agent-task-schema.md`.
- **LLM model:** `qwen3:14b` in dev (mistral was not installed; the abstraction made the swap trivial — exactly its purpose).
- **Kafka integration tests use `llm_backend="anthropic"`.** The stub raises `NotImplementedError` immediately; `echo_agent` catches it and returns `status="error"` — fast, hermetic, no network call. The LLM failure path doubling as a test fixture.
- **Aggregator entities live in `com.sentinel.incident`.** `AgentTrace` and `IncidentReport` were first defined there on Day 2. Repositories live in `aggregate` and import across the package boundary. Avoids a rename migration or duplicate entity conflict.

## Known simplifications (deliberate, addressed in later sprints)

- **One-agent flow:** Day-9's `state == DISPATCHED` guard becomes multi-agent fan-in + deadline + `PARTIAL` handling in Sprint 3.
- **DLQ coverage gap:** `agent.tasks` has a DLQ (Day 7); `agent.results` does not. Sprint 5.
- **Dual-write gap on ingestion:** DB commit then Kafka send — known, deferred to Sprint 5 reliability hardening.
- **No retries/circuit breakers on LLM calls.** Sprint 5.
- **No cost accounting** (`cost_usd` defaults to 0). Sprint 5.
- **`parseOrSkip` in AggregatorListener** logs and drops unparseable `agent.results` messages. Proper DLQ is Sprint 5.

## The single riskiest unknown going into Sprint 2

The demo app must produce telemetry realistic enough that the Log Analyzer and Metrics agents have something genuine to find. If the failure modes are too easy, the agents look smarter than they are; too hard, and they fail. Calibrating the signal-to-noise ratio of synthetic incidents is the unknown.

## What I would do differently

- **Set `BigDecimal.ZERO` defaults on all NOT NULL numeric entity fields on Day 2.** Would have prevented the Day-9 `cost_usd NOT NULL` constraint violation that failed the first aggregator test run.
- **Document the Kafka image divergence on the day it happened** (Day 7), not at sprint close. A one-line comment in the test file would have saved the Day-9 debugging.
- **Resolve the JSON column mapping pattern on Day 2.** The existing `AgentTrace` entity used `String` for `input`/`output` instead of `Map<String, Object>`. Discovering this mismatch on Day 9 required a pivot; a Day-2 decision would have been clean.

## Sprint 2 prep — one decision to make before starting

What is the demo app? A Spring Boot order/inventory service with three failure modes (memory leak, downstream timeout, slow query) is the plan in `04-SPRINT-2-DEMO-APP-AND-AGENTS.md`. Confirm or amend before Day 1.
