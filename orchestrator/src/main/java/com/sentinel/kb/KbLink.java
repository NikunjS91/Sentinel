package com.sentinel.kb;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "kb_links", schema = "knowledge_base")
public class KbLink {

    @Id
    private UUID id;

    @Column(name = "from_service", nullable = false)
    private String fromService;

    @Column(name = "to_service", nullable = false)
    private String toService;

    @Column(nullable = false)
    private String relationship;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getFromService() { return fromService; }
    public void setFromService(String fromService) { this.fromService = fromService; }

    public String getToService() { return toService; }
    public void setToService(String toService) { this.toService = toService; }

    public String getRelationship() { return relationship; }
    public void setRelationship(String relationship) { this.relationship = relationship; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
