# 04 — Sprint 2: Demo App & First Agents

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## Sprint goal

**A realistic demo app emits real logs and metrics; the Log Analyzer and Metrics agents produce genuine findings from that telemetry.** Sprint 1 proved the pipes — this sprint puts real intelligence into two of them.

## Sprint acceptance criteria

1. A demo app runs, exposes Prometheus metrics, and emits structured logs to Loki.
2. The demo app has three toggleable failure modes: memory leak, downstream timeout, slow query.
3. A synthetic incident generator produces alert payloads plus matching log/metric fixtures.
4. The Log Analyzer agent queries Loki and returns real error-pattern findings.
5. The Metrics agent queries Prometheus and returns real SLO-violation findings.
6. A versioned prompt registry loads prompts from files at startup.
7. Triggering a real failure in the demo app produces an incident with two real agent findings.
8. All new code has tests; CI stays green.

## Sprint backlog

| ID | Ticket | Priority |
|---|---|---|
| S2-1 | Demo app skeleton + Prometheus metrics + Loki logs | MUST |
| S2-2 | Demo app failure modes (3, toggleable) | MUST |
| S2-3 | Prometheus + Loki + Grafana wired into Compose | MUST |
| S2-4 | Synthetic incident generator | MUST |
| S2-5 | Tool layer: LogQL query tool | MUST |
| S2-6 | Tool layer: PromQL query tool | MUST |
| S2-7 | Prompt registry (versioned, file-based) | MUST |
| S2-8 | Log Analyzer agent | MUST |
| S2-9 | Metrics agent | MUST |
| S2-10 | Multi-agent dispatch (orchestrator dispatches 2 agents) | MUST |
| S2-11 | Integration + e2e tests for the new agents | MUST |
| S2-12 | Grafana dashboard for the demo app | SHOULD |

---

## Day 1 — Demo app skeleton

**Objective:** a small Spring Boot service runs with a few realistic endpoints, instrumented for metrics.

### Steps
1. Create `demo-app/` — a Spring Boot service simulating an order system: `GET /orders`, `POST /orders`, `GET /inventory`.
2. Add Micrometer + the Prometheus registry; expose `/actuator/prometheus`.
3. Add request-count, latency, and error-count metrics per endpoint.
4. Add structured (JSON) logging.

### Code — instrumented endpoint (skeleton)
```java
@RestController
class OrderController {
  private final Counter orders = Metrics.counter("orders_created_total");

  @PostMapping("/orders")
  Order create(@RequestBody OrderRequest req) {
    orders.increment();
    log.info("order_created", kv("service", "order-api"), kv("amount", req.amount()));
    return service.create(req);
  }
}
```

### End-of-day goal
The demo app runs; `/actuator/prometheus` shows live metrics; logs are JSON.

### Test cases
> **TC-2.1.1** — Demo app serves requests
> **Given** the demo app running → **When** `POST /orders` → **Then** 200 and an order is returned.
> *Type:* integration · *Automated:* yes

> **TC-2.1.2** — Metrics are exposed
> **Given** several requests made → **When** `/actuator/prometheus` is scraped → **Then** `orders_created_total` reflects the count.
> *Type:* integration · *Automated:* yes

---

## Day 2 — Demo app failure modes

**Objective:** the demo app can be told to misbehave in three controlled, realistic ways.

### Steps
1. Add an admin endpoint `POST /admin/failure-mode` accepting `none | memory-leak | downstream-timeout | slow-query`.
2. **Memory leak:** a mode that retains objects in a static list, growing heap over time.
3. **Downstream timeout:** a mode that makes a fake downstream call sleep past a timeout.
4. **Slow query:** a mode that injects an artificial delay into a "DB" call.
5. Each mode must visibly move the metrics (heap, latency, error rate).

### Code — failure-mode switch (skeleton)
```java
enum FailureMode { NONE, MEMORY_LEAK, DOWNSTREAM_TIMEOUT, SLOW_QUERY }

@Component
class FailureModeController {
  private volatile FailureMode mode = FailureMode.NONE;
  private final List<byte[]> leak = new ArrayList<>();   // memory-leak sink

  void apply() {
    switch (mode) {
      case MEMORY_LEAK -> leak.add(new byte[1_000_000]);
      case SLOW_QUERY  -> sleep(2000);
      case DOWNSTREAM_TIMEOUT -> callFakeDownstream(withTimeout(true));
      case NONE -> {}
    }
  }
}
```

### End-of-day goal
Toggling each mode produces a visible, distinct change in the demo app's metrics.

