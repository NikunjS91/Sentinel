# Day 32 — Rehydration + Reliability Floor

**Sprint 5 · Day 1 · Execution guide**

> Hands-on, execution-ready plan for Sprint 5 Day 1 (overall Day 32).
> Work top to bottom. Hand this file to Claude Code and work one section
> at a time. Prerequisite: Sprint 4 is closed, `sprint-4` tag pushed,
> `main` verified green, PR #32 (metadata parse + worker crash + 300s→600s
> timeout) merged.

---

## Day 32 objective

By the end of today:
1. Any stale state accumulated since the Sprint 4 close is cleared or
   reconciled — Kafka offsets, orphaned incidents, half-finished
   embeddings.
2. **A single incident reaches RESOLVED end-to-end within the deadline,
   in a reproducible way, from a clean start.** This is the day's
   central proof — if we can't get one clean run, we can't calibrate
   anything.
3. Instrumentation is added at three specific points so that when
   Day 33 tunes model choices and timeouts, we have real numbers to
   tune against:
   - Per-agent LLM latency (already partially exposed via
     `agent_traces.latency_ms` — verify it's actually populated).
   - Per-agent timeout events (new counter — how often is a specialist
     hitting its HTTP timeout?).
   - Deadline breach events (new counter — how often is the sweeper
     transitioning to AGGREGATING_PARTIAL because time ran out?).
4. A small `docs/RELIABILITY-BASELINE.md` records today's numbers as
   the baseline against which Sprint 5 improvement will be measured.
5. The screenshot capture from the aborted Day-31.5 attempt succeeds,
   this time — because incidents reliably resolve.

**This is deliberately a modest day.** Sprint 5 opens with a smoke
test, not a feature. The goal is to establish that *the system works
reliably enough to measure*, which is a prerequisite for the eval
harness (Day 35+) to have anything trustworthy to consume.

**Scope note:** No new agents. No new orchestration. No new UI. Just:
clean the slate, prove one incident works, instrument what's already
there, record the baseline. The model ladder proper (fallback from
qwen3:14b to qwen2.5:3b) is Day 33.

---

## Before you start

```bash
docker compose ps                # 8 services — should still be running
                                 # from the Sprint 4 close work, or bring
                                 # them up.
git checkout main && git pull
git log -1 --oneline             # verify sprint-4 tag and PR #32 are both
                                 # actually on main.
git checkout -b feat/S5-D1-rehydrate

# Sanity baseline — Sprint 4 final state
cd orchestrator && ./mvnw verify -q     # ~85 green
cd ../agents && .venv/bin/pytest        # ~60 green
cd ../demo-app && ./mvnw verify -q      # 8 green
cd ..
```

> **If any of those don't pass**, something rotted since the sprint
> tag — investigate before proceeding. The whole point of today's
> rehydration is to catch this kind of drift.

---

## Step 1 — Diagnose current state

Before touching anything, capture what's there. This becomes the "before"
half of the baseline.

```bash
# 1. What incidents exist?
docker compose exec postgres psql -U sentinel -d sentinel -c "
SELECT state, count(*) as count
FROM incidents
GROUP BY state
ORDER BY count DESC;"

# Expected from your Q1 answer: 8 undecided incidents, likely mixed
# states (some resolved, some partial, some possibly stuck mid-pipeline).

# 2. Any incidents stuck?
docker compose exec postgres psql -U sentinel -d sentinel -c "
SELECT id, service, state, created_at, deadline_at,
       (deadline_at < now()) as overdue
FROM incidents
WHERE state NOT IN ('RESOLVED', 'PARTIAL')
ORDER BY created_at DESC;"

# 3. Kafka consumer lag on the orchestrator's aggregator group.
docker compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe \
  --group orchestrator-aggregator 2>&1 | grep -v '^$'

# 4. Any messages sitting in agent.tasks.dlq?
docker compose exec kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic agent.tasks.dlq --from-beginning --max-messages 5 \
  --timeout-ms 5000 2>&1 | head -20
```

**Paste the outputs into a scratch file.** Do not fix anything yet.
Today's first act is *observation*, not intervention.

Interpret:

- If states are mostly `RESOLVED` or `PARTIAL`: system is basically fine,
  just needs a fresh incident to prove the pipeline works.
- If there are incidents stuck in `DISPATCHED` or `AGGREGATING` with
  `overdue = true`: the sweeper is behind, or the sweeper worked but
  crashed. Not a disaster; the Step-2 clear will handle it.
- If Kafka consumer lag is non-zero: messages are queued up. The
  orchestrator may still be catching up from a prior burst.
- If DLQ has messages: something failed to parse. Read them. Do NOT
  ignore.

> **The DLQ is the most important check.** If Day 9's DLQ has messages
> nobody noticed, that's real signal about what breaks in production.
> Save the messages to a file for Day 34 (hardening day) — they're
> primary source material.

---

## Step 2 — Clean the slate deliberately

Now, based on what Step 1 revealed, decide what to clean.

Three levels of cleanup, from least to most aggressive:

### Level 1 — Force-resolve stuck incidents

Do this if you have incidents in intermediate states that are overdue but
haven't been swept. This is *not* deleting them; it's flushing them to
PARTIAL so they exit the working set.

```bash
docker compose exec postgres psql -U sentinel -d sentinel -c "
UPDATE incidents
SET state = 'PARTIAL',
    resolved_at = now()
WHERE state IN ('DISPATCHED', 'AGGREGATING', 'AGGREGATING_PARTIAL', 'SYNTHESIZED_PARTIAL')
  AND deadline_at < now() - interval '10 minutes';"
```

> **Why 10 minutes not 0:** anything within 10 minutes might still be
> processing legitimately (SSE users watching). Anything older is
> genuinely stuck.

### Level 2 — Clear Kafka consumer lag

Do this only if the orchestrator has fallen far behind and you want a
fresh run rather than a catch-up run.

```bash
# Reset the aggregator consumer to end (i.e., ignore all pending messages)
docker compose exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group orchestrator-aggregator \
  --topic agent.results \
  --reset-offsets --to-latest --execute
```

> **This is destructive to any pending work.** Only do this if Step 1
> showed the lag is genuinely stale and you're OK dropping it.

### Level 3 — Full reset (nuclear)

Only if the DB is genuinely a mess and you want a completely fresh state.
Note this destroys your accumulated KB embeddings.

```bash
# Stop the app services first
# Then:
docker compose down
docker compose down -v      # blows away volumes
docker compose up -d
# Wait for orchestrator startup to seed KB again
```

> **You almost certainly don't want Level 3 today.** Just Level 1 is
> usually enough. Reserve Level 3 for genuine "nothing else works."

Pick the level. Do it. Verify:

```bash
docker compose exec postgres psql -U sentinel -d sentinel -c "
SELECT state, count(*) as count
FROM incidents
GROUP BY state
ORDER BY count DESC;"
```

There should be no incidents in intermediate states now.

---

## Step 3 — The reliability smoke test

Now the day's central experiment: **one clean incident, end to end,
within deadline, from a known-clean start.**

Fire an alert deliberately shaped to be a "moderate" case — enough signal
that all agents have something to say, not so pathological that any one
agent takes 300 seconds.

```bash
# 1. Ensure the demo app is in a known state.
curl -s -X POST http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' -d '{"mode":"slow_query"}'

# 2. Generate ~10 slow requests to build a real log/metric signal.
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8090/orders \
    -H 'Content-Type: application/json' \
    -d '{"sku":"SKU-001","quantity":1,"totalUsd":9.99}' >/dev/null
done
sleep 15

# 3. Record the start time.
START_TIME=$(date +%s)
echo "Smoke test started at $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 4. Fire the alert.
INCIDENT_RESPONSE=$(curl -s -X POST http://localhost:8080/alerts \
  -H 'Content-Type: application/json' \
  -d '{
    "source": "prometheus",
    "alertName": "SlowOrders",
    "service": "orders-svc",
    "severity": "warning",
    "fingerprint": "d32-smoke-1",
    "labels": {},
    "annotations": {"summary": "Order latency elevated"}
  }')
INCIDENT_ID=$(echo "$INCIDENT_RESPONSE" | jq -r '.id')
echo "Incident: $INCIDENT_ID"

# 5. Watch it progress.
for i in $(seq 1 30); do
  sleep 30
  STATE=$(docker compose exec -T postgres psql -U sentinel -d sentinel -tA -c \
    "SELECT state FROM incidents WHERE id = '$INCIDENT_ID'")
  ELAPSED=$(( $(date +%s) - START_TIME ))
  echo "  t=${ELAPSED}s  state=$STATE"
  if [ "$STATE" = "RESOLVED" ] || [ "$STATE" = "PARTIAL" ]; then
    break
  fi
done
```

Three possible outcomes:

### Outcome A — Reaches RESOLVED within deadline
Great. This is the day's central result. Move to Step 4.

### Outcome B — Reaches PARTIAL within deadline
One or more agents timed out. Not a failure — the system's error-handling
worked. Note *which* agents:

```bash
docker compose exec postgres psql -U sentinel -d sentinel -c "
SELECT agent_name, status, latency_ms, tokens_used
FROM agent_traces
WHERE incident_id = '$INCIDENT_ID'
ORDER BY agent_name;"
```

Missing rows = timed-out agents. Note them for Day 33's model-ladder
work.

### Outcome C — Still not terminal after 15 minutes
Something is wrong. Diagnose:

```bash
# What state is it in?
docker compose exec postgres psql -U sentinel -d sentinel -c \
  "SELECT * FROM incidents WHERE id = '$INCIDENT_ID' \\gx"

# What traces exist?
docker compose exec postgres psql -U sentinel -d sentinel -c \
  "SELECT agent_name, status, created_at FROM agent_traces
   WHERE incident_id = '$INCIDENT_ID'"

# Is the sweeper running?
docker compose logs orchestrator --tail 100 | grep -i sweeper

# Is Ollama responsive?
curl -s http://localhost:11434/api/tags | jq
```

Typical causes:
- Ollama hung on a specific prompt — restart the Ollama container.
- Aggregator listener crashed — check `orchestrator` logs for
  exceptions.
- Deadline sweeper isn't running — check that `@Scheduled` beans
  are registered.

**If Outcome C happens, today becomes a debugging day, not a
Day-32 day.** That's OK — that's exactly the kind of pre-existing
issue Sprint 5's reliability track was meant to surface. Fix
whatever's broken; document it; consider today's plan half-done.

Reset:

```bash
curl -s -X POST http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' -d '{"mode":"none"}' >/dev/null
```

---

## Step 4 — Instrument what's already there

Now that we have (hopefully) at least one clean run, add measurement.

The goal: three specific counters that Day 33's calibration can act on.

### 4a. Per-agent timeout events

Where: `agents/app/worker.py` (or wherever the HTTP-timeout catch is).

Add a Prometheus counter. If the orchestrator's `micrometer` config is
already exposing metrics via `/actuator/prometheus`, mirror the pattern
on the Python side:

```python
# agents/app/metrics.py (new file)
from prometheus_client import Counter, Histogram

AGENT_TIMEOUTS = Counter(
    "sentinel_agent_timeouts_total",
    "Number of times an agent's LLM call timed out",
    ["agent_name"],
)

AGENT_LLM_LATENCY = Histogram(
    "sentinel_agent_llm_latency_seconds",
    "LLM call latency per agent",
    ["agent_name"],
    buckets=(1, 5, 10, 30, 60, 120, 300, 600),
)
```

Wire into the agent function loop:

```python
# In the worker's _handle method or wherever the LLM call happens
try:
    with AGENT_LLM_LATENCY.labels(agent_name=task.agent_name).time():
        result = await agent_fn(task, ctx)
except asyncio.TimeoutError:
    AGENT_TIMEOUTS.labels(agent_name=task.agent_name).inc()
    # existing error handling
```

Expose via a metrics endpoint in FastAPI (if not already):

```python
# agents/app/main.py
from prometheus_client import make_asgi_app

metrics_app = make_asgi_app()
app.mount("/metrics", metrics_app)
```

> **You may already have this.** Sprint 2's observability day added
> similar wiring. Verify with `curl -s http://localhost:8001/metrics
> | head -20` — if there's a `sentinel_*` prefix already, follow that
> naming convention.

### 4b. Deadline breach counter

Where: `orchestrator/src/main/java/.../DeadlineSweeper.java` (Day 22
work).

```java
@Component
public class DeadlineSweeper {
    private final Counter deadlineBreaches;

    public DeadlineSweeper(MeterRegistry registry) {
        this.deadlineBreaches = Counter.builder("sentinel_deadline_breaches_total")
            .description("Incidents transitioned to PARTIAL due to deadline expiry")
            .register(registry);
    }

    @Scheduled(fixedDelay = 5000)
    public void sweep() {
        List<Incident> overdue = incidents.findOverdueActive();
        for (Incident inc : overdue) {
            deadlineBreaches.increment();
            // existing transition logic
        }
    }
}
```

### 4c. Verify `agent_traces.latency_ms` is actually populated

Sanity check today's smoke-test data:

```bash
docker compose exec postgres psql -U sentinel -d sentinel -c "
SELECT agent_name,
       min(latency_ms) as min_ms,
       max(latency_ms) as max_ms,
       avg(latency_ms)::int as avg_ms,
       count(*) as n
FROM agent_traces
WHERE created_at > now() - interval '1 day'
  AND latency_ms IS NOT NULL
GROUP BY agent_name
ORDER BY avg_ms DESC;"
```

If `latency_ms` is all NULL, the field isn't being written correctly.
Trace back into the agent code — the `AgentResult` constructor should
populate it. Fix now; Day 33 needs this data.

---

## Step 5 — Write down the baseline

Create `docs/RELIABILITY-BASELINE.md`:

```markdown
# Reliability Baseline — Sprint 5 opening

Captured on <date>. This is the reference point against which Sprint 5's
calibration work (Days 33-34) will be measured. Sprint 5 close should
show meaningful improvement on each metric.

## Configuration at time of capture

- LLM backend: Ollama, qwen3:14b (Q4 quant, CPU inference)
- Hardware: <your laptop description — model, RAM, cores>
- Default per-incident deadline: 600s
- Number of specialists: 6 (echo, log_analyzer, metrics, history, topology, runbook)
- Synthesizer: yes

## Smoke test result

Test: single `slow_query` incident (fingerprint `d32-smoke-1`).

- Start-to-terminal wall clock: <fill in from Step 3>
- Terminal state: <RESOLVED | PARTIAL>
- Agents that reported: <list from agent_traces>
- Agents that timed out or missed deadline: <list, or "none">

## Per-agent latency (last 24 hours, if any prior runs exist)

| agent_name | min ms | avg ms | max ms | n |
|-----------|--------|--------|--------|---|
| <fill from Step 4c query>

## Counter snapshots

- `sentinel_agent_timeouts_total`: <values by agent from /metrics>
- `sentinel_deadline_breaches_total`: <value from orchestrator /actuator/prometheus>

## Observations

- <fill in — anything that stood out. e.g. "Synthesizer LLM call averaged
  90s vs specialists at 40s" or "Metrics agent occasionally hits 200s
  when Prometheus query is slow">
- <another observation, honest>

## Sprint 5 improvement targets (aspirational)

- Deadline breaches: <current> → <target>
- p95 incident wall-clock: <current> → <target>
- Specialists timing out: <current> → 0 as an aspirational target

## Method for re-measurement

To reproduce this baseline later:

1. Reset failure_mode to none.
2. Ensure DB has no pending incidents.
3. Trigger `slow_query` mode, generate 10 orders, wait 15s.
4. POST alert with the same shape as `d32-smoke-1`.
5. Watch to terminal state.
6. Query the /metrics endpoints and agent_traces.
```

Fill in what you actually observed. **Be honest about the numbers**,
even if they look bad. Day 33's whole job is to make them look better;
starting from an inflated baseline just makes Day 33 look less
productive.

---

## Step 6 — Capture the deferred screenshots (optional but sensible)

If Step 3 produced a RESOLVED incident cleanly, you now have exactly the
conditions the Day-31.5 screenshot attempt was missing. Run the
Playwright script:

```bash
cd tools/screenshots
node capture.mjs
```

Verify the PNGs. If they look good:

```bash
git add docs/screenshots/*.png
git commit -m "docs: capture UI screenshots for README"
```

If they don't, that's fine too — screenshots aren't Day 32's central
deliverable. Log it as remaining work for later.

---

## Step 7 — Commit today's work

```bash
git add agents/app/metrics.py agents/app/main.py agents/app/worker.py \
        orchestrator/src/main/java/com/sentinel/deadline/DeadlineSweeper.java \
        docs/RELIABILITY-BASELINE.md
git commit -m "feat: reliability baseline + Prometheus counters for agent timeouts and deadline breaches"
git push -u origin feat/S5-D1-rehydrate
```

Open the PR. CI four jobs green. Merge.

**Then verify post-merge `main` CI green** (Day-29 rule, still in effect).

Append to PROGRESS.md:

```markdown
## Sprint 5 — Reliability + Eval Harness

### Day 32 (S5-D1) — Rehydration + reliability floor
- Done: cleared stale state from Sprint 4 work (Level 1 cleanup, N
  incidents force-transitioned to PARTIAL). Smoke test:
  `slow_query` incident reached <RESOLVED|PARTIAL> in <N>s (deadline
  600s). Added prometheus counters for per-agent LLM timeouts and
  deadline breaches. Verified `agent_traces.latency_ms` is populated.
  Baseline captured in docs/RELIABILITY-BASELINE.md.
- Baseline highlights: <2-3 most notable numbers from the doc>
- Screenshots: <captured | still deferred>
- Next: Day 33 — model ladder. qwen3:14b for Synthesizer, qwen2.5:3b
  for specialists. Per-agent HTTP timeouts. Expected to cut p95
  incident time in half.
```

---

## End-of-day goal

- [ ] Step 1 diagnostic snapshot captured (paste-able output)
- [ ] Step 2 cleanup executed at the chosen level (usually Level 1)
- [ ] Step 3 smoke test reached terminal state (RESOLVED preferred,
      PARTIAL acceptable, non-terminal = debugging day)
- [ ] Step 4 instrumentation added and verified via `/metrics` endpoints
- [ ] `docs/RELIABILITY-BASELINE.md` exists with honest numbers
- [ ] Screenshots either captured or explicitly deferred
- [ ] PR merged, post-merge `main` CI verified green

## Test cases

Today's work is instrumentation and observation. There are no new
functional test cases. However:

> **TC-5.1.1** — Deadline breach counter increments when incident is
> swept to PARTIAL
> **Given** an incident with a deadline in the past → **When** the
> DeadlineSweeper fires → **Then** the `sentinel_deadline_breaches_total`
> counter increments by 1.
> *Type:* integration · *Automated:* yes (short test using the existing
> deadline-sweeper test infrastructure)

> **TC-5.1.2** — Agent timeout counter increments when LLM call
> times out
> **Given** a fake LLM that throws asyncio.TimeoutError → **When** the
> agent is invoked → **Then** `sentinel_agent_timeouts_total{agent_name=X}`
> increments by 1.
> *Type:* unit · *Automated:* yes

Add these to keep the discipline of "if it's not tested, it doesn't
count."

---

## How to run today's session with Claude Code

1. Open a terminal in the repo. Run `claude`.
2. Opening instruction:

   > "Read `docs/day-plans/DAY-32-REHYDRATE.md`. This is Sprint 5 Day 1
   > — the reliability floor. Do NOT skip Step 1 (diagnostic snapshot);
   > paste the outputs so I can see them. Step 3 is the day's central
   > experiment — one incident must reach terminal state within the
   > deadline. If it doesn't, today becomes a debugging day. Steps 4-5
   > are instrumentation; keep them small. The Playwright screenshots
   > in Step 6 are opportunistic — do them if Step 3 succeeded, skip
   > if not. Work steps in order. Pause after each."

3. **Read the outputs from Step 1 yourself.** The pattern of stuck
   incidents / consumer lag / DLQ messages is real information about
   what's actually broken. Claude Code will happily gloss over
   surprising outputs if you don't stop it.

4. **Do not let Claude Code adjust the deadline beyond 600s to force
   Step 3 to succeed.** That's cheating. If 600s isn't enough, that's
   Day 33's problem to solve, not today's to paper over.

5. **The baseline numbers in Step 5 are the most important artifact.**
   Sprint 5's whole story ("we measured reliability, we improved it")
   depends on these being honest.

---

## If something goes wrong

- **Step 1's Kafka DLQ query hangs:** the `--timeout-ms` flag should
  make it exit cleanly. If it hangs, the DLQ topic doesn't exist (Day 9
  work may have been rolled back or renamed). List topics with
  `docker compose exec kafka kafka-topics.sh --list --bootstrap-server
  localhost:9092`.

