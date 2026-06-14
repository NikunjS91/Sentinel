package com.sentinel.api;

import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.incident.AgentTrace;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentReport;
import com.sentinel.incident.IncidentRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class IncidentListController {

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
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return incidents.findRecent(limit).stream()
            .map(this::toListItem)
            .toList();
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

    private Map<String, Object> toListItem(Incident inc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incident_id", inc.getId().toString());
        m.put("state", inc.getState().name());
        m.put("service", inc.getSource());
        m.put("severity", inc.getSeverity());
        m.put("created_at", inc.getCreatedAt().toString());
        return m;
    }

    private Map<String, Object> reportMap(IncidentReport r, List<AgentTrace> incidentTraces) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", r.getSummary());
        m.put("root_cause", r.getRootCause());
        m.put("recommended_action", r.getRecommendedAction());
        m.put("confidence", r.getConfidence());
        // dissenting_notes and contributing_agents come from the synthesizer trace
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
