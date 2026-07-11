"""NVIDIA NIM LLM client.

Uses the OpenAI-compatible chat completions endpoint hosted at
https://integrate.api.nvidia.com/v1. Get a free API key at
https://build.nvidia.com — no credit card required for the free tier.

Why NIM over local Ollama: cloud inference, no local GPU/CPU pressure,
sub-second cold-start, same OpenAI-style JSON API."""

from __future__ import annotations

import time
from typing import Any

import httpx

from .base import LLMResponse


class NimClient:
    """Calls the NVIDIA NIM OpenAI-compatible chat completions API."""

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://integrate.api.nvidia.com/v1",
        default_model: str = "meta/llama-3.1-8b-instruct",
        default_timeout_s: float = 30.0,
        _transport: Any = None,
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._default_model = default_model
        self._default_timeout_s = default_timeout_s
        self._transport = _transport

    async def complete(
        self,
        prompt: str,
        model: str | None = None,
        timeout_s: float | None = None,
    ) -> LLMResponse:
        model_to_use = model or self._default_model
        t = timeout_s if timeout_s is not None else self._default_timeout_s

        payload = {
            "model": model_to_use,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 2048,
            "temperature": 0.1,
            "response_format": {"type": "json_object"},
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

        t0 = time.monotonic()
        async with httpx.AsyncClient(timeout=t, transport=self._transport) as client:
            resp = await client.post(
                f"{self._base_url}/chat/completions",
                json=payload,
                headers=headers,
            )
            resp.raise_for_status()
            data = resp.json()

        latency_ms = int((time.monotonic() - t0) * 1000)
        text = data["choices"][0]["message"]["content"]
        tokens = data.get("usage", {}).get("total_tokens", 0)
        return LLMResponse(text=text.strip(), tokens=tokens, latency_ms=latency_ms)
