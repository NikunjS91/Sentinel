import asyncio
from typing import Any

from .base import EmbeddingClient, EmbeddingResponse

_MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"


class SentenceTransformerEmbedding(EmbeddingClient):
    """Real 384-dim embedder using all-MiniLM-L6-v2 (lazy-loaded on first call)."""

    def __init__(self) -> None:
        self._model: Any = None

    def _load(self) -> None:
        if self._model is None:
            from sentence_transformers import SentenceTransformer
            self._model = SentenceTransformer(_MODEL_NAME)

    async def embed(self, text: str) -> EmbeddingResponse:
        def _sync() -> list[float]:
            self._load()
            vec: list[float] = self._model.encode(text, normalize_embeddings=True).tolist()
            return vec

        vector = await asyncio.to_thread(_sync)
        return EmbeddingResponse(vector=vector, model=_MODEL_NAME, dim=len(vector))
