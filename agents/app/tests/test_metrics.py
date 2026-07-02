"""TC-5.1.2 — Agent timeout counter increments when LLM call times out."""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from ..agents._parse import call_with_retry
from ..llm.base import LLMResponse


class _FailingLLM:
    """Always raises to simulate an LLM timeout."""

    async def complete(self, prompt: str) -> LLMResponse:  # noqa: ARG002
        raise TimeoutError("simulated LLM timeout")


class _SimpleModel(BaseModel):
    value: str = ""


@pytest.mark.asyncio
async def test_tc_5_1_2_timeout_increments_counter() -> None:
    """TC-5.1.2 — sentinel_agent_timeouts_total increments on LLM failure."""
    from ..metrics import AGENT_TIMEOUTS

    agent_name = "test_agent_timeout"
    before = AGENT_TIMEOUTS.labels(agent_name=agent_name)._value.get()

    fallback = _SimpleModel(value="fallback")
    result, stats = await call_with_retry(
        llm=_FailingLLM(),
        prompt="irrelevant",
        model=_SimpleModel,
        fallback=fallback,
        agent_name=agent_name,
    )

    after = AGENT_TIMEOUTS.labels(agent_name=agent_name)._value.get()

    assert result is fallback, "should return fallback on LLM failure"
    assert stats.total_latency_ms == 0, "latency should be 0 when LLM threw"
    assert after == before + 1, f"counter should have incremented once (was {before}, now {after})"
