package com.sentinel.humanloop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.events.IncidentEventPublisher;
import com.sentinel.humanloop.HumanDecisionRequests.EditRequest;
import com.sentinel.incident.AuditWriter;
import com.sentinel.incident.IncidentReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class HumanDecisionService {

    private final IncidentReportRepository reportRepo;
    private final AuditWriter audit;
    private final IncidentEventPublisher events;
    private final ObjectMapper json;

    public HumanDecisionService(IncidentReportRepository reportRepo,
                                AuditWriter audit,
                                IncidentEventPublisher events,
                                ObjectMapper json) {
        this.reportRepo = reportRepo;
        this.audit = audit;
        this.events = events;
        this.json = json;
    }

    @Transactional
    public void accept(UUID incidentId) {
        IncidentReport report = findAndGuard(incidentId);
        report.setHumanDecision("ACCEPTED");
        report.setHumanDecidedAt(OffsetDateTime.now());
        reportRepo.save(report);
        audit.record(incidentId, "HUMAN_ACCEPTED", "operator", null);
        events.publish(humanDecisionEvent(incidentId, "ACCEPTED", null));
    }

    @Transactional
    public void reject(UUID incidentId, String reason) {
        IncidentReport report = findAndGuard(incidentId);
        report.setHumanDecision("REJECTED");
        report.setHumanDecisionReason(reason);
        report.setHumanDecidedAt(OffsetDateTime.now());
        reportRepo.save(report);
        audit.record(incidentId, "HUMAN_REJECTED", "operator", toJson(Map.of("reason", reason)));
        events.publish(humanDecisionEvent(incidentId, "REJECTED", reason));
    }

    @Transactional
    public void edit(UUID incidentId, EditRequest req) {
        IncidentReport report = findAndGuard(incidentId);
        report.setHumanDecision("EDITED");
        report.setHumanDecidedAt(OffsetDateTime.now());
        // Preserve AI originals — only write edited_* columns
        report.setEditedSummary(req.summary());
        report.setEditedRootCause(req.rootCause());
        report.setEditedRecommendedAction(req.recommendedAction());
        reportRepo.save(report);
        audit.record(incidentId, "HUMAN_EDITED", "operator", toJson(req));
        events.publish(humanDecisionEvent(incidentId, "EDITED", null));
    }

    private IncidentReport findAndGuard(UUID incidentId) {
        IncidentReport report = reportRepo.findByIncidentId(incidentId)
                .orElseThrow(() -> new ReportNotFoundException(incidentId));
        if (report.getHumanDecision() != null) {
            throw new AlreadyDecidedException(incidentId);
        }
        return report;
    }

    private Map<String, Object> humanDecisionEvent(UUID incidentId, String decision, String reason) {
        java.util.LinkedHashMap<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "incident.human_decision");
        event.put("ts", OffsetDateTime.now().toString());
        event.put("incident_id", incidentId.toString());
        event.put("decision", decision);
        event.put("reason", reason);
        return event;
    }

    private String toJson(Object obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
