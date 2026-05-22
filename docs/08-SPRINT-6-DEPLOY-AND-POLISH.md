# 08 — Sprint 6: Deploy & Polish

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## Sprint goal

**Sentinel is deployable, documented, and demoable.** Kubernetes manifests, a full CI/CD pipeline, complete documentation, architecture decision records, and a demo video. By the end, the project is portfolio-ready and resume-ready.

## Sprint acceptance criteria

1. All services have container images built by CI.
2. Kubernetes manifests bring up the full stack on a local cluster (kind/minikube).
3. The CI/CD pipeline runs lint → test → eval → build → push images.
4. The README and `/docs` are complete and accurate.
5. Architecture Decision Records exist for the major design choices.
6. A 3-minute demo video is recorded.
7. The headline metrics are finalized and written into the README and resume bullet.

## Sprint backlog

| ID | Ticket | Priority |
|---|---|---|
| S6-1 | Dockerfiles for all services | MUST |
| S6-2 | Kubernetes manifests (or Helm/Kustomize) | MUST |
| S6-3 | Local cluster deploy (kind) verified | MUST |
| S6-4 | CI/CD: build + push images | MUST |
| S6-5 | CI/CD: deploy step to kind | SHOULD |
| S6-6 | Documentation pass (README + docs) | MUST |
| S6-7 | Architecture Decision Records | MUST |
| S6-8 | Demo video | MUST |
| S6-9 | Final eval run + metrics locked | MUST |
| S6-10 | Backlog grooming for Phase 2; final retrospective | SHOULD |

---

## Day 1 — Dockerfiles

**Objective:** every service builds into a clean container image.

### Steps
1. Write a multi-stage Dockerfile for the orchestrator (build with Maven, run on a slim JRE).
2. Write a Dockerfile for the agent service (slim Python base, install deps, run uvicorn).
3. Write a Dockerfile for the UI (build with Node, serve static files via nginx).
4. Write a Dockerfile for the demo app.
5. Build all images locally; confirm each runs.

### End-of-day goal
All four images build and a container of each starts successfully.

### Test cases
> **TC-6.1.1** — Images build
> **Given** the Dockerfiles → **When** each is built → **Then** the build succeeds with no errors.
> *Type:* manual · *Automated:* no

> **TC-6.1.2** — Containers start
> **Given** the built images → **When** each container is run → **Then** the service starts and its health check passes.
> *Type:* manual · *Automated:* no

---

## Day 2 — Kubernetes manifests

**Objective:** the full stack is described as Kubernetes resources.

### Steps
1. Create `infra/k8s/` with manifests: Deployments and Services for orchestrator, agents, UI, demo app.
2. Add StatefulSets (or dev-grade Deployments) for Postgres, Redis, Kafka.
3. Add ConfigMaps for non-secret config and Secrets for sensitive values.
4. Use Kustomize for a `base` plus a `dev` overlay.
5. Add liveness and readiness probes to every app workload.

### End-of-day goal
The manifests are complete and pass a dry-run validation (`kubectl apply --dry-run`).

### Test cases
> **TC-6.2.1** — Manifests validate
> **Given** the manifests → **When** `kubectl apply --dry-run=client` runs → **Then** all resources validate with no errors.
> *Type:* manual · *Automated:* no

> **TC-6.2.2** — Every workload has probes
> **Given** the app Deployments → **When** reviewed → **Then** each has liveness and readiness probes.
> *Type:* manual · *Automated:* no

---

## Day 3 — Local cluster deploy

**Objective:** the full stack runs on a real (local) Kubernetes cluster.

### Steps
1. Create a kind (or minikube) cluster.
2. Load the locally-built images into the cluster.
3. `kubectl apply -k infra/k8s/overlays/dev`.
4. Wait for all pods to be Ready; debug any that are not.
5. Run a smoke test against the deployed stack — an alert in, a report out.

### End-of-day goal
`kubectl get pods` shows everything Ready; an incident can be processed end to end on the cluster.

