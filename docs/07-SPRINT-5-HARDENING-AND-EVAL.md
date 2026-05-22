# 07 — Sprint 5: Hardening & Evaluation

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## Sprint goal

**Make Sentinel production-grade and measurable.** Circuit breakers, retries, cost budgets, an evaluation harness that scores the swarm, prompt versioning, and the production LLM path. This is the sprint that turns a working demo into a *credible system* — and produces the numbers for your resume.

## Sprint acceptance criteria

1. Every external call (Kafka, DB, LLM, tools) has a timeout, retry, and circuit breaker.
2. Poison messages land in the DLQ without stalling the worker.
3. Token budgets are enforced per-incident, per-tenant, and globally, with a kill switch.
4. The evaluation harness runs the swarm against the synthetic corpus and reports accuracy, latency, and cost.
5. The eval harness runs in CI on every PR.
6. The production LLM path (Anthropic/Groq) works and can be compared to Ollama via the harness.
7. A Grafana dashboard shows Sentinel's own SLOs and cost-per-incident.

## Sprint backlog

| ID | Ticket | Priority |
|---|---|---|
| S5-1 | Circuit breakers + retries (orchestrator) | MUST |
| S5-2 | LLM gateway: retry, breaker, model ladder | MUST |
| S5-3 | DLQ handling for poison messages | MUST |
| S5-4 | Budget governor (per-incident/tenant/global + kill switch) | MUST |
| S5-5 | Cost + token accounting on every LLM call | MUST |
| S5-6 | Evaluation harness | MUST |
| S5-7 | Eval harness in CI | MUST |
| S5-8 | Production LLM path (Anthropic/Groq) | MUST |
| S5-9 | Self-observability: Sentinel's own metrics + Grafana dashboard | MUST |
| S5-10 | Prompt versioning workflow + eval-per-version | SHOULD |

---

## Day 1 — Circuit breakers and retries (orchestrator)

**Objective:** the orchestrator survives flaky dependencies.

### Steps
1. Add Resilience4j to the orchestrator.
2. Wrap DB calls and Kafka publishes with retry (exponential backoff + jitter) and a circuit breaker.
3. Define sensible policies: a few retries, a breaker that opens after repeated failures and half-opens after a cooldown.
4. On a tripped breaker, fail the incident to `FAILED` cleanly with an audit entry rather than hanging.

### Code — resilience wrapper (snippet)
```java
@Retry(name = "db")
@CircuitBreaker(name = "db", fallbackMethod = "onDbFailure")
void persist(Incident inc) { repo.save(inc); }

void onDbFailure(Incident inc, Throwable t) {
  log.error("db circuit open for {}", inc.id(), t);
  // mark incident FAILED, write audit row
}
```

### End-of-day goal
With a dependency forced to fail, the orchestrator degrades cleanly instead of hanging or crashing.

### Test cases
> **TC-5.1.1** — Retry then succeed
> **Given** a DB call that fails twice then succeeds → **When** invoked → **Then** the call ultimately succeeds and the retry count is recorded.
> *Type:* integration · *Automated:* yes

> **TC-5.1.2** — Open breaker fails the incident cleanly
> **Given** a dependency failing continuously → **When** the breaker opens → **Then** the incident is moved to `FAILED` with an audit row, and the service stays responsive.
> *Type:* integration · *Automated:* yes

---

## Day 2 — LLM gateway hardening

**Objective:** LLM calls — the least reliable part of the system — are wrapped in their own resilience layer with a model fallback ladder.

### Steps
1. Introduce an `LLMGateway` in front of the raw `LLMClient`.
2. Add retry with backoff for transient errors (rate limits, timeouts).
3. Add a circuit breaker per backend.
4. Implement the model ladder: try the configured primary; on persistent failure, fall back down the ladder (e.g. Ollama → Groq → Anthropic, or the reverse in prod).
5. Every call records which backend actually served it.

### Code — model ladder (skeleton)
```python
async def complete_with_ladder(prompt: str) -> LLMResponse:
    for client in LADDER:                      # ordered list of clients
        if breaker[client].is_open():
            continue
        try:
            return await with_retry(client.complete, prompt)
        except LLMError:
            breaker[client].record_failure()
    raise AllBackendsFailed()
```

### End-of-day goal
If the primary LLM backend fails repeatedly, the gateway transparently falls back to the next.

### Test cases
> **TC-5.2.1** — Fallback on primary failure
> **Given** the primary backend always failing → **When** a completion is requested → **Then** the secondary backend serves it and the response records the fallback.
> *Type:* unit · *Automated:* yes (stub clients)

> **TC-5.2.2** — All-backends-failed surfaces an error
> **Given** every backend failing → **When** a completion is requested → **Then** `AllBackendsFailed` is raised and the agent returns an error result, not a hang.
> *Type:* unit · *Automated:* yes

---

## Day 3 — Dead-letter queue handling

**Objective:** messages that cannot be processed are quarantined without stalling the system.

