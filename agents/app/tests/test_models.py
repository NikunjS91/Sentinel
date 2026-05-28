import json
from uuid import UUID

from app.models import AgentTask


# TC-1.7.4: camelCase wire aliases round-trip
def test_tc_1_7_4_wire_alias_round_trip() -> None:
    incident_id = UUID("12345678-1234-5678-1234-567812345678")
    raw_json = json.dumps(
        {
            "incidentId": str(incident_id),
            "agentName": "echo",
            "service": "order-api",
            "payload": None,
        }
    )

    task = AgentTask.model_validate_json(raw_json)

    # Python-side: snake_case attributes
    assert task.incident_id == incident_id
    assert task.agent_name == "echo"
    assert task.service == "order-api"

    # Wire-side: camelCase keys when serializing
    wire = json.loads(task.model_dump_json(by_alias=True))
    assert "incidentId" in wire
    assert "agentName" in wire
    assert wire["incidentId"] == str(incident_id)
    assert wire["agentName"] == "echo"
