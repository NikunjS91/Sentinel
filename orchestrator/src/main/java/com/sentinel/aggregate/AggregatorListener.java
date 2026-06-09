package com.sentinel.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.incident.AgentTrace;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentReport;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import com.sentinel.incident.IncidentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class AggregatorListener {

    private static final Logger log = LoggerFactory.getLogger(AggregatorListener.class);

    private final IncidentRepository incidents;
    private final AgentTraceRepository traces;
    private final IncidentReportRepository reports;
    private final IncidentStateMachine stateMachine;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public AggregatorListener(IncidentRepository incidents,
                              AgentTraceRepository traces,
                              IncidentReportRepository reports,
                              IncidentStateMachine stateMachine,
                              KafkaTemplate<String, String> kafka,
                              ObjectMapper json) {
        this.incidents = incidents;
        this.traces = traces;
        this.reports = reports;
        this.stateMachine = stateMachine;
        this.kafka = kafka;
        this.json = json;
    }

    @KafkaListener(topics = KafkaTopicConfig.AGENT_RESULTS, groupId = "orchestrator")
    @Transactional
    public void onAgentResult(String raw) {
        AgentResult result = parseOrSkip(raw);
        if (result == null) return;

        // Idempotency: same (incident, agent) arriving twice is a no-op.
        if (traces.findByIncidentIdAndAgentName(
                result.incidentId(), result.agentName()).isPresent()) {
            return;
        }

        Incident inc = incidents.findById(result.incidentId()).orElse(null);
        if (inc == null) return;

        // Record the trace always — even if status=error, the attempt is logged.
        traces.save(toTrace(result));

        // Resolve when all expected agents have reported. The expected set is
        // recorded on the incident at dispatch time (Day 19). No hardcoded count.
        long traceCount = traces.countByIncidentId(inc.getId());
        int expectedCount = inc.getExpectedAgents().size();

        if (inc.getState() == IncidentState.DISPATCHED
                && expectedCount > 0
                && traceCount >= expectedCount) {
            stateMachine.transition(inc, IncidentState.AGGREGATING);
            reports.save(buildReport(inc, result));
            stateMachine.transition(inc, IncidentState.SYNTHESIZED);
            stateMachine.transition(inc, IncidentState.RESOLVED);

            kafka.send(KafkaTopicConfig.INCIDENTS_SYNTHESIZED,
                       inc.getId().toString(),
                       inc.getId().toString());
        }
    }

    private AgentResult parseOrSkip(String raw) {
        try {
            return json.readValue(raw, AgentResult.class);
        } catch (Exception e) {
            log.warn("unparseable agent result, skipping: {}", e.getMessage());
            return null;
        }
    }

    private AgentTrace toTrace(AgentResult r) {
        AgentTrace t = new AgentTrace();
        t.setId(UUID.randomUUID());
        t.setIncidentId(r.incidentId());
        t.setAgentName(r.agentName());
        t.setPromptVersion(r.promptVersion());
        t.setOutput(serializeOutput(r));
        t.setTokensUsed(r.tokensUsed());
        t.setCostUsd(BigDecimal.ZERO);
        t.setLatencyMs(r.latencyMs());
        t.setStatus(r.status());
        t.setCreatedAt(OffsetDateTime.now());
        return t;
    }

    private String serializeOutput(AgentResult r) {
        if (r.output() == null) return null;
        try {
            return json.writeValueAsString(r.output());
        } catch (Exception e) {
            return "{}";
        }
    }

    private IncidentReport buildReport(Incident inc, AgentResult r) {
        IncidentReport rep = new IncidentReport();
        rep.setId(UUID.randomUUID());
        rep.setIncidentId(inc.getId());
        Object msg = r.output() == null ? null : r.output().get("message");
        rep.setSummary(msg == null ? "(no summary)" : String.valueOf(msg));
        rep.setCreatedAt(OffsetDateTime.now());
        return rep;
    }
}
