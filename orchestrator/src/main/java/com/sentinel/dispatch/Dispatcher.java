package com.sentinel.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class Dispatcher {

    private final KafkaTemplate<String, String> kafka;
    private final IncidentRepository incidents;
    private final ObjectMapper json;

    public Dispatcher(KafkaTemplate<String, String> kafka,
                      IncidentRepository incidents,
                      ObjectMapper json) {
        this.kafka = kafka;
        this.incidents = incidents;
        this.json = json;
    }

    /** Publish one AgentTask per agent, keyed by incident id for partition ordering.
     *  Records the expected agent set on the incident so the aggregator can resolve
     *  dynamically instead of against a hardcoded count. */
    public void dispatch(Incident incident, List<String> agentNames) {
        // Record which agents we're dispatching — the aggregator reads this to
        // know when the incident is complete. Defensive copy prevents caller mutation.
        incident.setExpectedAgents(List.copyOf(agentNames));
        incidents.save(incident);

        for (String agent : agentNames) {
            AgentTask task = new AgentTask(
                    incident.getId(),
                    agent,
                    incident.getSource(),
                    null
            );
            kafka.send(KafkaTopicConfig.AGENT_TASKS,
                       incident.getId().toString(),
                       toJson(task));
        }
    }

    /** Dispatch the Synthesizer as a second-stage task after specialists complete.
     *  Does NOT touch expected_agents — the aggregator resolves on AGGREGATING state. */
    public void dispatchSynthesizer(Incident incident, Map<String, Object> payload) {
        AgentTask task = new AgentTask(
                incident.getId(),
                "synthesizer",
                incident.getSource(),
                payload
        );
        kafka.send(KafkaTopicConfig.AGENT_TASKS,
                   incident.getId().toString(),
                   toJson(task));
    }

    private String toJson(AgentTask task) {
        try { return json.writeValueAsString(task); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize AgentTask", e); }
    }
}
