import httpx
import pytest

from app.llm.ollama import OllamaClient

_OLLAMA_HOST = "http://localhost:11434"


def _ollama_reachable() -> bool:
    try:
        with httpx.Client(timeout=2.0) as client:
            resp = client.get(f"{_OLLAMA_HOST}/api/tags")
            return resp.status_code == 200
    except Exception:
        return False


# TC-1.8.3: real Ollama round-trip — skipped when Ollama is absent
@pytest.mark.skipif(not _ollama_reachable(), reason="Ollama not available")
async def test_tc_1_8_3_ollama_produces_text() -> None:
    client = OllamaClient(host=_OLLAMA_HOST, model="qwen3:14b")
    resp = await client.complete("Say hello in one word.")
    assert resp.text != ""
    assert resp.tokens > 0
    assert resp.latency_ms > 0
