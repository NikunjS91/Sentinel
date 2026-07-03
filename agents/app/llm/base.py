from typing import Protocol

from pydantic import BaseModel


class LLMResponse(BaseModel):
    """Normalized LLM response, independent of which backend produced it."""

    text: str
    tokens: int
    latency_ms: int


class LLMClient(Protocol):
    """Single method every backend implements. Async because LLM calls are slow I/O."""

    async def complete(
        self,
        prompt: str,
        model: str | None = None,
        timeout_s: float | None = None,
    ) -> LLMResponse: ...
