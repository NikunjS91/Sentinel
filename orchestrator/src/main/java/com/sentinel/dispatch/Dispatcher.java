package com.sentinel.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.incident.Incident;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Dispatcher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public Dispatcher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /** Publish one AgentTask per agent, keyed by incident id for partition ordering. */
    public void dispatch(Incident incident, List<String> agentNames) {
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

    private String toJson(AgentTask task) {
        try { return json.writeValueAsString(task); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize AgentTask", e); }
    }
}
