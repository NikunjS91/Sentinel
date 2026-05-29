import time

import httpx

from .base import LLMResponse


class OllamaClient:
    """Talks to a local Ollama server via its HTTP API."""

    def __init__(self, host: str, model: str, timeout_s: float = 60.0) -> None:
        self._host = host.rstrip("/")
        self._model = model
        self._timeout = timeout_s

    async def complete(self, prompt: str) -> LLMResponse:
        t0 = time.monotonic()
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            resp = await client.post(
                f"{self._host}/api/generate",
                json={"model": self._model, "prompt": prompt, "stream": False},
            )
            resp.raise_for_status()
            data = resp.json()

        latency_ms = int((time.monotonic() - t0) * 1000)
        tokens = int(data.get("prompt_eval_count", 0)) + int(data.get("eval_count", 0))
        return LLMResponse(
            text=data.get("response", "").strip(),
            tokens=tokens,
            latency_ms=latency_ms,
        )