### Test cases
> **TC-6.3.1** — All pods reach Ready
> **Given** the manifests applied to kind → **When** the cluster settles → **Then** every pod is `Running` and `Ready`.
> *Type:* manual · *Automated:* no

> **TC-6.3.2** — Smoke test passes on the cluster
> **Given** the deployed stack → **When** an alert is sent → **Then** an incident reaches `RESOLVED` with a report.
> *Type:* e2e · *Automated:* yes (against the cluster)

---

## Day 4 — CI/CD: build and push images

**Objective:** CI builds and publishes container images on every merge to `main`.

### Steps
1. Extend the CI workflow: after tests and eval pass, build all images.
2. Tag images with the git SHA and `latest`.
3. Push to a registry (GitHub Container Registry, `ghcr.io`).
4. Ensure the pipeline order is: lint → unit → integration → eval → build → push.

### Code — CI build/push stage (skeleton)
```yaml
  build-and-push:
    needs: [orchestrator, agents, eval]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with: { registry: ghcr.io, username: ${{ github.actor }}, password: ${{ secrets.GITHUB_TOKEN }} }
      - run: |
          for svc in orchestrator agents ui demo-app; do
            docker build -t ghcr.io/nikunjs91/sentinel-$svc:${{ github.sha }} ./$svc
            docker push ghcr.io/nikunjs91/sentinel-$svc:${{ github.sha }}
          done
```

### End-of-day goal
A merge to `main` produces published, tagged images in the registry.

### Test cases
> **TC-6.4.1** — Images published on merge
> **Given** a merge to `main` → **When** CI completes → **Then** four SHA-tagged images exist in the registry.
> *Type:* manual · *Automated:* no

---

## Day 5 — CI/CD: deploy step (stretch) and pipeline hardening

**Objective:** optionally auto-deploy to a kind cluster in CI; harden the pipeline.

### Steps
1. (Stretch) Add a CI job that spins up kind, loads the images, applies the manifests, runs the smoke test.
2. Make the pipeline fail fast and report clearly.
3. Add caching (Maven, pip, Docker layers) to keep CI fast.
4. Add status badges to the README.

### End-of-day goal
The pipeline is fast, reliable, and (stretch) proves a real deploy on every change.

### Test cases
> **TC-6.5.1** — Pipeline is green end to end
> **Given** a PR → **When** CI runs → **Then** every stage passes and total time is reasonable.
> *Type:* manual · *Automated:* no

---

## Day 6 — Documentation pass

**Objective:** the README and `/docs` are complete, accurate, and match reality.

### Steps
1. Update the README: the "getting started" steps must actually work from a clean clone.
2. Update `/docs` — fix anything that drifted from the plan during the build.
3. Add a `CONTRIBUTING.md` and ensure `LICENSE` exists.
4. Add the architecture diagram (export the Mermaid/diagram) into the docs.
5. Verify every command in the docs by running it.

### End-of-day goal
A stranger could clone the repo and get it running using only the docs.

### Test cases
> **TC-6.6.1** — Getting-started works from clean clone
> **Given** a fresh clone → **When** the README steps are followed exactly → **Then** the stack runs and processes an incident.
> *Type:* manual · *Automated:* no

---

## Day 7 — Architecture Decision Records

**Objective:** the major design choices are documented with their reasoning.

### Steps
1. Create `docs/adr/`.
2. Write one ADR per major decision. Each ADR: context, decision, alternatives considered, consequences.
3. Suggested ADRs:
   - ADR-001: Hybrid Java/Python split
   - ADR-002: Kafka as the event backbone
   - ADR-003: Stateless orchestrator + durable state machine
   - ADR-004: Swarm of specialist agents vs a single agent
   - ADR-005: Partial-result handling and graceful degradation
   - ADR-006: LLM gateway with a model fallback ladder
   - ADR-007: Pluggable LLM backends (Ollama dev / API prod)

