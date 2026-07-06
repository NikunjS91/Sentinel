from ..settings import Settings
from .anthropic import AnthropicClient
from .base import LLMClient
from .groq import GroqClient
from .nim import NimClient
from .ollama import OllamaClient


def make_llm_client(settings: Settings) -> LLMClient:
    """Select the LLM backend from settings.llm_backend."""
    backend = settings.llm_backend.lower()
    if backend == "ollama":
        return OllamaClient(settings.ollama_host, settings.ollama_model, timeout_s=600.0)
    if backend == "anthropic":
        return AnthropicClient(settings.anthropic_api_key, settings.anthropic_model)
    if backend == "groq":
        return GroqClient(settings.groq_api_key, settings.groq_model)
    if backend == "nim":
        return NimClient(
            api_key=settings.nim_api_key,
            base_url=settings.nim_base_url,
            default_model=settings.nim_specialist_model,
            default_timeout_s=settings.nim_timeout_s,
        )
    raise ValueError(f"unknown LLM_BACKEND: {settings.llm_backend!r}")
