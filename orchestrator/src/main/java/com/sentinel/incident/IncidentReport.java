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

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", insertable = false, updatable = false)
    private Incident incident;

    private String summary;

    @Column(name = "root_cause")
    private String rootCause;

    @Column(name = "recommended_action")
    private String recommendedAction;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "human_decision")
    private String humanDecision;

    @Column(name = "human_decision_reason")
    private String humanDecisionReason;

    @Column(name = "human_decided_at")
    private OffsetDateTime humanDecidedAt;

    @Column(name = "edited_summary")
    private String editedSummary;

    @Column(name = "edited_root_cause")
    private String editedRootCause;

    @Column(name = "edited_recommended_action")
    private String editedRecommendedAction;

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

    public String getHumanDecisionReason() { return humanDecisionReason; }
    public void setHumanDecisionReason(String humanDecisionReason) { this.humanDecisionReason = humanDecisionReason; }

    public OffsetDateTime getHumanDecidedAt() { return humanDecidedAt; }
    public void setHumanDecidedAt(OffsetDateTime humanDecidedAt) { this.humanDecidedAt = humanDecidedAt; }

    public String getEditedSummary() { return editedSummary; }
    public void setEditedSummary(String editedSummary) { this.editedSummary = editedSummary; }

    public String getEditedRootCause() { return editedRootCause; }
    public void setEditedRootCause(String editedRootCause) { this.editedRootCause = editedRootCause; }

    public String getEditedRecommendedAction() { return editedRecommendedAction; }
    public void setEditedRecommendedAction(String editedRecommendedAction) { this.editedRecommendedAction = editedRecommendedAction; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
