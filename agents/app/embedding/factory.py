from .base import EmbeddingClient


def make_embedding_client() -> EmbeddingClient:
    from ..settings import settings

    backend = settings.embedding_backend
    if backend == "fixture":
        from .fixture import FixtureEmbedding

        return FixtureEmbedding()
    if backend == "sentence_transformer":
        from .sentence_transformer import SentenceTransformerEmbedding

        return SentenceTransformerEmbedding()
    raise ValueError(f"Unknown embedding_backend: {backend!r}")
