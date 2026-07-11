"""Tests for the History agent (TC-4.2.1 – TC-4.2.5)."""

from __future__ import annotations

import json
import os
from unittest.mock import AsyncMock, patch
from uuid import uuid4

import httpx
import pytest

from app.agents._context import AgentContext
from app.agents.history import history
from app.embedding.base import EmbeddingClient, EmbeddingResponse
from app.llm.base import LLMClient, LLMResponse
from app.models import AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import settings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_FAKE_VECTOR = [0.1] * 384

_KB_CANDIDATES = [
    {
        "id": str(uuid4()),
        "title": "High error rate on order-api",
        "body": "DB timeout cascade caused p95 latency breach.",
        "distance": 0.12,
        "metadata": {"service": "order-api", "severity": "critical"},
    },
    {
        "id": str(uuid4()),
        "title": "Payment service latency spike",
        "body": "Slow query in payment DB under high load.",
        "distance": 0.28,
        "metadata": {"service": "payment-service", "severity": "high"},
    },
    {
        "id": str(uuid4()),
        "title": "Auth service 503 errors",
        "body": "Token validation timeout due to Redis unavailability.",
        "distance": 0.45,
        "metadata": {"service": "auth-service", "severity": "critical"},
    },
]

_VALID_FINDING = {
    "matched_incidents": [
        {
            "id": _KB_CANDIDATES[0]["id"],
            "title": _KB_CANDIDATES[0]["title"],
            "distance": 0.12,
            "metadata": {"service": "order-api", "severity": "critical"},
        }
    ],
    "most_relevant_match_id": _KB_CANDIDATES[0]["id"],
    "assessment": "Strong match: previous order-api DB timeout cascade matches current symptoms.",
    "confidence": 0.85,
}


class _FakeEmbedder(EmbeddingClient):
    async def embed(self, text: str) -> EmbeddingResponse:
        return EmbeddingResponse(vector=_FAKE_VECTOR, model="fixture", dim=384)


class _FakeLLM(LLMClient):
    def __init__(self, responses: list[str]) -> None:
        self._responses = list(responses)
        self._index = 0

    async def complete(
        self, prompt: str, model: str | None = None, timeout_s: float | None = None
    ) -> LLMResponse:  # noqa: ARG002
        text = self._responses[self._index % len(self._responses)]
        self._index += 1
        return LLMResponse(text=text, tokens=80, latency_ms=300)


def _make_task() -> AgentTask:
    return AgentTask(
        incident_id=uuid4(),
        agent_name="history",
        service="order-api",
        payload={
            "severity": "critical",
            "alert_name": "HighErrorRate",
            "symptoms_summary": "p95 latency > 2s, DB connection errors in logs",
        },
    )


def _ctx(llm: LLMClient, embedder: EmbeddingClient | None = None) -> AgentContext:
    registry = PromptRegistry(settings.prompts_dir)
    return AgentContext(llm=llm, prompts=registry, embedder=embedder or _FakeEmbedder())


# ---------------------------------------------------------------------------
# TC-4.2.1: fixture embedder + 3 KB candidates → matched_incidents, prompt_version, assessment
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_2_1_happy_path_with_kb_candidates() -> None:
    llm = _FakeLLM([json.dumps(_VALID_FINDING)])

    with patch("app.agents.history._search_kb", new=AsyncMock(return_value=_KB_CANDIDATES)):
        result = await history(_make_task(), _ctx(llm))

    assert result.status == "ok"
    assert result.agent_name == "history"
    assert len(result.output["matched_incidents"]) >= 1
    assert result.prompt_version is not None
    assert result.output["assessment"] is not None
    assert result.output["assessment"] != ""


