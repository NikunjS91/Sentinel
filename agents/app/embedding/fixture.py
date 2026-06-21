import hashlib
import struct

from .base import EmbeddingClient, EmbeddingResponse

_MODEL = "fixture/sha256"
_DIM = 384


class FixtureEmbedding(EmbeddingClient):
    """Deterministic fake embedder — maps text to a reproducible 384-dim vector via SHA-256."""

    async def embed(self, text: str) -> EmbeddingResponse:
        digest = hashlib.sha256(text.encode()).digest()
        # Repeat the 32-byte digest to fill 384 floats (384 * 4 = 1536 bytes, 32-byte blocks)
        repeated = (digest * (((_DIM * 4) // len(digest)) + 1))[: _DIM * 4]
        raw = struct.unpack(f"{_DIM}f", repeated)
        # Map uint32 floats to [-1, 1] by treating them as signed bytes
        vector = [((v % 2.0) - 1.0) for v in raw]
        return EmbeddingResponse(vector=list(vector), model=_MODEL, dim=_DIM)
