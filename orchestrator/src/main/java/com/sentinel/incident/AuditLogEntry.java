package com.sentinel.incident;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Column(nullable = false)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public AuditLogEntry() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
