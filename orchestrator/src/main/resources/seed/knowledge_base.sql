-- Sprint-4 seed data for the knowledge base.
-- Re-runnable: uses fixed UUIDs and ON CONFLICT DO NOTHING.

-- 1. Past incidents (will be augmented by the History agent's writes).
INSERT INTO knowledge_base.kb_documents (id, source_type, title, body, metadata)
VALUES
  ('11111111-0001-0000-0000-000000000001',
   'past_incident',
   'Slow checkout queries during evening peak',
   'Order creation latency p95 climbed from 50ms to 800ms between 18:00 and 19:30. Root cause: missing index on orders.created_at_userid composite. Resolved by adding index. No customer impact visible — orders still succeeded, just slow.',
   '{"severity":"p2","service":"demo-app","resolved_via":"index_add"}'::jsonb),

  ('11111111-0001-0000-0000-000000000002',
   'past_incident',
   'Payment gateway timeouts',
   'Downstream payment-gateway returned 504s for ~12 minutes. Root cause: gateway partner DNS resolution failure. Resolved when partner fixed DNS. We need a circuit breaker on this dependency.',
   '{"severity":"p1","service":"demo-app","resolved_via":"upstream_fix"}'::jsonb),

  ('11111111-0001-0000-0000-000000000003',
   'past_incident',
   'Memory leak after deploy',
   'Heap usage grew unbounded after the 2026-05-12 deploy. Root cause: connection pool not being closed in the new metrics scraper. Resolved by rollback + fixing the pool close in v1.4.2.',
   '{"severity":"p2","service":"demo-app","resolved_via":"hotfix"}'::jsonb),

  ('11111111-0001-0000-0000-000000000004',
   'past_incident',
   'Cache stampede on cold start',
   'After a deploy, all instances tried to rebuild the inventory cache simultaneously. Database CPU pinned for 8 minutes. Resolved when cache warmed. Fix: stagger cache rebuilds across instances.',
   '{"severity":"p2","service":"demo-app","resolved_via":"in_progress"}'::jsonb),

  ('11111111-0001-0000-0000-000000000005',
   'past_incident',
   'Kafka consumer lag during bulk import',
   'Order import job pushed 50k messages in 3 minutes. The aggregator listener fell behind by 4 minutes. Resolved when import finished. No data loss but real-time dashboards were stale.',
   '{"severity":"p3","service":"orchestrator","resolved_via":"natural_drain"}'::jsonb)
ON CONFLICT (id) DO NOTHING;

-- 2. Service topology.
INSERT INTO knowledge_base.kb_links (id, from_service, to_service, relationship, metadata)
VALUES
  ('22222222-0001-0000-0000-000000000001', 'demo-app', 'postgres', 'reads_from',
   '{"description":"orders + inventory queries"}'::jsonb),
  ('22222222-0001-0000-0000-000000000002', 'demo-app', 'redis', 'reads_from',
   '{"description":"session cache"}'::jsonb),
  ('22222222-0001-0000-0000-000000000003', 'demo-app', 'payment-gateway', 'calls',
   '{"description":"3rd-party payment processing"}'::jsonb),
  ('22222222-0001-0000-0000-000000000004', 'orchestrator', 'postgres', 'reads_from',
   '{"description":"incidents + agent_traces + audit_log"}'::jsonb),
  ('22222222-0001-0000-0000-000000000005', 'orchestrator', 'kafka', 'calls',
   '{"description":"event bus"}'::jsonb)
ON CONFLICT DO NOTHING;

-- 3. Runbooks.
INSERT INTO knowledge_base.kb_runbooks (id, title, summary, body, tags)
VALUES
  ('33333333-0001-0000-0000-000000000001',
   'Slow database queries',
   'How to triage slow Postgres queries',
   E'## Slow database queries\n\n1. Check pg_stat_statements for top-N slow queries.\n2. EXPLAIN ANALYZE the worst offender.\n3. Common causes: missing index, full table scan, lock waits.\n4. Common fixes: add index, rewrite query, partition table.',
   ARRAY['database', 'postgres', 'performance']),

  ('33333333-0001-0000-0000-000000000002',
   'Downstream dependency timeouts',
   'When a partner API stops responding',
   E'## Downstream timeouts\n\n1. Check the partner status page.\n2. Look at the circuit breaker metrics.\n3. Drain in-flight requests; surface clear error to users.\n4. Escalate to vendor on-call if > 5 min.',
   ARRAY['external_dependency', 'timeout', 'circuit_breaker']),

  ('33333333-0001-0000-0000-000000000003',
   'Memory leak triage',
   'Heap usage climbing unbounded',
   E'## Memory leak triage\n\n1. Take a heap dump from the affected instance.\n2. Compare against a healthy instance''s heap.\n3. Look for retained objects: connection pools, caches, listeners.\n4. If recent deploy: roll back as the first hypothesis.',
   ARRAY['memory_leak', 'jvm', 'debugging']),

  ('33333333-0001-0000-0000-000000000004',
   'Kafka consumer lag',
   'When the orchestrator falls behind',
   E'## Kafka consumer lag\n\n1. Check the consumer group offset vs partition end offsets.\n2. If lag is recent: look at producer rate (bulk import?).\n3. If lag is chronic: scale up consumer parallelism.\n4. Worst case: pause non-critical producers.',
   ARRAY['kafka', 'event_bus', 'lag'])
ON CONFLICT DO NOTHING;
