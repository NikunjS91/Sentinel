"""Tests for the Topology agent (TC-4.3.1 – TC-4.3.5)."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch
from uuid import uuid4

import httpx
import pytest

from app.agents._context import AgentContext
from app.agents.topology import _flatten_neighbors, topology
from app.llm.base import LLMClient, LLMResponse
from app.models import AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import settings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_TOPO_RESPONSE: dict[str, object] = {
    "service": "demo-app",
    "outgoing": [
        {"toService": "payment-service", "relationship": "calls", "metadata": {}},
        {"toService": "inventory-service", "relationship": "calls", "metadata": {}},
    ],
    "incoming": [
        {"fromService": "api-gateway", "relationship": "calls", "metadata": {}},
    ],
}

_LLM_TOPOLOGY_JSON = """{
  "neighbors": [
    {"service": "payment-service", "direction": "outgoing",
     "relationship": "calls", "metadata": {}},
    {"service": "inventory-service", "direction": "outgoing",
     "relationship": "calls", "metadata": {}},
    {"service": "api-gateway", "direction": "incoming",
     "relationship": "calls", "metadata": {}}
  ],
  "likely_implicated": ["payment-service"],
  "assessment": "payment-service is a likely cause given outgoing dependency",
  "confidence": 0.7
}"""


class _FakeLLM(LLMClient):
    def __init__(self, response_text: str = _LLM_TOPOLOGY_JSON) -> None:
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
        agent_name="topology",
        service="demo-app",
        payload={"severity": "critical", "alert_name": "HighErrorRate"},
    )


def _ctx(llm: LLMClient) -> AgentContext:
    registry = PromptRegistry(settings.prompts_dir)
    return AgentContext(llm=llm, prompts=registry, embedder=None)


# ---------------------------------------------------------------------------
# TC-4.3.1: happy path — 3 neighbors returned, prompt_version set, assessment non-null
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_3_1_happy_path_three_neighbors() -> None:
    with patch("app.agents.topology._fetch_topology", new=AsyncMock(return_value=_TOPO_RESPONSE)):
        result = await topology(_make_task(), _ctx(_FakeLLM()))

    assert result.status == "ok"
    assert len(result.output["neighbors"]) == 3
    assert result.output["assessment"] is not None
    assert result.prompt_version is not None


# ---------------------------------------------------------------------------
# TC-4.3.2: empty topology — no LLM call, neighbors=[], confidence=0.0
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_3_2_empty_topology_skips_llm() -> None:
    fake_llm = _FakeLLM()
    fake_llm.complete = AsyncMock()  # type: ignore[method-assign]

    with patch("app.agents.topology._fetch_topology", new=AsyncMock(return_value={})):
        result = await topology(_make_task(), _ctx(fake_llm))

    fake_llm.complete.assert_not_called()
    assert result.output["neighbors"] == []
    assert result.output["confidence"] == 0.0
    assert "no topology data available" in result.output["assessment"]


# ---------------------------------------------------------------------------
# TC-4.3.3: httpx.TimeoutException → graceful fallback, no exception raised
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_3_3_timeout_returns_empty_gracefully() -> None:
    mock_client = AsyncMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    mock_client.get = AsyncMock(side_effect=httpx.TimeoutException("timed out"))

    with patch("app.agents.topology.httpx.AsyncClient", return_value=mock_client):
        result = await topology(_make_task(), _ctx(_FakeLLM()))

    assert result.status == "ok"
    assert result.output["neighbors"] == []
    assert result.output["confidence"] == 0.0


# ---------------------------------------------------------------------------
# TC-4.3.4: LLM returns neighbors=[] → post-guard fills from _fetch_topology result
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_3_4_postguard_fills_neighbors_when_llm_returns_empty() -> None:
    empty_neighbors_json = """{
      "neighbors": [],
      "likely_implicated": [],
      "assessment": "no implication found",
      "confidence": 0.0
    }"""

    with patch("app.agents.topology._fetch_topology", new=AsyncMock(return_value=_TOPO_RESPONSE)):
        result = await topology(_make_task(), _ctx(_FakeLLM(empty_neighbors_json)))

    assert len(result.output["neighbors"]) == 3


# ---------------------------------------------------------------------------
# TC-4.3.5: _flatten_neighbors direction test — 2 outgoing + 1 incoming
# ---------------------------------------------------------------------------


def test_tc_4_3_5_flatten_neighbors_directions() -> None:
    neighbors = _flatten_neighbors(_TOPO_RESPONSE)

    assert len(neighbors) == 3

    outgoing = [n for n in neighbors if n.direction == "outgoing"]
    incoming = [n for n in neighbors if n.direction == "incoming"]

    assert len(outgoing) == 2
    assert len(incoming) == 1
    assert outgoing[0].service == "payment-service"
    assert outgoing[1].service == "inventory-service"
    assert incoming[0].service == "api-gateway"
