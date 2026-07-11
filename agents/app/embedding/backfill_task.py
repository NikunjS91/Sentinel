"""Periodic backfill of NULL embeddings in kb_documents.

Runs as an asyncio task started by the FastAPI lifespan. Polls
kb_documents for rows with NULL embedding and fills them in. Idempotent
— a row that already has an embedding is skipped on every subsequent pass."""

from __future__ import annotations

import asyncio
import logging

import asyncpg

from ..settings import settings
from .base import EmbeddingClient
from .factory import make_embedding_client

log = logging.getLogger(__name__)


class EmbeddingBackfillTask:
    def __init__(self, interval_s: float = 30.0) -> None:
        self.interval_s = interval_s
        self._task: asyncio.Task[None] | None = None
        self._stop = asyncio.Event()

    async def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="embedding-backfill")

    async def stop(self) -> None:
        self._stop.set()
        if self._task is not None:
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _run(self) -> None:
        log.info("embedding backfill task started; interval=%.0fs", self.interval_s)
        embedder: EmbeddingClient = make_embedding_client()
        backoff = self.interval_s
        while not self._stop.is_set():
            sleep_for = backoff  # sleep with current backoff; double happens after sleep
            try:
                await self._pass(embedder)
                backoff = self.interval_s  # reset on success
            except asyncpg.PostgresConnectionError as exc:
                log.warning(
                    "backfill: Postgres unavailable (%s); retrying in %.0fs", exc, sleep_for
                )
                backoff = min(backoff * 2, 300.0)  # double for next failure
            except Exception:  # noqa: BLE001
                log.exception("backfill pass failed; continuing")
                backoff = self.interval_s
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=sleep_for)
            except TimeoutError:
                pass

    async def _pass(self, embedder: EmbeddingClient) -> None:
        conn = await asyncpg.connect(settings.database_url)
        try:
            rows = await conn.fetch(
                "SELECT id, title, body FROM knowledge_base.kb_documents "
                "WHERE embedding IS NULL LIMIT 20"
            )
            if not rows:
                return
            log.info("backfill: embedding %d document(s)", len(rows))
            for row in rows:
                text = f"{row['title']}\n\n{row['body']}"
                resp = await embedder.embed(text)
                vec_str = "[" + ",".join(f"{v:.6f}" for v in resp.vector) + "]"
                await conn.execute(
                    "UPDATE knowledge_base.kb_documents SET embedding = $1::vector WHERE id = $2",
                    vec_str,
                    row["id"],
                )
        finally:
            await conn.close()
