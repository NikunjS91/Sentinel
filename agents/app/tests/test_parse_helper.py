"""Tests for the shared LLM-output parse helper (TC-2.9.1, TC-2.9.2)."""

from __future__ import annotations

import asyncio

import pytest

from app.agents._parse import call_with_retry, try_parse
from app.agents.types import MetricsAgentFinding
from app.llm.base import LLMClient, LLMResponse

# ---------------------------------------------------------------------------
# Fake LLM
# ---------------------------------------------------------------------------


class _FakeLLM(LLMClient):
    """Returns a fixed sequence of responses."""

    def __init__(self, responses: list[tuple[str, int, int]]) -> None:
        # Each item: (text, tokens, latency_ms)
        self._queue = list(responses)

    async def complete(
        self, prompt: str, model: str | None = None, timeout_s: float | None = None
    ) -> LLMResponse:  # noqa: ARG002
        text, tokens, latency = self._queue.pop(0)
        return LLMResponse(text=text, tokens=tokens, latency_ms=latency)


_VALID_JSON = (
    '{"slo_status": "violations_present", "anomalies": ["high latency"],'
    ' "most_likely_cause": "slow DB", "confidence": 0.9}'
)


# ---------------------------------------------------------------------------
# TC-2.9.1: try_parse handles raw, fenced, and prose-wrapped JSON
# ---------------------------------------------------------------------------


def test_tc_2_9_1_try_parse_raw_json() -> None:
    result = try_parse(_VALID_JSON, MetricsAgentFinding)
    assert result is not None
    assert result.slo_status == "violations_present"
    assert result.confidence == 0.9


def test_tc_2_9_1_try_parse_fenced_json() -> None:
    fenced = f"```json\n{_VALID_JSON}\n```"
    result = try_parse(fenced, MetricsAgentFinding)
    assert result is not None
    assert result.slo_status == "violations_present"


def test_tc_2_9_1_try_parse_prose_wrapped_json() -> None:
    prose = f"Sure, here is the analysis:\n{_VALID_JSON}\nHope that helps!"
    result = try_parse(prose, MetricsAgentFinding)
    assert result is not None
    assert result.most_likely_cause == "slow DB"


def test_tc_2_9_1_try_parse_returns_none_on_garbage() -> None:
    assert try_parse("not json at all", MetricsAgentFinding) is None


# ---------------------------------------------------------------------------
# TC-2.9.2: call_with_retry accumulates tokens across retry
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_2_9_2_call_with_retry_accumulates_tokens() -> None:
    # First call returns garbage (10 tokens), second returns valid JSON (20 tokens).
    llm = _FakeLLM(
        [
            ("garbage", 10, 100),
            (_VALID_JSON, 20, 200),
        ]
    )
    fallback = MetricsAgentFinding()
    result, stats = await call_with_retry(llm, "prompt", MetricsAgentFinding, fallback, "test")

    assert result.slo_status == "violations_present"
    assert stats.total_tokens == 30  # 10 + 20
    assert stats.total_latency_ms == 300  # 100 + 200
    assert stats.fallback_reason is None
    assert stats.status == "ok"


@pytest.mark.asyncio
async def test_tc_2_9_2_call_with_retry_returns_fallback_on_double_failure() -> None:
    llm = _FakeLLM(
        [
            ("garbage", 5, 50),
            ("still garbage", 5, 50),
        ]
    )
    fallback = MetricsAgentFinding(slo_status="ok", confidence=0.0)
    result, stats = await call_with_retry(llm, "prompt", MetricsAgentFinding, fallback, "test")

    assert result.confidence == 0.0
    assert result.slo_status == "ok"
    assert stats.total_tokens == 10
    assert stats.fallback_reason == "parse_error"
    assert stats.status == "degraded"


# ---------------------------------------------------------------------------
# Fallback on LLM exception must record elapsed wall-clock time, not 0ms
# ---------------------------------------------------------------------------


class _ExplodingLLM(LLMClient):
    """Simulates a slow call that ultimately fails (timeout, 401, 429...)."""

    async def complete(
        self, prompt: str, model: str | None = None, timeout_s: float | None = None
    ) -> LLMResponse:  # noqa: ARG002
        await asyncio.sleep(0.02)
        raise RuntimeError("simulated LLM failure")


@pytest.mark.asyncio
async def test_llm_error_records_elapsed_latency_and_degraded_status() -> None:
    fallback = MetricsAgentFinding(slo_status="ok", confidence=0.0)
    result, stats = await call_with_retry(
        _ExplodingLLM(), "prompt", MetricsAgentFinding, fallback, "test"
    )

    assert result is fallback
    assert stats.fallback_reason == "llm_error"
    assert stats.status == "degraded"
    # The failed call took ~20ms of wall clock — it must not be reported as 0.
    assert stats.total_latency_ms > 0
