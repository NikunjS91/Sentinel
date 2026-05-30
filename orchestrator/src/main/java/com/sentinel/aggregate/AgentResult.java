package com.sentinel.aggregate;

import java.util.Map;
import java.util.UUID;

/**
 * Inbound message on agent.results from the Python agent service.
 * Wire format: camelCase (matches Pydantic AgentResult aliases).
 */
public record AgentResult(
        UUID incidentId,
        String agentName,
        Map<String, Object> output,
        int tokensUsed,
        int latencyMs,
        String status,
        String promptVersion
) {}
