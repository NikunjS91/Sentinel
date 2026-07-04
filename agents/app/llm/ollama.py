import time
from typing import Any

import httpx

from .base import LLMResponse


class OllamaClient:
    """Talks to a local Ollama server via its HTTP API."""

    def __init__(
        self,
        host: str,
        model: str,
        timeout_s: float = 180.0,
        _transport: Any = None,
    ) -> None:
        self._host = host.rstrip("/")
        self._model = model
        self._timeout = timeout_s
        self._transport = _transport

    async def complete(
        self,
        prompt: str,
        model: str | None = None,
        timeout_s: float | None = None,
    ) -> LLMResponse:
        model_to_use = model or self._model
        t = timeout_s if timeout_s is not None else self._timeout

        t0 = time.monotonic()
        async with httpx.AsyncClient(timeout=t, transport=self._transport) as client:
            resp = await client.post(
                f"{self._host}/api/generate",
                json={
                    "model": model_to_use,
                    "prompt": prompt,
                    "stream": False,
                    "format": "json",
                },
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
