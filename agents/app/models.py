from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class AgentTask(BaseModel):
    """Mirrors orchestrator/.../dispatch/AgentTask.java — camelCase on the wire."""

    model_config = ConfigDict(populate_by_name=True)

    incident_id: UUID = Field(alias="incidentId")
    agent_name: str = Field(alias="agentName")
    service: str
    payload: Any | None = None


class AgentResult(BaseModel):
    """Published to agent.results. camelCase on the wire via model_dump_json(by_alias=True)."""

    model_config = ConfigDict(populate_by_name=True)

    incident_id: UUID = Field(alias="incidentId")
    agent_name: str = Field(alias="agentName")
    output: dict[str, Any]
    tokens_used: int = Field(default=0, alias="tokensUsed")
    latency_ms: int = Field(default=0, alias="latencyMs")
    status: str = "ok"
    prompt_version: str | None = Field(default=None, alias="promptVersion")
