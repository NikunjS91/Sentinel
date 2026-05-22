# Sentinel — Project Setup Report

**A complete, day-by-day engineering plan for building Sentinel, an AI agent swarm for incident triage.**

| | |
|---|---|
| **Project** | Sentinel — Incident Triage Swarm |
| **Author** | Nikunj Shetye |
| **Document type** | Project setup & execution report |
| **Scope** | Phase 1 (MVP) — 6 sprints, 12 weeks, ~60 working days |
| **Status** | Planning complete — ready to execute Sprint 1 |

---

## How to read this document

This report is split into linked files so it stays navigable. Read them in order the first time; afterwards, jump straight to the sprint you are working on.

| File | Contents |
|---|---|
| `00-PROJECT-SETUP-REPORT.md` | This file — index, conventions, definitions |
| `01-OVERVIEW-AND-ARCHITECTURE.md` | Vision, architecture, tech stack, repo layout |
| `02-ENVIRONMENT-AND-CONVENTIONS.md` | Toolchain, local setup, coding standards, testing strategy |
| `03-SPRINT-1-FOUNDATION.md` | Sprint 1 — day-by-day, goals, code skeletons, test cases |
| `04-SPRINT-2-DEMO-APP-AND-AGENTS.md` | Sprint 2 — day-by-day |
| `05-SPRINT-3-SYNTHESIS-AND-UI.md` | Sprint 3 — day-by-day |
| `06-SPRINT-4-REMAINING-AGENTS.md` | Sprint 4 — day-by-day |
| `07-SPRINT-5-HARDENING-AND-EVAL.md` | Sprint 5 — day-by-day |
| `08-SPRINT-6-DEPLOY-AND-POLISH.md` | Sprint 6 — day-by-day |
| `09-TEST-STRATEGY-AND-ACCEPTANCE.md` | Cross-sprint test matrix, definition of done, release checklist |

---

## Document conventions

Each sprint file follows the same structure so you always know where to look:

1. **Sprint goal** — one sentence describing the end state.
2. **Sprint acceptance criteria** — what must be true to call the sprint done.
3. **Sprint backlog** — the tickets, sized and ordered.
4. **Day-by-day plan** — for each of the 10 working days:
   - *Objective* — what the day produces.
   - *Steps* — ordered tasks.
   - *Code* — skeleton/snippets for the key pieces.
   - *End-of-day goal* — the concrete, demonstrable result.
   - *Test cases* — what to verify, and how.
5. **Sprint demo script** — how to show the sprint result.
6. **Sprint retrospective prompts** — questions to answer before moving on.

### Sizing and pacing assumptions

- A "day" is **one focused working session of 4–6 hours**, not a calendar day. If you are also job-hunting and studying, a sprint may take 3 calendar weeks instead of 2. That is fine — the *sequence* matters more than the calendar.
- Each sprint has **10 working days**. Days 9–10 are deliberately lighter (integration, testing, demo, buffer) because something always overruns.
- Code in this document is **skeleton + key snippets**, not complete files. It shows structure, signatures, and the non-obvious parts. You fill in the bodies — that is the actual learning.

### Status keywords used in tickets

- `MUST` — sprint fails without it.
- `SHOULD` — strongly expected; defer only with a written reason.
- `STRETCH` — do it if time remains; never at the cost of a `MUST`.

### Definition of Done (applies to every ticket)

A ticket is done when: code is written and committed; it compiles/runs; its test cases pass; it is documented where a future reader would look; and it does not break any previously passing test.

---

## Glossary

| Term | Meaning |
|---|---|
| **Swarm** | A coordinated group of specialist AI agents working in parallel on one incident. |
| **Agent** | A single-purpose worker: a prompt + tools + a narrow job. |
| **Orchestrator** | The Java control-plane service that classifies, dispatches, and aggregates. |
| **Incident** | A unit of work: one problem to be triaged, with its own lifecycle. |
| **Idempotency key** | A unique fingerprint of an alert used to drop duplicates. |
| **State machine** | The explicit lifecycle an incident moves through, persisted in Postgres. |
| **DLQ** | Dead-letter queue — where messages that repeatedly fail are quarantined. |
| **Eval harness** | A test rig that runs the swarm against known incidents and scores it. |
| **Partial result** | A diagnosis produced when one or more agents failed to respond in time. |
| **SSE** | Server-Sent Events — one-way live streaming from server to browser. |

---

## The six-sprint arc at a glance

| Sprint | Theme | End state |
|---|---|---|
| 1 | Foundation | Alert in → dummy agent → result persisted. End-to-end skeleton, no real intelligence. |
| 2 | Demo app + first agents | Realistic telemetry; Log Analyzer + Metrics agents produce real findings. |
| 3 | Synthesis + UI | Synthesizer combines findings; live React dashboard streams the swarm working. |
| 4 | Full swarm | Topology, History, Runbook agents complete the six-agent swarm. |
| 5 | Hardening + eval | Circuit breakers, budgets, evaluation harness, prompt versioning. |
| 6 | Deploy + polish | Kubernetes, CI/CD, documentation, demo video, resume-ready numbers. |

Continue to `01-OVERVIEW-AND-ARCHITECTURE.md`.
