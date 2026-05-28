import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .settings import settings
from .worker import KafkaWorker

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Start the Kafka worker on app start; stop it cleanly on shutdown."""
    worker = KafkaWorker(settings)
    await worker.start()
    app.state.worker = worker
    try:
        yield
    finally:
        await worker.stop()


app = FastAPI(title="Sentinel Agents", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
