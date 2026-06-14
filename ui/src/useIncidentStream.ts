import { useEffect, useState } from 'react';
import type { IncidentListItem, IncidentEvent, IncidentReport } from './types';

const API = import.meta.env.VITE_API ?? 'http://localhost:8080';

interface IncidentWithReport extends IncidentListItem {
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

    const onEvent = (msg: MessageEvent) => {
      const event: IncidentEvent = JSON.parse(msg.data as string);
      setIncidents(prev => mergeEvent(prev, event));
    };
    sse.addEventListener('incident.state_changed', onEvent);
    sse.addEventListener('incident.completed', onEvent);

    return () => sse.close();
  }, []);

  return { incidents, connected };
}

function mergeEvent(
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
    report: event.report ?? prev[existing]?.report ?? null,
  };
  if (existing >= 0) {
    const next = [...prev];
    next[existing] = updated;
    return next;
  }
  return [updated, ...prev];
}
