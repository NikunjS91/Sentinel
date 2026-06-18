"""Backfill NULL embeddings in knowledge_base.kb_documents.

Usage:
    python -m scripts.backfill_embeddings
"""

import asyncio
import logging

import asyncpg

from app.embedding.factory import make_embedding_client
from app.settings import settings

log = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)


async def main() -> None:
    embedder = make_embedding_client()
    conn = await asyncpg.connect(settings.database_url)
    try:
        rows = await conn.fetch(
            "SELECT id, title || ' ' || body AS text "
            "FROM knowledge_base.kb_documents WHERE embedding IS NULL"
        )
        log.info("Found %d documents with NULL embedding", len(rows))
        for row in rows:
            resp = await embedder.embed(row["text"])
            vec_str = "[" + ",".join(str(v) for v in resp.vector) + "]"
            await conn.execute(
                "UPDATE knowledge_base.kb_documents SET embedding = $1::vector WHERE id = $2",
                vec_str,
                row["id"],
            )
            log.info("Embedded document %s", row["id"])
    finally:
        await conn.close()


if __name__ == "__main__":
    asyncio.run(main())
