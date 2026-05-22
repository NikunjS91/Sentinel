# 05 — Sprint 3: Synthesis & UI

[← Back to index](./00-PROJECT-SETUP-REPORT.md)

---

## Sprint goal

**The Synthesizer agent combines all findings into one report; a live React dashboard streams the swarm working in real time.** This is the sprint where the project stops being a backend and starts being a *product you can show*.

## Sprint acceptance criteria

1. The Synthesizer agent consumes all specialist findings and produces a structured report (summary, root cause, recommended action, confidence).
2. The orchestrator dispatches agents in parallel with a per-incident deadline.
3. If an agent misses the deadline, the incident proceeds as `PARTIAL` with the gap flagged.
4. An SSE endpoint streams incident lifecycle events.
5. The React dashboard shows a live incident list, a per-incident timeline, and per-agent finding panels.
6. A human can approve, reject, or edit the report from the UI.
7. All new code is tested; CI stays green.

## Sprint backlog

| ID | Ticket | Priority |
|---|---|---|
| S3-1 | Synthesizer agent | MUST |
| S3-2 | Parallel dispatch + per-incident deadline | MUST |
| S3-3 | Partial-result handling (`PARTIAL` path) | MUST |
| S3-4 | SSE endpoint for incident events | MUST |
| S3-5 | React app skeleton + routing + API client | MUST |
| S3-6 | Incident list view (live) | MUST |
| S3-7 | Incident detail: timeline + agent panels | MUST |
| S3-8 | Human-in-the-loop: approve / reject / edit | MUST |
| S3-9 | Integration + e2e tests | MUST |
| S3-10 | UI polish, empty/error states, demo | SHOULD |

---

## Day 1 — Synthesizer agent

**Objective:** an agent that takes all specialist findings and produces one structured report.

### Steps
1. Create `agents/app/agents/synthesizer.py`.
2. Input: the list of specialist `AgentResult` outputs for an incident.
3. Build a prompt instructing the LLM to weigh the findings, resolve disagreements, and output strict JSON: `summary`, `root_cause`, `recommended_action`, `confidence`, `dissenting_notes`.
4. Defensive JSON parsing with one retry and a fallback.
5. The fallback report (when synthesis fails) still returns something usable, flagged low-confidence.

### Code — Synthesizer (skeleton)
```python
async def synthesizer(findings: list[AgentResult], llm, prompts) -> dict:
    body, version = prompts.get("synthesizer")
    prompt = body.format(findings=[f.output for f in findings])
    resp = await llm.complete(prompt)
    return parse_json_report(resp.text)   # {summary, root_cause, action, confidence}
```

### End-of-day goal
Given two findings, the Synthesizer produces one coherent report.

### Test cases
> **TC-3.1.1** — Synthesizer produces a structured report
> **Given** two specialist findings → **When** the Synthesizer runs → **Then** the report contains summary, root_cause, recommended_action, and a confidence in [0,1].
> *Type:* integration · *Automated:* yes (real Ollama; skipped in CI)

> **TC-3.1.2** — Conflicting findings are noted
> **Given** two findings with different root causes → **When** the Synthesizer runs → **Then** `dissenting_notes` is non-empty.
> *Type:* integration · *Automated:* yes

---

## Day 2 — Parallel dispatch with a deadline

**Objective:** the orchestrator dispatches all specialist agents at once and enforces a wall-clock deadline per incident.

### Steps
1. Update the dispatcher to publish all specialist tasks together (already two; designed for more).
2. On dispatch, record a `deadline_at` on the incident (e.g. now + 30s).
3. Add a scheduled sweeper that checks for incidents past their deadline still in `AGGREGATING`.
4. The sweeper triggers the partial-result path (Day 3).

### Code — deadline sweeper (skeleton)
```java
@Scheduled(fixedDelay = 2000)
void sweepDeadlines() {
  for (Incident inc : repo.findAggregatingPastDeadline(Instant.now())) {
    aggregator.finalizeAsPartial(inc);     // Day 3
  }
}
```

### End-of-day goal
An incident has a deadline; a sweeper detects when it is exceeded.

### Test cases
> **TC-3.2.1** — Deadline is set on dispatch
> **Given** an incident reaching `DISPATCHED` → **When** dispatch completes → **Then** `deadline_at` is set in the future.
> *Type:* integration · *Automated:* yes

> **TC-3.2.2** — Sweeper finds overdue incidents
> **Given** an incident in `AGGREGATING` past its deadline → **When** the sweeper runs → **Then** that incident is selected for finalization.
> *Type:* integration · *Automated:* yes

---

## Day 3 — Partial-result handling

**Objective:** when an agent does not respond in time, the incident still completes — as `PARTIAL`, with the gap recorded.

