# Reliability Baseline — Sprint 5 Opening

Captured 2026-07-02. This is the reference point against which Sprint 5's calibration work
(Day 33 model ladder) will be measured. Sprint 5 close should show meaningful improvement
on each metric below.

## Configuration at time of capture

| Setting | Value |
|---|---|
| LLM backend | Ollama — `qwen3:14b` (Q4 quant, CPU inference) |
| Hardware | MacBook Pro M-series, 36 GB RAM, no dedicated GPU |
| Per-incident deadline | 600 s |
| Specialists | 6 (echo, log_analyzer, metrics, history, topology, runbook) |
| Synthesizer | yes |
| TOOL_MODE | fixture (Loki/Prometheus calls return canned data instantly) |

## Smoke test result — Day 32

**Scenario:** single `slow_query` incident (fingerprint `d32-smoke-1`), `service=orders-svc`.

- **Outcome: C — did not reach terminal state within 28 minutes of polling (600s deadline)**
- Terminal state reached: never (stuck in `AGGREGATING_PARTIAL` indefinitely)
- Sweeper fired correctly at t≈598s → transitioned to `AGGREGATING_PARTIAL` → dispatched Synthesizer
- Synthesizer never completed (LLM call still running / timed out beyond 600s)

### What agents reported (from `agent_traces`)

| agent_name | status | latency_ms | tokens_used | notes |
|---|---|---|---|---|
| echo | error | 0 | 0 | Unexpected exception — likely asyncpg connection error during Postgres recovery |
| log_analyzer | ok | 0 | 0 | LLM call timed out → returned fallback finding (confidence=0.0) |
| metrics | — | — | — | Did not run (Kafka backlog / agent crash) |
| history | — | — | — | Did not run |
| topology | — | — | — | Did not run |
| runbook | — | — | — | Did not run |
| synthesizer | — | — | — | Dispatched by sweeper but never completed |

`latency_ms=0` for completed agents means the LLM call threw an exception before returning
(no wall-clock latency could be recorded). This is expected: `call_with_retry` returns 0ms
when an exception is raised.

## Counter snapshots

Prometheus instrumentation was added as part of Day 32 (this baseline capture). First values:

- `sentinel_agent_timeouts_total` — counter added today; expected to show non-zero values
  after the next complete smoke test with the new instrumentation deployed
- `sentinel_deadline_breaches_total` — counter added today; 1 breach occurred during smoke test

## Root causes identified

1. **Postgres recovery flaps** — Postgres container intermittently enters recovery mode
   (seen twice today), causing asyncpg connections to fail. Echo agent failed with a DB error.
   This also caused gaps in the state-polling output.

2. **qwen3:14b too slow on CPU** — 30–300+ seconds per LLM call. With 6 specialists + 1
   Synthesizer, the full pipeline cannot complete within 600s on this hardware.
   Log analyzer timed out at the LLM call level; metrics/history/topology/runbook likely
   didn't even start before the sweeper fired.

3. **Single-model architecture blocks** — all 7 LLM calls (6 specialists + Synthesizer) share
   one Ollama process. When one call is running, all others queue behind it. Wall-clock time
   for a full pipeline = sum of all individual LLM latencies, not the maximum.

## Sprint 5 improvement targets (Day 33 model ladder)

| Metric | Baseline (today) | Target (Day 33) |
|---|---|---|
| Deadline breaches per smoke test | 1 (100%) | 0 |
| Specialists completing before deadline | 0–2 of 6 | 5–6 of 6 |
| p95 incident wall-clock | >600s (never completes) | <300s |
| LLM timeouts total | 1+ per smoke test | 0 |
| Outcome | C (never terminal) | A or B |

## Method for re-measurement

To reproduce this baseline after Day 33 changes:

1. Ensure DB has no pending incidents: force-resolve any stuck in intermediate states.
2. Reset Kafka agent.tasks offsets to latest (no stale backlog).
3. Set `failure_mode=slow_query` on demo-app, generate 10 orders, wait 15 s.
4. POST alert with fingerprint `d32-smoke-1` (deduplicated — use a new fingerprint each run).
5. Poll state every 30s until RESOLVED, PARTIAL, or 15-minute timeout.
6. Query `agent_traces` for the incident and compare to table above.
7. Scrape `http://localhost:8001/metrics | grep sentinel_` and
   `http://localhost:8080/actuator/prometheus | grep sentinel_` for counter values.

## Observations

- The system's error-handling worked: Postgres flaps didn't crash the orchestrator, and the
  deadline sweeper correctly transitioned the overdue incident and dispatched the Synthesizer.
  The Synthesizer task itself just never completed.
- DLQ was empty — no poison messages from Sprint 4.
- With TOOL_MODE=fixture, tool calls are instant. The bottleneck is entirely the LLM.
- Day 33's core fix: use `qwen2.5:3b` for the 6 specialist agents (fast, ~5–10s each)
  and keep `qwen3:14b` only for the Synthesizer (1 call, runs after all specialists complete).
  Expected savings: 5 × (40s avg) = 200s saved, bringing total well under the 600s deadline.
