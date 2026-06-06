import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from typing import cast

from fastapi import FastAPI

from .db import upsert_prompt_versions
from .prompt_registry import PromptRegistry
from .settings import settings
from .worker import KafkaWorker

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    registry = PromptRegistry(settings.prompts_dir)
    await upsert_prompt_versions(registry)
    app.state.prompt_registry = registry

    worker = KafkaWorker(settings)
    worker.prompt_registry = registry  # type: ignore[attr-defined]
    await worker.start()
    app.state.worker = worker
    try:
        yield
    finally:
        await worker.stop()


app = FastAPI(title="Sentinel Agents", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, object]:
    reg = cast(PromptRegistry, app.state.prompt_registry)
    return {
        "status": "ok",
        "prompts_loaded": len(reg),
    }
