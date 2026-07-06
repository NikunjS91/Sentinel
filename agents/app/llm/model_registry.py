"""Per-agent model selection.

Maps agent name → (model, timeout_s). The active ladder is chosen from
settings.llm_backend so switching backends (ollama → nim) automatically
picks the right model names without touching agent code.

Ollama defaults:
  - 6 specialists: qwen2.5:3b, 30s timeout
  - Synthesizer:   qwen3:14b,  180s timeout

NIM defaults (cloud inference, fast):
  - 6 specialists: meta/llama-3.1-8b-instruct, 30s timeout
  - Synthesizer:   meta/llama-3.1-70b-instruct, 180s timeout
"""

from __future__ import annotations

from dataclasses import dataclass

_SPECIALIST_NAMES = ("echo", "log_analyzer", "metrics", "history", "topology", "runbook")


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


def _nim_ladder() -> dict[str, AgentModelSpec]:
    from ..settings import settings

    specialist = AgentModelSpec(
        model=settings.nim_specialist_model, timeout_s=settings.nim_timeout_s
    )
    synthesizer = AgentModelSpec(
        model=settings.nim_synthesizer_model, timeout_s=settings.nim_synthesizer_timeout_s
    )
    return {**{name: specialist for name in _SPECIALIST_NAMES}, "synthesizer": synthesizer}


def resolve_agent_spec(agent_name: str) -> AgentModelSpec:
    """Return the (model, timeout) for an agent.

    Automatically selects the right model names for the configured backend.
    Falls back to the Synthesizer spec for unknown agent names."""
    from ..settings import settings

    ladder = _nim_ladder() if settings.llm_backend.lower() == "nim" else DEFAULT_LADDER
    return ladder.get(agent_name, ladder["synthesizer"])
