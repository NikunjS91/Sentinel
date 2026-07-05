"""TC-5.3.x — NVIDIA NIM LLM client unit tests."""

from __future__ import annotations

import json

import httpx
import pytest

from ..llm.nim import NimClient


def _make_client(handler: object, *, model: str = "meta/llama-3.1-8b-instruct") -> NimClient:
    return NimClient(
        api_key="test-key",
        base_url="https://fake-nim.example.com/v1",
        default_model=model,
        _transport=httpx.MockTransport(handler),  # type: ignore[arg-type]
    )


def _nim_response(content: str, total_tokens: int = 10) -> dict[str, object]:
    return {
        "choices": [{"message": {"content": content}}],
        "usage": {"total_tokens": total_tokens},
    }


# ---------------------------------------------------------------------------
# TC-5.3.1 — NimClient sends correct payload and parses response
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_5_3_1_nim_client_payload_and_response() -> None:
    """TC-5.3.1 — NimClient sends model/messages/response_format and returns LLMResponse."""
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        captured["model"] = body.get("model")
        captured["messages"] = body.get("messages")
        captured["response_format"] = body.get("response_format")
        captured["auth"] = request.headers.get("Authorization")
        return httpx.Response(200, json=_nim_response('{"ok": true}', total_tokens=42))

    client = _make_client(handler)
    resp = await client.complete("diagnose this", model="meta/llama-3.1-70b-instruct")

    assert captured["model"] == "meta/llama-3.1-70b-instruct"
    assert captured["messages"] == [{"role": "user", "content": "diagnose this"}]
    assert captured["response_format"] == {"type": "json_object"}
    assert captured["auth"] == "Bearer test-key"
    assert resp.text == '{"ok": true}'
    assert resp.tokens == 42
    assert resp.latency_ms >= 0


# ---------------------------------------------------------------------------
# TC-5.3.2 — Default model used when no override given
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_5_3_2_default_model_used_when_no_override() -> None:
    """TC-5.3.2 — complete(prompt) without model= uses the constructor default."""
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        captured["model"] = body.get("model")
        return httpx.Response(200, json=_nim_response('{"result": "ok"}'))

    client = _make_client(handler, model="meta/llama-3.1-8b-instruct")
    await client.complete("hello")

    assert captured["model"] == "meta/llama-3.1-8b-instruct"


# ---------------------------------------------------------------------------
# TC-5.3.3 — HTTP error raises for status
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_5_3_3_http_error_raises() -> None:
    """TC-5.3.3 — Non-2xx response raises httpx.HTTPStatusError."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": "unauthorized"})

    client = _make_client(handler)
    with pytest.raises(httpx.HTTPStatusError):
        await client.complete("test")
