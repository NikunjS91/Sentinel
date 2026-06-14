# SSE Events Schema (Sprint 3)

## Wire format: snake_case JSON, one event per SSE message.

## Event types

### `incident.state_changed`
Fired when an incident transitions between non-terminal states.

Fields: type, ts (ISO 8601), incident_id, state, service, severity,
alert_name (nullable), expected_agents, report (null).

### `incident.completed`
Fired when an incident reaches RESOLVED or PARTIAL.

Fields: same as state_changed, but `report` is populated with summary,
root_cause, recommended_action, confidence, dissenting_notes,
contributing_agents.

## Example: state_changed

```json
{
  "type": "incident.state_changed",
  "ts": "2026-06-14T15:32:18.471Z",
  "incident_id": "a1b2c3d4-...",
  "state": "DISPATCHED",
  "service": "demo-app",
  "severity": "p1",
  "alert_name": null,
  "expected_agents": ["echo", "log_analyzer", "metrics"],
  "report": null
}
```

## Example: completed

```json
{
  "type": "incident.completed",
  "ts": "2026-06-14T15:33:05.000Z",
  "incident_id": "a1b2c3d4-...",
  "state": "RESOLVED",
  "service": "demo-app",
  "severity": "p1",
  "alert_name": null,
  "expected_agents": ["echo", "log_analyzer", "metrics"],
  "report": {
    "summary": "Order-create latency p95 exceeded SLO; likely slow DB query.",
    "root_cause": "downstream DB query latency",
    "recommended_action": "Investigate Postgres connection pool and query plans",
    "confidence": 0.82,
    "dissenting_notes": [],
    "contributing_agents": ["echo", "log_analyzer", "metrics"]
  }
}
```

## Notes

- Two events fire on terminal transitions: the state machine's `incident.completed`
  (report: null) followed by the aggregator's `incident.completed` (report populated).
  Clients should preserve any non-null report when merging events for the same incident.
- `alert_name` is null in Sprint 3 (placeholder for Sprint 4).
- No authentication on this endpoint in Sprint 3. Sprint 6 wires auth.
