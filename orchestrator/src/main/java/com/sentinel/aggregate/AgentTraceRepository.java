package com.sentinel.aggregate;

import com.sentinel.incident.AgentTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentTraceRepository extends JpaRepository<AgentTrace, UUID> {
    Optional<AgentTrace> findByIncidentIdAndAgentName(UUID incidentId, String agentName);
}
