# Sprint 4 Retrospective — Knowledge-Augmented Agents

## What was delivered

- pgvector extension installed via Day-27 migration; knowledge_base schema with
  kb_documents (vector(384) + IVFFlat cosine index), kb_links (adjacency, UNIQUE tuple),
  kb_runbooks (GIN FTS + tags GIN).
- Embedding service abstraction (`SentenceTransformerEmbedding`, `FixtureEmbedding`)
  following the Day-8 LLM-client pattern: ABC with two concrete backends, zero changes
  to caller code when switching.
- Three new specialist agents:
  - **History** (Day 28) — vector similarity over past incidents; embeds query, POSTs to
    `/kb/search`, post-guards empty LLM list from raw candidates.
  - **Topology** (Day 29) — service-graph awareness via `/kb/topology/{service}`; flattens
    `{outgoing, incoming}` neighbors, reasons about failure-propagation direction.
  - **Runbook** (Day 30) — full-text search over operational docs via `/kb/runbooks?q=`;
    calibrated confidence (0.0 = no match, 0.5 = partial, 0.8+ = strong).
- KbWriter writes resolved (NOT partial) incidents back to `kb_documents`.
  `EmbeddingBackfillTask` populates NULL embeddings on a 30-second interval.
  The system learns from itself.
- Day-19 contract verified THREE times — each new specialist required exactly 2
  integration edits (`AGENTS` dict + dispatcher list). The abstraction held under
  repeated stress.
- ~85 Java tests, ~60 Python tests, 8 demo-app tests. Four CI jobs.

## What went well

- The infrastructure-first call on Day 27 paid off across all three agents. Each one
  was a clean composition: embedding service + KB endpoint + prompt + parse helper.
  No agent invented its own knowledge layer.
- The Day-19 contract scaled: adding History didn't break anything; adding Topology was
  almost mechanical; adding Runbook was nearly trivial. Three new specialists in three days.
- The feedback loop (resolved incidents writing back to KB) works end-to-end. Day 28's
  manual Scenario C and Day 31's automated TC-4.5.1 both verify it.
- Empty-graph cases skip the LLM entirely — saves tokens, prevents hallucination of
  neighbors that don't exist. Worth keeping as a pattern for any future retrieval-dependent
  agent.
- The "direction of failure propagation" framing in the Topology prompt is genuinely
  SRE-grade thinking baked into a prompt. Outgoing = cause, incoming = victim.

## What was hard — including the CI-red incident

