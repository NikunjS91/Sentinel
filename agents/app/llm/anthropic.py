from .base import LLMResponse


class AnthropicClient:
    """Production backend — implemented in Sprint 5 (Day 5-8)."""

    def __init__(self, api_key: str, model: str) -> None:
        self._api_key = api_key
        self._model = model

    async def complete(
        self,
        prompt: str,
        model: str | None = None,
        timeout_s: float | None = None,
    ) -> LLMResponse:
        raise NotImplementedError("AnthropicClient lands in Sprint 5")
