"""Tiny Postgres helpers for the agent service.

We only need a couple of queries (upsert prompt_versions today, maybe a
small read later). Keep it minimal — no ORM."""

from __future__ import annotations

import logging

import asyncpg

from .prompt_registry import PromptRegistry
from .settings import settings

log = logging.getLogger(__name__)


async def upsert_prompt_versions(registry: PromptRegistry) -> None:
    """Write every loaded prompt to prompt_versions on startup.

    ON CONFLICT DO NOTHING — re-running with unchanged prompts is a no-op;
    edited prompts produce a new row (old row preserved as history)."""
    if len(registry) == 0:
        log.warning("no prompts to upsert")
        return

    conn: asyncpg.Connection = await asyncpg.connect(settings.database_url)
    try:
        await conn.executemany(
            """
            INSERT INTO prompt_versions (version, agent_name, body, created_at)
            VALUES ($1, $2, $3, now())
            ON CONFLICT (version) DO NOTHING
            """,
            [(p.version, p.name, p.body) for p in registry.all()],
        )
        log.info("upserted %d prompt versions", len(registry))
    finally:
        await conn.close()
