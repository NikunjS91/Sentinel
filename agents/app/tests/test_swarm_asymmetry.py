"""The architectural-thesis test: two specialists, two perspectives.

On a slow_query failure, the Log Analyzer agent should be UNCERTAIN
(low confidence, no clear pattern) because slow queries don't produce
distinctive log lines. The Metrics agent should be CONFIDENT (high
confidence, SLO violation cited) because slow queries produce a clean
metric signature.

This asymmetry is what motivates the swarm. If both agents always
return identical confidence, there's no value in having two."""

from __future__ import annotations

import json

import pytest

from app.agents._context import AgentContext
from app.agents.log_analyzer import log_analyzer
from app.agents.metrics_agent import metrics_agent
from app.llm.base import LLMResponse
from app.models import AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import settings


class _FakeLLM:
    """Returns canned responses keyed by what's in the prompt — calibrated
    to mimic the real Day-18 e2e behavior on slow_query."""

    def __init__(self, log_response: str, metrics_response: str) -> None:
        self._log_response = log_response
        self._metrics_response = metrics_response

    async def complete(self, prompt: str) -> LLMResponse:
        # Route by the prompt's distinctive marker.
        if "Log Analyzer agent" in prompt:
            text = self._log_response
        elif "Metrics agent" in prompt:
            text = self._metrics_response
        else:
            text = '{"confidence": 0}'
        return LLMResponse(text=text, tokens=100, latency_ms=500)


@pytest.mark.asyncio
async def test_slow_query_produces_swarm_asymmetry(monkeypatch):
    """TC-2.11.2 — On slow_query the agents reach DIFFERENT conclusions.

    Log Analyzer sees ordinary INFO logs → low confidence.
    Metrics agent sees SLO violation → high confidence.

    This is the architectural justification for the swarm."""

    # 1. Force the tools into fixture mode so we get the slow_query
    #    metric signature (latency p95 > threshold).
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    # 2. Set up the fake LLM with calibrated responses.
    log_response = json.dumps({
        "error_patterns": [],
        "most_likely_symptom": None,
        "supporting_evidence": ["only INFO-level order_created lines observed"],
        "confidence": 0.15,
    })
    metrics_response = json.dumps({
        "slo_status": "violations_present",
        "anomalies": ["p95 order-create latency 0.82s exceeds 0.50s SLO"],
        "most_likely_cause": "downstream DB query latency",
        "confidence": 0.88,
    })
    fake = _FakeLLM(log_response, metrics_response)

    # 3. Use the real prompt registry — the prompts shape the LLM input
    #    and we want this test to break if a prompt change breaks the
    #    pattern.
    registry = PromptRegistry(settings.prompts_dir)
    ctx = AgentContext(llm=fake, prompts=registry)

    # 4. Same task to both agents.
    task = AgentTask(
        incident_id="00000000-0000-0000-0000-000000000001",
        agent_name="log_analyzer",  # the dispatch name doesn't matter here
        service="demo-app",
        payload=None,
    )

    # 5. Run both.
    log_result = await log_analyzer(task, ctx)
    metrics_result = await metrics_agent(task, ctx)

    # 6. ASSERT THE ASYMMETRY.
    log_confidence = log_result.output["confidence"]
    metric_confidence = metrics_result.output["confidence"]

    # Hard threshold: at least 0.5 difference. Calibrated to be far above
    # noise but not so tight it breaks on prompt tuning.
    assert metric_confidence - log_confidence > 0.5, (
        f"Expected metrics confidence to exceed log confidence by > 0.5 "
        f"on slow_query. Got log={log_confidence}, metric={metric_confidence}. "
        f"If this test fails, the swarm has lost its differentiating value."
    )

    # Both agents should still produce valid, well-formed output.
    assert log_result.status == "ok"
    assert metrics_result.status == "ok"
    assert metrics_result.output["slo_status"] == "violations_present"
