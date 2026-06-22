"""Typed output shapes for specialist agents."""

from __future__ import annotations

from typing import Any

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


class SynthesizerFinding(BaseModel):
    """The unified incident report the Synthesizer produces by combining
    specialist findings."""

    summary: str
    root_cause: str | None = None
    recommended_action: str | None = None
    confidence: float = 0.0
    dissenting_notes: list[str] = Field(default_factory=list)
    contributing_agents: list[str] = Field(default_factory=list)


class MatchedIncident(BaseModel):
    """One past incident the KB search returned."""

    id: str
    title: str
    distance: float          # cosine distance; lower = more similar
    metadata: dict[str, Any] = Field(default_factory=dict)


class HistoryFinding(BaseModel):
    """The History agent's structured output.

    Mirrors the JSON shape its prompt requests."""

    matched_incidents: list[MatchedIncident] = Field(default_factory=list)
    most_relevant_match_id: str | None = None
    assessment: str | None = None
    confidence: float = 0.0


class TopologyNeighbor(BaseModel):
    """One neighboring service in the topology graph."""

    service: str
    direction: str          # "outgoing" (we → them) | "incoming" (they → us)
    relationship: str
    metadata: dict[str, Any] = Field(default_factory=dict)


class TopologyFinding(BaseModel):
    """The Topology agent's structured output.

    Mirrors the JSON shape its prompt requests."""

    neighbors: list[TopologyNeighbor] = Field(default_factory=list)
    likely_implicated: list[str] = Field(default_factory=list)
    assessment: str | None = None
    confidence: float = 0.0
