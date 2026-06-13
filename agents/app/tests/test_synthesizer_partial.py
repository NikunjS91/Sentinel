"""Tests for Synthesizer partial-input handling (TC-3.2.6, TC-3.2.7)."""

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

_ONE_FINDING = {
    "agent_name": "echo",
    "output": {"message": "acknowledged"},
    "status": "ok",
    "tokens_used": 20,
    "latency_ms": 50,
    "prompt_version": None,
}


def _make_task(
    findings: list[object] | None = None,
    payload_extras: dict[str, object] | None = None,
) -> AgentTask:
    payload: dict[str, object] = {
        "specialist_findings": findings if findings is not None else [],
        "alert_name": "HighLatency",
        "severity": "critical",
    }
    if payload_extras:
        payload.update(payload_extras)
    return AgentTask(
        incident_id=uuid4(),
        agent_name="synthesizer",
        service="demo-app",
        payload=payload,
    )


class _FakeLLM(LLMClient):
    def __init__(self, responses: list[str]) -> None:
        self._responses = list(responses)
        self._index = 0

    async def complete(self, prompt: str) -> LLMResponse:
        text = self._responses[self._index % len(self._responses)]
        self._index += 1
        return LLMResponse(text=text, tokens=50, latency_ms=100)


def _ctx(llm: LLMClient) -> AgentContext:
    registry = PromptRegistry(settings.prompts_dir)
    return AgentContext(llm=llm, prompts=registry)


# ---------------------------------------------------------------------------
# TC-3.2.6: zero findings + bad LLM → fallback with "No specialist findings"
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_3_2_6_zero_findings_fallback() -> None:
    llm = _FakeLLM(["not json", "still not json"])
    result = await synthesizer(_make_task(findings=[]), _ctx(llm))

    assert result.output["summary"].startswith("No specialist findings available")
    assert result.output["confidence"] == 0.0
    assert result.output["contributing_agents"] == []


# ---------------------------------------------------------------------------
# TC-3.2.7: one finding + missing_specialists → dissenting_notes mentions missing agents
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_tc_3_2_7_partial_input_dissenting_notes() -> None:
    partial_synthesis = {
        "summary": "Partial analysis: only echo agent reported.",
        "root_cause": None,
        "recommended_action": None,
        "confidence": 0.3,
        "dissenting_notes": [
            "missing specialist: log_analyzer",
            "missing specialist: metrics",
        ],
        "contributing_agents": ["echo"],
    }
    llm = _FakeLLM([json.dumps(partial_synthesis)])

    result = await synthesizer(
        _make_task(
            findings=[_ONE_FINDING],
            payload_extras={"missing_specialists": ["log_analyzer", "metrics"]},
        ),
        _ctx(llm),
    )

    assert len(result.output["dissenting_notes"]) > 0
    notes_text = " ".join(result.output["dissenting_notes"])
    assert "log_analyzer" in notes_text or "metrics" in notes_text
