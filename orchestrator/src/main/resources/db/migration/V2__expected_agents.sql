-- V2 — Track which agents the orchestrator dispatched for an incident.
-- Aggregator resolves the incident once all expected agents have reported.

ALTER TABLE incidents
    ADD COLUMN expected_agents JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN incidents.expected_agents IS
  'JSON array of agent_name strings dispatched for this incident.';