### End-of-day goal
Seven concise ADRs exist, each explaining *why*, not just *what*.

### Test cases
> **TC-6.7.1** — ADRs are complete
> **Given** `docs/adr/` → **When** reviewed → **Then** each ADR has context, decision, alternatives, and consequences.
> *Type:* manual · *Automated:* no

---

## Day 8 — Demo video

**Objective:** a tight 3-minute video that shows Sentinel working.

### Steps
1. Script the demo: problem (15s) → architecture (30s) → live incident with the swarm working (90s) → eval numbers and Grafana (30s) → close (15s).
2. Record a clean run — demo app failure, swarm triaging live in the UI, the report, the approval.
3. Show the eval harness output and the self-observability dashboard.
4. Keep it under 3 minutes; edit out dead time.
5. Link the video in the README.

### End-of-day goal
A polished demo video is recorded and linked.

### Test cases
> **TC-6.8.1** — Video covers the full story
> **Given** the recorded video → **When** watched → **Then** it shows the problem, the live swarm, the report, and the metrics within ~3 minutes.
> *Type:* manual · *Automated:* no

---

## Day 9 — Final eval run and metrics

**Objective:** lock the headline numbers.

### Steps
1. Run the eval harness a final time on a clean build, on both backends, over the full corpus.
2. Record: triage accuracy, mean and p95 time-to-triage, mean cost per incident, partial-rate.
3. Put these numbers in the README and in `/docs`.
4. Draft the resume bullet using the real numbers.

### Code — resume bullet template
```
Built Sentinel, a multi-agent incident-triage platform (Spring Boot, FastAPI,
Kafka, PostgreSQL, Kubernetes): six specialist LLM agents investigate incidents
in parallel and synthesize a unified diagnosis. Achieved <ACCURACY>% triage
accuracy on a <N>-incident evaluation corpus at $<COST>/incident with <P95>s
p95 latency. Implemented partial-result handling, circuit breakers, a model
fallback ladder, token budgets, and a CI-integrated evaluation harness.
```

### End-of-day goal
The headline metrics are final, honest, and written into the README and a resume bullet.

### Test cases
> **TC-6.9.1** — Final metrics are reproducible
> **Given** the final build and corpus → **When** the eval is run twice → **Then** the headline numbers are stable within expected variance.
> *Type:* manual · *Automated:* no

---

## Day 10 — Phase 2 grooming and final retrospective

**Objective:** close Phase 1 cleanly and tee up the future.

### Steps
1. Write a short `docs/PHASE-2.md` outlining the Remediation Swarm, real integrations, and the collective-intelligence idea — so the vision is captured but clearly scoped as future work.
2. File GitHub issues for known bugs and deferred items, labelled by phase.
3. Final project retrospective.
4. Tag a `v1.0.0` release.

### End-of-day goal — **Sprint 6 and Phase 1 complete**
Sentinel is deployed, documented, measured, demoed, and released as v1.0.0.

### Test cases
> **TC-6.10.1** — Release is tagged and complete
> **Given** the repo → **When** the `v1.0.0` tag is inspected → **Then** it points at a green build with complete docs and a linked demo.
> *Type:* manual · *Automated:* no

---

## Sprint 6 demo script

This is the final demo — the one you show in interviews.

1. 15s — the problem: incident response is slow and manual.
2. 30s — the architecture: three planes, six agents, the diagram.
3. 90s — live: trigger a demo-app failure, watch the swarm triage it in the UI, show the report, approve it.
4. 30s — the rigor: the eval harness numbers, the Grafana self-observability dashboard, CI green.
5. 15s — the vision: Phase 2 in one sentence.

## Final project retrospective prompts

- Which sprint delivered the most value relative to effort?
- What would you design differently if starting over?
- Which parts are you most ready to be questioned on in an interview?
- Is Phase 2 worth pursuing, and if so, what is the very first step?

Continue to `09-TEST-STRATEGY-AND-ACCEPTANCE.md`.
