package com.sentinel.incident;

import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class AuditWriter {

    private final AuditLogRepository repo;

    public AuditWriter(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void write(UUID incidentId, String eventType, String actor) {
        record(incidentId, eventType, actor, null);
    }

    public void record(UUID incidentId, String eventType, String actor, String detail) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setId(UUID.randomUUID());
        entry.setIncidentId(incidentId);
        entry.setEventType(eventType);
        entry.setActor(actor);
        entry.setDetail(detail);
        entry.setCreatedAt(OffsetDateTime.now());
        repo.save(entry);
    }
}
