package com.sentinel.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.incident.AgentTrace;
import com.sentinel.incident.Incident;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the payload for the Synthesizer's AgentTask: specialist findings
 * from agent_traces plus a small incident context block.
 */
@Component
public class SynthesizerTaskBuilder {

    private final AgentTraceRepository traces;
    private final ObjectMapper json;

    public SynthesizerTaskBuilder(AgentTraceRepository traces, ObjectMapper json) {
        this.traces = traces;
        this.json = json;
    }

    public Map<String, Object> buildPayload(Incident incident) {
        List<AgentTrace> specialistTraces = traces
                .findByIncidentId(incident.getId()).stream()
                .filter(t -> !t.getAgentName().equals("synthesizer"))
                .toList();

        List<Map<String, Object>> findings = specialistTraces.stream()
                .map(t -> {
                    Map<String, Object> f = new HashMap<>();
                    f.put("agent_name", t.getAgentName());
                    f.put("output", deserializeOutput(t.getOutput()));
                    f.put("status", t.getStatus());
                    f.put("tokens_used", t.getTokensUsed());
                    f.put("latency_ms", t.getLatencyMs());
                    f.put("prompt_version", t.getPromptVersion());
                    return f;
                })
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("specialist_findings", findings);
        payload.put("alert_name", incident.getSource());
        payload.put("severity", incident.getSeverity());
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Object deserializeOutput(String raw) {
        if (raw == null) return Map.of();
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
