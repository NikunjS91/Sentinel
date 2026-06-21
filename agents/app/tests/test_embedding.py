import importlib.util

import pytest

from app.embedding.fixture import FixtureEmbedding


# TC-4.1.6: FixtureEmbedding is deterministic and produces 384-dim vectors
@pytest.mark.asyncio
async def test_fixture_deterministic() -> None:
    emb = FixtureEmbedding()
    r1 = await emb.embed("hello")
    r2 = await emb.embed("hello")
    assert r1.vector == r2.vector
    assert r1.dim == 384
    assert len(r1.vector) == 384


@pytest.mark.asyncio
async def test_fixture_different_texts_differ() -> None:
    emb = FixtureEmbedding()
    r1 = await emb.embed("hello")
    r2 = await emb.embed("world")
    assert r1.vector != r2.vector


# TC-4.1.7: SentenceTransformerEmbedding (skip-guarded — requires model download)
_st_available = importlib.util.find_spec("sentence_transformers") is not None


@pytest.mark.asyncio
@pytest.mark.skipif(not _st_available, reason="sentence_transformers not installed")
async def test_sentence_transformer_384() -> None:
    from app.embedding.sentence_transformer import SentenceTransformerEmbedding

    emb = SentenceTransformerEmbedding()
    r = await emb.embed("hello")
    assert r.dim == 384
    assert len(r.vector) == 384
    assert r.model == "sentence-transformers/all-MiniLM-L6-v2"
