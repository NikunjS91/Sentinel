package com.sentinel.api;

import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.incident.AgentTrace;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentReport;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentSpecifications;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class IncidentListController {

    private static final List<String> VALID_DECISIONS =
        List.of("undecided", "accepted", "rejected", "edited");

    private final IncidentRepository incidents;
    private final IncidentReportRepository reports;
    private final AgentTraceRepository traces;

    public IncidentListController(IncidentRepository incidents,
                                  IncidentReportRepository reports,
                                  AgentTraceRepository traces) {
        this.incidents = incidents;
        this.reports = reports;
        this.traces = traces;
    }

    @CrossOrigin(origins = "${sentinel.ui.cors-origin:http://localhost:5173}")
    @GetMapping("/incidents")
    public ListResponse list(
            @RequestParam(name = "state", required = false) List<String> state,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "decision", required = false) String decision,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "before", required = false) OffsetDateTime before) {

        if (decision != null && !VALID_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException(
                "decision must be one of: undecided, accepted, rejected, edited");
        }
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }

        IncidentFilter filter = new IncidentFilter(state, service, decision, q, limit, before);
        PageRequest page = PageRequest.of(0, filter.limit());
        List<Incident> rows = incidents
            .findAll(IncidentSpecifications.matching(filter), page)
            .getContent();

        List<Map<String, Object>> items = rows.stream().map(this::toListItem).toList();

        String nextBefore = null;
        if (rows.size() == filter.limit()) {
            nextBefore = rows.get(rows.size() - 1).getCreatedAt().toString();
        }

        return new ListResponse(items, nextBefore);
    }

    @CrossOrigin(origins = "${sentinel.ui.cors-origin:http://localhost:5173}")
    @GetMapping("/incidents/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        Incident inc = incidents.findById(id)
            .orElseThrow(() -> new IncidentNotFoundException(id));
        IncidentReport report = reports.findByIncidentId(id).orElse(null);
        List<AgentTrace> incidentTraces = traces.findByIncidentId(id);

        Map<String, Object> out = toListItem(inc);
        out.put("report", report == null ? null : reportMap(report, incidentTraces));
        out.put("traces", incidentTraces.stream().map(this::traceMap).toList());
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    public record ListResponse(List<Map<String, Object>> items, String nextBefore) {}

    private Map<String, Object> toListItem(Incident inc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incident_id", inc.getId().toString());
        m.put("state", inc.getState().name());
        m.put("service", inc.getSource());
        m.put("severity", inc.getSeverity());
        m.put("created_at", inc.getCreatedAt().toString());
        IncidentReport report = reports.findByIncidentId(inc.getId()).orElse(null);
        m.put("human_decision", report != null ? report.getHumanDecision() : null);
        m.put("human_decision_reason", report != null ? report.getHumanDecisionReason() : null);
        return m;
    }

    private Map<String, Object> reportMap(IncidentReport r, List<AgentTrace> incidentTraces) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", r.getSummary());
        m.put("root_cause", r.getRootCause());
        m.put("recommended_action", r.getRecommendedAction());
        m.put("confidence", r.getConfidence());
        AgentTrace synthTrace = incidentTraces.stream()
            .filter(t -> "synthesizer".equals(t.getAgentName()))
            .findFirst()
            .orElse(null);
        if (synthTrace != null && synthTrace.getOutput() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> out = (Map<String, Object>)
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(synthTrace.getOutput(), Map.class);
                m.put("dissenting_notes", out.getOrDefault("dissenting_notes", List.of()));
                m.put("contributing_agents", out.getOrDefault("contributing_agents", List.of()));
            } catch (Exception e) {
                m.put("dissenting_notes", List.of());
                m.put("contributing_agents", List.of());
            }
        } else {
            m.put("dissenting_notes", List.of());
            m.put("contributing_agents", List.of());
        }
        return m;
    }

    private Map<String, Object> traceMap(AgentTrace t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent_name", t.getAgentName());
        m.put("status", t.getStatus());
        m.put("output", t.getOutput());
        m.put("tokens_used", t.getTokensUsed());
        m.put("latency_ms", t.getLatencyMs());
        m.put("prompt_version", t.getPromptVersion());
        return m;
    }
}
