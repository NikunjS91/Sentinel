# AgentTask Wire Contract

**Topic**: `agent.tasks`  
**Key**: `incidentId` (UUID string) — all messages for one incident land on the same partition  
**Value**: JSON object

## Field schema

| Field       | Type   | JSON key    | Description                                    |
|-------------|--------|-------------|------------------------------------------------|
| incidentId  | UUID   | `incidentId`| The incident this task belongs to              |
| agentName   | String | `agentName` | Which agent should handle this task            |
| service     | String | `service`   | Affected service (source from the alert)       |
| payload     | Object | `payload`   | Free-form agent context (null in Sprint 1)     |

## Wire format decision

**camelCase** on both sides. Java serializes via Jackson (default camelCase). Python consumers must configure Pydantic accordingly:

```python
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

class AgentTask(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
    incident_id: str
    agent_name: str
    service: str
    payload: dict | None = None
```

## Sprint 1 simplifications

- `agentName` is always `"echo"` — a placeholder until real agents exist (Day 7+)
- `payload` is always `null` — becomes typed in Sprint 2
- One task per incident — swarm of many agents begins Sprint 2
