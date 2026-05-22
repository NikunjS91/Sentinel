# Day 1 — Project Setup & Skeleton

**Sprint 1 · Day 1 · Execution guide**

> This is the hands-on, execution-ready version of Sprint 1 Day 1. Work it top to
> bottom. Every folder, file, and command is listed in order. Hand this file to
> Claude Code and work one section at a time — do not skip ahead.

---

## Day 1 objective

By the end of today:
1. The full monorepo folder structure exists and is committed to GitHub.
2. `CLAUDE.md`, `.gitignore`, and `.env.example` are in place.
3. `docker-compose.yml` brings up Postgres, Redis, and Kafka — all healthy.
4. A placeholder CI pipeline runs green on GitHub.

**No application code is written today.** Today is pure scaffolding. Resist the urge to start the Spring Boot app — that is Day 3.

---

## Before you start — prerequisites check

Run each command. If any fails, stop and install that tool before continuing.

```bash
java -version            # expect 21.x
mvn -version             # expect 3.9+
python3 --version        # expect 3.11+
node --version           # expect 20.x
docker compose version   # expect a version line
ollama --version         # expect a version line
git --version            # expect a version line
claude --version         # Claude Code installed
```

Then pull the dev model once (it is large; do this while reading on):

```bash
ollama pull mistral
```

---

## Step 1 — Initialize the repository

You said you have a code folder ready. Open a terminal in it.

```bash
# inside your existing code folder
git init
git branch -M main
```

If you have not yet created the GitHub repo: create an empty repo named
`sentinel` on GitHub (no README, no .gitignore — you are adding those yourself),
then:

```bash
git remote add origin https://github.com/NikunjS91/sentinel.git
```

---

## Step 2 — Create the folder structure

This is the complete monorepo layout. Create every folder now, even the ones not
used until later sprints — a stable structure from Day 1 prevents churn later.

```
sentinel/
├── CLAUDE.md                       # Claude Code project guide (Step 4)
├── README.md                       # already created — copy it in
├── .gitignore                      # Step 3
├── .env.example                    # Step 5
├── docker-compose.yml              # Step 6
│
├── .github/
│   └── workflows/
│       └── ci.yml                  # Step 7
│
├── docs/                           # the 10 planning docs — copy them in
│   ├── 00-PROJECT-SETUP-REPORT.md
│   ├── 01-OVERVIEW-AND-ARCHITECTURE.md
│   ├── 02-ENVIRONMENT-AND-CONVENTIONS.md
│   ├── 03-SPRINT-1-FOUNDATION.md
│   ├── 04-SPRINT-2-DEMO-APP-AND-AGENTS.md
│   ├── 05-SPRINT-3-SYNTHESIS-AND-UI.md
│   ├── 06-SPRINT-4-REMAINING-AGENTS.md
│   ├── 07-SPRINT-5-HARDENING-AND-EVAL.md
│   ├── 08-SPRINT-6-DEPLOY-AND-POLISH.md
│   ├── 09-TEST-STRATEGY-AND-ACCEPTANCE.md
│   └── day-plans/
│       └── DAY-01-SETUP.md          # this file
│
├── orchestrator/                   # Spring Boot (Java) — control plane
│   └── src/
│       ├── main/
│       │   ├── java/com/sentinel/
│       │   │   ├── ingest/         # alert intake, dedupe
│       │   │   ├── incident/       # state machine, entities
│       │   │   ├── dispatch/       # task fan-out
│       │   │   ├── aggregate/      # result fan-in
│       │   │   ├── budget/         # cost governor
│       │   │   ├── stream/         # SSE endpoints
│       │   │   └── config/         # Kafka, DB config
│       │   └── resources/
│       │       └── db/migration/   # Flyway SQL migrations
│       └── test/java/com/sentinel/
│
├── agents/                         # FastAPI (Python) — agent plane
│   └── app/
│       ├── llm/                    # LLM gateway, model ladder
│       ├── tools/                  # PromQL, LogQL, git, vector tools
│       ├── agents/                 # one module per agent
│       ├── prompts/                # versioned prompt files
│       └── tests/
│
├── ui/                             # React + Vite — dashboard
│   └── src/
│
├── demo-app/                       # Sprint 2 — the breakable service
│
├── synthetic/                      # Sprint 2 — incident generator
│
├── eval/                           # Sprint 5 — evaluation harness
│
├── contracts/                      # shared JSON Schema for messages
│
├── runbooks/                       # Sprint 4 — runbook markdown files
│
└── infra/
    ├── k8s/                        # Sprint 6 — Kubernetes manifests
    └── grafana/                    # Grafana dashboards
```

### Command to create it all at once

