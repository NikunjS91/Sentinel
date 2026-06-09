"""Typed output shapes for specialist agents."""

from __future__ import annotations

from pydantic import BaseModel, Field


class LogAnalyzerFinding(BaseModel):
    """The structured output the Log Analyzer agent produces.
    Mirrors the JSON shape its prompt requests."""

    error_patterns: list[str] = Field(default_factory=list)
    most_likely_symptom: str | None = None
    supporting_evidence: list[str] = Field(default_factory=list)
    confidence: float = 0.0


class MetricsAgentFinding(BaseModel):
    """The structured output the Metrics agent produces.
    Mirrors the JSON shape its prompt requests."""

    slo_status: str = "ok"
    anomalies: list[str] = Field(default_factory=list)
    most_likely_cause: str | None = None
    confidence: float = 0.0
