# 02 — Environment & Conventions

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## 2.1 Toolchain to install (do this before Sprint 1 Day 1)

| Tool | Version | Verify with |
|---|---|---|
| Java (JDK) | 21 LTS | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Python | 3.11+ | `python3 --version` |
| Node.js | 20 LTS | `node --version` |
| Docker + Compose | latest | `docker compose version` |
| Ollama | latest | `ollama --version` |
| Git | latest | `git --version` |

After installing Ollama, pull the dev model once: `ollama pull mistral`.

Recommended IDE setup: IntelliJ IDEA for `orchestrator/`, VS Code for `agents/` and `ui/`. One window per service avoids confusion.

## 2.2 The local stack (`docker-compose.yml`)

Sprint 1 Day 1 produces this. It runs everything except the three application services (which run from your IDE for fast iteration).

| Service | Image | Port | Role |
|---|---|---|---|
| postgres | postgres:16 | 5432 | Relational store |
| redis | redis:7 | 6379 | Cache, idempotency |
| kafka | bitnami/kafka (KRaft mode) | 9092 | Event bus |
| prometheus | prom/prometheus | 9090 | Metrics (Sprint 2+) |
| loki | grafana/loki | 3100 | Logs (Sprint 2+) |
| grafana | grafana/grafana | 3000 | Dashboards (Sprint 2+) |
| minio | minio/minio | 9000 | Object store (Sprint 5+) |

Ollama runs natively on the host (not in Compose) so it can use your Mac's GPU.

## 2.3 Environment variables

Each service reads a `.env` file. A `.env.example` is committed; the real `.env` is gitignored.

```
# shared
DATABASE_URL=postgresql://sentinel:sentinel@localhost:5432/sentinel
KAFKA_BOOTSTRAP=localhost:9092
REDIS_URL=redis://localhost:6379

# agents only
LLM_BACKEND=ollama            # ollama | anthropic | groq
OLLAMA_HOST=http://localhost:11434
OLLAMA_MODEL=mistral
ANTHROPIC_API_KEY=            # empty in dev
INCIDENT_TOKEN_BUDGET=20000   # per-incident cap
```

**Rule:** no secret is ever committed. `ANTHROPIC_API_KEY` stays empty until Sprint 5.

## 2.4 Coding conventions

### Java (orchestrator)
- Package root `com.sentinel`. One package per concern (see repo layout).
- Constructor injection only — no field `@Autowired`.
- Records for DTOs and events; classes for entities and services.
- Every external call (Kafka, DB, HTTP) has an explicit timeout.
- Format with the Spring Java Format plugin; no manual style debates.

### Python (agents)
- `app/` package. One module per agent under `app/agents/`.
- Type hints everywhere; `mypy` clean.
- `ruff` for lint + format.
- All I/O is `async`. No blocking calls inside the worker loop.
- Pydantic models for every message in and out.

### General
- Conventional Commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`.
- One ticket = one branch = one pull request, even solo — it builds the habit and gives you a clean history.
- No commented-out code on `main`.

## 2.5 Branch and commit workflow

```
main                  always green; every commit builds and passes tests
  └── feat/S1-D2-...   one branch per ticket, named with sprint+day
```

Even working alone: branch, commit in small steps, open a PR, let CI run, merge. The PR history becomes evidence of disciplined engineering — interviewers look at it.

## 2.6 Testing strategy

Four test levels. Not every ticket needs all four; the day-by-day plan says which apply.

| Level | Scope | Tooling | Speed |
|---|---|---|---|
| Unit | One class/function, no I/O | JUnit 5 / pytest | ms |
| Integration | One service + real deps via Testcontainers | JUnit + Testcontainers / pytest | seconds |
| Contract | Message shapes between services match | Shared JSON schema + validation tests | fast |
| End-to-end | Whole pipeline, alert in → report out | A test script driving the running stack | slow |

**Testcontainers** is important: it spins up real Postgres/Kafka in a container for a test, then tears it down. This means integration tests are real, not mocked, and run anywhere including CI.

### The test pyramid for this project
Aim for many unit tests, a solid layer of integration tests, a few contract tests, and a small number of end-to-end tests. Do not invert it — end-to-end tests are slow and brittle; rely on them only for the critical happy path and one or two failure paths.

### Coverage target
Not a percentage. The rule: **every `MUST` ticket has tests that would fail if the feature broke.** That is the only coverage metric that matters.

## 2.7 How test cases are written in this report

Each day lists test cases in this format:

> **TC-x.y.z** — *Title*
> **Given** (starting state) → **When** (action) → **Then** (expected result)
> *Type:* unit | integration | e2e · *Automated:* yes/no

You implement them as real automated tests wherever "Automated: yes". Manual checks (UI look, demo flow) are marked "Automated: no" and you run them by hand.

## 2.8 Definition of Done (repeated — it matters)

A ticket is done when:
1. Code is written and committed on a feature branch.
2. It compiles / runs with no errors.
3. Its listed test cases are implemented and pass.
4. CI is green on the PR.
5. No previously passing test now fails.
6. Anything non-obvious is documented in code or `/docs`.

If any box is unchecked, the ticket is not done — it is "in progress".

## 2.9 Daily routine (use this every working day)

1. **Start (10 min):** read the day's plan in this report. Pull `main`. Create the day's branch.
2. **Build (core hours):** work the steps in order. Commit in small increments.
3. **Test (before you stop):** implement and run the day's test cases.
4. **Close (15 min):** open the PR, let CI run, merge if green. Write two sentences in a `PROGRESS.md` log — what got done, what is blocked.
5. **Verify the end-of-day goal:** if you cannot demonstrate it, the day is not finished — note exactly where you stopped so tomorrow starts clean.

Continue to `03-SPRINT-1-FOUNDATION.md`.
