# 06 — Sprint 4: Remaining Agents

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## Sprint goal

**The full six-agent swarm is complete.** Topology, History, and Runbook agents join the swarm, and the Synthesizer is upgraded to weigh six inputs and resolve conflicts.

## Sprint acceptance criteria

1. The Topology agent maps an affected service to its dependencies and recent deploys.
2. The History agent finds similar past incidents using vector similarity search.
3. The Runbook agent matches an incident to a documented playbook.
4. The History agent's database is seeded with 50+ synthetic past incidents.
5. The orchestrator dispatches all five specialist agents in parallel.
6. The Synthesizer weighs all inputs and produces a conflict-aware report.
7. All new code is tested; CI stays green.

## Sprint backlog

| ID | Ticket | Priority |
|---|---|---|
| S4-1 | Service topology config + deploy log table | MUST |
| S4-2 | Topology agent | MUST |
| S4-3 | pgvector setup + embedding pipeline | MUST |
| S4-4 | Seed 50+ historical incidents | MUST |
| S4-5 | History agent (vector similarity) | MUST |
| S4-6 | Runbook store + matching tool | MUST |
| S4-7 | Runbook agent | MUST |
| S4-8 | Five-agent parallel dispatch | MUST |
| S4-9 | Synthesizer v2 (six-input, conflict-aware) | MUST |
| S4-10 | Integration + e2e tests, demo | MUST |

---

## Day 1 — Topology data

**Objective:** the system knows the demo app's service graph and recent deploys.

### Steps
1. Create a `service_graph.yaml` describing demo-app services and their dependencies.
2. Add a `deployments` table: `service`, `version`, `deployed_at`.
3. Seed a few fake recent deploys so the Topology agent has something to find.
4. Add a tool `get_topology(service)` returning dependencies + recent deploys for that service.

### Code — topology config (skeleton)
```yaml
services:
  order-api:
    depends_on: [inventory-svc, payment-svc]
  inventory-svc:
    depends_on: [postgres]
  payment-svc:
    depends_on: [external-gateway]
```

### End-of-day goal
`get_topology("order-api")` returns its dependencies and any deploy in the last hour.

### Test cases
> **TC-4.1.1** — Topology tool returns dependencies
> **Given** the service graph loaded → **When** `get_topology("order-api")` runs → **Then** `inventory-svc` and `payment-svc` are returned.
> *Type:* unit · *Automated:* yes

> **TC-4.1.2** — Recent deploys are surfaced
> **Given** a deploy 10 minutes ago → **When** `get_topology` runs for that service → **Then** the recent deploy is included.
> *Type:* unit · *Automated:* yes

---

## Day 2 — Topology agent

**Objective:** an agent that reasons about whether a dependency or a recent deploy explains the incident.

### Steps
1. Create `agents/app/agents/topology_agent.py`.
2. Flow: `get_topology` for the affected service → build a prompt → LLM → structured finding (`suspected_dependency`, `recent_deploy_implicated`, `confidence`).
3. Defensive JSON parse + fallback, as with previous agents.
4. Register it in the task router.

### End-of-day goal
Given an incident on a service with a recent deploy, the Topology agent flags the deploy.

### Test cases
> **TC-4.2.1** — Topology agent implicates a recent deploy
> **Given** an incident on a service deployed 10 minutes ago → **When** the Topology agent runs → **Then** `recent_deploy_implicated` is true.
> *Type:* integration · *Automated:* yes (real Ollama; skipped in CI)

> **TC-4.2.2** — Topology agent handles no-dependency case
> **Given** an incident on a leaf service with no recent deploy → **When** the agent runs → **Then** it returns a low-confidence finding without crashing.
> *Type:* unit · *Automated:* yes (mock LLM)

---

## Day 3 — pgvector and the embedding pipeline

**Objective:** incidents can be embedded as vectors and stored for similarity search.

### Steps
1. Enable the `pgvector` extension; add a migration creating an `incident_embeddings` table with a `vector` column.
2. Choose an embedding approach: a local embedding model via Ollama, or a small sentence-transformer. Keep it swappable like the LLM layer.
3. Implement `embed(text) -> vector`.
4. Implement `find_similar(vector, k)` using a cosine-distance query.

