"""Log Analyzer agent — the first specialist in the Sentinel swarm.

Reads recent logs for the affected service, asks the LLM to identify
patterns, returns a structured AgentResult."""

from __future__ import annotations

import logging
from datetime import timedelta

from ..models import AgentResult, AgentTask
from ..tools.logs import query_logs
from ._context import AgentContext
from ._parse import call_with_retry
from .types import LogAnalyzerFinding

log = logging.getLogger(__name__)

_LOG_WINDOW = timedelta(minutes=10)


async def log_analyzer(task: AgentTask, ctx: AgentContext) -> AgentResult:
    """Run the Log Analyzer on one incident."""
    logql = f'{{service="{task.service}"}}'
    log_result = await query_logs(logql, window=_LOG_WINDOW)

    prompt = ctx.prompts.get("log_analyzer")
    rendered = prompt.body.replace("{log_summary}", log_result.model_dump_json())

    fallback = LogAnalyzerFinding(
        error_patterns=[],
        most_likely_symptom=None,
        supporting_evidence=["agent output was not parseable JSON"],
        confidence=0.0,
    )
    finding, stats = await call_with_retry(
        ctx.llm, rendered, LogAnalyzerFinding, fallback, "log_analyzer"
    )

    return AgentResult(
        incident_id=task.incident_id,
        agent_name="log_analyzer",
        output=finding.model_dump(),
        tokens_used=stats.total_tokens,
        latency_ms=stats.total_latency_ms,
        status=stats.status,
        prompt_version=prompt.version,
    )
