"""History agent — vector-similarity matching against past incidents.

Embeds the current incident's symptoms, queries the orchestrator's
/kb/search endpoint, and returns the LLM's assessment of which (if
any) past incident genuinely applies."""

from __future__ import annotations

import json
import logging

import httpx

from ..models import AgentResult, AgentTask
from ..settings import settings
from ._context import AgentContext
from ._parse import call_with_retry
from .types import HistoryFinding, MatchedIncident

log = logging.getLogger(__name__)

_TOP_K = 5


async def history(task: AgentTask, ctx: AgentContext) -> AgentResult:
    """Find past incidents similar to the current one."""
    if ctx.embedder is None:
        return _fallback(task, "history agent has no embedder configured")

    query_text = _build_query_text(task)

    try:
        emb_resp = await ctx.embedder.embed(query_text)
    except Exception as e:  # noqa: BLE001
        log.exception("history: embedding failed")
        return _fallback(task, f"embedding failed: {e}")

    candidates = await _search_kb(emb_resp.vector)
    if not candidates:
        finding = HistoryFinding(
            matched_incidents=[],
            most_relevant_match_id=None,
            assessment="no candidates returned from knowledge base",
            confidence=0.0,
        )
        return _success(task, finding, 0, 0, None)

    prompt = ctx.prompts.get("history")
    rendered = (
        prompt.body
        .replace("{current_incident}", json.dumps(_current_incident_view(task)))
        .replace("{candidates}", json.dumps(candidates))
    )

    fallback_finding = HistoryFinding(
        matched_incidents=[_to_matched(c) for c in candidates[:_TOP_K]],
        most_relevant_match_id=None,
        assessment="(LLM output unparseable)",
        confidence=0.0,
    )
    finding, stats = await call_with_retry(
        ctx.llm, rendered, HistoryFinding, fallback_finding,
        agent_name="history",
    )

    if not finding.matched_incidents:
        finding = finding.model_copy(
            update={"matched_incidents": [_to_matched(c) for c in candidates[:_TOP_K]]}
        )

    return _success(task, finding, stats.total_tokens, stats.total_latency_ms, prompt.version)


def _build_query_text(task: AgentTask) -> str:
    payload = task.payload or {}
    parts = [
        f"service: {task.service}",
        f"severity: {payload.get('severity', 'unknown')}",
    ]
    if alert_name := payload.get("alert_name"):
        parts.append(f"alert: {alert_name}")
    if symptoms := payload.get("symptoms_summary"):
        parts.append(f"symptoms: {symptoms}")
    return "\n".join(parts)


def _current_incident_view(task: AgentTask) -> dict[str, object]:
    payload = task.payload or {}
    return {
        "incident_id": str(task.incident_id),
        "service": task.service,
        "severity": payload.get("severity"),
        "alert_name": payload.get("alert_name"),
        "symptoms_summary": payload.get("symptoms_summary"),
    }


async def _search_kb(embedding: list[float]) -> list[dict[str, object]]:
    embedding_str = "[" + ",".join(f"{v:.6f}" for v in embedding) + "]"
    body = {
        "embedding": embedding_str,
        "source_type": "past_incident",
        "limit": _TOP_K,
    }
    url = f"{settings.orchestrator_url}/kb/search"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(url, json=body)
            resp.raise_for_status()
            return resp.json()  # type: ignore[no-any-return]
    except httpx.TimeoutException as e:
        log.warning("history: /kb/search timed out: %s", e)
        return []
    except Exception:  # noqa: BLE001
        log.exception("history: /kb/search failed")
        return []


def _to_matched(c: dict[str, object]) -> MatchedIncident:
    return MatchedIncident(
        id=str(c.get("id", "")),
        title=str(c.get("title", "")),
        distance=float(c.get("distance", 1.0)),  # type: ignore[arg-type]
        metadata=c.get("metadata") or {},
    )


def _success(
    task: AgentTask,
    finding: HistoryFinding,
    stats_tokens: int,
    stats_latency: int,
    prompt_version: str | None,
) -> AgentResult:
    return AgentResult(
        incident_id=task.incident_id,
        agent_name="history",
        output=finding.model_dump(),
        tokens_used=stats_tokens or 0,
        latency_ms=stats_latency or 0,
        status="ok",
        prompt_version=prompt_version,
    )


def _fallback(task: AgentTask, reason: str) -> AgentResult:
    finding = HistoryFinding(
        matched_incidents=[],
        most_relevant_match_id=None,
        assessment=f"(history agent could not run: {reason})",
        confidence=0.0,
    )
    return _success(task, finding, 0, 0, None)
