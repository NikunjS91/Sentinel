import httpx
import pytest

from app.settings import settings
from app.tools.logs import LogQueryResult, _summarize, query_logs


def _loki_reachable() -> bool:
    try:
        with httpx.Client(timeout=2.0) as client:
            resp = client.get(f"{settings.loki_url}/ready")
            return "ready" in resp.text
    except Exception:
        return False


# TC-2.5.1: fixture mode truncates when sample_limit < total lines
@pytest.mark.asyncio
async def test_tc_2_5_1_truncation(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "log_sample_limit", 5)
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    result = await query_logs('{service="demo-app"} |= "downstream_timeout"')

    assert len(result.sampled_lines) == 5
    assert result.truncated is True


# TC-2.5.2: fixture mode returns correct level distribution without network
@pytest.mark.asyncio
async def test_tc_2_5_2_fixture_mode_offline(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "tool_mode", "fixture")

    result = await query_logs(
        '{service="demo-app", level="ERROR"} |= "downstream_timeout"'
    )

    assert "ERROR" in result.top_levels
    assert any("downstream_timeout" in msg for msg, _count in result.top_messages)


# TC-2.5.3: _summarize with empty lines returns status=empty
def test_tc_2_5_3_empty_result() -> None:
    result: LogQueryResult = _summarize("test", [], ("t0", "t1"))

    assert result.status == "empty"
    assert result.sampled_lines == []
    assert result.line_count == 0


# TC-2.5.4: live integration with Loki (skipped when Loki is absent)
@pytest.mark.asyncio
@pytest.mark.skipif(not _loki_reachable(), reason="Loki not available")
async def test_tc_2_5_4_live_loki(monkeypatch: pytest.MonkeyPatch) -> None:
    from datetime import timedelta

    monkeypatch.setattr(settings, "tool_mode", "live")

    result = await query_logs('{service="demo-app"}', window=timedelta(minutes=30))

    assert result.status == "ok"
    assert result.line_count > 0
    assert "INFO" in result.top_levels


# TC-2.5.5: unreachable Loki returns error/timeout without raising
@pytest.mark.asyncio
async def test_tc_2_5_5_network_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "loki_url", "http://localhost:19999")
    monkeypatch.setattr(settings, "tool_mode", "live")

    result = await query_logs('{service="demo-app"}')

    assert result.status in ("error", "timeout")
    assert result.error is not None
