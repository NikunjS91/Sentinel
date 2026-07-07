import logging
from collections import Counter
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
from pydantic import BaseModel, Field

from ..settings import settings

logger = logging.getLogger(__name__)


class LogQueryResult(BaseModel):
    query: str
    line_count: int
    sampled_lines: list[str] = Field(default_factory=list)
    top_levels: dict[str, int] = Field(default_factory=dict)
    top_messages: list[tuple[str, int]] = Field(default_factory=list)
    time_range: tuple[str, str]
    truncated: bool = False
    status: str = "ok"
    error: str | None = None


async def query_logs(
    logql: str,
    *,
    start: datetime | None = None,
    end: datetime | None = None,
    window: timedelta = timedelta(minutes=10),
    limit: int = 5_000,
) -> LogQueryResult:
    end = end or datetime.now(tz=UTC)
    start = start or (end - window)
    time_range = (start.isoformat(), end.isoformat())

    if settings.tool_mode == "fixture":
        return _fixture(logql, time_range)

    try:
        lines = await _call_loki(logql, start, end, limit)
    except httpx.TimeoutException:
        return LogQueryResult(
            query=logql,
            line_count=0,
            time_range=time_range,
            status="timeout",
            error="Loki request timed out",
        )
    except Exception as exc:  # noqa: BLE001
        return LogQueryResult(
            query=logql,
            line_count=0,
            time_range=time_range,
            status="error",
            error=str(exc),
        )

    return _summarize(logql, lines, time_range)


async def _call_loki(
    logql: str,
    start: datetime,
    end: datetime,
    limit: int,
) -> list[str]:
    params: dict[str, Any] = {
        "query": logql,
        "start": int(start.timestamp() * 1e9),
        "end": int(end.timestamp() * 1e9),
        "limit": limit,
        "direction": "forward",
    }
    async with httpx.AsyncClient(timeout=settings.log_query_timeout_s) as client:
        resp = await client.get(f"{settings.loki_url}/loki/api/v1/query_range", params=params)
        resp.raise_for_status()
        data = resp.json()

    lines: list[str] = []
    for stream in data.get("data", {}).get("result", []):
        for _ts, line in stream.get("values", []):
            lines.append(line)
    return lines


def _summarize(
    logql: str,
    lines: list[str],
    time_range: tuple[str, str],
) -> LogQueryResult:
    if not lines:
        return LogQueryResult(
            query=logql,
            line_count=0,
            time_range=time_range,
            status="empty",
        )

    n = len(lines)
    cap = settings.log_sample_limit
    truncated = n > cap
    if truncated:
        step = n / cap
        sampled = [lines[int(i * step)] for i in range(cap)]
    else:
        sampled = list(lines)

    level_order = ("ERROR", "WARN", "INFO", "DEBUG")
    level_counts: Counter[str] = Counter()
    for line in lines:
        upper = line.upper()
        for lvl in level_order:
            if lvl in upper:
                level_counts[lvl] += 1
                break

    top_msgs = Counter(line[:80] for line in lines).most_common(5)

    return LogQueryResult(
        query=logql,
        line_count=n,
        sampled_lines=sampled,
        top_levels=dict(level_counts),
        top_messages=top_msgs,
        time_range=time_range,
        truncated=truncated,
        status="ok",
    )


_FIXTURES: dict[str, list[str]] = {
    "downstream_timeout": [
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"ERROR","message":"downstream_timeout","dependency":"payment-gateway","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-001","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-002","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-003","service":"demo-app"}',
    ],
    "order_rejected": [
        '{"level":"WARN","message":"order_rejected","reason":"insufficient_stock","service":"demo-app"}',
        '{"level":"WARN","message":"order_rejected","reason":"insufficient_stock","service":"demo-app"}',
        '{"level":"WARN","message":"order_rejected","reason":"insufficient_stock","service":"demo-app"}',
        '{"level":"WARN","message":"order_rejected","reason":"insufficient_stock","service":"demo-app"}',
        '{"level":"WARN","message":"order_rejected","reason":"insufficient_stock","service":"demo-app"}',
    ],
    "_default": [
        '{"level":"INFO","message":"order_created","orderId":"o-001","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-002","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-003","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-004","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-005","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-006","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-007","service":"demo-app"}',
        '{"level":"INFO","message":"order_created","orderId":"o-008","service":"demo-app"}',
    ],
}


def _fixture(logql: str, time_range: tuple[str, str]) -> LogQueryResult:
    for key, lines in _FIXTURES.items():
        if key == "_default":
            continue
        if key in logql:
            return _summarize(logql, lines, time_range)
    return _summarize(logql, _FIXTURES["_default"], time_range)
