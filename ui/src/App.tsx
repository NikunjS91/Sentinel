import { useEffect, useMemo, useState } from 'react';
import { useIncidentStream } from './useIncidentStream';
import type { IncidentWithReport } from './useIncidentStream';
import type { Filter, IncidentState } from './types';
import { EMPTY_FILTER } from './types';
import './App.css';

const API = import.meta.env.VITE_API ?? 'http://localhost:8080';

const TERMINAL_STATES: IncidentState[] = ['RESOLVED', 'PARTIAL'];
const STATE_COLORS: Record<IncidentState, string> = {
  RECEIVED: '#555',
  CLASSIFIED: '#555',
  DISPATCHED: '#4a9eff',
  AGGREGATING: '#e08a00',
  AGGREGATING_PARTIAL: '#c06800',
  SYNTHESIZED: '#e08a00',
  SYNTHESIZED_PARTIAL: '#c06800',
  RESOLVED: '#2ea043',
  PARTIAL: '#c25700',
  FAILED: '#cf222e',
};

export default function App() {
  const { incidents, filter, setFilter, nextBefore, loadOlder, loading, connected } =
    useIncidentStream();
  const [selected, setSelected] = useState<string | null>(null);

  // Hydrate filter from URL on mount.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const initial: Filter = {
      state: params.get('state')?.split(',').filter(Boolean) ?? [],
      service: params.get('service') ?? null,
      decision: (params.get('decision') as Filter['decision']) ?? 'all',
      q: params.get('q') ?? '',
    };
    const hasParams = params.toString().length > 0;
    if (hasParams) setFilter(initial);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Sync filter to URL on every change.
  useEffect(() => {
    const p = new URLSearchParams();
    if (filter.state.length > 0) p.set('state', filter.state.join(','));
    if (filter.service) p.set('service', filter.service);
    if (filter.decision !== 'all') p.set('decision', filter.decision);
    if (filter.q.trim()) p.set('q', filter.q.trim());
    const qs = p.toString();
    window.history.replaceState(null, '', qs ? `?${qs}` : window.location.pathname);
  }, [filter]);

  const services = useMemo(
    () => Array.from(new Set(incidents.map(i => i.service))).sort(),
    [incidents],
  );

  return (
    <div className="app">
      <header>
        <h1>Sentinel</h1>
        <span className={`conn-indicator ${connected ? 'on' : 'off'}`}>
          {connected ? 'live' : 'reconnecting…'}
        </span>
      </header>
      <main>
        <FilterBar filter={filter} setFilter={setFilter} services={services} />
        <section className="incident-list">
          {!loading && incidents.length === 0 && (
            <div className="empty">
              <p>No incidents match the current filter.</p>
              <p className="empty-hint">Try "All states" or clear the search to see all incidents.</p>
            </div>
          )}
          {incidents.map(inc => (
            <article
              key={inc.incident_id}
              className={`incident ${selected === inc.incident_id ? 'selected' : ''}`}
              onClick={() => setSelected(
                selected === inc.incident_id ? null : inc.incident_id
              )}
            >
              <div className="row">
                <span
                  className="state-pill"
                  style={{ background: STATE_COLORS[inc.state] ?? '#555' }}>
                  {inc.state}
                </span>
                <span className="service">{inc.service}</span>
                <span className="severity">{inc.severity}</span>
                <span className="time mono">{new Date(inc.created_at).toLocaleTimeString()}</span>
                {inc.human_decision && (
                  <span className="decision-chip">{inc.human_decision}</span>
                )}
              </div>
              {TERMINAL_STATES.includes(inc.state) && inc.report && (
                <p className="summary">{inc.report.summary}</p>
              )}
              {selected === inc.incident_id && inc.report && (
                <IncidentDetail inc={inc} />
              )}
            </article>
          ))}
        </section>
        {nextBefore && (
          <div className="load-more">
            <button onClick={loadOlder} disabled={loading}>
              {loading ? 'Loading…' : 'Load older'}
            </button>
          </div>
        )}
      </main>
    </div>
  );
}

function FilterBar({ filter, setFilter, services }: {
  filter: Filter;
  setFilter: (f: Filter) => void;
  services: string[];
}) {
  const hasActive = filter.state.length > 0 || filter.service ||
    filter.decision !== 'all' || filter.q;

  return (
    <div className="filter-bar">
      <select
        value={filter.state.length === 0 ? 'all' : filter.state.join(',')}
        onChange={e => {
          const v = e.target.value;
          setFilter({ ...filter, state: v === 'all' ? [] : v.split(',') });
        }}>
        <option value="all">All states</option>
        <option value="RECEIVED,CLASSIFIED,DISPATCHED,AGGREGATING,AGGREGATING_PARTIAL">Active</option>
        <option value="RESOLVED">Resolved</option>
        <option value="PARTIAL">Partial</option>
        <option value="RESOLVED,PARTIAL">All terminal</option>
      </select>

      <select
        value={filter.service ?? ''}
        onChange={e => setFilter({ ...filter, service: e.target.value || null })}>
        <option value="">All services</option>
        {services.map(s => <option key={s} value={s}>{s}</option>)}
      </select>

      <select
        value={filter.decision}
        onChange={e => setFilter({ ...filter, decision: e.target.value as Filter['decision'] })}>
        <option value="all">All decisions</option>
        <option value="undecided">Undecided</option>
        <option value="accepted">Accepted</option>
        <option value="rejected">Rejected</option>
        <option value="edited">Edited</option>
      </select>

      <input
        type="search"
        placeholder="Search summary, cause, action…"
        value={filter.q}
        onChange={e => setFilter({ ...filter, q: e.target.value })}
      />

      {hasActive && (
        <button className="clear" onClick={() => setFilter(EMPTY_FILTER)}>Clear</button>
      )}
    </div>
  );
}