- **Step 3 smoke test hits Outcome C repeatedly:** likely Ollama is
  the choke point. `docker stats` will show which container is CPU-
  bound. If Ollama is pegged at 100% CPU, that's expected on your
  hardware and Day 33's model ladder is the fix. **Do not increase the
  deadline as a workaround today.**

- **`latency_ms` still NULL after Step 4c:** check that the AgentResult
  constructor is being called with the elapsed time, not with None.
  Common bug: the `latency_ms` field is passed by name in some call
  sites and by position in others; one is null-defaulting.

- **Prometheus counter shows up as flat 0:** the counter is being
  registered but never `.inc()`'d. Grep for the counter name in the
  code — it should appear at both the registration site and the
  increment site.

- **Docker compose down -v accidentally destroyed the KB:** re-run
  Day 27's backfill script (`python -m scripts.backfill_embeddings`)
  after re-seeding. Also: Day 32 should generally NOT be doing Level 3
  cleanup, and if you did, note it in the baseline doc so Day 35's
  synthetic corpus work knows the KB is minimal.

- **Screenshots still won't capture cleanly:** don't chase this today.
  It's a Sprint 5 deliverable, not a Sprint 5 blocker. Come back to
  it after Day 33's model ladder makes incidents fast and reliable.

---

## Next

When Day 32 is done and the baseline is captured, ask for
`DAY-33-MODEL-LADDER`. Day 33's central work: swap in `qwen2.5:3b` for
specialist agents while keeping `qwen3:14b` for the Synthesizer, add
per-agent HTTP timeouts, and re-run the smoke test to compare against
today's baseline. Target: p95 incident wall-clock cut in half.

Sprint 5's rhythm is genuinely different from Sprints 1-4. There is
less new code per day and more measurement. Some days will feel like
they produced less because the artifact is a number instead of a
feature. That's the sprint. The eval harness (Days 35-37) is the
payoff.

---

## One honest note about today

If Step 3 produces Outcome C — nothing reaches terminal state — that's
not a failure of the plan. It's the plan doing its job: **surfacing that
Sprint 4 shipped a system that doesn't reliably complete an incident on
your hardware.** The Sprint 4 tests passed against fake LLMs; production
qwen3:14b behavior wasn't part of the test surface.

That's exactly the kind of thing Sprint 5 exists to fix. Don't panic if
today is mostly a debugging day. Note what breaks, document it in the
baseline, and Day 33 becomes even more valuable because the model ladder
is directly solving the problem you spent today diagnosing.

Sprint 5 is about honesty, not velocity.
