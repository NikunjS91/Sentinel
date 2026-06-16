import { useState } from 'react';
import { useIncidentStream } from './useIncidentStream';
import type { IncidentWithReport } from './useIncidentStream';
import type { IncidentState } from './types';
import './App.css';

const API = import.meta.env.VITE_API ?? 'http://localhost:8080';

const TERMINAL_STATES: IncidentState[] = ['RESOLVED', 'PARTIAL'];
const STATE_COLORS: Record<IncidentState, string> = {
  RECEIVED: '#888',
  CLASSIFIED: '#888',
  DISPATCHED: '#5b8def',
  AGGREGATING: '#f0a500',
  AGGREGATING_PARTIAL: '#f07000',
  SYNTHESIZED: '#f0a500',
  SYNTHESIZED_PARTIAL: '#f07000',
  RESOLVED: '#2ea043',
  PARTIAL: '#c25700',
  FAILED: '#cf222e',
};

export default function App() {
  const { incidents, connected } = useIncidentStream();
  const [selected, setSelected] = useState<string | null>(null);

  return (
    <div className="app">
      <header>
        <h1>Sentinel</h1>
        <span className={`conn-indicator ${connected ? 'on' : 'off'}`}>
          {connected ? 'live' : 'reconnecting...'}
        </span>
      </header>
      <main>
        <section className="incident-list">
          {incidents.length === 0 && (
            <p className="empty">No incidents yet. POST one to /alerts.</p>
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
                <span className="time">{new Date(inc.created_at).toLocaleTimeString()}</span>
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
      </main>
    </div>
  );
}

function IncidentDetail({ inc }: { inc: IncidentWithReport }) {
  const report = inc.report!;
  const [editing, setEditing] = useState(false);
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

  const handleReject = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setError(null);
    const reason = window.prompt('Reason for rejection?');
    if (reason === null) return;
    const res = await fetch(`${API}/incidents/${inc.incident_id}/reject`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason }),
    });
    if (!res.ok) setError(`Reject failed: ${res.status}`);
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
        <p><strong>Contributing agents:</strong> {report.contributing_agents.join(', ')}</p>
      )}

      {inc.human_decision ? (
        <div className="decision-banner">
          {inc.human_decision === 'ACCEPTED' && '✓ Accepted'}
          {inc.human_decision === 'REJECTED' && `✗ Rejected${inc.human_decision_reason ? `: ${inc.human_decision_reason}` : ''}`}
          {inc.human_decision === 'EDITED' && '✎ Edited'}
        </div>
      ) : TERMINAL_STATES.includes(inc.state) && !editing && (
        <div className="actions">
          <button onClick={handleAccept}>Accept</button>
          <button onClick={handleReject}>Reject</button>
          <button onClick={e => { e.stopPropagation(); setEditing(true); }}>Edit</button>
        </div>
      )}

      {editing && (
        <form className="edit-form" onSubmit={handleEditSubmit}
              onClick={e => e.stopPropagation()}>
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
            <button type="submit">Save</button>
            <button type="button" onClick={e => { e.stopPropagation(); setEditing(false); }}>
              Cancel
            </button>
          </div>
        </form>
      )}

      {error && <div className="error">{error}</div>}
    </div>
  );
}
