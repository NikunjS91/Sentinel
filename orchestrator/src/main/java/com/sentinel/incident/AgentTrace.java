package com.sentinel.incident;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_traces")
public class AgentTrace {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "prompt_version")
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String output;

    @Column(name = "tokens_used", nullable = false)
    private Integer tokensUsed;

    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "latency_ms", nullable = false)
    private Integer latencyMs;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public AgentTrace() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public Integer getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(Integer tokensUsed) { this.tokensUsed = tokensUsed; }

    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
