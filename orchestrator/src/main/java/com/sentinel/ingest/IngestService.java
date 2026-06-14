package com.sentinel.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.events.IncidentEventPublisher;
import com.sentinel.incident.AuditWriter;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class IngestService {

    private final IncidentRepository incidents;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final AuditWriter audit;
    private final IncidentEventPublisher events;

    public IngestService(IncidentRepository incidents,
                         KafkaTemplate<String, String> kafka,
                         ObjectMapper json,
                         AuditWriter audit,
                         IncidentEventPublisher events) {
        this.incidents = incidents;
        this.kafka = kafka;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    public record IngestResult(Incident incident, boolean created) {}

    @Transactional
    public IngestResult handle(AlertRequest req) {
        String key = IdempotencyKey.of(req);

        var existing = incidents.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            audit.write(existing.get().getId(), "INGEST_DUPLICATE", "system");
            return new IngestResult(existing.get(), false);
        }

        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey(key);
        inc.setSource(req.source());
        inc.setSeverity(req.severity());
        inc.setState(IncidentState.RECEIVED);
        inc.setRawAlert(toJson(req));
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        incidents.save(inc);

        audit.write(inc.getId(), "INGESTED", "system");

        kafka.send(KafkaTopicConfig.INCIDENTS_RAW,
                   inc.getId().toString(),
                   inc.getId().toString());

        events.publish(receivedEvent(inc));

        return new IngestResult(inc, true);
    }

    private Map<String, Object> receivedEvent(Incident inc) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "incident.state_changed");
        e.put("ts", OffsetDateTime.now().toString());
        e.put("incident_id", inc.getId().toString());
        e.put("state", IncidentState.RECEIVED.name());
        e.put("service", inc.getSource());
        e.put("severity", inc.getSeverity());
        e.put("alert_name", null);
        e.put("expected_agents", inc.getExpectedAgents());
        e.put("report", null);
        return e;
    }

    private String toJson(AlertRequest req) {
        try {
            return json.writeValueAsString(req);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to serialize alert to JSON", e);
        }
    }
}
