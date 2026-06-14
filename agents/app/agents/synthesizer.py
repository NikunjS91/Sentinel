"""Synthesizer — the meta-agent that combines specialist findings.

Unlike the three specialists (which read raw telemetry), the Synthesizer's
input is the other agents' outputs. Its task payload carries the
specialists' findings; it produces one unified report."""

from __future__ import annotations

import json
import logging

from ..models import AgentResult, AgentTask
from ._context import AgentContext
from ._parse import call_with_retry
from .types import SynthesizerFinding

log = logging.getLogger(__name__)


def _build_fallback(payload: dict[str, object], agent_names: list[str]) -> SynthesizerFinding:
    raw_missing = payload.get("missing_specialists", [])
    missing: list[str] = list(raw_missing) if isinstance(raw_missing, list) else []
    raw_findings = payload.get("specialist_findings", [])
    specialist_findings = raw_findings if isinstance(raw_findings, list) else []
    if not specialist_findings:
        notes = [f"missing specialist: {m}" for m in missing] or ["no specialists reported"]
        return SynthesizerFinding(
            summary="No specialist findings available within the deadline.",
            root_cause=None,
            recommended_action=None,
            confidence=0.0,
            dissenting_notes=notes,
            contributing_agents=[],
        )
    return SynthesizerFinding(
        summary="(synthesizer could not parse specialist findings)",
        root_cause=None,
        recommended_action=None,
        confidence=0.0,
        dissenting_notes=[f"missing specialist: {m}" for m in missing],
        contributing_agents=agent_names,
    )


async def synthesizer(task: AgentTask, ctx: AgentContext) -> AgentResult:
    payload = task.payload or {}
    specialist_findings: list[object] = (
        payload.get("specialist_findings", []) if isinstance(payload, dict) else []
    )
    incident_context = {
        "service": task.service,
        "alert": payload.get("alert_name") if isinstance(payload, dict) else None,
        "severity": payload.get("severity") if isinstance(payload, dict) else None,
    }

    prompt = ctx.prompts.get("synthesizer")
    rendered = (
        prompt.body
        .replace("{incident_context}", json.dumps(incident_context))
        .replace("{specialist_findings}", json.dumps(specialist_findings))
    )

    agent_names = [
        f.get("agent_name", "unknown") if isinstance(f, dict) else "unknown"
        for f in specialist_findings
    ]

    fallback = _build_fallback(payload if isinstance(payload, dict) else {}, agent_names)

    finding, stats = await call_with_retry(
        ctx.llm, rendered, SynthesizerFinding, fallback,
        agent_name="synthesizer",
    )

    if not finding.contributing_agents:
        finding.contributing_agents = agent_names

    return AgentResult(
        incident_id=task.incident_id,
        agent_name="synthesizer",
        output=finding.model_dump(),
        tokens_used=stats.total_tokens,
        latency_ms=stats.total_latency_ms,
        status="ok",
        prompt_version=prompt.version,
    )
