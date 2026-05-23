package com.sentinel.incident;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "prompt_versions")
public class PromptVersion {

    @Id
    private String version;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public PromptVersion() {}

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
