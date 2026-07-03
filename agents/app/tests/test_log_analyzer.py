"""Tests for the Log Analyzer agent (TC-2.8.1 – TC-2.8.5)."""

from __future__ import annotations

import json
from uuid import uuid4

import httpx
import pytest

from app.agents._context import AgentContext
from app.agents.log_analyzer import log_analyzer
from app.llm.base import LLMClient, LLMResponse
from app.models import AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import settings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_VALID_FINDING = {
    "error_patterns": ["downstream_timeout"],
    "most_likely_symptom": "payment-gateway unreachable",
    "supporting_evidence": ["ERROR downstream_timeout dependency=payment-gateway"],
    "confidence": 0.85,
}

_VALID_JSON = json.dumps(_VALID_FINDING)


def _make_task(service: str = "demo-app") -> AgentTask:
    return AgentTask(
        incident_id=uuid4(),
        agent_name="log_analyzer",
        service=service,
    )


class _FakeLLM(LLMClient):
    """Cycles through provided responses on each complete() call."""

    def __init__(self, responses: list[str], tokens: int = 10, latency_ms: int = 50) -> None:
        self._responses = responses
        self._call_count = 0
        self._tokens = tokens
        self._latency_ms = latency_ms

    async def complete(
        self, prompt: str, model: str | None = None, timeout_s: float | None = None
    ) -> LLMResponse:  # noqa: ARG002
        resp = self._responses[min(self._call_count, len(self._responses) - 1)]
        self._call_count += 1
        return LLMResponse(text=resp, tokens=self._tokens, latency_ms=self._latency_ms)


def _real_registry() -> PromptRegistry:
    return PromptRegistry(settings.prompts_dir)


def _ollama_reachable() -> bool:
    try:
        with httpx.Client(timeout=2.0) as client:
            resp = client.get(f"{settings.ollama_host}/api/tags")
            return resp.status_code == 200
    except Exception:
        return False


# ---------------------------------------------------------------------------
# TC-2.8.1: happy path — valid JSON on first LLM call
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_2_8_1_happy_path(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    llm = _FakeLLM([_VALID_JSON], tokens=20, latency_ms=100)
    registry = _real_registry()
    task = _make_task()

    result = await log_analyzer(task, AgentContext(llm=llm, prompts=registry))

    assert result.status == "ok"
    assert result.agent_name == "log_analyzer"
    assert result.incident_id == task.incident_id
    assert result.output["most_likely_symptom"] == "payment-gateway unreachable"
    assert result.tokens_used == 20
    assert result.latency_ms == 100
    assert result.prompt_version == registry.get("log_analyzer").version
    assert llm._call_count == 1


# ---------------------------------------------------------------------------
# TC-2.8.2: malformed first call, valid second call — tokens are cumulative
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_2_8_2_retry_on_malformed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    llm = _FakeLLM(["this is not json at all", _VALID_JSON], tokens=15, latency_ms=80)
    registry = _real_registry()
    task = _make_task()

    result = await log_analyzer(task, AgentContext(llm=llm, prompts=registry))

    assert result.status == "ok"
    assert result.output["most_likely_symptom"] == "payment-gateway unreachable"
    assert llm._call_count == 2
    assert result.tokens_used == 30  # 15 + 15
    assert result.latency_ms == 160  # 80 + 80


# ---------------------------------------------------------------------------
# TC-2.8.3: both calls return garbage — fallback with confidence=0
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_2_8_3_persistent_parse_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    llm = _FakeLLM(["garbage", "still garbage"], tokens=5, latency_ms=30)
    registry = _real_registry()
    task = _make_task()

    result = await log_analyzer(task, AgentContext(llm=llm, prompts=registry))

    assert result.status == "ok"
    assert result.output["confidence"] == 0.0
    assert any("not parseable" in e for e in result.output["supporting_evidence"])
    assert llm._call_count == 2


# ---------------------------------------------------------------------------
# TC-2.8.4: code fences stripped, parsed on first attempt
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_2_8_4_code_fences_stripped(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    fenced = f"```json\n{_VALID_JSON}\n```"
    llm = _FakeLLM([fenced])
    registry = _real_registry()
    task = _make_task()

    result = await log_analyzer(task, AgentContext(llm=llm, prompts=registry))

    assert result.status == "ok"
    assert result.output["confidence"] == pytest.approx(0.85)
    assert llm._call_count == 1


# ---------------------------------------------------------------------------
# TC-2.8.5: live Ollama integration (skip-guarded)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
@pytest.mark.skipif(not _ollama_reachable(), reason="Ollama not available")
async def test_tc_2_8_5_live_ollama(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    from app.llm.factory import make_llm_client

    llm = make_llm_client(settings)
    registry = _real_registry()
    task = _make_task(service="demo-app")

    result = await log_analyzer(task, AgentContext(llm=llm, prompts=registry))

    assert result.status == "ok"
    assert result.prompt_version is not None
    assert isinstance(result.output.get("confidence"), float)
