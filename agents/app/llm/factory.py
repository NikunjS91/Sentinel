from ..settings import Settings
from .anthropic import AnthropicClient
from .base import LLMClient
from .groq import GroqClient
from .ollama import OllamaClient


def make_llm_client(settings: Settings) -> LLMClient:
    """Select the LLM backend from settings.llm_backend."""
    backend = settings.llm_backend.lower()
    if backend == "ollama":
        return OllamaClient(settings.ollama_host, settings.ollama_model)
    if backend == "anthropic":
        return AnthropicClient(settings.anthropic_api_key, settings.anthropic_model)
    if backend == "groq":
        return GroqClient(settings.groq_api_key, settings.groq_model)
    raise ValueError(f"unknown LLM_BACKEND: {settings.llm_backend!r}")
