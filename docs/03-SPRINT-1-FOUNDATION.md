# 03 — Sprint 1: Foundation

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## Sprint goal

**An alert can be sent in, a dummy agent processes it, and the result is persisted — end to end.** No real intelligence yet. The point is a working skeleton: every plane connected, every pipe proven.

## Sprint acceptance criteria

The sprint is done when all of these are true:

1. `docker compose up` brings up Postgres, Redis, and Kafka cleanly.
2. The orchestrator (Spring Boot) starts, connects to Kafka and Postgres.
3. The agent service (FastAPI) starts, connects to Kafka, talks to Ollama.
4. `POST /alerts` with a sample payload creates an incident row in `RECEIVED`.
5. The orchestrator classifies it and publishes one `agent.tasks` message.
6. An "echo agent" consumes the task, calls Ollama, publishes an `agent.results` message.
7. The orchestrator consumes the result and moves the incident to `RESOLVED`.
8. The whole flow is provable by an end-to-end test and visible in the DB.
9. CI runs lint + unit + integration tests on every PR and is green.

## Sprint backlog

| ID | Ticket | Priority |
|---|---|---|
| S1-1 | Monorepo + docker-compose + CI skeleton | MUST |
| S1-2 | Postgres schema + migrations | MUST |
| S1-3 | Kafka topics provisioning | MUST |
| S1-4 | Orchestrator skeleton + health check | MUST |
| S1-5 | `POST /alerts` ingestion + idempotency | MUST |
| S1-6 | Incident state machine + persistence | MUST |
| S1-7 | Classifier + dispatcher (publish `agent.tasks`) | MUST |
| S1-8 | FastAPI agent skeleton + Kafka consumer | MUST |
| S1-9 | LLM abstraction layer + echo agent | MUST |
| S1-10 | Aggregator (consume `agent.results`) + resolve | MUST |
| S1-11 | End-to-end test + CI integration | MUST |
| S1-12 | `PROGRESS.md`, README stub, cleanup | SHOULD |

---

## Day 1 — Repository, local stack, CI skeleton

**Objective:** the monorepo exists, the local infrastructure runs, and an empty CI pipeline passes.

### Steps
1. Create the repo, add `.gitignore` (Java, Python, Node, `.env`), push the initial commit.
2. Create the directory layout from section 1.6 (empty folders with `.gitkeep`).
3. Write `docker-compose.yml` with Postgres, Redis, Kafka (KRaft mode — no Zookeeper).
4. Write `.env.example`.
5. Write `.github/workflows/ci.yml` with a no-op job that just checks out and echoes.
6. Bring the stack up, confirm all three containers are healthy.

### Code — `docker-compose.yml` (skeleton)
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: sentinel
      POSTGRES_PASSWORD: sentinel
      POSTGRES_DB: sentinel
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U sentinel"]
      interval: 5s
  redis:
    image: redis:7
    ports: ["6379:6379"]
  kafka:
    image: bitnami/kafka:3.7
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      # ... controller quorum config
    ports: ["9092:9092"]
