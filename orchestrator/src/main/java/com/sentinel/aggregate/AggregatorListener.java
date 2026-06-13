package com.sentinel.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.dispatch.Dispatcher;
import com.sentinel.dispatch.SynthesizerTaskBuilder;
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
import java.util.Map;
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
    private final Dispatcher dispatcher;
    private final SynthesizerTaskBuilder synthesizerTaskBuilder;

    public AggregatorListener(IncidentRepository incidents,
                              AgentTraceRepository traces,
                              IncidentReportRepository reports,
                              IncidentStateMachine stateMachine,
                              KafkaTemplate<String, String> kafka,
                              ObjectMapper json,
                              Dispatcher dispatcher,
                              SynthesizerTaskBuilder synthesizerTaskBuilder) {
        this.incidents = incidents;
        this.traces = traces;
        this.reports = reports;
        this.stateMachine = stateMachine;
        this.kafka = kafka;
        this.json = json;
        this.dispatcher = dispatcher;
        this.synthesizerTaskBuilder = synthesizerTaskBuilder;
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

        traces.save(toTrace(result));

        if ("synthesizer".equals(result.agentName())) {
            handleSynthesizerResult(inc, result);
        } else {
            handleSpecialistResult(inc);
        }
    }

    /** A specialist reported. When all specialists are done, dispatch the Synthesizer. */
    private void handleSpecialistResult(Incident inc) {
        long specialistTraces = traces.countByIncidentIdAndAgentNameNot(inc.getId(), "synthesizer");
        int expectedCount = inc.getExpectedAgents().size();

        if (inc.getState() == IncidentState.DISPATCHED
                && expectedCount > 0
                && specialistTraces >= expectedCount) {

            stateMachine.transition(inc, IncidentState.AGGREGATING);
            dispatcher.dispatchSynthesizer(inc, synthesizerTaskBuilder.buildPayload(inc));
        }
    }

    /** The Synthesizer reported. Write the report and advance to RESOLVED or PARTIAL. */
    private void handleSynthesizerResult(Incident inc, AgentResult result) {
        if (inc.getState() == IncidentState.AGGREGATING) {
            reports.save(buildReportFromSynthesizer(inc, result));
            stateMachine.transition(inc, IncidentState.SYNTHESIZED);
            stateMachine.transition(inc, IncidentState.RESOLVED);
        } else if (inc.getState() == IncidentState.AGGREGATING_PARTIAL) {
            reports.save(buildReportFromSynthesizer(inc, result));
            stateMachine.transition(inc, IncidentState.SYNTHESIZED_PARTIAL);
            stateMachine.transition(inc, IncidentState.PARTIAL);
        } else {
            log.warn("Synthesizer result for incident {} arrived in unexpected state {}; trace recorded, ignoring.",
                     inc.getId(), inc.getState());
            return;
        }

        kafka.send(KafkaTopicConfig.INCIDENTS_SYNTHESIZED,
                   inc.getId().toString(),
                   inc.getId().toString());
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

    private IncidentReport buildReportFromSynthesizer(Incident inc, AgentResult r) {
        IncidentReport rep = new IncidentReport();
        rep.setId(UUID.randomUUID());
        rep.setIncidentId(inc.getId());
        Map<String, Object> out = r.output() == null ? Map.of() : r.output();
        rep.setSummary(stringField(out, "summary"));
        rep.setRootCause(stringField(out, "root_cause"));
        rep.setRecommendedAction(stringField(out, "recommended_action"));
        Object conf = out.get("confidence");
        if (conf instanceof Number n) {
            rep.setConfidence(BigDecimal.valueOf(n.doubleValue()));
        }
        rep.setCreatedAt(OffsetDateTime.now());
        return rep;
    }

    private static String stringField(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
