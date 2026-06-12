package com.sentinel.aggregate;

import com.sentinel.incident.AgentTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentTraceRepository extends JpaRepository<AgentTrace, UUID> {
    Optional<AgentTrace> findByIncidentIdAndAgentName(UUID incidentId, String agentName);
    long countByIncidentId(UUID incidentId);
    List<AgentTrace> findByIncidentId(UUID incidentId);
    long countByIncidentIdAndAgentNameNot(UUID incidentId, String agentName);
}