### Steps
1. Implement `finalizeAsPartial(incident)`: gather whatever results arrived, mark missing agents.
2. Transition `AGGREGATING → PARTIAL`.
3. Still run synthesis — the Synthesizer is told which agents are missing and lowers confidence accordingly.
4. Transition `PARTIAL → SYNTHESIZED → RESOLVED`.
5. The report explicitly lists which agents were missing.

### End-of-day goal
An incident where one agent never responds still produces a report, clearly marked partial.

### Test cases
> **TC-3.3.1** — Missing agent yields a PARTIAL incident
> **Given** an incident where one of two agents never returns → **When** the deadline passes → **Then** the incident reaches `SYNTHESIZED` via `PARTIAL` and the report lists the missing agent.
> *Type:* integration · *Automated:* yes

> **TC-3.3.2** — Partial reports carry lower confidence
> **Given** a partial synthesis vs a complete one for similar inputs → **When** both run → **Then** the partial report's confidence is not higher than the complete one's.
> *Type:* integration · *Automated:* yes

> **TC-3.3.3** — A late result after PARTIAL is ignored gracefully
> **Given** an incident already `RESOLVED` as partial → **When** the slow agent's result finally arrives → **Then** it is recorded as a trace but does not change the incident state.
> *Type:* integration · *Automated:* yes

---

## Day 4 — SSE endpoint

**Objective:** the orchestrator streams incident lifecycle events to the browser over Server-Sent Events.

### Steps
1. Add an SSE endpoint `GET /incidents/{id}/stream`.
2. Publish events on every state transition and every agent trace recorded.
3. Event types: `state_changed`, `agent_started`, `agent_finished`, `synthesized`.
4. Also add `GET /incidents/stream` for the live list (all incidents).
5. Handle client disconnects cleanly.

### Code — SSE endpoint (skeleton)
```java
@GetMapping("/incidents/{id}/stream")
SseEmitter stream(@PathVariable UUID id) {
  SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());
  eventBus.subscribe(id, event -> {
    try { emitter.send(SseEmitter.event().name(event.type()).data(event)); }
    catch (IOException e) { emitter.completeWithError(e); }
  });
  return emitter;
}
```

### End-of-day goal
`curl -N /incidents/{id}/stream` shows live events as an incident is processed.

### Test cases
> **TC-3.4.1** — SSE emits state changes
> **Given** an open SSE connection for an incident → **When** the incident transitions state → **Then** a `state_changed` event is received.
> *Type:* integration · *Automated:* yes

> **TC-3.4.2** — SSE survives client disconnect
> **Given** an SSE client that disconnects → **When** more events occur → **Then** the server logs the disconnect and does not crash.
> *Type:* integration · *Automated:* yes

---

## Day 5 — React app skeleton

**Objective:** the React app runs, routes between two pages, and can call the orchestrator's API.

### Steps
1. Scaffold `ui/` with Vite + React + Tailwind.
2. Set up routing: `/` (incident list), `/incidents/:id` (detail).
3. Build an API client module (fetch wrapper with base URL, error handling).
4. Build a reusable SSE hook (`useEventStream(url)`).
5. Add a layout shell: header, nav, content area.

### Code — SSE hook (skeleton)
```jsx
function useEventStream(url) {
  const [events, setEvents] = useState([]);
  useEffect(() => {
    const es = new EventSource(url);
    es.onmessage = (e) => setEvents((prev) => [...prev, JSON.parse(e.data)]);
    return () => es.close();
  }, [url]);
  return events;
}
```

### End-of-day goal
The app runs; both routes render placeholder content; the API client fetches successfully.

### Test cases
> **TC-3.5.1** — App routes render
> **Given** the app running → **When** navigating to `/` and `/incidents/test` → **Then** each route renders without error.
> *Type:* unit · *Automated:* yes (React Testing Library)

> **TC-3.5.2** — API client handles errors
> **Given** the API client → **When** a request returns 500 → **Then** the client surfaces an error rather than throwing uncaught.
> *Type:* unit · *Automated:* yes

---

## Day 6 — Incident list view

**Objective:** a live-updating list of incidents.

### Steps
1. Build the incident list: fetch existing incidents, then subscribe to `/incidents/stream` for live updates.
2. Each row: id (short), source, severity, state (with a color), age.
3. New incidents appear at the top in real time; state changes update in place.
4. Clicking a row navigates to the detail page.
5. Handle the empty state ("no incidents yet").

### End-of-day goal
The list shows incidents and updates live as new ones arrive and change state.

### Test cases
> **TC-3.6.1** — List renders incidents
> **Given** incidents returned by the API → **When** the list mounts → **Then** one row per incident is shown.
> *Type:* unit · *Automated:* yes

> **TC-3.6.2** — Live update on new incident
> **Given** the list mounted → **When** an SSE `incident_created` event arrives → **Then** a new row appears.
> *Type:* unit · *Automated:* yes (mock EventSource)

---

