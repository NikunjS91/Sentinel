"""Log Analyzer agent — the first specialist in the Sentinel swarm.

Reads recent logs for the affected service, asks the LLM to identify
patterns, returns a structured AgentResult."""

from __future__ import annotations

import json
import logging
import re
from datetime import timedelta

from pydantic import BaseModel, ValidationError

from ..llm.base import LLMClient
from ..models import AgentResult, AgentTask
from ..prompt_registry import PromptRegistry
from ..tools.logs import query_logs
from .types import LogAnalyzerFinding

log = logging.getLogger(__name__)

_LOG_WINDOW = timedelta(minutes=10)


class _LLMStats(BaseModel):
    total_tokens: int = 0
    total_latency_ms: int = 0


async def log_analyzer(
    task: AgentTask, llm: LLMClient, prompts: PromptRegistry
) -> AgentResult:
    """Run the Log Analyzer on one incident."""
    logql = f'{{service="{task.service}"}}'
    log_result = await query_logs(logql, window=_LOG_WINDOW)

    prompt = prompts.get("log_analyzer")
    rendered = prompt.body.replace("{log_summary}", log_result.model_dump_json())

    finding, stats = await _call_with_retry(llm, rendered)

    return AgentResult(
        incident_id=task.incident_id,
        agent_name="log_analyzer",
        output=finding.model_dump(),
        tokens_used=stats.total_tokens,
        latency_ms=stats.total_latency_ms,
        status="ok",
        prompt_version=prompt.version,
    )


async def _call_with_retry(
    llm: LLMClient, prompt: str
) -> tuple[LogAnalyzerFinding, _LLMStats]:
    """Call the LLM; if the response isn't parseable JSON, send a corrective
    nudge once. If that also fails, return a low-confidence fallback."""
    stats = _LLMStats()

    resp = await llm.complete(prompt)
    stats.total_tokens += resp.tokens
    stats.total_latency_ms += resp.latency_ms
    parsed = _try_parse(resp.text)
    if parsed is not None:
        return parsed, stats

    log.warning("log_analyzer first parse failed; retrying with nudge")
    nudge = prompt + (
        "\n\nIMPORTANT: Your previous response was not valid JSON. Return "
        "ONLY a single JSON object matching the schema above. No prose, "
        "no markdown fences."
    )
    resp2 = await llm.complete(nudge)
    stats.total_tokens += resp2.tokens
    stats.total_latency_ms += resp2.latency_ms
    parsed = _try_parse(resp2.text)
    if parsed is not None:
        return parsed, stats

    log.error("log_analyzer parse failed after retry; returning fallback")
    return LogAnalyzerFinding(
        error_patterns=[],
        most_likely_symptom=None,
        supporting_evidence=["agent output was not parseable JSON"],
        confidence=0.0,
    ), stats


_FENCE_RE = re.compile(r"^```(?:json)?|```$", re.MULTILINE)


def _try_parse(text: str) -> LogAnalyzerFinding | None:
    """Strip common LLM clutter, try to parse, return None on failure."""
    cleaned = _FENCE_RE.sub("", text).strip()
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end == -1 or end < start:
        return None
    candidate = cleaned[start : end + 1]
    try:
        data = json.loads(candidate)
        return LogAnalyzerFinding.model_validate(data)
    except (json.JSONDecodeError, ValidationError):
        return None
