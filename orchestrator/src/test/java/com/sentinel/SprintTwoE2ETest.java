package com.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentResult;
import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * TC-2.11.1 — Sprint-2 three-agent e2e: POST alert → classify → dispatch →
 * three canned AgentResults (non-canonical order) → aggregator → RESOLVED,
 * expected_agents set, three traces, one report.
 */
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SprintTwoE2ETest {

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

    // TC-2.11.1: three-agent flow resolves with expected_agents set
    @Test
    void three_agent_flow_resolves_with_expected_agents_set() throws Exception {
        // 1. POST an alert.
        var alert = Map.of(
            "source", "demo", "service", "demo-app",
            "alertName", "S2E2ETest", "fingerprint", "s2-e2e-1",
            "severity", "critical",
            "labels", Map.of(), "annotations", Map.of());

        var resp = http.postForEntity("/alerts", alert, Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString((String) resp.getBody().get("id"));

        // 2. Wait for the orchestrator to classify, dispatch, and persist expected_agents.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var inc = incidents.findById(id).orElseThrow();
            assertThat(inc.getState()).isEqualTo(IncidentState.DISPATCHED);
            assertThat(inc.getExpectedAgents())
                .containsExactlyInAnyOrder("echo", "log_analyzer", "metrics", "history", "topology");
        });

        // 3. Simulate five specialists reporting back, in non-canonical order.
        //    Deliberate: aggregator must be order-independent.
        for (String agent : List.of("metrics", "echo", "log_analyzer", "history", "topology")) {
            AgentResult result = new AgentResult(
                id, agent,
                Map.of("message", agent + " stub e2e result"),
                50, 200, "ok",
                agent.equals("echo") ? null : "abc123def456");
            kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                       id.toString(),
                       json.writeValueAsString(result)).get();
        }

        // 4. Wait for the aggregator to transition to AGGREGATING (all specialists done).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING));

        // 5. Simulate the Synthesizer reporting back.
        AgentResult synthResult = new AgentResult(
            id, "synthesizer",
            Map.of(
                "summary", "Stub synthesized report",
                "root_cause", "stub",
                "recommended_action", "stub",
                "confidence", 0.8,
                "dissenting_notes", List.of(),
                "contributing_agents", List.of("echo", "log_analyzer", "metrics")
            ),
            100, 500, "ok", "synth-v1");
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(synthResult)).get();

        // 6. Wait for the aggregator to close the loop.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED));

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