### Test cases
> **TC-2.2.1** — Failure mode is settable
> **Given** the demo app → **When** `POST /admin/failure-mode {memory-leak}` → **Then** the active mode is `memory-leak`.
> *Type:* integration · *Automated:* yes

> **TC-2.2.2** — Slow-query mode increases latency
> **Given** `slow-query` mode active → **When** an endpoint is called → **Then** measured latency exceeds the normal baseline by the injected delay.
> *Type:* integration · *Automated:* yes

> **TC-2.2.3** — Memory-leak mode grows heap
> **Given** `memory-leak` mode active → **When** requests run for a period → **Then** the JVM heap-used metric trends upward.
> *Type:* manual · *Automated:* no (timing-dependent; verify by eye in Grafana)

---

## Day 3 — Prometheus, Loki, Grafana wired in

**Objective:** the observability stack scrapes the demo app and is queryable.

### Steps
1. Add Prometheus to Compose; configure it to scrape the demo app's `/actuator/prometheus`.
2. Add Loki to Compose; add a log shipper (Promtail or the Loki Docker driver) for the demo app's logs.
3. Add Grafana; provision Prometheus and Loki as data sources via config files (not click-ops).
4. Confirm: a PromQL query in Grafana returns demo-app metrics; a LogQL query returns demo-app logs.

### End-of-day goal
In Grafana you can run a PromQL query and a LogQL query and see live demo-app data.

### Test cases
> **TC-2.3.1** — Prometheus scrapes the demo app
> **Given** the stack running → **When** Prometheus targets are listed → **Then** the demo app target is `UP`.
> *Type:* manual · *Automated:* no

> **TC-2.3.2** — Loki receives demo-app logs
> **Given** the demo app generating logs → **When** a LogQL query for its job runs → **Then** log lines are returned.
> *Type:* manual · *Automated:* no

---

## Day 4 — Synthetic incident generator

**Objective:** a script produces realistic alert payloads with matching telemetry fixtures, so agents can be tested offline.

### Steps
1. Create `synthetic/generate.py`.
2. Define incident templates: each has an alert payload, a set of log lines, and a metric series, all consistent with one root cause.
3. Support a `--count N` flag to generate a corpus.
4. Each generated incident carries a ground-truth label (the true root cause) for later scoring in Sprint 5.
5. Output: alert JSON files plus fixture files the tool layer can be pointed at in test mode.

### Code — generator (skeleton)
```python
@dataclass
class SyntheticIncident:
    alert: dict
    log_lines: list[str]
    metric_series: dict
    ground_truth_cause: str          # used by the eval harness later

TEMPLATES = [memory_leak_template, db_timeout_template, slow_query_template]

def generate(count: int) -> list[SyntheticIncident]:
    return [random.choice(TEMPLATES)() for _ in range(count)]
```

### End-of-day goal
`python generate.py --count 20` produces 20 labelled, internally-consistent synthetic incidents.

### Test cases
> **TC-2.4.1** — Generator produces the requested count
> **Given** `--count 20` → **When** the generator runs → **Then** 20 incidents are produced.
> *Type:* unit · *Automated:* yes

> **TC-2.4.2** — Each incident is internally consistent
> **Given** a generated incident → **When** inspected → **Then** its alert, logs, and metrics all point to the same `ground_truth_cause`.
> *Type:* unit · *Automated:* yes

---

## Day 5 — Tool layer: LogQL query tool

**Objective:** agents can query Loki (or a fixture) for logs around an incident window.

### Steps
1. Create `agents/app/tools/logs.py`.
2. Implement `query_logs(service, start, end, filter)` calling Loki's HTTP API.
3. Add a fixture mode: when pointed at a synthetic incident, return its fixture log lines instead of hitting Loki. This lets tests run without the full stack.
4. Return a structured result: matched lines, counts, time range.
5. Bound the result size (never return thousands of lines to an LLM — summarize/sample).

### Code — log tool (skeleton)
```python
class LogQueryResult(BaseModel):
    line_count: int
    sampled_lines: list[str]        # bounded, e.g. top 50
    time_range: tuple[str, str]

async def query_logs(service: str, start: str, end: str,
                      filter_expr: str = "") -> LogQueryResult:
    if settings.TOOL_MODE == "fixture":
        return _from_fixture(service)
    # else call Loki /loki/api/v1/query_range
    ...
```

### End-of-day goal
`query_logs` returns structured, bounded results from both Loki and fixtures.

### Test cases
> **TC-2.5.1** — Log query returns bounded results
> **Given** a service with thousands of log lines → **When** `query_logs` runs → **Then** `sampled_lines` length does not exceed the configured cap.
> *Type:* unit · *Automated:* yes

