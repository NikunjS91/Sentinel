import { useState } from 'react';
import { useIncidentStream } from './useIncidentStream';
import type { IncidentState, IncidentReport } from './types';
import './App.css';

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
                <span className="time">{
                  new Date(inc.created_at).toLocaleTimeString()
                }</span>
              </div>
              {TERMINAL_STATES.includes(inc.state) && inc.report && (
                <p className="summary">{inc.report.summary}</p>
              )}
              {selected === inc.incident_id && inc.report && (
                <IncidentDetail report={inc.report} />
              )}
            </article>
          ))}
        </section>
      </main>
    </div>
  );
}

function IncidentDetail({ report }: { report: IncidentReport }) {
  return (
    <div className="detail">
      <p><strong>Root cause:</strong> {report.root_cause ?? '(not identified)'}</p>
      <p><strong>Recommended action:</strong> {report.recommended_action ?? '(none)'}</p>
      <p><strong>Confidence:</strong> {(report.confidence * 100).toFixed(0)}%</p>
      {report.dissenting_notes.length > 0 && (
        <>
          <p><strong>Dissenting notes:</strong></p>
          <ul>
            {report.dissenting_notes.map((n, i) =>
              <li key={i}>{n}</li>
            )}
          </ul>
        </>
      )}
      {report.contributing_agents.length > 0 && (
        <p>
          <strong>Contributing agents:</strong>{' '}
          {report.contributing_agents.join(', ')}
        </p>
      )}
    </div>
  );
}
