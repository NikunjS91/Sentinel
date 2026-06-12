"""Tests for the Synthesizer agent (TC-3.1.1 – TC-3.1.3)."""

from __future__ import annotations

import json
from uuid import uuid4

import pytest

from app.agents._context import AgentContext
from app.agents.synthesizer import synthesizer
from app.llm.base import LLMClient, LLMResponse
from app.models import AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import settings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_SPECIALIST_FINDINGS = [
    {
        "agent_name": "log_analyzer",
        "output": {
            "error_patterns": ["db_timeout"],
            "most_likely_symptom": "database unreachable",
            "supporting_evidence": [],
            "confidence": 0.15,
        },
        "status": "ok",
        "tokens_used": 100,
        "latency_ms": 300,
        "prompt_version": "abc123",
    },
    {
        "agent_name": "metrics",
        "output": {
            "slo_status": "violated",
            "anomalies": ["p95_latency_high"],
            "most_likely_cause": "slow DB query causing p95 breach",
            "confidence": 0.88,
        },
        "status": "ok",
        "tokens_used": 150,
        "latency_ms": 400,
        "prompt_version": "def456",
    },
    {
        "agent_name": "echo",
        "output": {"message": "acknowledged"},
        "status": "ok",
        "tokens_used": 20,
        "latency_ms": 50,
        "prompt_version": None,
    },
]

_VALID_SYNTHESIS = {
    "summary": "Order-create latency p95 exceeded SLO; likely slow DB query.",
    "root_cause": "slow DB query causing timeout cascade",
    "recommended_action": "Check slow query log; scale read replica.",
    "confidence": 0.75,
    "dissenting_notes": [
        "log_analyzer low confidence; metrics agent high confidence with concrete p95 evidence"
    ],
    "contributing_agents": ["log_analyzer", "metrics", "echo"],
}


def _make_task(findings: list[object] | None = None) -> AgentTask:
    return AgentTask(
        incident_id=uuid4(),
        agent_name="synthesizer",
        service="demo-app",
        payload={
            "specialist_findings": findings if findings is not None else _SPECIALIST_FINDINGS,
            "alert_name": "HighLatency",
            "severity": "critical",
        },
    )


class _FakeLLM(LLMClient):
    def __init__(self, responses: list[str]) -> None:
        self._responses = list(responses)
        self._index = 0

    async def complete(self, prompt: str) -> LLMResponse:
        text = self._responses[self._index % len(self._responses)]
        self._index += 1
        return LLMResponse(text=text, tokens=100, latency_ms=500)


def _ctx(llm: LLMClient) -> AgentContext:
    registry = PromptRegistry(settings.prompts_dir)
    return AgentContext(llm=llm, prompts=registry)


# ---------------------------------------------------------------------------
# TC-3.1.1: happy path — structured report produced
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_3_1_1_synthesizer_happy_path() -> None:
    llm = _FakeLLM([json.dumps(_VALID_SYNTHESIS)])
    result = await synthesizer(_make_task(), _ctx(llm))

    assert result.status == "ok"
    assert result.agent_name == "synthesizer"
    assert result.output["summary"] == _VALID_SYNTHESIS["summary"]
    assert result.output["contributing_agents"] == ["log_analyzer", "metrics", "echo"]
    assert result.prompt_version is not None


# ---------------------------------------------------------------------------
# TC-3.1.2: asymmetric inputs produce a dissenting note
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_3_1_2_asymmetric_inputs_produce_dissenting_note() -> None:
    synthesis_with_dissent = dict(_VALID_SYNTHESIS)
    synthesis_with_dissent["dissenting_notes"] = [
        "log_analyzer reported low confidence (0.15); metrics agent reported high confidence (0.88)"
    ]
    llm = _FakeLLM([json.dumps(synthesis_with_dissent)])

    result = await synthesizer(_make_task(), _ctx(llm))

    assert len(result.output["dissenting_notes"]) > 0


# ---------------------------------------------------------------------------
# TC-3.1.3: persistent parse failure preserves contributing_agents
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_3_1_3_parse_failure_preserves_contributing_agents() -> None:
    llm = _FakeLLM(["not valid json at all", "still not json"])

    result = await synthesizer(_make_task(), _ctx(llm))

    assert result.output["confidence"] == 0.0
    assert result.output["summary"].startswith("(synthesizer could not parse")
    assert set(result.output["contributing_agents"]) == {"log_analyzer", "metrics", "echo"}
