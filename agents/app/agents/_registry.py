"""Agent registry — single source of truth for which agents exist.

The worker dispatches by `task.agent_name`. To add a new agent: write the
agent function (any module under app/agents/), import it here, add a
key/value to AGENTS. That's it."""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from ..models import AgentResult, AgentTask
from ._context import AgentContext
from .echo import echo_agent
from .history import history
from .log_analyzer import log_analyzer
from .metrics_agent import metrics_agent
from .synthesizer import synthesizer

# The uniform agent signature.
AgentFn = Callable[[AgentTask, AgentContext], Awaitable[AgentResult]]

AGENTS: dict[str, AgentFn] = {
    "echo": echo_agent,
    "log_analyzer": log_analyzer,
    "metrics": metrics_agent,
    "history": history,
    "synthesizer": synthesizer,
}


def known_agents() -> frozenset[str]:
    """All agent names this worker can run."""
    return frozenset(AGENTS.keys())
