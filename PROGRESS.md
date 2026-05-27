# Sentinel — Progress Log

## Sprint 1 — Foundation

### Day 6 — Classifier & dispatcher
- Done: AgentTask message record (wire contract decided: camelCase — incidentId, agentName,
  service, payload — documented in contracts/agent-task-schema.md); rule-based
  IncidentClassifier (severity p1-p4, never throws); Dispatcher publishes keyed AgentTask
  messages and is built for many agents; ClassifierListener — the first Kafka consumer —
  drives RECEIVED→CLASSIFIED→DISPATCHED and dispatches the echo task, with an idempotency
  guard against at-least-once redelivery. All operations in one @Transactional on
  onRawIncident to prevent partial re-inserts under concurrent test cleanup. Tests
  TC-1.6.1–1.6.4 pass. All 25 tests green.
- Decision: AgentTask wire format is camelCase — recorded in contracts/agent-task-schema.md.
- Known simplification: listener logs+skips a missing incident; proper DLQ is Sprint 5.
- Next: Day 7 — FastAPI agent service: the Python plane, a Kafka consumer for agent.tasks.

### Day 5 — Incident state machine
- Done: IncidentState enum (8 states, terminal flag); IllegalStateTransitionException;
  IncidentStateMachine with the legal-transition table, canTransition check, and a
  transactional transition() that persists + audits. Incident entity state field
  migrated to the enum; Day-4 code (IngestService, IngestController, SchemaIntegrationTest)
  updated to match. Unit tests TC-1.5.1–1.5.5 and integration test TC-1.5.6 pass.
  All 21 tests green.
- Blocked: (none)
- Next: Day 6 — Classifier & dispatcher: consume incidents.raw, assign severity,
  drive RECEIVED->CLASSIFIED->DISPATCHED, publish to agent.tasks.

### Day 4 — Alert ingestion & idempotency
- Done: POST /alerts with AlertRequest validation; SHA-256 idempotency key from
  identity fields (source|service|alertName|fingerprint); IngestService dedups,
  persists a RECEIVED incident, writes an audit row, publishes the incident id to
  incidents.raw; AuditWriter component + AuditLogRepository wired; clean 400 on
  invalid payloads via IngestExceptionHandler. Integration tests TC-1.4.1–1.4.4
  pass. All 9 tests green.
- Note: Kafka send after DB commit — known gap (message lost if send fails);
  addressed in Sprint 5 hardening.
- Blocked: (none)
- Next: Day 5 — Incident state machine: explicit, validated, persisted,
  audited state transitions.

### Day 1 — Setup
- Done: monorepo structure, docker-compose, CLAUDE.md, .gitignore,
  .env.example, placeholder CI. Stack comes up healthy. CI green.
- Blocked: (none)
- Next: Day 4 — Alert ingestion: POST /alerts, idempotency-key dedup, persist a RECEIVED incident, publish to incidents.raw.

### Day 3 — Kafka topics & orchestrator skeleton
- Done: spring-kafka added; six topics provisioned automatically on startup via
  NewTopic beans; KafkaTopicConfig holds topic-name constants; custom Kafka
  health indicator wired into /actuator/health; integration tests TC-1.3.1 and
  TC-1.3.2 pass with a Testcontainers Kafka. All 5 tests green.
- Blocked: (none)
- Next: Day 4 — Alert ingestion: POST /alerts, idempotency-key dedup, persist a
  RECEIVED incident, publish to incidents.raw.

### Day 2 — Database schema
- Done: Spring Boot project created; Flyway wired in; V1 migration creates the
  5 core tables; JPA entities validate against the schema; IncidentRepository
  added; integration tests (TC-1.2.1, TC-1.2.2) pass.
- Note: Docker 29.x requires API ≥ 1.44 — set `api.version=1.44` in Surefire
  argLine to unblock Testcontainers on this machine.
- Blocked: (none)
- Next: Day 3 — Kafka topics and the orchestrator skeleton (Kafka config,
  topic provisioning, health check including Kafka).