# ---------------------------------------------------------------------------
# TC-4.2.2: /kb/search returns [] → no LLM call, empty matched_incidents, confidence 0.0
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_2_2_empty_kb_returns_no_match() -> None:
    llm_called = False

    class _TrackingLLM(LLMClient):
        async def complete(
            self, prompt: str, model: str | None = None, timeout_s: float | None = None
        ) -> LLMResponse:  # noqa: ARG002
            nonlocal llm_called
            llm_called = True
            return LLMResponse(text="{}", tokens=0, latency_ms=0)

    with patch("app.agents.history._search_kb", new=AsyncMock(return_value=[])):
        result = await history(_make_task(), _ctx(_TrackingLLM()))

    assert result.status == "no_data"
    assert result.output["matched_incidents"] == []
    assert result.output["most_relevant_match_id"] is None
    assert result.output["confidence"] == 0.0
    assert not llm_called


# ---------------------------------------------------------------------------
# TC-4.2.3: /kb/search raises httpx.TimeoutException → graceful fallback, no raise
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_2_3_kb_timeout_returns_graceful_fallback() -> None:
    # Patch at the httpx level so the real _search_kb runs and catches the timeout
    mock_client = AsyncMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    mock_client.post = AsyncMock(side_effect=httpx.TimeoutException("connect timed out"))

    with patch("app.agents.history.httpx.AsyncClient", return_value=mock_client):
        result = await history(_make_task(), _ctx(_FakeLLM([])))

    assert result.status == "no_data"
    assert result.output["matched_incidents"] == []
    assert result.output["confidence"] == 0.0


# ---------------------------------------------------------------------------
# TC-4.2.4: LLM returns JSON with matched_incidents=[] → post-LLM guard populates from KB
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tc_4_2_4_empty_llm_matched_incidents_filled_from_kb() -> None:
    empty_finding: dict[str, object] = {
        "matched_incidents": [],
        "most_relevant_match_id": None,
        "assessment": "No strong match found.",
        "confidence": 0.1,
    }
    llm = _FakeLLM([json.dumps(empty_finding)])

    with patch("app.agents.history._search_kb", new=AsyncMock(return_value=_KB_CANDIDATES)):
        result = await history(_make_task(), _ctx(llm))

    assert result.status == "ok"
    assert len(result.output["matched_incidents"]) > 0
    assert result.output["matched_incidents"][0]["id"] == _KB_CANDIDATES[0]["id"]


# ---------------------------------------------------------------------------
# TC-4.2.5: EmbeddingBackfillTask._pass() embeds NULL rows; second pass skips them
# (requires DATABASE_URL pointing at a real database — skipped in CI)
# ---------------------------------------------------------------------------


@pytest.mark.skipif(
    not os.environ.get("DATABASE_URL"),
    reason="DATABASE_URL not set; skipping live DB backfill test",
)
@pytest.mark.asyncio
async def test_tc_4_2_5_backfill_task_embeds_null_rows() -> None:
    import asyncpg

    from app.embedding.backfill_task import EmbeddingBackfillTask
    from app.embedding.fixture import FixtureEmbedding

    db_url = os.environ["DATABASE_URL"]
    conn = await asyncpg.connect(db_url)
    try:
        # Insert a test row with NULL embedding
        doc_id = str(uuid4())
        await conn.execute(
            """
            INSERT INTO knowledge_base.kb_documents (id, source_type, title, body, metadata)
            VALUES ($1::uuid, 'test_backfill', 'Test Backfill Row', 'body text', '{}'::jsonb)
            """,
            doc_id,
        )

        task = EmbeddingBackfillTask(interval_s=1.0)
        embedder = FixtureEmbedding()

        # First pass: should embed the NULL row
        await task._pass(embedder)

        row = await conn.fetchrow(
            "SELECT embedding FROM knowledge_base.kb_documents WHERE id = $1::uuid", doc_id
        )
        assert row is not None
        assert row["embedding"] is not None

        # Second pass: row already has embedding — should not re-embed
        call_count = 0
        original_embed = embedder.embed

        async def counting_embed(text: str) -> EmbeddingResponse:
            nonlocal call_count
            call_count += 1
            return await original_embed(text)

        embedder.embed = counting_embed  # type: ignore[method-assign]
        await task._pass(embedder)
        assert call_count == 0, "second pass should not re-embed already-embedded rows"

    finally:
        await conn.execute("DELETE FROM knowledge_base.kb_documents WHERE id = $1::uuid", doc_id)
        await conn.close()
