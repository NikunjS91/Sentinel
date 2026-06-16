import { useEffect, useState } from 'react';
import type {
  IncidentListItem, IncidentEvent, HumanDecisionEvent,
  IncidentReport, ListResponse, Filter,
} from './types';
import { EMPTY_FILTER } from './types';

const API = import.meta.env.VITE_API ?? 'http://localhost:8080';

export interface IncidentWithReport extends IncidentListItem {
  report?: IncidentReport | null;
}

export function useIncidentStream() {
  const [incidents, setIncidents] = useState<IncidentWithReport[]>([]);
  const [filter, setFilter] = useState<Filter>(EMPTY_FILTER);
  const [nextBefore, setNextBefore] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [connected, setConnected] = useState(false);

  // Re-fetch whenever the filter changes.
  useEffect(() => {
    setLoading(true);
    fetch(buildUrl(filter, null))
      .then(r => r.json() as Promise<ListResponse>)
      .then(data => {
        setIncidents(data.items);
        setNextBefore(data.nextBefore);
      })
      .catch(err => console.error('fetch failed', err))
      .finally(() => setLoading(false));
  }, [filter]);

  // SSE subscription — re-subscribe when filter changes for matchesFilter.
  useEffect(() => {
    const sse = new EventSource(`${API}/incidents/stream`);
    sse.onopen = () => setConnected(true);
    sse.onerror = () => setConnected(false);

    const onStateEvent = (msg: MessageEvent) => {
      const event: IncidentEvent = JSON.parse(msg.data as string);
      if (!matchesFilter(event, filter)) return;
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
  }, [filter]);

  const loadOlder = () => {
    if (!nextBefore) return;
    setLoading(true);
    fetch(buildUrl(filter, nextBefore))
      .then(r => r.json() as Promise<ListResponse>)
      .then(data => {
        setIncidents(prev => [...prev, ...data.items]);
        setNextBefore(data.nextBefore);
      })
      .catch(err => console.error('loadOlder failed', err))
      .finally(() => setLoading(false));
  };

  return { incidents, filter, setFilter, nextBefore, loadOlder, loading, connected };
}

export function buildUrl(filter: Filter, before: string | null): string {
  const p = new URLSearchParams();
  p.set('limit', '20');
  if (filter.state.length > 0) p.set('state', filter.state.join(','));
  if (filter.service) p.set('service', filter.service);
  if (filter.decision !== 'all') p.set('decision', filter.decision);
  if (filter.q.trim()) p.set('q', filter.q.trim());
  if (before) p.set('before', before);
  return `${API}/incidents?${p.toString()}`;
}

function matchesFilter(event: IncidentEvent, filter: Filter): boolean {
  if (filter.state.length > 0 && !filter.state.includes(event.state)) return false;
  if (filter.service && event.service !== filter.service) return false;
  return true;
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
