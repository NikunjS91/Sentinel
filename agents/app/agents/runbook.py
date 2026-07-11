"""Runbook agent — knowledge-base runbook matching.

Queries the orchestrator's /kb/runbooks endpoint with a full-text search
derived from the incident context, then asks the LLM whether any returned
runbook genuinely applies and which steps are relevant."""

from __future__ import annotations

import json
import logging

import httpx

from ..models import AgentResult, AgentTask
from ..settings import settings
from ._context import AgentContext
from ._parse import call_with_retry
from .types import RunbookFinding, RunbookMatch

log = logging.getLogger(__name__)

_LIMIT = 5


async def runbook(task: AgentTask, ctx: AgentContext) -> AgentResult:
    """Match the incident against operational runbooks in the knowledge base."""

    query = _build_query(task)
    candidates = await _fetch_runbooks(query)

    if not candidates:
        log.info("runbook: no runbooks found for %s — skipping LLM call", task.service)
        finding = RunbookFinding(
            matched_runbooks=[],
            assessment=f"no runbooks found for {task.service}",
            confidence=0.0,
        )
        return _success(task, finding, tokens=0, latency=0, prompt_version=None, status="no_data")

    prompt = ctx.prompts.get("runbook")
    rendered = prompt.body.replace("{current_incident}", json.dumps(_incident_view(task))).replace(
        "{runbooks}", json.dumps(candidates)
    )

    fallback = RunbookFinding(
        matched_runbooks=[_to_match(c) for c in candidates],
        assessment="(LLM output unparseable)",
        confidence=0.0,
    )
    finding, stats = await call_with_retry(
        ctx.llm,
        rendered,
        RunbookFinding,
        fallback,
        agent_name="runbook",
    )

    if not finding.matched_runbooks:
        finding.matched_runbooks = [_to_match(c) for c in candidates]

    return _success(
        task, finding, stats.total_tokens, stats.total_latency_ms, prompt.version, stats.status
    )


async def _fetch_runbooks(query: str) -> list[dict[str, object]]:
    """GET /kb/runbooks?q=<query>&limit=5. Returns the raw list or []."""
    url = f"{settings.orchestrator_url}/kb/runbooks"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url, params={"q": query, "limit": _LIMIT})
            resp.raise_for_status()
            return resp.json()  # type: ignore[no-any-return]
    except httpx.TimeoutException as e:
        log.warning("runbook: /kb/runbooks timed out: %s", e)
        return []
    except Exception:  # noqa: BLE001
        log.exception("runbook: /kb/runbooks failed")
        return []


def _build_query(task: AgentTask) -> str:
    """Build a full-text search query from the incident context."""
    payload = task.payload or {}
    parts = [task.service]
    if alert_name := payload.get("alert_name"):
        parts.append(str(alert_name))
    if symptoms := payload.get("symptoms_summary"):
        parts.append(str(symptoms))
    return " ".join(parts)


def _incident_view(task: AgentTask) -> dict[str, object]:
    payload = task.payload or {}
    return {
        "incident_id": str(task.incident_id),
        "service": task.service,
        "severity": payload.get("severity"),
        "alert_name": payload.get("alert_name"),
        "symptoms_summary": payload.get("symptoms_summary"),
    }


def _to_match(c: dict[str, object]) -> RunbookMatch:
    tags_raw = c.get("tags") or []
    tags = [str(t) for t in tags_raw] if isinstance(tags_raw, list) else []
    return RunbookMatch(
        id=str(c.get("id", "")),
        title=str(c.get("title", "")),
        summary=str(c.get("summary", "")),
        tags=tags,
    )


def _success(
    task: AgentTask,
    finding: RunbookFinding,
    tokens: int,
    latency: int,
    prompt_version: str | None,
    status: str = "ok",
) -> AgentResult:
    return AgentResult(
        incident_id=task.incident_id,
        agent_name="runbook",
        output=finding.model_dump(),
        tokens_used=tokens,
        latency_ms=latency,
        status=status,
        prompt_version=prompt_version,
    )