### Steps
1. Define a retry budget per message (e.g. 3 attempts).
2. On exhausting retries — or on an unparseable message — publish to `agent.tasks.dlq` with the failure reason attached.
3. The consumer commits its offset and moves on; one poison message never blocks the partition.
4. Add a small DLQ-inspection script (or endpoint) to view quarantined messages.

### End-of-day goal
A deliberately poisoned message ends up in the DLQ; the worker keeps processing everything else.

### Test cases
> **TC-5.3.1** — Poison message is quarantined
> **Given** an unparseable message on `agent.tasks` → **When** the worker processes the partition → **Then** the message is in the DLQ and later valid messages are still processed.
> *Type:* integration · *Automated:* yes

> **TC-5.3.2** — Retry budget is respected
> **Given** a message that fails transiently → **When** processed → **Then** it is retried up to the budget before going to the DLQ, not before.
> *Type:* integration · *Automated:* yes

---

## Day 4 — Budget governor

**Objective:** the system cannot run away on cost.

### Steps
1. Implement budget tracking at three scopes: per-incident, per-tenant, global. (Tenant is a single default tenant for now, but the scope exists.)
2. Before each LLM call, the governor checks remaining budget; if exhausted, the agent returns a budget-exceeded result and the incident proceeds partial.
3. Implement a global kill switch (a config flag / Redis key) that halts all LLM calls immediately.
4. Every budget decision is audited.

### Code — budget check (skeleton)
```python
def check_budget(incident_id: str, est_tokens: int) -> bool:
    if kill_switch_active():
        return False
    return (incident_spend[incident_id] + est_tokens <= INCIDENT_BUDGET
            and global_spend + est_tokens <= GLOBAL_BUDGET)
```

### End-of-day goal
An incident that would exceed its token budget is stopped gracefully; the kill switch halts all LLM activity.

### Test cases
> **TC-5.4.1** — Per-incident budget enforced
> **Given** an incident near its token budget → **When** an agent attempts another large call → **Then** the call is refused and the incident completes partial.
> *Type:* integration · *Automated:* yes

> **TC-5.4.2** — Kill switch halts LLM calls
> **Given** the kill switch active → **When** any agent runs → **Then** no LLM call is made and the agent returns a halted result.
> *Type:* integration · *Automated:* yes

---

## Day 5 — Cost and token accounting

**Objective:** every LLM call's tokens and cost are recorded and queryable.

### Steps
1. Ensure every `agent_traces` row has accurate `tokens_used` and `cost_usd` (cost computed from a per-model price table).
2. Add an aggregate query: cost per incident, cost per day, cost per agent.
3. Expose these as metrics for Prometheus.

### End-of-day goal
You can answer "what did this incident cost?" and "what is our cost per incident this week?" from real data.

### Test cases
> **TC-5.5.1** — Cost recorded per call
> **Given** an LLM call with a known token count → **When** the trace is written → **Then** `cost_usd` equals tokens × the model's price.
> *Type:* unit · *Automated:* yes

> **TC-5.5.2** — Cost aggregates correctly
> **Given** an incident with five agent traces → **When** incident cost is queried → **Then** it equals the sum of the five trace costs.
> *Type:* integration · *Automated:* yes

---

## Day 6 — Evaluation harness

**Objective:** a rig that runs the swarm against the labelled synthetic corpus and scores it.

### Steps
1. Create `eval/run_eval.py`.
2. For each synthetic incident (which carries a ground-truth root cause): run the full swarm, capture the report.
3. Score: does the report's root cause match the ground truth? (Use a rubric — exact category match, plus an LLM-judged "is this substantially correct" for nuance.)
4. Aggregate metrics: triage accuracy (%), mean and p95 time-to-triage, mean cost per incident, partial-rate.
5. Output a results file (JSON + a human-readable summary).

### Code — eval loop (skeleton)
```python
def run_eval(corpus: list[SyntheticIncident]) -> EvalReport:
    results = []
    for inc in corpus:
        report = run_swarm(inc)
        results.append(score(report, inc.ground_truth_cause))
    return EvalReport(
        accuracy=mean(r.correct for r in results),
        p95_latency_ms=percentile([r.latency for r in results], 95),
        mean_cost_usd=mean(r.cost for r in results),
        partial_rate=mean(r.was_partial for r in results),
    )
```

### End-of-day goal
`python run_eval.py` runs the swarm over the corpus and prints accuracy, latency, cost, partial-rate.

### Test cases
> **TC-5.6.1** — Eval runs end to end
> **Given** a 20-incident corpus → **When** `run_eval` runs → **Then** an `EvalReport` with all four metrics is produced.
> *Type:* integration · *Automated:* yes

> **TC-5.6.2** — Scoring is correct on a known case
> **Given** a report whose root cause matches ground truth → **When** scored → **Then** it counts as correct; a mismatch counts as incorrect.
> *Type:* unit · *Automated:* yes

---

