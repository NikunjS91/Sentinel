package com.sentinel.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String INCIDENTS_RAW         = "incidents.raw";
    public static final String AGENT_TASKS           = "agent.tasks";
    public static final String AGENT_RESULTS         = "agent.results";
    public static final String INCIDENTS_SYNTHESIZED = "incidents.synthesized";
    public static final String AUDIT_EVENTS          = "audit.events";
    public static final String AGENT_TASKS_DLQ       = "agent.tasks.dlq";

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    @Bean NewTopic incidentsRaw() {
        return TopicBuilder.name(INCIDENTS_RAW).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean NewTopic agentTasks() {
        return TopicBuilder.name(AGENT_TASKS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean NewTopic agentResults() {
        return TopicBuilder.name(AGENT_RESULTS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean NewTopic incidentsSynthesized() {
        return TopicBuilder.name(INCIDENTS_SYNTHESIZED).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean NewTopic auditEvents() {
        return TopicBuilder.name(AUDIT_EVENTS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    @Bean NewTopic agentTasksDlq() {
        return TopicBuilder.name(AGENT_TASKS_DLQ).partitions(PARTITIONS).replicas(REPLICAS).build();
    }
}
