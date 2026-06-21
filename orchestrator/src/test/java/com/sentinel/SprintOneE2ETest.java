package com.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentResult;
import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import com.sentinel.ingest.IncidentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * TC-1.10.1 — full Sprint-1 pipeline: POST alert → classify → dispatch →
 * canned AgentResult → aggregator → RESOLVED, one trace, one report.
 */
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SprintOneE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    static KafkaContainer kafkaContainer =
        new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired TestRestTemplate http;
    @Autowired IncidentRepository incidents;
    @Autowired AgentTraceRepository traces;
    @Autowired IncidentReportRepository reports;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
    }

    // TC-1.10.1: full pipeline — alert in, incident RESOLVED out
    @Test
    void alert_flows_all_the_way_to_resolved() throws Exception {
        // 1. POST an alert.
        var alert = Map.of(
            "source", "demo",
            "service", "order-api",
            "alertName", "HighErrorRate",
            "fingerprint", "e2e-fp-001",
            "severity", "critical",
            "labels", Map.of("env", "prod"),
            "annotations", Map.of()
        );
        var resp = http.postForEntity("/alerts", alert, IncidentDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = resp.getBody().id();

        // 2. Wait for the classifier to advance to DISPATCHED.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.DISPATCHED)
        );

        // 3. Simulate the Python worker — publish canned AgentResults for all five agents.
        // Sprint 4 Day 29: dispatcher sends echo + log_analyzer + metrics + history + topology.
        AgentResult echoResult = new AgentResult(
            id, "echo",
            Map.of("message", "Acknowledged incident for order-api."),
            42, 100, "ok", null
        );
        AgentResult logResult = new AgentResult(
            id, "log_analyzer",
            Map.of("error_patterns", java.util.List.of(), "confidence", 0.1),
            10, 50, "ok", "abc123def456"
        );
        AgentResult metricsResult = new AgentResult(
            id, "metrics",
            Map.of("slo_status", "ok", "anomalies", java.util.List.of(), "confidence", 0.1),
            10, 50, "ok", "def456abc123"
        );
        AgentResult historyResult = new AgentResult(
            id, "history",
            Map.of("matched_incidents", java.util.List.of(), "confidence", 0.0),
            10, 50, "ok", "hist-v1"
        );
        AgentResult topologyResult = new AgentResult(
            id, "topology",
            Map.of("neighbors", java.util.List.of(), "likely_implicated", java.util.List.of(), "confidence", 0.0),
            10, 50, "ok", "topo-v1"
        );
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(echoResult)).get();
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(logResult)).get();
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(metricsResult)).get();
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(historyResult)).get();
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(topologyResult)).get();

        // 4. Wait for AGGREGATING (all specialists done; Synthesizer dispatched).
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING)
        );

        // 5. Simulate the Synthesizer result.
        AgentResult synthResult = new AgentResult(
            id, "synthesizer",
            Map.of(
                "summary", "stub synthesized report",
                "root_cause", "unknown",
                "recommended_action", "investigate",
                "confidence", 0.5,
                "dissenting_notes", java.util.List.of(),
                "contributing_agents", java.util.List.of("echo", "log_analyzer", "metrics")
            ),
            100, 300, "ok", "synth-v1"
        );
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(synthResult)).get();

        // 6. Wait for full resolution.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED)
        );

        // 7. Six traces (5 specialists + synthesizer), one report from synthesizer.
        assertThat(traces.findByIncidentIdAndAgentName(id, "echo")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "log_analyzer")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "metrics")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "history")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "topology")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "synthesizer")).isPresent();
        assertThat(reports.count()).isEqualTo(1);
    }
}