## Day 7 — Eval harness in CI

**Objective:** the eval harness runs automatically and guards against regressions.

### Steps
1. Add a CI job that runs the eval harness against a small fixed corpus using a deterministic/cheap backend.
2. Set a regression guard: fail the build if accuracy drops below a baseline threshold.
3. Publish the eval report as a CI artifact so you can track it over time.

### End-of-day goal
Every PR runs the eval; a prompt change that hurts accuracy fails CI.

### Test cases
> **TC-5.7.1** — Eval job runs in CI
> **Given** a PR → **When** CI runs → **Then** the eval job completes and uploads a report artifact.
> *Type:* manual · *Automated:* no

> **TC-5.7.2** — Regression guard trips
> **Given** a deliberately broken prompt → **When** the eval job runs → **Then** the build fails on the accuracy threshold.
> *Type:* manual · *Automated:* no (verify once by intentionally breaking a prompt)

---

## Day 8 — Production LLM path

**Objective:** the Anthropic/Groq backend works and can be compared to Ollama.

### Steps
1. Implement the previously-stubbed `AnthropicClient` (and/or `GroqClient`).
2. Confirm token accounting and cost calculation are correct for the real API.
3. Run the eval harness twice — once on Ollama, once on the production backend — and compare accuracy, latency, cost.
4. Document the trade-off (the production backend should be more accurate but cost more).

### End-of-day goal
The same eval corpus can be scored on both backends, with a documented comparison.

### Test cases
> **TC-5.8.1** — Production client returns valid completions
> **Given** a configured production API key → **When** a completion is requested → **Then** a valid response with accurate token counts is returned.
> *Type:* integration · *Automated:* yes (runs only when a key is configured)

> **TC-5.8.2** — Backend swap needs no agent code change
> **Given** `LLM_BACKEND` switched → **When** the swarm runs → **Then** no agent code changed and the swarm still functions.
> *Type:* integration · *Automated:* yes

---

## Day 9 — Self-observability

**Objective:** Sentinel monitors itself, on an isolated stack.

### Steps
1. Add OpenTelemetry tracing across the orchestrator and the agent service — a trace per incident spanning both planes.
2. Expose Sentinel's own metrics: incidents/min, time-to-triage, partial-rate, cost/incident, agent error-rate.
3. Build a Grafana dashboard for these — Sentinel watching Sentinel.
4. Keep this telemetry separate from the demo app's so a demo-app outage never blinds Sentinel.

### End-of-day goal
A Grafana dashboard shows Sentinel's own health and cost in real time.

### Test cases
> **TC-5.9.1** — Cross-plane trace exists
> **Given** an incident processed → **When** traces are inspected → **Then** a single trace spans both the orchestrator and the agent service.
> *Type:* integration · *Automated:* yes

> **TC-5.9.2** — Self-metrics are exposed
> **Given** incidents processed → **When** Sentinel's metrics endpoint is scraped → **Then** time-to-triage and cost-per-incident are present.
> *Type:* integration · *Automated:* yes

---

## Day 10 — Prompt versioning workflow, integration, demo

**Objective:** prompt changes are tracked and tied to eval scores; the sprint is demoable.

### Steps
1. Finalize the prompt versioning workflow: editing a prompt creates a new version; the eval harness records which prompt versions produced which scores.
2. A simple view/report: prompt version → eval accuracy. This lets you prove a prompt change improved things.
3. Full integration pass; run the demo; retrospective.
4. **Record the headline numbers** — accuracy, p95 latency, cost/incident — these go on your resume.

### End-of-day goal — **Sprint 5 complete**
The swarm is hardened, measurable, and you have real numbers: "X% accuracy, Y s p95, $Z/incident."

### Test cases
> **TC-5.10.1** — Eval score is attributed to a prompt version
> **Given** an eval run → **When** results are stored → **Then** each result records the prompt versions used.
> *Type:* integration · *Automated:* yes

> **TC-5.10.2** — Headline metrics are reproducible
> **Given** the same corpus and backend → **When** the eval is run twice → **Then** accuracy is stable within a small expected variance.
> *Type:* manual · *Automated:* no

---

## Sprint 5 demo script

1. Show the eval harness running over the corpus — accuracy, latency, cost printed live.
2. Show the same eval on Ollama vs the production backend — the trade-off.
3. Show the Grafana dashboard of Sentinel's own SLOs and cost-per-incident.
4. Force a backend to fail — show the model ladder falling back transparently.
5. Flip the kill switch — show all LLM activity halting.
6. Show a poison message landing in the DLQ while the system keeps running.

## Sprint 5 retrospective prompts

- What is the real accuracy number? Is it honest, or inflated by easy synthetic incidents?
- Where is the cost concentrated — which agent is most expensive?
- Did hardening reveal any design flaw that should be fixed before Sprint 6?
- Are the headline numbers strong enough to put on a resume as-is?

Continue to `08-SPRINT-6-DEPLOY-AND-POLISH.md`.