```bash
mkdir -p .github/workflows \
  docs/day-plans \
  orchestrator/src/main/java/com/sentinel/{ingest,incident,dispatch,aggregate,budget,stream,config} \
  orchestrator/src/main/resources/db/migration \
  orchestrator/src/test/java/com/sentinel \
  agents/app/{llm,tools,agents,prompts,tests} \
  ui/src \
  demo-app synthetic eval contracts runbooks \
  infra/k8s infra/grafana

# Git ignores empty folders — add a .gitkeep so the structure is committed
find . -type d -empty -not -path './.git/*' -exec touch {}/.gitkeep \;
```

### Then copy in the documents you already have
- Put your finished `README.md` at the repo root.
- Put the 10 planning docs into `docs/`.
- Put this file at `docs/day-plans/DAY-01-SETUP.md`.

---

## Step 3 — Create `.gitignore`

Create `.gitignore` at the repo root with this content:

```gitignore
# --- Secrets — never commit ---
.env
*.env
!.env.example

# --- Java / Maven ---
target/
*.class
*.jar
!.mvn/wrapper/maven-wrapper.jar

# --- Python ---
__pycache__/
*.py[cod]
.venv/
venv/
.mypy_cache/
.ruff_cache/
.pytest_cache/
*.egg-info/

# --- Node / React ---
node_modules/
dist/
.vite/

# --- IDEs ---
.idea/
.vscode/
*.iml

# --- OS ---
.DS_Store
Thumbs.db

# --- Project ---
PROGRESS.md.bak
*.log
```

> Note: `PROGRESS.md` itself IS committed (it is your sprint log). Only backups
> and logs are ignored.

---

## Step 4 — Create `CLAUDE.md`

This file is read automatically by Claude Code at the start of every session.
Create `CLAUDE.md` at the repo root with this content:

```markdown
# Sentinel — Claude Code Project Guide

Sentinel is a multi-agent incident-triage system. When something breaks in a
software system, a swarm of specialist AI agents investigates in parallel and
produces a unified diagnosis.

## Read first
The full plan lives in `/docs`. Start with `/docs/00-PROJECT-SETUP-REPORT.md`.
Each sprint has a day-by-day file. Day plans are in `/docs/day-plans/`.

## Current position
- Sprint: 1 (Foundation)
- The day-by-day plan: `/docs/03-SPRINT-1-FOUNDATION.md`
- Do NOT work ahead of the current day. One day at a time.

## Architecture (three planes)
- `orchestrator/` — Spring Boot, Java 21. The control plane: ingestion,
  classification, dispatch, aggregation, state machine.
- `agents/` — FastAPI, Python 3.11. The agent plane: the LLM agents,
  the LLM gateway, the tool layer.
- `ui/` — React + Vite + Tailwind. The live dashboard.
- Kafka connects the planes. Postgres is the source of truth.

## Coding rules
### Java (orchestrator)
- Constructor injection only — never field @Autowired.
- Records for DTOs and events; classes for entities and services.
- Every external call (DB, Kafka, HTTP) has an explicit timeout.

### Python (agents)
- Async everywhere. No blocking calls in the worker loop.
- Type hints on everything; code must be `mypy` clean.
- `ruff` for lint and format.
- Pydantic models for every message in and out.

### General
- Conventional Commits: feat:, fix:, test:, docs:, chore:, refactor:.
- One ticket = one branch = one pull request.
- Branch naming: feat/S1-D2-short-description.
- No commented-out code on main. No secrets in code, ever.

## Definition of Done
A ticket is done only when: code is committed; it builds/runs; its test
cases pass; CI is green; no previously passing test now fails.

## Verify before claiming a task is done
- orchestrator: `cd orchestrator && mvn verify`
- agents: `cd agents && ruff check . && mypy . && pytest`
- Always run the relevant verify command before saying a task is complete.

## What NOT to do
- Do not run destructive commands (rm -rf, dropping databases) without asking.
- Do not modify or read the real `.env` file.
- Do not work on future sprints or future days.
- Do not add dependencies without noting why in the commit message.
```

---

## Step 5 — Create `.env.example`

Create `.env.example` at the repo root:

```bash
# Shared
DATABASE_URL=postgresql://sentinel:sentinel@localhost:5432/sentinel
KAFKA_BOOTSTRAP=localhost:9092
REDIS_URL=redis://localhost:6379

# Agents only
LLM_BACKEND=ollama
OLLAMA_HOST=http://localhost:11434
OLLAMA_MODEL=mistral
ANTHROPIC_API_KEY=
INCIDENT_TOKEN_BUDGET=20000
TOOL_MODE=live
```

Then create your real local file — this one is gitignored:

```bash
cp .env.example .env
```

Leave `.env` as-is for now; `ANTHROPIC_API_KEY` stays empty until Sprint 5.

