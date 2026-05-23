package com.sentinel.incident;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "incident_reports")
public class IncidentReport {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    private String summary;

    @Column(name = "root_cause")
    private String rootCause;

    @Column(name = "recommended_action")
    private String recommendedAction;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "human_decision")
    private String humanDecision;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public IncidentReport() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public String getHumanDecision() { return humanDecision; }
    public void setHumanDecision(String humanDecision) { this.humanDecision = humanDecision; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
