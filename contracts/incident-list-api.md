# Incident List API

`GET /incidents` — Returns a filtered, paginated list of incidents.

## Query parameters

| Param      | Type          | Default | Semantics |
|------------|---------------|---------|-----------|
| `state`    | string        | (any)   | Exact match on `incidents.state`. Accepts comma list: `state=RESOLVED,PARTIAL`. |
| `service`  | string        | (any)   | Exact match on `incidents.source`. Single value. |
| `decision` | string        | (any)   | One of: `undecided`, `accepted`, `rejected`, `edited`. Joins `incident_reports`. `undecided` includes incidents with no report (still in flight) AND incidents with a report but no human decision. |
| `q`        | string        | (none)  | Case-insensitive substring across `summary`, `root_cause`, `recommended_action` in `incident_reports`. |
| `limit`    | int (1–100)   | 20      | Max rows. Values outside range → 400. |
| `before`   | ISO 8601 ts   | (none)  | Cursor — return rows with `created_at` strictly before this timestamp. |

All parameters are optional and compose with AND semantics.

## Response shape

```json
{
  "items": [
    {
      "incident_id": "uuid",
      "state": "RESOLVED",
      "service": "demo-app",
      "severity": "p1",
      "created_at": "2026-06-14T12:00:00Z",
      "human_decision": "ACCEPTED",
      "human_decision_reason": null
    }
  ],
  "nextBefore": "2026-06-14T11:45:00Z"
}
```

`nextBefore` is `null` when fewer rows than `limit` were returned (end of list). Use it as the `before` parameter to load the next page.

## Examples

```
GET /incidents
GET /incidents?state=RESOLVED&decision=undecided
GET /incidents?state=RESOLVED,PARTIAL
GET /incidents?service=demo-app&q=latency&limit=10
GET /incidents?limit=20&before=2026-06-14T15:00:00Z
```

## Error responses

| Status | Condition |
|--------|-----------|
| 400    | `decision` value not in allowed set |
| 400    | `limit` outside 1–100 |
| 400    | `state` contains unknown state name |