### Code — similarity query (snippet)
```sql
SELECT incident_id, summary, 1 - (embedding <=> :query_vec) AS similarity
FROM incident_embeddings
ORDER BY embedding <=> :query_vec
LIMIT :k;
```

### End-of-day goal
A text can be embedded, stored, and retrieved by similarity.

### Test cases
> **TC-4.3.1** — Embedding round-trip
> **Given** a text → **When** embedded and stored → **Then** `find_similar` with the same text returns it as the top match.
> *Type:* integration · *Automated:* yes (Testcontainers Postgres + pgvector)

> **TC-4.3.2** — Similar texts rank above dissimilar ones
> **Given** three stored incidents → **When** querying with text close to one → **Then** that one ranks first.
> *Type:* integration · *Automated:* yes

---

## Day 4 — Seed historical incidents

**Objective:** the History agent has a realistic corpus to search.

### Steps
1. Extend the synthetic generator to also produce "resolved past incidents" with summaries and root causes.
2. Generate 50+ such incidents across the three failure types.
3. Embed each and load into `incident_embeddings`.
4. Make this a repeatable seed script (`synthetic/seed_history.py`).

### End-of-day goal
The database holds 50+ embedded historical incidents.

### Test cases
> **TC-4.4.1** — Seed loads the expected count
> **Given** the seed script → **When** run → **Then** at least 50 rows exist in `incident_embeddings`.
> *Type:* integration · *Automated:* yes

> **TC-4.4.2** — Seeded incidents span all failure types
> **Given** the seeded corpus → **When** grouped by root cause → **Then** all three failure types are represented.
> *Type:* integration · *Automated:* yes

---

## Day 5 — History agent

**Objective:** an agent that finds similar past incidents and reasons about the precedent.

### Steps
1. Create `agents/app/agents/history_agent.py`.
2. Flow: embed the current incident → `find_similar(k=3)` → build a prompt with the matches → LLM → structured finding (`similar_incidents`, `precedent_cause`, `confidence`).
3. If no sufficiently-similar incident exists (similarity below a threshold), the agent says so honestly — no false precedent.
4. Register in the router.

### End-of-day goal
Given an incident similar to a seeded one, the History agent surfaces the precedent.

### Test cases
> **TC-4.5.1** — History agent finds a known precedent
> **Given** an incident closely matching a seeded memory-leak incident → **When** the History agent runs → **Then** a similar incident is returned with its root cause.
> *Type:* integration · *Automated:* yes (real Ollama; skipped in CI)

> **TC-4.5.2** — No false precedent for a novel incident
> **Given** an incident unlike anything seeded → **When** the History agent runs → **Then** it reports no strong precedent rather than inventing one.
> *Type:* integration · *Automated:* yes

---

## Day 6 — Runbook store and matching tool

**Objective:** runbooks exist as documents and can be matched to an incident.

### Steps
1. Create `runbooks/` with 5–8 markdown runbooks (memory leak, DB timeout, slow query, disk full, etc.). Each has a title, symptoms, and steps.
2. Implement a matching tool: embed runbook symptoms, match against the incident (reuse the embedding pipeline), or use keyword matching as a simpler first cut.
3. `match_runbook(incident) -> (runbook, score)`.

### End-of-day goal
`match_runbook` returns the most relevant runbook for an incident with a confidence score.

### Test cases
> **TC-4.6.1** — Runbook matching picks the right doc
> **Given** a memory-leak incident → **When** `match_runbook` runs → **Then** the memory-leak runbook is the top match.
> *Type:* unit · *Automated:* yes

> **TC-4.6.2** — Low score when nothing matches
> **Given** an incident unlike any runbook → **When** `match_runbook` runs → **Then** the top score is low.
> *Type:* unit · *Automated:* yes

---

## Day 7 — Runbook agent

**Objective:** an agent that proposes runbook steps for the incident.

### Steps
1. Create `agents/app/agents/runbook_agent.py`.
2. Flow: `match_runbook` → if a good match, build a prompt with the runbook steps → LLM → structured finding (`matched_runbook`, `proposed_steps`, `confidence`).
3. If no good match, return a finding that says so.
4. Register in the router.

### End-of-day goal
Given a recognizable incident, the Runbook agent proposes concrete remediation steps.

