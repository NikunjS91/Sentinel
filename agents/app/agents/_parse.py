"""Shared LLM-output parsing for specialist agents.

Every agent gets the same defensive parsing: strip code fences, find the
JSON object, validate against a typed model. One retry with a corrective
nudge if parse fails. Cumulative token/latency tracking across calls.

Generic over the Pydantic model the agent expects."""

from __future__ import annotations

import json
import logging
import re
from typing import TypeVar

from pydantic import BaseModel, ValidationError

from ..llm.base import LLMClient
from ..llm.model_registry import resolve_agent_spec
from ..metrics import AGENT_LLM_LATENCY, AGENT_TIMEOUTS

log = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

_FENCE_RE = re.compile(r"^```(?:json)?|```$", re.MULTILINE)


class LLMStats(BaseModel):
    """Accumulates tokens + latency across one or more LLM calls."""

    total_tokens: int = 0
    total_latency_ms: int = 0


def try_parse(text: str, model: type[T]) -> T | None:
    """Strip common LLM clutter, locate the JSON object, validate.
    Returns None on any failure — callers handle retry/fallback."""
    cleaned = _FENCE_RE.sub("", text).strip()
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end == -1 or end < start:
        return None
    candidate = cleaned[start : end + 1]
    try:
        data = json.loads(candidate)
        return model.model_validate(data)
    except (json.JSONDecodeError, ValidationError):
        return None


async def call_with_retry(
    llm: LLMClient,
    prompt: str,
    model: type[T],
    fallback: T,
    agent_name: str = "unknown",
) -> tuple[T, LLMStats]:
    """Call the LLM; one corrective retry on parse failure; fallback otherwise.

    Tokens and latency accumulate across both calls. `fallback` is what the
    caller wants returned if both attempts fail (typically a confidence=0
    instance of the target model)."""
    stats = LLMStats()
    spec = resolve_agent_spec(agent_name)

    try:
        resp = await llm.complete(prompt, model=spec.model, timeout_s=spec.timeout_s)
    except Exception:
        AGENT_TIMEOUTS.labels(agent_name=agent_name).inc()
        log.exception("%s: LLM call failed; returning fallback", agent_name)
        return fallback, stats
    stats.total_tokens += resp.tokens
    stats.total_latency_ms += resp.latency_ms
    AGENT_LLM_LATENCY.labels(agent_name=agent_name).observe(resp.latency_ms / 1000)
    parsed = try_parse(resp.text, model)
    if parsed is not None:
        return parsed, stats

    log.warning("%s: first parse failed; retrying with nudge", agent_name)
    nudge = prompt + (
        "\n\nIMPORTANT: Your previous response was not valid JSON. Return "
        "ONLY a single JSON object matching the schema above. No prose, "
        "no markdown fences."
    )
    try:
        resp2 = await llm.complete(nudge, model=spec.model, timeout_s=spec.timeout_s)
    except Exception:
        AGENT_TIMEOUTS.labels(agent_name=agent_name).inc()
        log.exception("%s: LLM retry call failed; returning fallback", agent_name)
        return fallback, stats
    stats.total_tokens += resp2.tokens
    stats.total_latency_ms += resp2.latency_ms
    AGENT_LLM_LATENCY.labels(agent_name=agent_name).observe(resp2.latency_ms / 1000)
    parsed = try_parse(resp2.text, model)
    if parsed is not None:
        return parsed, stats

    log.error("%s: parse failed after retry; returning fallback", agent_name)
    return fallback, stats
