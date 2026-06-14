package com.sentinel.deadline;

import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.dispatch.Dispatcher;
import com.sentinel.dispatch.SynthesizerTaskBuilder;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import com.sentinel.incident.IncidentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DeadlineSweeper {

    private static final Logger log = LoggerFactory.getLogger(DeadlineSweeper.class);

    private final IncidentRepository incidents;
    private final AgentTraceRepository traces;
    private final IncidentStateMachine stateMachine;
    private final Dispatcher dispatcher;
    private final SynthesizerTaskBuilder synthesizerTaskBuilder;

    public DeadlineSweeper(IncidentRepository incidents,
                           AgentTraceRepository traces,
                           IncidentStateMachine stateMachine,
                           Dispatcher dispatcher,
                           SynthesizerTaskBuilder synthesizerTaskBuilder) {
        this.incidents = incidents;
        this.traces = traces;
        this.stateMachine = stateMachine;
        this.dispatcher = dispatcher;
        this.synthesizerTaskBuilder = synthesizerTaskBuilder;
    }

    @Scheduled(fixedDelayString = "${sentinel.incident.sweeper-interval-ms:5000}")
    @Transactional
    public void sweep() {
        List<Incident> overdue = incidents.findOverdueActive(OffsetDateTime.now());
        for (Incident inc : overdue) {
            try {
                progressOverdue(inc);
            } catch (Exception e) {
                log.warn("DeadlineSweeper: failed to progress incident {}: {}", inc.getId(), e.getMessage());
            }
        }
    }

    private void progressOverdue(Incident inc) {
        if (inc.getState() != IncidentState.DISPATCHED) {
            return;
        }

        stateMachine.transition(inc, IncidentState.AGGREGATING_PARTIAL);
        incidents.save(inc);

        Map<String, Object> payload = synthesizerTaskBuilder.buildPayload(inc);

        List<String> reportedAgents = traces.findByIncidentId(inc.getId()).stream()
                .filter(t -> !t.getAgentName().equals("synthesizer"))
                .map(t -> t.getAgentName())
                .toList();

        List<String> expectedAgents = inc.getExpectedAgents() != null
                ? inc.getExpectedAgents()
                : List.of();

        List<String> missingSpecialists = new ArrayList<>(expectedAgents);
        missingSpecialists.removeAll(reportedAgents);

        payload.put("missing_specialists", missingSpecialists);
        payload.put("partial", true);

        dispatcher.dispatchSynthesizer(inc, payload);
        log.info("DeadlineSweeper: dispatched synthesizer for overdue incident {} (missing: {})",
                inc.getId(), missingSpecialists);
    }
}