```

### Code — `ci.yml` (skeleton)
```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: echo "pipeline placeholder — real jobs added per sprint"
```

### End-of-day goal
`docker compose up -d` shows three healthy containers; `git push` triggers a green CI run.

### Test cases
> **TC-1.1.1** — Stack starts clean
> **Given** a fresh checkout → **When** `docker compose up -d` → **Then** `docker compose ps` shows postgres, redis, kafka all healthy.
> *Type:* manual · *Automated:* no

> **TC-1.1.2** — CI passes on empty pipeline
> **Given** the repo on GitHub → **When** a commit is pushed → **Then** the CI workflow completes green.
> *Type:* manual · *Automated:* no

---

## Day 2 — Database schema and migrations

**Objective:** the five core tables exist, created by a versioned migration tool.

### Steps
1. Add Flyway to the orchestrator's `pom.xml`.
2. Write `V1__core_schema.sql` with the five tables from section 1.8.
3. Add indexes: `incidents.idempotency_key` (unique), `agent_traces.incident_id`, `audit_log.incident_id`.
4. Configure the orchestrator's `application.yml` to run Flyway on startup.
5. Start the orchestrator once; confirm tables are created.

### Code — `V1__core_schema.sql` (skeleton)
```sql
CREATE TABLE incidents (
  id UUID PRIMARY KEY,
  idempotency_key TEXT UNIQUE NOT NULL,
  source TEXT NOT NULL,
  severity TEXT,
  state TEXT NOT NULL DEFAULT 'RECEIVED',
  raw_alert JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE agent_traces ( /* columns per section 1.8 */ );
CREATE TABLE incident_reports ( /* ... */ );
CREATE TABLE audit_log ( /* ... */ );
CREATE TABLE prompt_versions ( /* ... */ );

CREATE INDEX idx_traces_incident ON agent_traces(incident_id);
CREATE INDEX idx_audit_incident  ON audit_log(incident_id);
```

### End-of-day goal
Connecting to Postgres shows all five tables with correct columns and indexes.

### Test cases
> **TC-1.2.1** — Migration applies cleanly
> **Given** an empty database → **When** the orchestrator starts → **Then** Flyway reports V1 applied and 5 tables exist.
> *Type:* integration · *Automated:* yes (Testcontainers Postgres + Flyway assertion)

> **TC-1.2.2** — Idempotency key is unique
> **Given** the `incidents` table → **When** two rows with the same `idempotency_key` are inserted → **Then** the second insert fails with a unique-constraint violation.
> *Type:* integration · *Automated:* yes

---

## Day 3 — Kafka topics and the orchestrator skeleton

**Objective:** all six Kafka topics exist; the orchestrator boots, exposes a health check, and connects to Kafka.

### Steps
1. Add a topic-provisioning step: either a `kafka-init` Compose service running `kafka-topics.sh`, or a Spring `@Bean` of `NewTopic` definitions.
2. Create topics from section 1.9, each partitioned (start with 3 partitions) and with the DLQ.
3. Scaffold the Spring Boot app: `SentinelApplication.java`, `application.yml`, Kafka config.
4. Add a `/actuator/health` endpoint (Spring Actuator) and a custom check confirming Kafka reachability.

### Code — topic definitions (skeleton)
```java
@Configuration
public class KafkaTopics {
  @Bean NewTopic incidentsRaw()       { return TopicBuilder.name("incidents.raw").partitions(3).build(); }
  @Bean NewTopic agentTasks()         { return TopicBuilder.name("agent.tasks").partitions(3).build(); }
  @Bean NewTopic agentResults()       { return TopicBuilder.name("agent.results").partitions(3).build(); }
  @Bean NewTopic incidentsSynth()     { return TopicBuilder.name("incidents.synthesized").partitions(3).build(); }
  @Bean NewTopic auditEvents()        { return TopicBuilder.name("audit.events").partitions(3).build(); }
  @Bean NewTopic agentTasksDlq()      { return TopicBuilder.name("agent.tasks.dlq").partitions(3).build(); }
}
```

### End-of-day goal
`GET /actuator/health` returns `UP`; `kafka-topics.sh --list` shows all six topics.

### Test cases
> **TC-1.3.1** — Health endpoint reports UP
> **Given** the orchestrator running with Kafka up → **When** `GET /actuator/health` → **Then** status is `UP` and the Kafka component is `UP`.
> *Type:* integration · *Automated:* yes

> **TC-1.3.2** — All topics provisioned
> **Given** the stack started → **When** topics are listed → **Then** all six expected topics are present.
> *Type:* integration · *Automated:* yes (Testcontainers Kafka)

---

## Day 4 — Alert ingestion and idempotency

**Objective:** `POST /alerts` accepts an alert, deduplicates it, persists an incident, publishes to `incidents.raw`.

### Steps
1. Define the `AlertRequest` DTO (record) and a Bean-Validation schema.
2. Implement `IngestController` with `POST /alerts`.
3. Compute an idempotency key — a hash of `(source, service, alertname, fingerprint)`.
4. Implement `IngestService`: if the key exists, return the existing incident (200, no new work); else insert a new `RECEIVED` incident and publish to `incidents.raw`.
5. Write an `audit_log` row for the ingestion event.

### Code — `IngestController` (skeleton)
```java
@RestController
@RequestMapping("/alerts")
class IngestController {
  private final IngestService ingest;
  IngestController(IngestService ingest) { this.ingest = ingest; }

  @PostMapping
  ResponseEntity<IncidentDto> receive(@Valid @RequestBody AlertRequest req) {
    IngestResult r = ingest.handle(req);          // dedup happens inside
    return r.created()
        ? ResponseEntity.status(201).body(r.incident())
        : ResponseEntity.ok(r.incident());        // duplicate → 200, existing
  }
}
```

### Code — idempotency key (snippet)
```java
String idempotencyKey(AlertRequest a) {
  String basis = a.source() + "|" + a.service() + "|" + a.alertName() + "|" + a.fingerprint();
  return DigestUtils.sha256Hex(basis);
}
```

### End-of-day goal
`curl -X POST /alerts -d @sample-alert.json` creates one incident; sending the same payload twice creates only one.

### Test cases
> **TC-1.4.1** — Valid alert creates an incident
> **Given** an empty DB → **When** a valid alert is POSTed → **Then** response is 201 and one `RECEIVED` incident exists.
> *Type:* integration · *Automated:* yes

> **TC-1.4.2** — Duplicate alert is deduplicated
> **Given** one incident already ingested → **When** the identical alert is POSTed again → **Then** response is 200 and the incident count stays 1.
> *Type:* integration · *Automated:* yes

> **TC-1.4.3** — Invalid alert is rejected
> **Given** a payload missing required fields → **When** POSTed → **Then** response is 400 and no incident is created.
> *Type:* integration · *Automated:* yes

> **TC-1.4.4** — Ingestion is audited
> **Given** a successful ingestion → **When** the audit log is queried → **Then** one `INGESTED` audit row exists for that incident.
> *Type:* integration · *Automated:* yes

---

## Day 5 — Incident state machine

**Objective:** incident state transitions are explicit, validated, persisted, and audited.

### Steps
1. Define `IncidentState` enum and a transition table (which states may follow which).
2. Implement `IncidentStateMachine` with a `transition(incident, target)` method that rejects illegal moves.
3. Every successful transition updates `incidents.state`, bumps `updated_at`, and writes an `audit_log` row.
4. Unit-test the transition rules thoroughly — this is core logic.

### Code — state machine (skeleton)
```java
enum IncidentState { RECEIVED, CLASSIFIED, DISPATCHED, AGGREGATING, PARTIAL, SYNTHESIZED, RESOLVED, FAILED }

class IncidentStateMachine {
  private static final Map<IncidentState, Set<IncidentState>> ALLOWED = Map.of(
    RECEIVED,   Set.of(CLASSIFIED, FAILED),
    CLASSIFIED, Set.of(DISPATCHED, FAILED),
    DISPATCHED, Set.of(AGGREGATING, FAILED),
    AGGREGATING,Set.of(SYNTHESIZED, PARTIAL, FAILED),
    PARTIAL,    Set.of(SYNTHESIZED, FAILED),
    SYNTHESIZED,Set.of(RESOLVED, FAILED)
  );

  void transition(Incident inc, IncidentState target) {
    if (!ALLOWED.getOrDefault(inc.state(), Set.of()).contains(target))
      throw new IllegalStateTransitionException(inc.state(), target);
    inc.setState(target);
    // persist + write audit row
  }
}
```

### End-of-day goal
A unit test suite proves every legal transition succeeds and every illegal one throws.

### Test cases
> **TC-1.5.1** — Legal transition succeeds
> **Given** an incident in `RECEIVED` → **When** transitioned to `CLASSIFIED` → **Then** state is `CLASSIFIED` and an audit row is written.
> *Type:* unit · *Automated:* yes

> **TC-1.5.2** — Illegal transition is rejected
> **Given** an incident in `RECEIVED` → **When** transitioned to `RESOLVED` → **Then** `IllegalStateTransitionException` is thrown and state is unchanged.
> *Type:* unit · *Automated:* yes

> **TC-1.5.3** — FAILED reachable from any state
> **Given** an incident in any non-terminal state → **When** transitioned to `FAILED` → **Then** it succeeds.
> *Type:* unit · *Automated:* yes (parameterized over all states)

---

## Day 6 — Classifier and dispatcher

**Objective:** the orchestrator consumes `incidents.raw`, assigns a severity, and publishes an `agent.tasks` message.

### Steps
1. Implement a Kafka listener on `incidents.raw`.
2. Implement a trivial rule-based classifier (Sprint 1 has no AI here): map alert labels to `p1..p4`. Real classification comes later — keep it simple and deterministic now.
3. On classify: transition `RECEIVED → CLASSIFIED`, then `CLASSIFIED → DISPATCHED`.
4. Build an `AgentTask` message (`incident_id`, `agent_name="echo"`, `payload`) and publish to `agent.tasks`.
5. Key the Kafka message by `incident_id`.

### Code — classifier listener (skeleton)
```java
@KafkaListener(topics = "incidents.raw", groupId = "orchestrator")
void onRawIncident(IncidentEvent ev) {
  Incident inc = repo.find(ev.incidentId());
  String severity = classifier.classify(inc.rawAlert());   // rule-based
  inc.setSeverity(severity);
  stateMachine.transition(inc, CLASSIFIED);
  stateMachine.transition(inc, DISPATCHED);
  dispatcher.dispatch(inc, List.of("echo"));               // Sprint 1: one agent
}
```

### End-of-day goal
POSTing an alert results, within a second, in a message appearing on `agent.tasks`.

### Test cases
> **TC-1.6.1** — Classification assigns severity
> **Given** a raw incident with a `critical` label → **When** the classifier runs → **Then** severity is `p1`.
> *Type:* unit · *Automated:* yes

> **TC-1.6.2** — Dispatch publishes a task
> **Given** an incident reaching `DISPATCHED` → **When** the dispatcher runs → **Then** exactly one message exists on `agent.tasks` keyed by the incident id.
> *Type:* integration · *Automated:* yes

> **TC-1.6.3** — State progresses correctly
> **Given** ingestion of an alert → **When** classification completes → **Then** the incident state is `DISPATCHED`.
> *Type:* integration · *Automated:* yes

---

## Day 7 — FastAPI agent service and Kafka consumer

**Objective:** the Python agent service runs, consumes `agent.tasks`, and can publish to `agent.results`.

### Steps
1. Scaffold the FastAPI app; add `aiokafka` for async Kafka.
2. Implement the worker loop: consume `agent.tasks`, deserialize into a Pydantic `AgentTask`.
3. For now the worker just logs the task and publishes a stub `AgentResult` back.
4. Add a `/health` endpoint.
5. Add graceful shutdown (close the consumer cleanly).

### Code — worker loop (skeleton)
```python
async def run_worker():
    consumer = AIOKafkaConsumer("agent.tasks", bootstrap_servers=KAFKA, group_id="agents")
    producer = AIOKafkaProducer(bootstrap_servers=KAFKA)
    await consumer.start(); await producer.start()
    try:
        async for msg in consumer:
            task = AgentTask.model_validate_json(msg.value)
            result = await handle_task(task)          # Day 8 fills this in
            await producer.send("agent.results", result.model_dump_json().encode())
    finally:
        await consumer.stop(); await producer.stop()
```

### End-of-day goal
With the orchestrator dispatching tasks, the agent service logs each received task and emits a stub result.

### Test cases
> **TC-1.7.1** — Agent service health
> **Given** the service running → **When** `GET /health` → **Then** 200 with status ok.
> *Type:* integration · *Automated:* yes

> **TC-1.7.2** — Worker consumes a task
> **Given** a message on `agent.tasks` → **When** the worker polls → **Then** it deserializes a valid `AgentTask` without error.
> *Type:* integration · *Automated:* yes (Testcontainers Kafka)

> **TC-1.7.3** — Malformed task goes to DLQ
> **Given** an unparseable message on `agent.tasks` → **When** the worker processes it → **Then** it is published to `agent.tasks.dlq` and the worker keeps running.
> *Type:* integration · *Automated:* yes

---

## Day 8 — LLM abstraction layer and the echo agent

**Objective:** a swappable LLM layer exists; the echo agent calls Ollama and returns its output as a real `AgentResult`.

### Steps
1. Define an `LLMClient` protocol with one method: `complete(prompt) -> LLMResponse` (text, tokens, latency).
2. Implement `OllamaClient`. Stub `AnthropicClient` and `GroqClient` (raise `NotImplementedError` for now).
3. A factory picks the client from `LLM_BACKEND`.
4. Implement the echo agent: take the task payload, send a trivial prompt to the LLM, wrap the answer in an `AgentResult`.
5. Record tokens and latency on the result.

### Code — LLM abstraction (skeleton)
```python
class LLMResponse(BaseModel):
    text: str
    tokens: int
    latency_ms: int

class LLMClient(Protocol):
    async def complete(self, prompt: str) -> LLMResponse: ...

class OllamaClient:
    async def complete(self, prompt: str) -> LLMResponse:
        t0 = time.monotonic()
        # POST to {OLLAMA_HOST}/api/generate with model + prompt
        # ... parse response ...
        return LLMResponse(text=..., tokens=..., latency_ms=int((time.monotonic()-t0)*1000))

def make_llm_client() -> LLMClient:
    return {"ollama": OllamaClient, "anthropic": AnthropicClient,
            "groq": GroqClient}[settings.LLM_BACKEND]()
```

### Code — echo agent (skeleton)
```python
async def echo_agent(task: AgentTask, llm: LLMClient) -> AgentResult:
    prompt = f"Acknowledge this incident in one sentence: {task.payload}"
    resp = await llm.complete(prompt)
    return AgentResult(
        incident_id=task.incident_id, agent_name="echo",
        output={"message": resp.text},
        tokens_used=resp.tokens, latency_ms=resp.latency_ms, status="ok",
    )
```

### End-of-day goal
A dispatched task produces an `agent.results` message containing a real one-sentence LLM response.

### Test cases
> **TC-1.8.1** — Factory selects backend
> **Given** `LLM_BACKEND=ollama` → **When** `make_llm_client()` is called → **Then** an `OllamaClient` is returned.
> *Type:* unit · *Automated:* yes

> **TC-1.8.2** — Echo agent returns a valid result
> **Given** a task and a stub LLM client → **When** `echo_agent` runs → **Then** an `AgentResult` with `status=ok` and non-empty output is returned.
> *Type:* unit · *Automated:* yes (mock LLM, no network)

> **TC-1.8.3** — Ollama integration produces text
> **Given** Ollama running locally → **When** `OllamaClient.complete` is called → **Then** a non-empty response with token count > 0 is returned.
> *Type:* integration · *Automated:* yes (skipped in CI if Ollama absent; runs locally)

---

## Day 9 — Aggregator and incident resolution

**Objective:** the orchestrator consumes `agent.results`, records the trace, and resolves the incident.

### Steps
1. Implement a Kafka listener on `agent.results`.
2. On a result: write an `agent_traces` row (input, output, tokens, latency, status).
3. Since Sprint 1 has one agent, one result completes the incident: transition `DISPATCHED → AGGREGATING → SYNTHESIZED → RESOLVED`.
4. Write a minimal `incident_reports` row (Sprint 1: just echo the agent message).
5. Publish to `incidents.synthesized`.
6. Handle a duplicate result (same incident + agent) idempotently — record once, ignore repeats.

### Code — aggregator listener (skeleton)
```java
@KafkaListener(topics = "agent.results", groupId = "orchestrator")
void onAgentResult(AgentResult res) {
  if (traceRepo.exists(res.incidentId(), res.agentName())) return;  // idempotent
  traceRepo.save(toTrace(res));
  Incident inc = repo.find(res.incidentId());
  stateMachine.transition(inc, AGGREGATING);
  reportRepo.save(buildReport(inc, res));        // Sprint 1: trivial report
  stateMachine.transition(inc, SYNTHESIZED);
  stateMachine.transition(inc, RESOLVED);
  publisher.publish("incidents.synthesized", inc.id());
}
```

### End-of-day goal
End to end: POST an alert → seconds later the incident is `RESOLVED` with a trace row and a report row.

### Test cases
> **TC-1.9.1** — Result creates a trace
> **Given** an `agent.results` message → **When** the aggregator processes it → **Then** one `agent_traces` row exists for that incident+agent.
> *Type:* integration · *Automated:* yes

> **TC-1.9.2** — Incident resolves
> **Given** a dispatched incident → **When** its agent result arrives → **Then** the incident reaches `RESOLVED`.
> *Type:* integration · *Automated:* yes

> **TC-1.9.3** — Duplicate result is ignored
> **Given** an incident already resolved → **When** the same agent result arrives again → **Then** no second trace row is created and state stays `RESOLVED`.
> *Type:* integration · *Automated:* yes

---

## Day 10 — End-to-end test, CI wiring, cleanup

**Objective:** a single automated test proves the whole pipeline; CI runs the full test suite; the sprint is demoable.

### Steps
1. Write an end-to-end test: start the stack (Testcontainers or Compose), POST an alert, poll until the incident is `RESOLVED` or a timeout, assert the final state and rows.
2. Expand `ci.yml`: a Java job (`mvn verify`), a Python job (`pytest`, `ruff`, `mypy`), both running on every PR.
3. Write `PROGRESS.md` (sprint log) and flesh out the README "getting started" section.
4. Run the sprint demo script end to end yourself.
5. Sprint retrospective — answer the prompts below.

### Code — end-to-end test (skeleton)
```java
@Test
void alert_flows_all_the_way_to_resolved() {
  var resp = http.post("/alerts", sampleAlert());
  assertThat(resp.status()).isEqualTo(201);
  UUID id = resp.body().id();

  await().atMost(Duration.ofSeconds(30))
         .until(() -> repo.find(id).state() == IncidentState.RESOLVED);

  assertThat(traceRepo.countFor(id)).isEqualTo(1);
  assertThat(reportRepo.find(id)).isPresent();
}
```

### Code — CI (expanded skeleton)
```yaml
jobs:
  orchestrator:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - run: cd orchestrator && mvn -B verify
  agents:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.11' }
      - run: cd agents && pip install -e ".[dev]" && ruff check . && mypy . && pytest
```

### End-of-day goal — **Sprint 1 complete**
All acceptance criteria from the top of this file are met and provable.

### Test cases
> **TC-1.10.1** — End-to-end pipeline passes
> **Given** the full stack running → **When** the e2e test runs → **Then** the incident reaches `RESOLVED` within 30s with one trace and one report.
> *Type:* e2e · *Automated:* yes

> **TC-1.10.2** — CI runs the full suite
> **Given** a PR → **When** CI runs → **Then** both the orchestrator and agents jobs pass.
> *Type:* manual · *Automated:* no

---

## Sprint 1 demo script

1. Show `docker compose ps` — infrastructure healthy.
2. Start the orchestrator and the agent service.
3. `curl -X POST .../alerts -d @sample-alert.json` — show the 201 response.
4. Re-run the same curl — show the 200 (deduplicated, no new incident).
5. Query Postgres: show the incident in `RESOLVED`, the trace row, the report row.
6. Show the audit log: the full transition history of the incident.
7. Show CI green on the latest PR.

## Sprint 1 retrospective prompts

- Did anything take far longer than expected? Why?
- Is there a shortcut you took that you must document as deliberate tech debt?
- Is the e2e test fast enough to keep running, or does it need trimming?
- What is the single riskiest unknown going into Sprint 2?

Continue to `04-SPRINT-2-DEMO-APP-AND-AGENTS.md`.
