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
  human_decision: string | null;
  human_decision_reason: string | null;
}

export interface ListResponse {
  items: IncidentListItem[];
  nextBefore: string | null;
}

export interface Filter {
  state: string[];
  service: string | null;
  decision: 'all' | 'undecided' | 'accepted' | 'rejected' | 'edited';
  q: string;
}

export const EMPTY_FILTER: Filter = { state: [], service: null, decision: 'all', q: '' };

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

export interface HumanDecisionEvent {
  type: 'incident.human_decision';
  ts: string;
  incident_id: string;
  decision: 'ACCEPTED' | 'REJECTED' | 'EDITED';
  reason: string | null;
}

export type SentinelEvent = IncidentEvent | HumanDecisionEvent;
