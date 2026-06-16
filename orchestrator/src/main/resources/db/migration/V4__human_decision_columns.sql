ALTER TABLE incident_reports
    ADD COLUMN human_decision_reason     TEXT,
    ADD COLUMN human_decided_at          TIMESTAMPTZ,
    ADD COLUMN edited_summary            TEXT,
    ADD COLUMN edited_root_cause         TEXT,
    ADD COLUMN edited_recommended_action TEXT;

CREATE INDEX idx_reports_decided
    ON incident_reports (human_decision)
    WHERE human_decision IS NOT NULL;