function IncidentDetail({ inc }: { inc: IncidentWithReport }) {
  const report = inc.report!;
  const [editing, setEditing] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [editSummary, setEditSummary] = useState(report.summary ?? '');
  const [editRootCause, setEditRootCause] = useState(report.root_cause ?? '');
  const [editAction, setEditAction] = useState(report.recommended_action ?? '');
  const [error, setError] = useState<string | null>(null);

  const handleAccept = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setError(null);
    const res = await fetch(`${API}/incidents/${inc.incident_id}/accept`, { method: 'POST' });
    if (!res.ok) setError(`Accept failed: ${res.status}`);
  };

  const handleRejectSubmit = async (e: React.MouseEvent | React.FormEvent) => {
    e.stopPropagation();
    (e as React.FormEvent).preventDefault?.();
    setError(null);
    const res = await fetch(`${API}/incidents/${inc.incident_id}/reject`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: rejectReason }),
    });
    if (res.ok) {
      setRejecting(false);
      setRejectReason('');
    } else {
      setError(`Reject failed: ${res.status}`);
    }
  };

  const handleEditSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setError(null);
    const res = await fetch(`${API}/incidents/${inc.incident_id}/report`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        summary: editSummary,
        rootCause: editRootCause,
        recommendedAction: editAction,
      }),
    });
    if (res.ok) {
      setEditing(false);
    } else {
      setError(`Edit failed: ${res.status}`);
    }
  };

  const isRejected = inc.human_decision === 'REJECTED';

  return (
    <div className="detail" onClick={e => e.stopPropagation()}>
      <p><strong>Root cause:</strong> {report.root_cause ?? '(not identified)'}</p>
      <p><strong>Recommended action:</strong> {report.recommended_action ?? '(none)'}</p>
      <p><strong>Confidence:</strong> {(report.confidence * 100).toFixed(0)}%</p>
      {report.dissenting_notes.length > 0 && (
        <>
          <p><strong>Dissenting notes:</strong></p>
          <ul>{report.dissenting_notes.map((n, i) => <li key={i}>{n}</li>)}</ul>
        </>
      )}
      {report.contributing_agents.length > 0 && (
        <p><strong>Contributing agents:</strong>{' '}
          <span className="mono">{report.contributing_agents.join(', ')}</span>
        </p>
      )}

      {inc.human_decision ? (
        <div className={`decision-banner ${isRejected ? 'rejected' : ''}`}>
          {inc.human_decision === 'ACCEPTED' && '✓ Accepted'}
          {isRejected && `✗ Rejected${inc.human_decision_reason ? `: ${inc.human_decision_reason}` : ''}`}
          {inc.human_decision === 'EDITED' && '✎ Edited'}
        </div>
      ) : TERMINAL_STATES.includes(inc.state) && !editing && (
        <>
          <div className="actions">
            <button className="btn-accept" onClick={handleAccept}>Accept</button>
            <button className="btn-reject" onClick={e => { e.stopPropagation(); setRejecting(r => !r); }}>
              Reject
            </button>
            <button className="btn-neutral" onClick={e => { e.stopPropagation(); setEditing(true); }}>Edit</button>
          </div>
          {rejecting && (
            <form className="reject-form" onSubmit={handleRejectSubmit} onClick={e => e.stopPropagation()}>
              <input
                autoFocus
                type="text"
                placeholder="Reason for rejection…"
                value={rejectReason}
                onChange={e => setRejectReason(e.target.value)}
              />
              <button type="submit" className="btn-reject" style={{ whiteSpace: 'nowrap' }}>Confirm</button>
              <button type="button" className="btn-neutral" onClick={e => { e.stopPropagation(); setRejecting(false); setRejectReason(''); }}>Cancel</button>
            </form>
          )}
        </>
      )}

      {editing && (
        <form className="edit-form" onSubmit={handleEditSubmit} onClick={e => e.stopPropagation()}>
          <label>
            Summary
            <textarea value={editSummary} onChange={e => setEditSummary(e.target.value)} rows={2} />
          </label>
          <label>
            Root cause
            <input value={editRootCause} onChange={e => setEditRootCause(e.target.value)} />
          </label>
          <label>
            Recommended action
            <input value={editAction} onChange={e => setEditAction(e.target.value)} />
          </label>
          <div className="form-actions">
            <button type="submit" className="btn-accept">Save</button>
            <button type="button" className="btn-neutral" onClick={e => { e.stopPropagation(); setEditing(false); }}>Cancel</button>
          </div>
        </form>
      )}

      {error && <div className="error">{error}</div>}
    </div>
  );
}
