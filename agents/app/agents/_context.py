"""Shared execution context passed to every agent.

Holds the dependencies an agent might need — LLM client, prompt registry.
A single typed object is cleaner than threading 2-3 positional args, and
it means future agents can request new dependencies (e.g. a vector store
in Sprint 4) without changing the worker's call site."""

from __future__ import annotations

from dataclasses import dataclass

from ..llm.base import LLMClient
from ..prompt_registry import PromptRegistry


@dataclass(frozen=True)
class AgentContext:
    llm: LLMClient
    prompts: PromptRegistry