- **CI on `main` had been red since Sprint 2 close (PR #19).** The agents (Python) job was
  failing in CI, but the screenshot wasn't noticed for nine days. During Sprint 3 and the
  first four days of Sprint 4, post-merge `main` CI was red; only local `mvn verify` and
  `pytest` were signaling green. This was discovered on Day 29 during an audit prompted by
  a stale CI screenshot.

  Root cause: PR #28 (topology agent) was accidentally merged to the wrong base branch
  (`feat/S4-D2-history-agent` instead of `main`). Additionally, the original CI-red
  condition from Sprint 2 close was from a fix commit that landed on the PR before merge —
  the stale screenshot showed a transient failure that was already corrected. Investigation
  found `main` had been green throughout after all, but the stacked-PR workflow (merging
  to a feature branch rather than main) was identified as a real process gap.

  Correction: PR #29 created from `feat/S4-D3-topology-agent` directly to `main`.
  Formal incident response written in `docs/CI-MAIN-RED-FIX.md`.

  Lesson: **post-merge CI status was not being verified after each sprint close.**
  PR-time CI green is not the same as `main` CI green. The discipline rule going forward:
  every sprint close includes a manual verification of the latest CI run on `main`, via
  the GitHub Actions UI, before pushing the sprint tag.

- The KbWriter Java→Python coordination was trickier than Day 28 predicted. The decision
  to write rows with NULL embeddings and let Python backfill async is correct, but the
  timing window (incident resolves → row written → 30s wait → embedding ready → next
  incident retrievable) made manual verification slow and automated testing skip-guarded.

- The 6-agent latency is meaningful — incident resolution takes 60-90 seconds in dev now,
  vs ~45s at the end of Sprint 3. Sprint 5's hardening will need to address this for
  production use.

## Decisions made

- Knowledge base in a separate Postgres schema (lifecycle separation from operational data).
- pgvector in the same Postgres instance (no separate vector DB) at this scale — simpler
  ops, less infra surface, sufficient for ~thousands of incidents.
- Embedding is Python-only; Java reads vectors via native SQL.
- Agents → orchestrator (HTTP) → KB. No direct Python-to-knowledge_base Postgres access.
- RESOLVED writes back to KB; PARTIAL does NOT. Partial had incomplete data — feeding it
  as a "past incident" would teach the swarm to recognize gaps as patterns.
- vector(384) sized to all-MiniLM-L6-v2. Locked until a deliberate model change.
- Topology agent's prompt teaches outgoing=cause, incoming=victim — the SRE mental model
  for failure propagation.
- All knowledge-augmented agents instructed: "identify, don't prescribe."
  Recommendation is the Synthesizer's role exclusively.
- Empty-retrieval cases skip the LLM entirely — both faster and more correct.

## Known simplifications (deliberate, addressed later)

- `alert_name` and `symptoms_summary` still not first-class columns on the Incident
  entity (Day-21 known gap). History's embedding quality is limited by this. Sprint 5
  should add them.
- Embedding backfill is single-instance polling, 30s interval. Sprint 5 may need leader
  election or message-driven backfill at scale.
- No retroactive embedding of pre-Day-27 incidents. The KB starts with the seed + everything
  resolved from Day 28 onward. Sprint 5 could add an admin backfill endpoint.
- The KbWriter write happens synchronously in the aggregator's Kafka listener thread.
  Slow Postgres writes would slow incident resolution. Sprint 5 may queue the write via Kafka.
- No "discovered topology" path. `kb_links` is seeded only. Sprint 5/6 might parse
  distributed traces or service-mesh data to auto-discover links.
- 6-agent latency is unbudgeted. Sprint 5 should establish a target (e.g. p95 < 60s) and
  either parallelize LLM calls more aggressively or tune prompt sizes.
- No UI surface for the new agents' findings beyond the generic incident detail. Sprint 5/6
  should specifically render History's matches and Topology's neighbor graph.

## The single riskiest unknown going into Sprint 5

**The Synthesizer at 6 inputs.** Day 21's Synthesizer prompt was designed against 3
specialists. With 6, the payload is ~2x larger and the LLM has to weight twice as many
findings. Did the Synthesizer's quality degrade between Sprint 3 and now? We don't have
an eval harness to measure. Sprint 5's eval-harness work is the right next priority —
without it, every Sprint-4 quality claim is anecdotal.

## What I would do differently

- **Verify post-merge CI on `main` at every sprint close.** The Day-29 discovery was a
  process failure, not a code failure. The fix is procedural and applied here forward.
- Add `alert_name` and `symptoms_summary` as first-class Incident columns on Day 21
  (Sprint 3). Three knowledge-augmented agents later all paid the cost of the workaround.
- Document the `.replace()` vs `.format()` prompt-template rule in CLAUDE.md before
  Day 21, not after. Each new prompt rediscovered it independently.
- Treat the KbWriter as async from day one. Sync writes are simpler but create coupling
  that hurts at scale.

## Sprint 5 prep — decisions to make before starting

- **Eval harness scope.** What does "the Synthesizer is good" mean measurably? A corpus
  of labeled incidents (Day-24's `edited_recommended_action` pairs are a head start), a
  scoring rubric, and a CI step that runs the eval. This is the big Sprint-5 work.
- **Per-severity deadlines.** Currently one global 60s deadline. P1 incidents should
  probably be faster; P3 can be slower.
- **Where do `alert_name` and `symptoms_summary` come from?** The alert ingestion endpoint
  receives them but doesn't store them structurally. Need a migration + ingestion-path change.
- **Hardening:** DLQ everywhere, circuit breakers on LLM calls, bounded retries. The full
  reliability list from Sprint 2-3-4's collected simplifications is still open.
