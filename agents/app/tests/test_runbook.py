"""Tests for the Runbook agent (TC-4.4.1 – TC-4.4.5)."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch
from uuid import uuid4

import httpx
import pytest

from app.agents._context import AgentContext
from app.agents.runbook import runbook
from app.llm.base import LLMClient, LLMResponse
from app.models import AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import settings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_RUNBOOK_RESPONSE: list[dict[str, object]] = [
    {
        "id": "rb-001",
        "title": "Slow DB Query Remediation",
        "summary": "Steps to identify and resolve slow PostgreSQL queries.",
        "tags": ["database", "postgres", "performance"],
        "body": "1. Check pg_stat_activity\n2. Kill long-running queries\n3. Review indexes",
    },
    {
        "id": "rb-002",
        "title": "Downstream Timeout Recovery",
        "summary": "Handle cascading timeouts from downstream service failures.",
        "tags": ["external_dependency", "timeout", "circuit_breaker"],
        "body": "1. Enable circuit breaker\n2. Add retry with backoff\n3. Alert the owning team",
    },
]

_LLM_RUNBOOK_JSON = """{
  "matched_runbooks": [
    {
      "id": "rb-001",
      "title": "Slow DB Query Remediation",
      "summary": "Steps to identify and resolve slow PostgreSQL queries.",
      "tags": ["database", "postgres", "performance"]
    }
  ],
  "best_match_id": "rb-001",
  "relevant_steps": [
    "Check pg_stat_activity for long-running queries",
    "Kill queries exceeding 30s",
    "Review missing indexes on the orders table"
  ],
  "assessment": "Slow DB Query runbook directly applies — DB timeout errors match the scenario",
  "confidence": 0.85
}"""


class _FakeLLM(LLMClient):
    def __init__(self, response_text: str = _LLM_RUNBOOK_JSON) -> None:
        self._response_text = response_text

    async def complete(
        self, prompt: str, model: str | None = None, timeout_s: float | None = None
    ) -> LLMResponse:  # noqa: ARG002
        return LLMResponse(
            text=self._response_text,
            tokens=30,
            latency_ms=50,
        )


def _make_task() -> AgentTask:
    return AgentTask(
        incident_id=uuid4(),
        agent_name="runbook",
        service="demo-app",
        payload={
            "severity": "critical",
            "alert_name": "DBTimeout",
            "symptoms_summary": "slow queries",
        },
    )


def _ctx(llm: LLMClient) -> AgentContext:
    registry = PromptRegistry(settings.prompts_dir)
    return AgentContext(llm=llm, prompts=registry, embedder=None)


# ---------------------------------------------------------------------------
# TC-4.4.1: happy path — runbooks returned, LLM parses, best_match_id set
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_4_1_happy_path() -> None:
    with patch("app.agents.runbook._fetch_runbooks", new=AsyncMock(return_value=_RUNBOOK_RESPONSE)):
        result = await runbook(_make_task(), _ctx(_FakeLLM()))

    assert result.status == "ok"
    assert result.output["best_match_id"] == "rb-001"
    assert result.output["confidence"] > 0
    assert len(result.output["relevant_steps"]) > 0
    assert result.prompt_version is not None


# ---------------------------------------------------------------------------
# TC-4.4.2: no candidates — LLM never called, confidence=0.0
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_4_2_no_candidates_skips_llm() -> None:
    fake_llm = _FakeLLM()
    fake_llm.complete = AsyncMock()  # type: ignore[method-assign]

    with patch("app.agents.runbook._fetch_runbooks", new=AsyncMock(return_value=[])):
        result = await runbook(_make_task(), _ctx(fake_llm))

    fake_llm.complete.assert_not_called()
    assert result.status == "no_data"
    assert result.output["matched_runbooks"] == []
    assert result.output["confidence"] == 0.0
    assert "no runbooks" in result.output["assessment"]


# ---------------------------------------------------------------------------
# TC-4.4.3: LLM returns garbage — fallback used, matched_runbooks filled
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_4_3_llm_unparseable_uses_fallback() -> None:
    with patch("app.agents.runbook._fetch_runbooks", new=AsyncMock(return_value=_RUNBOOK_RESPONSE)):
        result = await runbook(_make_task(), _ctx(_FakeLLM("not json at all")))

    assert result.status == "degraded"
    assert len(result.output["matched_runbooks"]) == 2
    assert result.output["confidence"] == 0.0


# ---------------------------------------------------------------------------
# TC-4.4.4: httpx.ReadTimeout → graceful fallback, no exception raised
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_4_4_timeout_returns_empty_gracefully() -> None:
    mock_client = AsyncMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    mock_client.get = AsyncMock(side_effect=httpx.ReadTimeout("timed out"))

    with patch("app.agents.runbook.httpx.AsyncClient", return_value=mock_client):
        result = await runbook(_make_task(), _ctx(_FakeLLM()))

    assert result.status == "no_data"
    assert result.output["matched_runbooks"] == []
    assert result.output["confidence"] == 0.0


# ---------------------------------------------------------------------------
# TC-4.4.5: agent_name is "runbook"
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_4_5_agent_name() -> None:
    with patch("app.agents.runbook._fetch_runbooks", new=AsyncMock(return_value=_RUNBOOK_RESPONSE)):
        result = await runbook(_make_task(), _ctx(_FakeLLM()))

    assert result.agent_name == "runbook"
