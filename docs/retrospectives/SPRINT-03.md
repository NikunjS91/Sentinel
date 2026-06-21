# Sprint 3 Retrospective — Synthesis, Deadlines, UI

## What was delivered

- The Synthesizer — the meta-agent that combines three specialists' findings, names the asymmetry from Sprint 2 ("dissent notes" surface explicit disagreements), and weights confidence honestly.
- Two-stage dispatch (orchestrator → specialists → Synthesizer with their findings in payload), with clean state separation. Synthesizer is not in `expected_agents` — a deliberate meta-agent vs specialist distinction.
- Per-incident deadlines + the PARTIAL terminal state. Three new intermediate states (AGGREGATING_PARTIAL, SYNTHESIZED_PARTIAL, PARTIAL) to preserve audit-log lineage between complete and partial flows.
- The DeadlineSweeper — `@Scheduled`, idempotent, single-instance. Picks up overdue DISPATCHED incidents and forces them through the partial path.
- Live React dashboard: SSE stream, REST hydration, dark monospace theme, no UI library (intentional). Filter bar + cursor pagination + URL sync. Decision=undecided is the SRE's working queue.
- Accept/Reject/Edit human-in-the-loop endpoints. Edit preserves AI originals alongside human edits — labeled training data starting Day 24.
- Two Sprint-3 e2e tests: TC-3.6.1 (full pipeline ending in human accept) and TC-3.6.2 (deadline path with one specialist, PARTIAL, human rejection).
- ~73 Java tests, 42 Python tests, 8 demo-app tests. Four CI jobs.

## What went well

- The Day-19 dispatch refactor paid off exactly as expected when the Synthesizer joined: edit one line in the AGENTS dict (Python), edit one place in the dispatcher (Java). The "meta-agent vs specialist" distinction was the only honest exception.
- The Day-22 three-state design (AGGREGATING_PARTIAL, SYNTHESIZED_PARTIAL, PARTIAL) cost nothing to write but provides Sprint 5's eval harness with a clean signal distinguishing deadline-driven flows from complete runs.
- The Day-24 "preserve AI original + store human edit separately" decision means every operator action since Day 24 is generating labeled training data. Six weeks of training data by the time Sprint 5's eval harness ships.
- The "no UI library" choice held all five UI days. The whole frontend is one App.tsx, one useIncidentStream hook, one CSS file. Easy to reason about; easy to extend.
- Cursor pagination + URL sync landed cleaner than expected. `replaceState` not `pushState` keeps one history entry per filter state, not per keystroke. Permissive SSE merge (state/service checked at event level, decision/q deferred to REST refresh) keeps the logic simple.

## What was hard

- Java 26 / Mockito incompatibility — the StateMachineUnitTest gap documented in KNOWN-ENVIRONMENT-QUIRKS.md. Workaround: run those tests with Java 21. CI is the source of truth.
- The double-publish on terminal SSE events (state machine fires with null report, aggregator fires with populated report). React merge logic handles it; documented in PROGRESS.md. Sprint 5 may refactor.
- `@OneToOne` addition on Incident for the filter join (Day 25) caused test cleanup ripples. Same lesson as Day 19 — adding a relationship late hurts more than adding it upfront.
- `getAlertName()` not existing on Incident (Day 21 catch). Used `getSource()` as a stand-in. Sprint 4 should add `alert_name` as a real column for Topology/History queries.
- `Map.of()` rejecting null values — discovered in TC-3.6.2 when trying to send `root_cause: null` in a synthesizer result. Replaced with empty string.
- Apache HttpClient5 missing for `TestRestTemplate` PATCH support (Day 24). Added test dependency — one-line fix, but hard to diagnose from the error message alone.

## Decisions made

- Synthesizer is registered in AGENTS but dispatched separately, not via `expected_agents`. Meta-agents differ from specialists.
- Three new states for the partial flow, not one. Audit-log legibility wins.
- Same `incidents.synthesized` topic for both RESOLVED and PARTIAL. State is the signal.
- Edit preserves the AI's original AND stores the human edit (labeled data). `edited_*` columns, not overwrites.
- No optimistic UI updates — SSE round-trip is the source of truth.
- No router library. URLSearchParams + replaceState.
- Permissive SSE merge — state/service checked at the event level, decision/q deferred to next REST refresh.

## Known simplifications (deliberate, addressed later)

- One human decision per report. Reversal/amendment is Sprint 5/6.
- No authentication — all operators look the same. Sprint 6.
- No `alert_name` column (Day 21 known gap). Sprint 4.
- Sweeper is single-instance. Sprint 5 adds leader election.
- No deadline on the Synthesizer's own LLM call beyond the agent's HTTP timeout. Sprint 5.
- No DLQ for unparseable agent.results messages (Day 9 still open). Sprint 5.
- Cursor pagination one-way only (newer → older). Sprint 6 if needed.
- No UI testing infrastructure. Sprint 6.
- No real-time concurrent-edit conflict resolution. Sprint 6 with auth.

## The single riskiest unknown going into Sprint 4

pgvector for incident similarity. Day 21 sketched it; Sprint 4's History agent will actually use it. The risk: embedding quality matters more than retrieval mechanics, and there is no eval harness yet to measure embedding quality. Sprint 5's eval harness will catch this — but for the four Sprint-4 days where History is being built, prompt-and-eyeball is the only tool.

## What I would do differently

- Add the `@OneToOne(report)` relationship to Incident on Day 9, not Day 25. Late adds are more painful than early ones.
- Add `alert_name` as an `Incident` column on Day 4 (ingestion). Day 21 wanted it; Sprint 4 will need it; Day 4 should have had it.
- Document the `.format()` vs `.replace()` prompt lesson in CLAUDE.md earlier. Day 21's metrics agent re-discovered it; that's wasted time.
- The deadline test (TC-3.6.2) writes an Incident directly. A cleaner approach would use a Spring test profile with `default-deadline-seconds: 1`. Pick a profile-based approach in Sprint 4.

## Sprint 4 prep — one decision to make before starting

Topology/History/Runbook agents are all specialists by the Day-19 contract (two-edit additions each). The order of building them matters less than: do we build them in three days (one each) or four (with a "shared knowledge base" infrastructure day first)? History will use pgvector; Topology needs a graph store or simple adjacency table; Runbook needs file storage. They all share a pattern. Decision: invest one day in shared infrastructure first, or build incrementally.
