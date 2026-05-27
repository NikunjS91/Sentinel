package com.sentinel.dispatch;

import java.util.UUID;

/**
 * A unit of work for one agent on one incident. Published to agent.tasks,
 * consumed by the Python agent service (Day 7+).
 *
 * Wire format: camelCase JSON (Jackson default). The Python Pydantic model
 * must use alias_generator=to_camel or explicit field aliases to match.
 * See contracts/agent-task-schema.md.
 *
 * payload is free-form for Sprint 1 (echo agent ignores it); becomes typed in Sprint 2.
 */
public record AgentTask(
        UUID incidentId,
        String agentName,
        String service,
        Object payload
) {}
