import { useEffect, useState } from 'react';
import type { IncidentListItem, IncidentEvent, HumanDecisionEvent, IncidentReport } from './types';

const API = import.meta.env.VITE_API ?? 'http://localhost:8080';

export interface IncidentWithReport extends IncidentListItem {
  report?: IncidentReport | null;
}

export function useIncidentStream() {
  const [incidents, setIncidents] = useState<IncidentWithReport[]>([]);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    fetch(`${API}/incidents?limit=50`)
      .then(r => r.json())
      .then((data: IncidentListItem[]) => setIncidents(data))
      .catch(err => console.error('initial fetch failed', err));

    const sse = new EventSource(`${API}/incidents/stream`);
    sse.onopen = () => setConnected(true);
    sse.onerror = () => setConnected(false);

    const onStateEvent = (msg: MessageEvent) => {
      const event: IncidentEvent = JSON.parse(msg.data as string);
      setIncidents(prev => mergeStateEvent(prev, event));
    };
    sse.addEventListener('incident.state_changed', onStateEvent);
    sse.addEventListener('incident.completed', onStateEvent);

    const onDecisionEvent = (msg: MessageEvent) => {
      const event: HumanDecisionEvent = JSON.parse(msg.data as string);
      setIncidents(prev => mergeDecisionEvent(prev, event));
    };
    sse.addEventListener('incident.human_decision', onDecisionEvent);

    return () => sse.close();
  }, []);

  return { incidents, connected };
}

function mergeStateEvent(
  prev: IncidentWithReport[],
  event: IncidentEvent,
): IncidentWithReport[] {
  const existing = prev.findIndex(i => i.incident_id === event.incident_id);
  const updated: IncidentWithReport = {
    incident_id: event.incident_id,
    state: event.state,
    service: event.service,
    severity: event.severity,
    created_at: existing >= 0 ? prev[existing].created_at : event.ts,
    human_decision: existing >= 0 ? (prev[existing].human_decision ?? null) : null,
    human_decision_reason: existing >= 0 ? (prev[existing].human_decision_reason ?? null) : null,
    report: event.report ?? prev[existing]?.report ?? null,
  };
  if (existing >= 0) {
    const next = [...prev];
    next[existing] = updated;
    return next;
  }
  return [updated, ...prev];
}

function mergeDecisionEvent(
  prev: IncidentWithReport[],
  event: HumanDecisionEvent,
): IncidentWithReport[] {
  const existing = prev.findIndex(i => i.incident_id === event.incident_id);
  if (existing < 0) return prev;
  const next = [...prev];
  next[existing] = {
    ...prev[existing],
    human_decision: event.decision,
    human_decision_reason: event.reason ?? null,
  };
  return next;
}