> **TC-2.5.2** — Fixture mode works offline
> **Given** `TOOL_MODE=fixture` and a synthetic incident → **When** `query_logs` runs → **Then** the incident's fixture lines are returned with no network call.
> *Type:* unit · *Automated:* yes

---

## Day 6 — Tool layer: PromQL query tool

**Objective:** agents can query Prometheus (or a fixture) for metrics around an incident window.

### Steps
1. Create `agents/app/tools/metrics.py`.
2. Implement `query_metrics(promql, start, end)` calling Prometheus' range-query API.
3. Add helpers: `slo_violations(service)` checks latency/error-rate against thresholds.
4. Add fixture mode mirroring the log tool.
5. Return structured results: series, summary statistics, violations found.

### Code — metrics tool (skeleton)
```python
class MetricResult(BaseModel):
    series_summary: dict            # min/max/avg per series
    slo_violations: list[str]

async def query_metrics(promql: str, start: str, end: str) -> MetricResult:
    if settings.TOOL_MODE == "fixture":
        return _from_fixture(promql)
    # else call Prometheus /api/v1/query_range
    ...
```

### End-of-day goal
`query_metrics` and `slo_violations` return structured results from Prometheus and fixtures.

### Test cases
> **TC-2.6.1** — Metric query returns a summary
> **Given** a metric series → **When** `query_metrics` runs → **Then** a per-series summary with min/max/avg is returned.
> *Type:* unit · *Automated:* yes

> **TC-2.6.2** — SLO violation is detected
> **Given** a fixture where error rate exceeds threshold → **When** `slo_violations` runs → **Then** the violation is listed.
> *Type:* unit · *Automated:* yes

---

## Day 7 — Prompt registry

**Objective:** prompts live in versioned files, are loaded at startup, and every agent run records which prompt version it used.

### Steps
1. Create `agents/app/prompts/` with one file per agent (e.g. `log_analyzer.txt`).
2. Implement a registry that loads all prompt files at startup and hashes each (the hash is the version).
3. On startup, upsert each prompt into the `prompt_versions` table.
4. Expose `get_prompt(agent_name) -> (body, version)`.
5. Every `AgentResult` will carry its `prompt_version` (used for replay and Sprint 5 eval).

### Code — prompt registry (skeleton)
```python
class PromptRegistry:
    def __init__(self, dir: Path):
        self._prompts = {}
        for f in dir.glob("*.txt"):
            body = f.read_text()
            version = hashlib.sha256(body.encode()).hexdigest()[:12]
            self._prompts[f.stem] = (body, version)

    def get(self, agent_name: str) -> tuple[str, str]:
        return self._prompts[agent_name]
```

### End-of-day goal
At startup all prompts are loaded, hashed, and recorded in `prompt_versions`.

### Test cases
> **TC-2.7.1** — Prompts load and version
> **Given** prompt files on disk → **When** the registry initializes → **Then** each prompt has a stable 12-char version hash.
> *Type:* unit · *Automated:* yes

> **TC-2.7.2** — Same content yields same version
> **Given** an unchanged prompt file → **When** the registry initializes twice → **Then** the version hash is identical both times.
> *Type:* unit · *Automated:* yes

---

## Day 8 — Log Analyzer agent

**Objective:** a real agent that queries logs, reasons with the LLM, and returns a structured finding.

### Steps
1. Create `agents/app/agents/log_analyzer.py`.
2. Flow: take the task → call `query_logs` for the incident window → build a prompt with the log summary → call the LLM → parse a structured finding.
3. The LLM must return JSON: `error_patterns`, `most_likely_symptom`, `confidence`. Prompt it strictly for JSON-only output and parse defensively.
4. Wrap the result in an `AgentResult` with tokens, latency, prompt version.
5. Handle the case where the LLM returns malformed JSON — retry once, then return a low-confidence fallback.

### Code — Log Analyzer (skeleton)
```python
async def log_analyzer(task: AgentTask, llm: LLMClient,
                        prompts: PromptRegistry) -> AgentResult:
    logs = await query_logs(task.service, task.window_start, task.window_end)
    body, version = prompts.get("log_analyzer")
    prompt = body.format(log_summary=logs.model_dump_json())
    resp = await llm.complete(prompt)
    finding = parse_json_finding(resp.text)        # defensive parse + 1 retry
    return AgentResult(
        incident_id=task.incident_id, agent_name="log_analyzer",
        output=finding, prompt_version=version,
        tokens_used=resp.tokens, latency_ms=resp.latency_ms, status="ok",
    )
```

### End-of-day goal
Given an incident with a log fixture, the Log Analyzer returns a structured, sensible finding.

