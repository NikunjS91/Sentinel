# 09 — Test Strategy & Acceptance

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

This file is the consolidated testing reference: the overall strategy, the full test-case index, the per-sprint acceptance gates, and the final release checklist. Use it to verify nothing was skipped.

## 9.1 Testing philosophy

Tests exist to let you change code without fear. For a solo, multi-month project that matters even more than on a team — the tests are how *future you* trusts *past you*.

Three rules:
1. **Every `MUST` ticket has at least one test that would fail if the feature broke.**
2. **The pipeline is the gate.** If CI is red, nothing else proceeds. A red `main` is an emergency.
3. **Test the contract, not the implementation.** Tests should survive a refactor; if a small internal change breaks many tests, the tests were too coupled.

## 9.2 The test pyramid for Sentinel

| Level | Count (target shape) | What it proves | Runs in CI |
|---|---|---|---|
| Unit | Many (the broad base) | Pure logic: state machine, parsers, budget math, scoring | Always |
| Integration | A solid middle layer | A service works with real Postgres/Kafka (Testcontainers) | Always |
| Contract | A small set | Message shapes match across the Java/Python boundary | Always |
| End-to-end | A few (critical paths only) | The whole pipeline, alert in → report out | Always (small corpus) |
| Manual | A short checklist | UI feel, demo flow, cluster deploy | By hand, per sprint |

Do not invert the pyramid. End-to-end tests are valuable but slow and brittle; lean on unit and integration tests for coverage, and reserve e2e for the happy path plus the one or two failure paths that matter most.

## 9.3 The Java/Python contract

The two planes communicate through Kafka messages. A schema drift between them is a whole class of bug. Defend against it:

1. Keep the canonical message schemas (`AgentTask`, `AgentResult`, `IncidentEvent`) as JSON Schema files in a shared `contracts/` directory.
2. The Java side validates outgoing/incoming messages against the schema; the Python side validates with Pydantic models generated from or checked against the same schema.
3. A contract test on each side asserts that a sample message validates. If someone changes a field, a contract test fails before an integration test would.

## 9.4 Test-case index

Every test case from the sprint files, in one place. `TC-s.d.n` = sprint s, day d, case n.

### Sprint 1 — Foundation
| ID | Title | Type | Automated |
|---|---|---|---|
| TC-1.1.1 | Stack starts clean | manual | no |
| TC-1.1.2 | CI passes on empty pipeline | manual | no |
| TC-1.2.1 | Migration applies cleanly | integration | yes |
| TC-1.2.2 | Idempotency key is unique | integration | yes |
| TC-1.3.1 | Health endpoint reports UP | integration | yes |
| TC-1.3.2 | All topics provisioned | integration | yes |
| TC-1.4.1 | Valid alert creates an incident | integration | yes |
| TC-1.4.2 | Duplicate alert is deduplicated | integration | yes |
| TC-1.4.3 | Invalid alert is rejected | integration | yes |
| TC-1.4.4 | Ingestion is audited | integration | yes |
| TC-1.5.1 | Legal transition succeeds | unit | yes |
| TC-1.5.2 | Illegal transition is rejected | unit | yes |
| TC-1.5.3 | FAILED reachable from any state | unit | yes |
| TC-1.6.1 | Classification assigns severity | unit | yes |
| TC-1.6.2 | Dispatch publishes a task | integration | yes |
| TC-1.6.3 | State progresses correctly | integration | yes |
| TC-1.7.1 | Agent service health | integration | yes |
| TC-1.7.2 | Worker consumes a task | integration | yes |
| TC-1.7.3 | Malformed task goes to DLQ | integration | yes |
| TC-1.8.1 | Factory selects backend | unit | yes |
| TC-1.8.2 | Echo agent returns a valid result | unit | yes |
| TC-1.8.3 | Ollama integration produces text | integration | yes |
| TC-1.9.1 | Result creates a trace | integration | yes |
| TC-1.9.2 | Incident resolves | integration | yes |
| TC-1.9.3 | Duplicate result is ignored | integration | yes |
| TC-1.10.1 | End-to-end pipeline passes | e2e | yes |
| TC-1.10.2 | CI runs the full suite | manual | no |

