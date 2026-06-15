export type IncidentState =
  | 'RECEIVED' | 'CLASSIFIED' | 'DISPATCHED'
  | 'AGGREGATING' | 'AGGREGATING_PARTIAL'
  | 'SYNTHESIZED' | 'SYNTHESIZED_PARTIAL'
  | 'RESOLVED' | 'PARTIAL' | 'FAILED';

export interface IncidentReport {
  summary: string;
  root_cause: string | null;
  recommended_action: string | null;
  confidence: number;
  dissenting_notes: string[];
  contributing_agents: string[];
}

export interface IncidentListItem {
  incident_id: string;
  state: IncidentState;
  service: string;
  severity: string;
  created_at: string;
}

export interface IncidentEvent {
  type: 'incident.state_changed' | 'incident.completed';
  ts: string;
  incident_id: string;
  state: IncidentState;
  service: string;
  severity: string;
  alert_name: string | null;
  expected_agents: string[];
  report: IncidentReport | null;
}
