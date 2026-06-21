package com.sentinel.incident;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {
    List<AuditLogEntry> findByIncidentId(UUID incidentId);
}