---

## Step 6 — Create `docker-compose.yml`

This runs the infrastructure. The three app services run from your IDE, not here.
Create `docker-compose.yml` at the repo root:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: sentinel
      POSTGRES_PASSWORD: sentinel
      POSTGRES_DB: sentinel
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U sentinel"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis:
    image: redis:7
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  kafka:
    image: bitnami/kafka:3.7
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_NODE_ID: "0"
      KAFKA_CFG_PROCESS_ROLES: "controller,broker"
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: "0@kafka:9093"
      KAFKA_CFG_LISTENERS: "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_CFG_ADVERTISED_LISTENERS: "PLAINTEXT://localhost:9092"
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      ALLOW_PLAINTEXT_LISTENER: "yes"
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
```

### Bring it up and verify

```bash
docker compose up -d
docker compose ps        # all three should show "healthy" after ~30s
```

If a service is not healthy, check logs: `docker compose logs kafka`.

---

## Step 7 — Create the placeholder CI pipeline

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  placeholder:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Placeholder
        run: echo "CI pipeline scaffold — real jobs added in Sprint 1 Day 10"
```

> Real build/test jobs are added on Day 10. Today only proves the pipeline runs.

---

## Step 8 — First commit and push

```bash
git add .
git commit -m "chore: initial monorepo structure, infra, and project docs"
git push -u origin main
```

Then open the repo on GitHub and confirm the CI run on the Actions tab is green.

---

## Step 9 — Create `PROGRESS.md`

Create `PROGRESS.md` at the repo root — this is your running sprint log.

```markdown
# Sentinel — Progress Log

## Sprint 1 — Foundation

### Day 1 — Setup
- Done: monorepo structure, docker-compose, CLAUDE.md, .gitignore,
  .env.example, placeholder CI. Stack comes up healthy. CI green.
- Blocked: (none)
- Next: Day 2 — Postgres schema and Flyway migrations.
```

Commit it:

```bash
git add PROGRESS.md
git commit -m "docs: add progress log"
git push
```

---

## End-of-day goal — verify before you stop

You are done with Day 1 when ALL of these are true:

- [ ] The full folder structure exists and is pushed to GitHub.
- [ ] `CLAUDE.md`, `.gitignore`, `.env.example` are committed.
- [ ] `.env` exists locally and is NOT in the repo (check: `git status` shows it ignored).
- [ ] `docker compose ps` shows postgres, redis, kafka all healthy.
- [ ] The CI run on GitHub Actions is green.
- [ ] `PROGRESS.md` exists with the Day 1 entry.

## Test cases

> **TC-1.1.1** — Stack starts clean
> **Given** a fresh checkout → **When** `docker compose up -d` → **Then**
> `docker compose ps` shows postgres, redis, kafka all healthy.
> *Type:* manual · *Automated:* no

> **TC-1.1.2** — CI passes on empty pipeline
> **Given** the repo on GitHub → **When** a commit is pushed → **Then** the CI
> workflow completes green.
> *Type:* manual · *Automated:* no

> **TC-1.1.3** — Secrets are not committed
> **Given** a local `.env` file → **When** `git status` is run → **Then** `.env`
> does not appear as a tracked or staged file.
> *Type:* manual · *Automated:* no

---

## How to run today's session with Claude Code

1. Open a terminal in your repo folder. Run `claude`.
2. Claude Code reads `CLAUDE.md` automatically — but on Day 1 you are creating
   it, so for this first session paste this opening instruction:

   > "Read `docs/day-plans/DAY-01-SETUP.md`. We are doing Sprint 1, Day 1 —
   > project scaffolding only, no application code. Work through the steps in
   > order. Pause after each step so I can verify before continuing."

3. Let Claude Code create files step by step. **Review each diff** before
   accepting — especially `docker-compose.yml` and `.gitignore`.
4. You run the verification commands yourself (`docker compose ps`,
   `git push`) — do not delegate the "is it actually working" check.
5. When all end-of-day checkboxes pass, update `PROGRESS.md` and stop.

---

## If something goes wrong

- **Kafka unhealthy:** the KRaft config is fussy. Run `docker compose logs kafka`,
  check the quorum-voters line matches the listener config. A full
  `docker compose down -v && docker compose up -d` often clears a bad state.
- **Port already in use:** something else is using 5432/6379/9092. Find it
  (`lsof -i :5432`) and stop it, or change the host-side port in compose.
- **CI not running:** confirm the file is exactly at `.github/workflows/ci.yml`
  and the YAML indentation is valid.

---

## Next

When Day 1 is fully verified, continue to Day 2 — Database schema and Flyway
migrations. See `/docs/03-SPRINT-1-FOUNDATION.md`, Day 2.
