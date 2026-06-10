"""Metrics agent — the second specialist in the Sentinel swarm.

Reads summarized Prometheus metrics for the affected service, runs the
SLO checks, asks the LLM to identify anomalies and a most-likely cause,
returns a structured AgentResult."""

from __future__ import annotations

import logging
from datetime import timedelta

from pydantic import BaseModel

from ..models import AgentResult, AgentTask
from ..tools.metrics import query_metrics, slo_violations
from ._context import AgentContext
from ._parse import call_with_retry
from .types import MetricsAgentFinding

log = logging.getLogger(__name__)

_METRIC_WINDOW = timedelta(minutes=10)


class _MetricsSummary(BaseModel):
    slo_violations: list[dict]  # type: ignore[type-arg]
    p95_latency_series: list[dict]  # type: ignore[type-arg]
    error_rate_series: list[dict]  # type: ignore[type-arg]
    heap_usage_series: list[dict]  # type: ignore[type-arg]


async def metrics_agent(task: AgentTask, ctx: AgentContext) -> AgentResult:
    """Run the Metrics agent on one incident."""
    service = task.service

    p95_query = (
        "histogram_quantile(0.95, sum by (le) "
        f'(rate(orders_create_latency_seconds_bucket{{job="{service}"}}[5m])))'
    )
    err_query = (
        f"100 * sum(rate(http_server_requests_seconds_count"
        f'{{job="{service}",status=~"5.."}}[5m])) / '
        f'sum(rate(http_server_requests_seconds_count{{job="{service}"}}[5m]))'
    )
    heap_query = (
        f'jvm_memory_used_bytes{{job="{service}",area="heap"}} / (1024 * 1024)'
    )

    p95 = await query_metrics(p95_query, window=_METRIC_WINDOW)
    err = await query_metrics(err_query, window=_METRIC_WINDOW)
    heap = await query_metrics(heap_query, window=_METRIC_WINDOW)
    violations = await slo_violations(service, window=_METRIC_WINDOW)

    summary = _MetricsSummary(
        slo_violations=[v.model_dump() for v in violations],
        p95_latency_series=[s.model_dump() for s in p95.series],
        error_rate_series=[s.model_dump() for s in err.series],
        heap_usage_series=[s.model_dump() for s in heap.series],
    )

    # Day 17 lesson: use .replace(), NOT .format() — the prompt body contains
    # literal {...} in its JSON schema example which breaks str.format().
    prompt = ctx.prompts.get("metrics_agent")
    rendered = prompt.body.replace("{metrics_summary}", summary.model_dump_json())

    fallback = MetricsAgentFinding(
        slo_status="ok",
        anomalies=[],
        most_likely_cause=None,
        confidence=0.0,
    )
    finding, stats = await call_with_retry(
        ctx.llm, rendered, MetricsAgentFinding, fallback, agent_name="metrics"
    )

    return AgentResult(
        incident_id=task.incident_id,
        agent_name="metrics",
        output=finding.model_dump(),
        tokens_used=stats.total_tokens,
        latency_ms=stats.total_latency_ms,
        status="ok",
        prompt_version=prompt.version,
    )