### Sprint 2 — Demo App & First Agents
| ID | Title | Type | Automated |
|---|---|---|---|
| TC-2.1.1 | Demo app serves requests | integration | yes |
| TC-2.1.2 | Metrics are exposed | integration | yes |
| TC-2.2.1 | Failure mode is settable | integration | yes |
| TC-2.2.2 | Slow-query mode increases latency | integration | yes |
| TC-2.2.3 | Memory-leak mode grows heap | manual | no |
| TC-2.3.1 | Prometheus scrapes the demo app | manual | no |
| TC-2.3.2 | Loki receives demo-app logs | manual | no |
| TC-2.4.1 | Generator produces requested count | unit | yes |
| TC-2.4.2 | Each incident is internally consistent | unit | yes |
| TC-2.5.1 | Log query returns bounded results | unit | yes |
| TC-2.5.2 | Fixture mode works offline | unit | yes |
| TC-2.6.1 | Metric query returns a summary | unit | yes |
| TC-2.6.2 | SLO violation is detected | unit | yes |
| TC-2.7.1 | Prompts load and version | unit | yes |
| TC-2.7.2 | Same content yields same version | unit | yes |
| TC-2.8.1 | Log Analyzer returns a structured finding | integration | yes |
| TC-2.8.2 | Malformed LLM output is handled | unit | yes |
| TC-2.9.1 | Metrics agent detects SLO breach | integration | yes |
| TC-2.9.2 | Router dispatches to the right agent | unit | yes |
| TC-2.10.1 | Two agents dispatched per incident | integration | yes |
| TC-2.10.2 | Aggregator waits for both | integration | yes |
| TC-2.10.3 | End-to-end with real agents | e2e | yes |

### Sprint 3 — Synthesis & UI
| ID | Title | Type | Automated |
|---|---|---|---|
| TC-3.1.1 | Synthesizer produces a structured report | integration | yes |
| TC-3.1.2 | Conflicting findings are noted | integration | yes |
| TC-3.2.1 | Deadline is set on dispatch | integration | yes |
| TC-3.2.2 | Sweeper finds overdue incidents | integration | yes |
| TC-3.3.1 | Missing agent yields a PARTIAL incident | integration | yes |
| TC-3.3.2 | Partial reports carry lower confidence | integration | yes |
| TC-3.3.3 | Late result after PARTIAL is ignored | integration | yes |
| TC-3.4.1 | SSE emits state changes | integration | yes |
| TC-3.4.2 | SSE survives client disconnect | integration | yes |
| TC-3.5.1 | App routes render | unit | yes |
| TC-3.5.2 | API client handles errors | unit | yes |
| TC-3.6.1 | List renders incidents | unit | yes |
| TC-3.6.2 | Live update on new incident | unit | yes |
| TC-3.7.1 | Agent panel shows running then finished | unit | yes |
| TC-3.7.2 | Final report renders | unit | yes |
| TC-3.8.1 | Approval is persisted and audited | integration | yes |
| TC-3.8.2 | Edited report is saved | integration | yes |
| TC-3.8.3 | UI reflects the decision | unit | yes |
| TC-3.9.1 | Full pipeline reaches a report | e2e | yes |
| TC-3.9.2 | Partial pipeline still reaches a report | e2e | yes |
| TC-3.10.1 | Empty and error states render | unit | yes |

### Sprint 4 — Remaining Agents
| ID | Title | Type | Automated |
|---|---|---|---|
| TC-4.1.1 | Topology tool returns dependencies | unit | yes |
| TC-4.1.2 | Recent deploys are surfaced | unit | yes |
| TC-4.2.1 | Topology agent implicates a recent deploy | integration | yes |
| TC-4.2.2 | Topology agent handles no-dependency case | unit | yes |
| TC-4.3.1 | Embedding round-trip | integration | yes |
| TC-4.3.2 | Similar texts rank above dissimilar ones | integration | yes |
| TC-4.4.1 | Seed loads the expected count | integration | yes |
| TC-4.4.2 | Seeded incidents span all failure types | integration | yes |
| TC-4.5.1 | History agent finds a known precedent | integration | yes |
| TC-4.5.2 | No false precedent for a novel incident | integration | yes |
| TC-4.6.1 | Runbook matching picks the right doc | unit | yes |
| TC-4.6.2 | Low score when nothing matches | unit | yes |
| TC-4.7.1 | Runbook agent proposes steps | integration | yes |
| TC-4.7.2 | Runbook agent admits no match | unit | yes |
| TC-4.8.1 | Five agents dispatched | integration | yes |
| TC-4.8.2 | Partial still works with five agents | integration | yes |
| TC-4.9.1 | Synthesizer integrates five findings | integration | yes |
| TC-4.9.2 | Agreement raises confidence | integration | yes |
| TC-4.10.1 | Full six-agent pipeline | e2e | yes |

