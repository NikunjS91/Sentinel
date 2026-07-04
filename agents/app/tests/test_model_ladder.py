"""TC-5.2.x — Model ladder: per-agent model selection and per-agent timeouts."""

from __future__ import annotations

import json

import httpx
import pytest
from pydantic import BaseModel

from ..agents._parse import call_with_retry
from ..llm.model_registry import resolve_agent_spec
from ..llm.ollama import OllamaClient

# ---------------------------------------------------------------------------
# TC-5.2.1 — Ladder resolves specialist to qwen2.5:3b
# ---------------------------------------------------------------------------


def test_tc_5_2_1_specialist_resolves_to_small_model() -> None:
    """TC-5.2.1 — resolve_agent_spec for a specialist returns qwen2.5:3b / 30s."""
    spec = resolve_agent_spec("history")
    assert spec.model == "qwen2.5:3b"
    assert spec.timeout_s == 30.0


# ---------------------------------------------------------------------------
# TC-5.2.2 — Ladder resolves Synthesizer to qwen3:14b
# ---------------------------------------------------------------------------


def test_tc_5_2_2_synthesizer_resolves_to_large_model() -> None:
    """TC-5.2.2 — resolve_agent_spec('synthesizer') returns qwen3:14b / 180s."""
    spec = resolve_agent_spec("synthesizer")
    assert spec.model == "qwen3:14b"
    assert spec.timeout_s == 180.0


# ---------------------------------------------------------------------------
# TC-5.2.3 — Ladder falls back for unknown agent name
# ---------------------------------------------------------------------------


def test_tc_5_2_3_unknown_agent_falls_back_to_synthesizer_spec() -> None:
    """TC-5.2.3 — Unknown agent gets Synthesizer spec as conservative default."""
    spec = resolve_agent_spec("bogus_agent")
    synthesizer_spec = resolve_agent_spec("synthesizer")
    assert spec == synthesizer_spec


# ---------------------------------------------------------------------------
# TC-5.2.4 — httpx.TimeoutException triggers fallback + counter increment
# ---------------------------------------------------------------------------


class _TimeoutLLM:
    """Simulates an LLM that raises httpx.TimeoutException on every call."""

    async def complete(
        self,
        prompt: str,
        model: str | None = None,
        timeout_s: float | None = None,
    ) -> None:
        raise httpx.TimeoutException("simulated timeout")


class _SimpleResult(BaseModel):
    value: str = ""


@pytest.mark.asyncio
async def test_tc_5_2_4_timeout_returns_fallback_and_increments_counter() -> None:
    """TC-5.2.4 — httpx.TimeoutException returns fallback and increments AGENT_TIMEOUTS."""
    from ..metrics import AGENT_TIMEOUTS

    agent_name = "metrics_timeout_test"
    before = AGENT_TIMEOUTS.labels(agent_name=agent_name)._value.get()

    fallback = _SimpleResult(value="fallback")
    result, stats = await call_with_retry(
        llm=_TimeoutLLM(),  # type: ignore[arg-type]
        prompt="irrelevant",
        model=_SimpleResult,
        fallback=fallback,
        agent_name=agent_name,
    )

    after = AGENT_TIMEOUTS.labels(agent_name=agent_name)._value.get()

    assert result is fallback
    assert stats.total_latency_ms == 0
    assert after == before + 1


# ---------------------------------------------------------------------------
# TC-5.2.5 — OllamaClient.complete sends the override model in the HTTP body
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_5_2_5_model_override_sent_in_payload() -> None:
    """TC-5.2.5 — complete(prompt, model='qwen2.5:3b') sends that model to Ollama."""
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        captured["model"] = body.get("model")
        return httpx.Response(
            200,
            json={"response": '{"ok": true}', "eval_count": 5, "prompt_eval_count": 3},
        )

    client = OllamaClient(
        host="http://fake-ollama",
        model="qwen3:14b",
        _transport=httpx.MockTransport(handler),
    )
    await client.complete("hello", model="qwen2.5:3b")

    assert captured["model"] == "qwen2.5:3b"
