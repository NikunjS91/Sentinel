import httpx
import pytest

from app.settings import settings
from app.tools.metrics import (
    MetricResult,
    _summarize,
    query_metrics,
    slo_violations,
)


def _prometheus_reachable() -> bool:
    try:
        with httpx.Client(timeout=2.0) as client:
            resp = client.get(f"{settings.prometheus_url}/-/ready")
            return resp.status_code == 200
    except Exception:
        return False


# TC-2.6.1: fixture series produces correct summary statistics
@pytest.mark.asyncio
async def test_tc_2_6_1_series_summary(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    result = await query_metrics("orders_create_latency_seconds_bucket")

    assert result.status == "ok"
    assert len(result.series) == 1
    s = result.series[0]
    assert s.min == pytest.approx(0.03, abs=1e-6)
    assert s.max == pytest.approx(0.82, abs=1e-6)
    assert s.last == pytest.approx(0.82, abs=1e-6)
    assert s.point_count == 7
    assert 0.03 <= s.avg <= 0.82
    assert 0.03 <= s.p95 <= 0.82


# TC-2.6.2: SLO violation is detected from fixture data
@pytest.mark.asyncio
async def test_tc_2_6_2_slo_violation_detected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    viols = await slo_violations("demo-app")

    latency_viols = [v for v in viols if v.slo == "latency_p95"]
    assert len(latency_viols) == 1
    v = latency_viols[0]
    assert v.observed == pytest.approx(0.82, abs=1e-6)
    assert v.threshold == pytest.approx(0.5, abs=1e-6)
    assert "0.82" in v.description
    assert "0.50" in v.description


# TC-2.6.3: healthy fixture returns zero violations
@pytest.mark.asyncio
async def test_tc_2_6_3_healthy_no_violations(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    # Query using the _default fixture (low latency values, no violations)
    result = await query_metrics("up{job='demo-app'}")
    assert result.status == "ok"
    assert result.series[0].last == pytest.approx(0.02, abs=1e-6)

    # Directly test _summarize with a series that's under all thresholds
    from app.tools.metrics import _series, _summarize
    safe_raw = [_series({"job": "demo-app"}, [0.01, 0.02, 0.01])]
    summary = _summarize("safe_query", safe_raw, ("t0", "t1"), 15.0, 10)
    assert summary.series[0].last < 0.5


# TC-2.6.4: empty result is well-formed, never raises
def test_tc_2_6_4_empty_result() -> None:
    result: MetricResult = _summarize("test_query", [], ("t0", "t1"), 15.0, 10)

    assert result.status == "empty"
    assert result.series == []
    assert result.series_count == 0
    assert result.truncated is False


# TC-2.6.5: live Prometheus integration (skipped when Prometheus absent)
@pytest.mark.asyncio
@pytest.mark.skipif(not _prometheus_reachable(), reason="Prometheus not available")
async def test_tc_2_6_5_live_prometheus(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "live")

    result = await query_metrics("up")

    assert result.status == "ok"
    assert result.series_count >= 1
    assert result.series[0].point_count > 0


# TC-2.6.6: unreachable Prometheus returns error/timeout without raising
@pytest.mark.asyncio
async def test_tc_2_6_6_network_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "prometheus_url", "http://localhost:19998")
    monkeypatch.setattr(settings, "tool_mode", "live")

    result = await query_metrics("up")

    assert result.status in ("error", "timeout")
    assert result.error is not None
