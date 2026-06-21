package com.sentinel.incident;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String source;

    private String severity;

    @Enumerated(STRING)
    @Column(nullable = false)
    private IncidentState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_alert", columnDefinition = "jsonb", nullable = false)
    private String rawAlert;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_agents", nullable = false, columnDefinition = "jsonb")
    private List<String> expectedAgents = new ArrayList<>();

    @Column(name = "deadline_at")
    private OffsetDateTime deadlineAt;

    @OneToOne(mappedBy = "incident", fetch = FetchType.LAZY)
    private IncidentReport report;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Incident() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public IncidentState getState() { return state; }
    public void setState(IncidentState state) { this.state = state; }

    public String getRawAlert() { return rawAlert; }
    public void setRawAlert(String rawAlert) { this.rawAlert = rawAlert; }

    public List<String> getExpectedAgents() { return expectedAgents; }
    public void setExpectedAgents(List<String> agents) { this.expectedAgents = agents; }

    public OffsetDateTime getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(OffsetDateTime deadlineAt) { this.deadlineAt = deadlineAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
