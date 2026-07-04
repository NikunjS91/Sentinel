"""Per-agent model selection.

Maps agent name → (model, timeout_s). Editing this file — not agent code —
swaps models.

Sprint 5 defaults:
  - 6 specialists use qwen2.5:3b with 30s timeout (fast, ~5-10s actual)
  - Synthesizer uses qwen3:14b with 180s timeout (one call, runs after all specialists)
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class AgentModelSpec:
    model: str
    timeout_s: float


DEFAULT_LADDER: dict[str, AgentModelSpec] = {
    "echo": AgentModelSpec(model="qwen2.5:3b", timeout_s=30.0),
    "log_analyzer": AgentModelSpec(model="qwen2.5:3b", timeout_s=30.0),
    "metrics": AgentModelSpec(model="qwen2.5:3b", timeout_s=30.0),
    "history": AgentModelSpec(model="qwen2.5:3b", timeout_s=30.0),
    "topology": AgentModelSpec(model="qwen2.5:3b", timeout_s=30.0),
    "runbook": AgentModelSpec(model="qwen2.5:3b", timeout_s=30.0),
    "synthesizer": AgentModelSpec(model="qwen3:14b", timeout_s=180.0),
}


def resolve_agent_spec(agent_name: str) -> AgentModelSpec:
    """Return the (model, timeout) for an agent.

    Falls back to the Synthesizer spec for unknown names — conservative so
    a mis-configured agent doesn't silently use an undersized model."""
    return DEFAULT_LADDER.get(agent_name, DEFAULT_LADDER["synthesizer"])
