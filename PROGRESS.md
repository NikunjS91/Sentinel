# Sentinel — Progress Log

## Sprint 1 — Foundation

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