### Test cases
> **TC-4.7.1** — Runbook agent proposes steps
> **Given** a DB-timeout incident with a matching runbook → **When** the agent runs → **Then** `proposed_steps` is non-empty and references the matched runbook.
> *Type:* integration · *Automated:* yes (real Ollama; skipped in CI)

> **TC-4.7.2** — Runbook agent admits no match
> **Given** an unmatched incident → **When** the agent runs → **Then** it reports no applicable runbook.
> *Type:* unit · *Automated:* yes

---

## Day 8 — Five-agent parallel dispatch

**Objective:** the orchestrator dispatches all five specialists in parallel for every incident.

### Steps
1. Update the dispatcher to publish five `agent.tasks` messages (log, metrics, topology, history, runbook).
2. Update the aggregator to expect five results, tracking each agent.
3. Confirm the deadline + partial-result logic still holds with five agents (e.g. four return, one times out → partial).
4. Update the SSE events so the UI shows five agent panels.

### End-of-day goal
An incident dispatches five agents in parallel; the aggregator correctly waits for all five (or the deadline).

### Test cases
> **TC-4.8.1** — Five agents dispatched
> **Given** an incident reaching `DISPATCHED` → **When** the dispatcher runs → **Then** five `agent.tasks` messages exist.
> *Type:* integration · *Automated:* yes

> **TC-4.8.2** — Partial still works with five agents
> **Given** four of five agents responding → **When** the deadline passes → **Then** the incident completes as `PARTIAL` with one agent flagged missing.
> *Type:* integration · *Automated:* yes

---

## Day 9 — Synthesizer v2

**Objective:** the Synthesizer weighs six inputs, resolves conflicts, and produces a confidence-calibrated report.

### Steps
1. Upgrade the Synthesizer prompt to handle five specialist findings.
2. Add explicit instructions for conflict resolution: when agents disagree, weigh by each agent's confidence and explain the call.
3. Calibrate confidence: more agreeing agents → higher confidence; missing agents → lower.
4. Output adds `agent_agreement` (a measure of how aligned the inputs were).

### End-of-day goal
With five real findings, the Synthesizer produces a coherent, conflict-aware report.

### Test cases
> **TC-4.9.1** — Synthesizer integrates five findings
> **Given** five specialist findings → **When** the Synthesizer runs → **Then** the report references evidence traceable to multiple agents.
> *Type:* integration · *Automated:* yes (real Ollama; skipped in CI)

> **TC-4.9.2** — Agreement raises confidence
> **Given** five findings that all agree vs five that conflict → **When** both are synthesized → **Then** the agreeing set yields the higher confidence.
> *Type:* integration · *Automated:* yes

---

## Day 10 — Integration, e2e, demo

**Objective:** the full six-agent swarm is proven end to end; the sprint is demoable.

### Steps
1. Write an e2e test: trigger an incident similar to a seeded one → assert all five findings recorded → assert the report cites the historical precedent and a runbook.
2. Confirm the UI shows all five agent panels plus the Synthesizer.
3. Run the demo; retrospective.

### End-of-day goal — **Sprint 4 complete**
An incident that resembles a past one is recognized; a runbook is auto-proposed; all six agents contribute.

### Test cases
> **TC-4.10.1** — Full six-agent pipeline
> **Given** the stack and an alert resembling a seeded incident → **When** the pipeline runs → **Then** five `agent_traces` rows exist and the report references both history and a runbook.
> *Type:* e2e · *Automated:* yes

---

## Sprint 4 demo script

1. Trigger an incident that resembles one already in the historical corpus.
2. Open the detail page — show all five agent panels populating.
3. Highlight the History agent's panel: "we have seen this before."
4. Highlight the Runbook agent's panel: the proposed steps.
5. Show the Synthesizer's report weaving all five together with a confidence score.

## Sprint 4 retrospective prompts

- Are five LLM calls per incident too slow? Does the deadline need tuning?
- Is the History agent genuinely useful, or are its matches superficial?
- Do the agents ever contradict each other in ways the Synthesizer mishandles?
- What is the riskiest unknown going into Sprint 5 (hardening + eval)?

Continue to `07-SPRINT-5-HARDENING-AND-EVAL.md`.
