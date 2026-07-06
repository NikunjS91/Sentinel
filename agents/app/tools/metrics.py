"""PromQL query tool for the agent swarm.

Calls Prometheus' HTTP API, returns a bounded summarized result designed for
an LLM prompt. Numeric time series are collapsed to min/max/avg/last/p95 per
series — never raw point lists."""

from __future__ import annotations

import logging
import math
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
from pydantic import BaseModel, Field

from ..settings import settings

log = logging.getLogger(__name__)


class SeriesSummary(BaseModel):
    """One time series, reduced to a handful of statistics."""

    labels: dict[str, str]
    point_count: int
    min: float
    max: float
    avg: float
    last: float
    p95: float


class MetricResult(BaseModel):
    """What `query_metrics` returns. LLM-prompt sized."""

    query: str
    time_range: tuple[str, str]
    step_s: float
    series: list[SeriesSummary] = Field(default_factory=list)
    series_count: int = 0
    truncated: bool = False
    status: str = "ok"
    error: str | None = None


class SLOViolation(BaseModel):
    """A single SLO breach detected by `slo_violations`."""

    slo: str
    observed: float
    threshold: float
    description: str


async def query_metrics(
    promql: str,
    *,
    start: datetime | None = None,
    end: datetime | None = None,
    window: timedelta = timedelta(minutes=10),
    step_s: float = 15.0,
    max_series: int = 10,
) -> MetricResult:
    """Run a PromQL range query and return a summarized result.

    In fixture mode: returns canned data, no network.
    In live mode: calls Prometheus, summarizes, never raises.
    Caps series at `max_series` to bound prompt size.
    """
    if end is None:
        end = datetime.now(tz=UTC)
    if start is None:
        start = end - window
    time_range = (start.isoformat(), end.isoformat())

    if settings.tool_mode == "fixture":
        return _fixture(promql, time_range, step_s)

    try:
        raw = await _call_prometheus(promql, start, end, step_s)
    except httpx.TimeoutException as exc:
        log.warning("query_metrics timeout: %s", exc)
        return MetricResult(
            query=promql,
            time_range=time_range,
            step_s=step_s,
            status="timeout",
            error=str(exc),
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("query_metrics failed")
        return MetricResult(
            query=promql,
            time_range=time_range,
            step_s=step_s,
            status="error",
            error=str(exc),
        )

    return _summarize(promql, raw, time_range, step_s, max_series)


async def _call_prometheus(
    promql: str,
    start: datetime,
    end: datetime,
    step_s: float,
) -> list[dict[str, Any]]:
    """Return the raw `result` list from Prometheus' range query."""
    params: dict[str, Any] = {
        "query": promql,
        "start": start.timestamp(),
        "end": end.timestamp(),
        "step": str(step_s),
    }
    async with httpx.AsyncClient(timeout=settings.metric_query_timeout_s) as client:
        resp = await client.get(
            f"{settings.prometheus_url}/api/v1/query_range",
            params=params,
        )
        resp.raise_for_status()
        data: dict[str, Any] = resp.json()

    if data.get("status") != "success":
        raise RuntimeError(f"prometheus query failed: {data.get('error')}")
    result: list[dict[str, Any]] = data.get("data", {}).get("result", [])
    return result


def _summarize(
    promql: str,
    raw: list[dict[str, Any]],
    time_range: tuple[str, str],
    step_s: float,
    max_series: int,
) -> MetricResult:
    if not raw:
        return MetricResult(
            query=promql,
            time_range=time_range,
            step_s=step_s,
            status="empty",
        )

    total = len(raw)
    truncated = total > max_series
    kept = raw[:max_series]

    summaries: list[SeriesSummary] = []
    for series in kept:
        labels: dict[str, str] = series.get("metric", {})
        values_raw: list[Any] = series.get("values", [])
        nums = [float(v[1]) for v in values_raw if v[1] != "NaN"]
        nums = [n for n in nums if not math.isnan(n)]
        if not nums:
            continue
        nums_sorted = sorted(nums)
        p95_idx = max(0, int(len(nums_sorted) * 0.95) - 1)
        summaries.append(
            SeriesSummary(
                labels=labels,
                point_count=len(nums),
                min=min(nums),
                max=max(nums),
                avg=sum(nums) / len(nums),
                last=nums[-1],
                p95=nums_sorted[p95_idx],
            )
        )

    return MetricResult(
        query=promql,
        time_range=time_range,
        step_s=step_s,
        series=summaries,
        series_count=total,
        truncated=truncated,
        status="ok" if summaries else "empty",
    )


_SLO_THRESHOLDS = {
    "latency_p95_s": 0.5,
    "error_rate_pct": 5.0,
    "heap_used_mb": 300.0,
}


async def slo_violations(
    service: str = "demo-app",
    *,
    window: timedelta = timedelta(minutes=10),
) -> list[SLOViolation]:
    """Check the three demo-app SLOs and return any violations."""
    violations: list[SLOViolation] = []

    p95_query = (
        f"histogram_quantile(0.95, "
        f'sum by (le) (rate(orders_create_latency_seconds_bucket{{job="{service}"}}[5m])))'
    )
    p95 = await query_metrics(p95_query, window=window)
    if p95.status == "ok" and p95.series:
        last_p95 = p95.series[0].last
        if last_p95 > _SLO_THRESHOLDS["latency_p95_s"]:
            violations.append(
                SLOViolation(
                    slo="latency_p95",
                    observed=last_p95,
                    threshold=_SLO_THRESHOLDS["latency_p95_s"],
                    description=(
                        f"p95 order-create latency {last_p95:.2f}s exceeds "
                        f"{_SLO_THRESHOLDS['latency_p95_s']:.2f}s SLO"
                    ),
                )
            )

    err_query = (
        f"100 * sum(rate(http_server_requests_seconds_count"
        f'{{job="{service}",status=~"5.."}}[5m])) / '
        f'sum(rate(http_server_requests_seconds_count{{job="{service}"}}[5m]))'
    )
    err = await query_metrics(err_query, window=window)
    if err.status == "ok" and err.series:
        err_rate = err.series[0].last
        if not math.isnan(err_rate) and err_rate > _SLO_THRESHOLDS["error_rate_pct"]:
            violations.append(
                SLOViolation(
                    slo="error_rate",
                    observed=err_rate,
                    threshold=_SLO_THRESHOLDS["error_rate_pct"],
                    description=(
                        f"5xx error rate {err_rate:.1f}% exceeds "
                        f"{_SLO_THRESHOLDS['error_rate_pct']:.1f}% SLO"
                    ),
                )
            )

    heap_query = f'jvm_memory_used_bytes{{job="{service}",area="heap"}} / (1024 * 1024)'
    heap = await query_metrics(heap_query, window=window)
    if heap.status == "ok" and heap.series:
        last_heap = max((s.last for s in heap.series), default=0.0)
        if last_heap > _SLO_THRESHOLDS["heap_used_mb"]:
            violations.append(
                SLOViolation(
                    slo="heap_usage",
                    observed=last_heap,
                    threshold=_SLO_THRESHOLDS["heap_used_mb"],
                    description=(
                        f"heap used {last_heap:.0f} MB exceeds "
                        f"{_SLO_THRESHOLDS['heap_used_mb']:.0f} MB SLO"
                    ),
                )
            )

    return violations


def _series(labels: dict[str, str], points: list[float]) -> dict[str, Any]:
    ts0 = 1_700_000_000
    return {
        "metric": labels,
        "values": [[ts0 + i * 15.0, f"{v}"] for i, v in enumerate(points)],
    }


_FIXTURES: dict[str, list[dict[str, Any]]] = {
    "orders_create_latency": [
        _series({"job": "demo-app"}, [0.03, 0.05, 0.4, 0.7, 0.81, 0.79, 0.82]),
    ],
    "jvm_memory_used_bytes": [
        _series(
            {"job": "demo-app", "area": "heap", "id": "G1 Eden Space"},
            [120_000_000, 180_000_000, 240_000_000, 310_000_000, 350_000_000],
        ),
    ],
    "5..": [
        _series({"job": "demo-app"}, [0.0, 0.0, 10.0, 55.0, 62.0, 58.0]),
    ],
    "_default": [
        _series({"job": "demo-app"}, [0.02, 0.03, 0.02, 0.03, 0.02]),
    ],
}


def _fixture(
    promql: str,
    time_range: tuple[str, str],
    step_s: float,
) -> MetricResult:
    for key, series in _FIXTURES.items():
        if key != "_default" and key in promql:
            return _summarize(promql, series, time_range, step_s, max_series=10)
    return _summarize(promql, _FIXTURES["_default"], time_range, step_s, max_series=10)
