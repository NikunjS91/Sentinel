"""Tests for the Metrics agent (TC-2.9.3 – TC-2.9.6)."""

from __future__ import annotations

import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest

from app.agents.metrics_agent import metrics_agent
from app.llm.base import LLMClient, LLMResponse
from app.models import AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import settings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_VALID_FINDING = {
    "slo_status": "violations_present",
    "anomalies": ["p95 latency 1.2s exceeds 0.5s SLO"],
    "most_likely_cause": "slow database query",
    "confidence": 0.88,
}

_VALID_JSON = json.dumps(_VALID_FINDING)


def _make_task(service: str = "demo-app") -> AgentTask:
    return AgentTask(
        incident_id=uuid4(),
        agent_name="metrics",
        service=service,
    )


def _real_registry() -> PromptRegistry:
    return PromptRegistry(Path(settings.prompts_dir))


class _FakeLLM(LLMClient):
    """Cycles through provided responses."""

    def __init__(self, responses: list[str], tokens: int = 15, latency_ms: int = 60) -> None:
        self._responses = responses
        self._call_count = 0
        self._tokens = tokens
        self._latency_ms = latency_ms
        self.last_prompt: str = ""

    async def complete(self, prompt: str) -> LLMResponse:
        self.last_prompt = prompt
        resp = self._responses[min(self._call_count, len(self._responses) - 1)]
        self._call_count += 1
        return LLMResponse(text=resp, tokens=self._tokens, latency_ms=self._latency_ms)


def _ollama_reachable() -> bool:
    try:
        with httpx.Client(timeout=2.0) as client:
            resp = client.get(f"{settings.ollama_host}/api/tags")
            return resp.status_code == 200
    except Exception:
        return False


def _prometheus_reachable() -> bool:
    try:
        with httpx.Client(timeout=2.0) as client:
            resp = client.get(f"{settings.prometheus_url}/-/ready")
            return resp.status_code == 200
    except Exception:
        return False


# ---------------------------------------------------------------------------
# TC-2.9.3: Metrics agent returns a structured finding (happy path)
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_2_9_3_happy_path(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    llm = _FakeLLM([_VALID_JSON], tokens=25, latency_ms=80)
    registry = _real_registry()
    task = _make_task()

    result = await metrics_agent(task, llm, registry)

    assert result.status == "ok"
    assert result.agent_name == "metrics"
    assert result.incident_id == task.incident_id
    assert result.prompt_version == registry.get("metrics_agent").version
    assert result.tokens_used == 25
    assert result.latency_ms == 80

    output = result.output
    assert output["slo_status"] == "violations_present"
    assert output["confidence"] == 0.88
    assert output["most_likely_cause"] == "slow database query"


# ---------------------------------------------------------------------------
# TC-2.9.4: SLO violations propagate into the agent's prompt input
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_2_9_4_slo_violations_in_prompt(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    llm = _FakeLLM([_VALID_JSON])
    registry = _real_registry()
    # Use a service name that the metrics fixture maps to slow_query (latency violations)
    task = _make_task(service="demo-app")

    await metrics_agent(task, llm, registry)

    # The rendered prompt captured in llm.last_prompt must contain "slo_violations"
    assert "slo_violations" in llm.last_prompt


# ---------------------------------------------------------------------------
# TC-2.9.5: Persistent parse failure yields the metrics fallback
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_2_9_5_persistent_failure_yields_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    llm = _FakeLLM(["not json at all", "still not json"])
    registry = _real_registry()
    task = _make_task()

    result = await metrics_agent(task, llm, registry)

    assert result.status == "ok"
    output = result.output
    assert output["confidence"] == 0.0
    assert output["slo_status"] == "ok"
    assert output["most_likely_cause"] is None


# ---------------------------------------------------------------------------
# TC-2.9.6: Live integration — slow_query produces a real metrics finding
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
@pytest.mark.skipif(
    not (_ollama_reachable() and _prometheus_reachable()),
    reason="Ollama and Prometheus must both be running",
)
async def test_tc_2_9_6_live_slow_query(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "live")

    from app.llm.factory import make_llm_client
    llm = make_llm_client(settings)
    registry = _real_registry()
    task = _make_task(service="demo-app")

    result = await metrics_agent(task, llm, registry)

    assert result.status == "ok"
    output = result.output
    assert output["slo_status"] == "violations_present"
    assert len(output["anomalies"]) > 0
    assert output["most_likely_cause"] is not None
    assert output["confidence"] > 0