### Sprint 5 — Hardening & Evaluation
| ID | Title | Type | Automated |
|---|---|---|---|
| TC-5.1.1 | Retry then succeed | integration | yes |
| TC-5.1.2 | Open breaker fails the incident cleanly | integration | yes |
| TC-5.2.1 | Fallback on primary failure | unit | yes |
| TC-5.2.2 | All-backends-failed surfaces an error | unit | yes |
| TC-5.3.1 | Poison message is quarantined | integration | yes |
| TC-5.3.2 | Retry budget is respected | integration | yes |
| TC-5.4.1 | Per-incident budget enforced | integration | yes |
| TC-5.4.2 | Kill switch halts LLM calls | integration | yes |
| TC-5.5.1 | Cost recorded per call | unit | yes |
| TC-5.5.2 | Cost aggregates correctly | integration | yes |
| TC-5.6.1 | Eval runs end to end | integration | yes |
| TC-5.6.2 | Scoring is correct on a known case | unit | yes |
| TC-5.7.1 | Eval job runs in CI | manual | no |
| TC-5.7.2 | Regression guard trips | manual | no |
| TC-5.8.1 | Production client returns valid completions | integration | yes |
| TC-5.8.2 | Backend swap needs no agent code change | integration | yes |
| TC-5.9.1 | Cross-plane trace exists | integration | yes |
| TC-5.9.2 | Self-metrics are exposed | integration | yes |
| TC-5.10.1 | Eval score attributed to a prompt version | integration | yes |
| TC-5.10.2 | Headline metrics are reproducible | manual | no |

### Sprint 6 — Deploy & Polish
| ID | Title | Type | Automated |
|---|---|---|---|
| TC-6.1.1 | Images build | manual | no |
| TC-6.1.2 | Containers start | manual | no |
| TC-6.2.1 | Manifests validate | manual | no |
| TC-6.2.2 | Every workload has probes | manual | no |
| TC-6.3.1 | All pods reach Ready | manual | no |
| TC-6.3.2 | Smoke test passes on the cluster | e2e | yes |
| TC-6.4.1 | Images published on merge | manual | no |
| TC-6.5.1 | Pipeline is green end to end | manual | no |
| TC-6.6.1 | Getting-started works from clean clone | manual | no |
| TC-6.7.1 | ADRs are complete | manual | no |
| TC-6.8.1 | Video covers the full story | manual | no |
| TC-6.9.1 | Final metrics are reproducible | manual | no |
| TC-6.10.1 | Release is tagged and complete | manual | no |

## 9.5 Per-sprint acceptance gates

Do not start the next sprint until the current sprint's gate is fully met. Each gate is the acceptance-criteria list at the top of that sprint's file. Summary:

| Sprint | Gate — the sprint is done when... |
|---|---|
| 1 | An alert flows end-to-end to `RESOLVED`; e2e test passes; CI green. |
| 2 | A real demo-app failure produces an incident with two real agent findings. |
| 3 | You can watch the swarm work live in the browser and approve the report; partial path works. |
| 4 | All six agents contribute; history and runbook agents add real value. |
| 5 | The swarm is hardened and measurable; you have honest headline numbers. |
| 6 | Deployed on Kubernetes, fully documented, demo video recorded, v1.0.0 tagged. |

## 9.6 Final release checklist (end of Sprint 6)

Tick every box before calling Phase 1 done.

**Functionality**
- [ ] An alert flows from ingestion to a human-approved report.
- [ ] All six agents run in parallel and contribute to the report.
- [ ] Duplicate alerts are deduplicated.
- [ ] A missing agent results in a graceful `PARTIAL` outcome.
- [ ] The kill switch halts all LLM activity.

**Quality**
- [ ] CI runs lint, unit, integration, contract, e2e, and eval — and is green.
- [ ] The eval harness reports honest accuracy, latency, and cost numbers.
- [ ] No `MUST` ticket is unfinished.
- [ ] `main` builds clean from a fresh clone.

**Operability**
- [ ] The full stack deploys to a local Kubernetes cluster.
- [ ] Sentinel's own metrics are visible in Grafana.
- [ ] Every app workload has liveness/readiness probes.

**Documentation**
- [ ] README is accurate; getting-started works verbatim.
- [ ] All nine `/docs` files are current.
- [ ] Seven ADRs explain the major decisions.
- [ ] `LICENSE` and `CONTRIBUTING.md` exist.

**Portfolio**
- [ ] A 3-minute demo video is recorded and linked.
- [ ] The headline metrics are written into the README.
- [ ] The resume bullet is drafted with real numbers.
- [ ] `v1.0.0` is tagged.

## 9.7 A note on honesty

The single most valuable property of this project in an interview is that its claims are *true*. An eval number you can reproduce live, a failure path you can actually demonstrate, a "this part is designed but not built" you can say plainly — these are worth more than a more impressive but shakier story. Build it honestly, measure it honestly, describe it honestly. That is what reads as senior.

[← Back to index](./00-PROJECT-SETUP-REPORT.md)
