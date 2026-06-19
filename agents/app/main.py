import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from typing import cast

from fastapi import FastAPI

from .db import upsert_prompt_versions
from .embedding.backfill_task import EmbeddingBackfillTask
from .embedding.factory import make_embedding_client
from .prompt_registry import PromptRegistry
from .settings import settings
from .worker import KafkaWorker

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
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


@app.get("/health")
async def health() -> dict[str, object]:
    reg = cast(PromptRegistry, app.state.prompt_registry)
    return {
        "status": "ok",
        "prompts_loaded": len(reg),
    }
