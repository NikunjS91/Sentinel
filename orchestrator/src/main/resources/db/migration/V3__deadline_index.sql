-- V3 — Sweeper lookup index for per-incident deadlines.
-- deadline_at column already exists from V1. Add the partial index for the sweeper query.

CREATE INDEX idx_incidents_deadline_active
    ON incidents (deadline_at)
    WHERE state IN ('DISPATCHED', 'AGGREGATING');

COMMENT ON COLUMN incidents.deadline_at IS
  'Hard deadline set at dispatch time. NULL on pre-Day-22 incidents. '
  'Sweeper forces progression past this point.';
