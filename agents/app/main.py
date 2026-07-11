import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from typing import cast

from fastapi import FastAPI
from prometheus_client import make_asgi_app

from .db import upsert_prompt_versions
from .embedding.backfill_task import EmbeddingBackfillTask
from .embedding.factory import make_embedding_client
from .llm.model_registry import resolve_agent_spec
from .prompt_registry import PromptRegistry
from .settings import settings
from .worker import KafkaWorker

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)


def _log_llm_config() -> None:
    """State the resolved backend at startup so a silent fallback to the
    ollama defaults (e.g. .env not found) is visible in the first log lines."""
    specialist = resolve_agent_spec("log_analyzer")
    synth = resolve_agent_spec("synthesizer")
    log.info(
        "LLM backend=%s | specialists=%s (timeout %.0fs) | synthesizer=%s (timeout %.0fs)",
        settings.llm_backend,
        specialist.model,
        specialist.timeout_s,
        synth.model,
        synth.timeout_s,
    )
    if settings.llm_backend.lower() == "nim" and not settings.nim_api_key:
        log.warning(
            "LLM_BACKEND=nim but NIM_API_KEY is empty — every LLM call will "
            "fail and all agents will return degraded fallbacks"
        )


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    _log_llm_config()
    registry = PromptRegistry(settings.prompts_dir)
    await upsert_prompt_versions(registry)
    app.state.prompt_registry = registry

    embedder = make_embedding_client()
    worker = KafkaWorker(settings)
    worker.prompt_registry = registry
    worker.embedder = embedder
    await worker.start()
    app.state.worker = worker

    backfill = EmbeddingBackfillTask(interval_s=30.0)
    await backfill.start()
    app.state.backfill = backfill

    try:
        yield
    finally:
        await backfill.stop()
        await worker.stop()


app = FastAPI(title="Sentinel Agents", lifespan=lifespan)
app.mount("/metrics", make_asgi_app())


@app.get("/health")
async def health() -> dict[str, object]:
    reg = cast(PromptRegistry, app.state.prompt_registry)
    return {
        "status": "ok",
        "prompts_loaded": len(reg),
    }