## Day 7 — Incident detail: timeline and agent panels

**Objective:** the detail page shows the incident's timeline and each agent's finding as it arrives.

### Steps
1. Build the detail page: fetch the incident, subscribe to `/incidents/{id}/stream`.
2. Timeline component: an ordered list of lifecycle events with timestamps.
3. Agent panels: one card per agent — shows "running", then the finding when it arrives.
4. Synthesizer panel: shows the final report prominently (summary, root cause, action, confidence).
5. This is the "watch the swarm work" view — it should feel alive.

### End-of-day goal
Triggering an incident and opening its detail page shows agents lighting up one by one and a final report appearing.

### Test cases
> **TC-3.7.1** — Agent panel shows running then finished
> **Given** the detail page open → **When** `agent_started` then `agent_finished` events arrive → **Then** the panel shows running, then the finding.
> *Type:* unit · *Automated:* yes

> **TC-3.7.2** — Final report renders
> **Given** a `synthesized` event → **When** received → **Then** the report panel shows summary, root cause, action, and confidence.
> *Type:* unit · *Automated:* yes

---

## Day 8 — Human-in-the-loop

**Objective:** a human can approve, reject, or edit the report from the UI.

### Steps
1. Add API endpoints: `POST /incidents/{id}/decision` accepting `approved | rejected` and `POST /incidents/{id}/report` for an edited report.
2. Each decision writes to `incident_reports.human_decision` and an `audit_log` row.
3. UI: approve/reject buttons and an editable report form on the detail page.
4. After a decision, the UI reflects the new state.
5. A rejected report can be annotated with a reason (useful as Sprint 5 eval signal).

### End-of-day goal
A reviewer can approve or edit a report and the decision is persisted and audited.

### Test cases
> **TC-3.8.1** — Approval is persisted and audited
> **Given** a synthesized incident → **When** approved via the API → **Then** `human_decision` is `approved` and an audit row exists.
> *Type:* integration · *Automated:* yes

> **TC-3.8.2** — Edited report is saved
> **Given** a synthesized incident → **When** an edited report is submitted → **Then** the stored report reflects the edits and the original is preserved in the audit log.
> *Type:* integration · *Automated:* yes

> **TC-3.8.3** — UI reflects the decision
> **Given** the detail page → **When** approve is clicked → **Then** the UI shows the incident as approved.
> *Type:* unit · *Automated:* yes

---

## Day 9 — Integration and end-to-end tests

**Objective:** an automated test proves the full Sprint-3 pipeline including synthesis and the partial path.

### Steps
1. Write an e2e test: trigger an incident, wait for `SYNTHESIZED`, assert the report exists with all fields.
2. Write an e2e test for the partial path: simulate a missing agent, assert a `PARTIAL`-routed report.
3. Add a UI e2e check (Playwright optional, or manual) covering the live detail view.
4. Confirm CI runs all of it.

### End-of-day goal
Both the happy path and the partial path are proven by automated tests.

### Test cases
> **TC-3.9.1** — Full pipeline reaches a report
> **Given** the stack and an alert → **When** the pipeline runs → **Then** within the deadline a complete report exists.
> *Type:* e2e · *Automated:* yes

> **TC-3.9.2** — Partial pipeline still reaches a report
> **Given** an incident with a deliberately disabled agent → **When** the pipeline runs → **Then** a partial report exists flagging the missing agent.
> *Type:* e2e · *Automated:* yes

---

## Day 10 — Polish and demo

**Objective:** the UI handles empty/error/loading states well; the sprint is demoable.

### Steps
1. Add loading skeletons, empty states, and error states to every view.
2. Tidy the visual design — consistent spacing, readable severity colors, sensible typography.
3. Record the demo flow.
4. Retrospective.

### End-of-day goal — **Sprint 3 complete**
You can trigger an incident and watch the entire swarm work, live, in the browser, then approve the result.

### Test cases
> **TC-3.10.1** — Empty and error states render
> **Given** no incidents / a failing API → **When** the views render → **Then** appropriate empty and error states are shown, not blank pages or crashes.
> *Type:* unit · *Automated:* yes

---

## Sprint 3 demo script

1. Open the dashboard — show the (possibly empty) live incident list.
2. Trigger a failure in the demo app and raise an alert.
3. Watch the new incident appear in the list in real time.
4. Open its detail page — watch the agent panels go from running to finished.
5. Watch the final report appear.
6. Click Approve — show the state update and the audit trail.

## Sprint 3 retrospective prompts

- Does the live UI feel genuinely real-time, or laggy? Where is the latency?
- Is the Synthesizer's output trustworthy, or does it hallucinate beyond its inputs?
- Is the partial path triggering when it should — and never when it should not?
- What is the riskiest unknown going into Sprint 4 (the remaining agents)?

Continue to `06-SPRINT-4-REMAINING-AGENTS.md`.