### Test cases
> **TC-2.8.1** — Log Analyzer returns a structured finding
> **Given** a memory-leak fixture → **When** the Log Analyzer runs → **Then** the output JSON contains `error_patterns` and a `confidence` between 0 and 1.
> *Type:* integration · *Automated:* yes (real Ollama; skipped in CI)

> **TC-2.8.2** — Malformed LLM output is handled
> **Given** a stub LLM that returns non-JSON → **When** the Log Analyzer runs → **Then** it returns a low-confidence fallback rather than crashing.
> *Type:* unit · *Automated:* yes (mock LLM)

---

## Day 9 — Metrics agent

**Objective:** a real agent that queries metrics, checks SLOs, reasons with the LLM, and returns a structured finding.

### Steps
1. Create `agents/app/agents/metrics_agent.py`, mirroring the Log Analyzer's structure.
2. Flow: query metrics for the incident window → run `slo_violations` → build a prompt → LLM → structured finding (`slo_status`, `anomalies`, `most_likely_cause`, `confidence`).
3. Same defensive JSON parsing and fallback.
4. Wire both agents into the worker's task router (dispatch by `task.agent_name`).

### Code — task router (snippet)
```python
AGENTS = {
    "echo": echo_agent,
    "log_analyzer": log_analyzer,
    "metrics": metrics_agent,
}

async def handle_task(task: AgentTask) -> AgentResult:
    agent_fn = AGENTS.get(task.agent_name)
    if agent_fn is None:
        return AgentResult(..., status="error", output={"reason": "unknown agent"})
    return await agent_fn(task, llm, prompts)
```

### End-of-day goal
Given an incident, both the Log Analyzer and the Metrics agent produce findings.

### Test cases
> **TC-2.9.1** — Metrics agent detects SLO breach
> **Given** a fixture with an error-rate SLO breach → **When** the Metrics agent runs → **Then** the finding's `slo_status` reports the breach.
> *Type:* integration · *Automated:* yes (real Ollama; skipped in CI)

> **TC-2.9.2** — Router dispatches to the right agent
> **Given** a task with `agent_name=metrics` → **When** `handle_task` runs → **Then** the Metrics agent function is invoked.
> *Type:* unit · *Automated:* yes

---

## Day 10 — Multi-agent dispatch, integration, demo

**Objective:** the orchestrator dispatches two agents per incident; both findings are aggregated; the sprint is demoable.

### Steps
1. Update the orchestrator's dispatcher: for an incident, publish two `agent.tasks` messages (`log_analyzer`, `metrics`).
2. Update the aggregator: it now expects two results before completing — track which agents have returned.
3. For now, "complete" just means both traces are recorded and the incident resolves (real synthesis is Sprint 3).
4. Write an e2e test: trigger a demo-app failure → assert two findings recorded.
5. Build a Grafana dashboard for the demo app (the SHOULD ticket) if time allows.
6. Run the demo; do the retrospective.

### End-of-day goal — **Sprint 2 complete**
Triggering a real failure in the demo app produces an incident with two real, distinct agent findings.

### Test cases
> **TC-2.10.1** — Two agents dispatched per incident
> **Given** an incident reaching `DISPATCHED` → **When** the dispatcher runs → **Then** two `agent.tasks` messages exist (log_analyzer, metrics).
> *Type:* integration · *Automated:* yes

> **TC-2.10.2** — Aggregator waits for both
> **Given** only one of two results received → **When** the aggregator state is inspected → **Then** the incident is still `AGGREGATING`, not resolved.
> *Type:* integration · *Automated:* yes

> **TC-2.10.3** — End-to-end with real agents
> **Given** the demo app in `memory-leak` mode and an alert raised → **When** the pipeline runs → **Then** two `agent_traces` rows exist with non-empty findings.
> *Type:* e2e · *Automated:* yes

---

## Sprint 2 demo script

1. Show the demo app running and healthy in Grafana.
2. `POST /admin/failure-mode {memory-leak}` — show the heap metric climbing.
3. Raise an alert for the demo app.
4. Show two `agent.tasks` messages dispatched.
5. Query `agent_traces`: show the Log Analyzer's finding and the Metrics agent's finding side by side.
6. Point out that they independently surfaced the problem.

## Sprint 2 retrospective prompts

- How reliable is the LLM's JSON output? Does the defensive parsing fire often?
- Are the fixtures realistic enough, or do they make the agents look better than they are?
- Is Ollama fast enough for a comfortable dev loop, or is it a bottleneck?
- What is the riskiest unknown going into Sprint 3 (synthesis + UI)?

Continue to `05-SPRINT-3-SYNTHESIS-AND-UI.md`.
