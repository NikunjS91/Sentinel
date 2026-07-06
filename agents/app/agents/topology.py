"""Topology agent — service-graph awareness.

Calls the orchestrator's /kb/topology/{service} endpoint, gets the
neighbors of the affected service, asks the LLM to reason about which
neighbors are implicated in the current incident."""

from __future__ import annotations

import json
import logging

import httpx

from ..models import AgentResult, AgentTask
from ..settings import settings
from ._context import AgentContext
from ._parse import call_with_retry
from .types import TopologyFinding, TopologyNeighbor

log = logging.getLogger(__name__)


async def topology(task: AgentTask, ctx: AgentContext) -> AgentResult:
    """Reason about which neighboring services may be implicated."""

    topo = await _fetch_topology(task.service)
    neighbors = _flatten_neighbors(topo)

    if not neighbors:
        finding = TopologyFinding(
            neighbors=[],
            likely_implicated=[],
            assessment=f"no topology data available for {task.service}",
            confidence=0.0,
        )
        return _success(task, finding, tokens=0, latency=0, prompt_version=None)

    prompt = ctx.prompts.get("topology")
    rendered = prompt.body.replace(
        "{current_incident}", json.dumps(_current_incident_view(task))
    ).replace("{neighbors}", json.dumps([n.model_dump() for n in neighbors]))

    fallback = TopologyFinding(
        neighbors=neighbors,
        likely_implicated=[],
        assessment="(LLM output unparseable)",
        confidence=0.0,
    )
    finding, stats = await call_with_retry(
        ctx.llm,
        rendered,
        TopologyFinding,
        fallback,
        agent_name="topology",
    )

    if not finding.neighbors:
        finding.neighbors = neighbors

    return _success(task, finding, stats.total_tokens, stats.total_latency_ms, prompt.version)


async def _fetch_topology(service: str) -> dict[str, object]:
    """GET /kb/topology/{service}. Returns the raw response or empty dict."""
    url = f"{settings.orchestrator_url}/kb/topology/{service}"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            return resp.json()  # type: ignore[no-any-return]
    except httpx.TimeoutException as e:
        log.warning("topology: /kb/topology/%s timed out: %s", service, e)
        return {}
    except Exception:  # noqa: BLE001
        log.exception("topology: /kb/topology/%s failed", service)
        return {}


def _flatten_neighbors(topo: dict[str, object]) -> list[TopologyNeighbor]:
    """Convert {outgoing: [...], incoming: [...]} into a flat list."""
    out: list[TopologyNeighbor] = []
    outgoing: list[dict[str, object]] = topo.get("outgoing") or []  # type: ignore[assignment]
    incoming: list[dict[str, object]] = topo.get("incoming") or []  # type: ignore[assignment]
    for link in outgoing:
        out.append(
            TopologyNeighbor(
                service=str(link.get("to_service") or link.get("toService", "")),
                direction="outgoing",
                relationship=str(link.get("relationship", "")),
                metadata=link.get("metadata") or {},
            )
        )
    for link in incoming:
        out.append(
            TopologyNeighbor(
                service=str(link.get("from_service") or link.get("fromService", "")),
                direction="incoming",
                relationship=str(link.get("relationship", "")),
                metadata=link.get("metadata") or {},
            )
        )
    return out


def _current_incident_view(task: AgentTask) -> dict[str, object]:
    payload = task.payload or {}
    return {
        "incident_id": str(task.incident_id),
        "service": task.service,
        "severity": payload.get("severity"),
        "alert_name": payload.get("alert_name"),
        "symptoms_summary": payload.get("symptoms_summary"),
    }


def _success(
    task: AgentTask,
    finding: TopologyFinding,
    tokens: int,
    latency: int,
    prompt_version: str | None,
) -> AgentResult:
    return AgentResult(
        incident_id=task.incident_id,
        agent_name="topology",
        output=finding.model_dump(),
        tokens_used=tokens,
        latency_ms=latency,
        status="ok",
        prompt_version=prompt_version,
    )
