-- Sentinel core schema: incidents and their lifecycle artifacts.

CREATE TABLE incidents (
    id              UUID PRIMARY KEY,
    idempotency_key TEXT        NOT NULL UNIQUE,
    source          TEXT        NOT NULL,
    severity        TEXT,
    state           TEXT        NOT NULL DEFAULT 'RECEIVED',
    raw_alert       JSONB       NOT NULL,
    deadline_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_traces (
    id             UUID PRIMARY KEY,
    incident_id    UUID        NOT NULL REFERENCES incidents(id),
    agent_name     TEXT        NOT NULL,
    prompt_version TEXT,
    input          JSONB,
    output         JSONB,
    tokens_used    INTEGER     NOT NULL DEFAULT 0,
    cost_usd       NUMERIC(10,6) NOT NULL DEFAULT 0,
    latency_ms     INTEGER     NOT NULL DEFAULT 0,
    status         TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE incident_reports (
    id                 UUID PRIMARY KEY,
    incident_id        UUID        NOT NULL REFERENCES incidents(id),
    summary            TEXT,
    root_cause         TEXT,
    recommended_action TEXT,
    confidence         NUMERIC(4,3),
    human_decision     TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    incident_id UUID        REFERENCES incidents(id),
    event_type  TEXT        NOT NULL,
    detail      JSONB,
    actor       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prompt_versions (
    version    TEXT PRIMARY KEY,
    agent_name TEXT        NOT NULL,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_traces_incident  ON agent_traces(incident_id);
CREATE INDEX idx_reports_incident ON incident_reports(incident_id);
CREATE INDEX idx_audit_incident   ON audit_log(incident_id);
CREATE INDEX idx_incidents_state  ON incidents(state);
