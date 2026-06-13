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
 * TC-3.1.6 — Sprint-3 four-trace e2e: POST alert → classify → dispatch →
 * three specialist AgentResults → AGGREGATING → Synthesizer dispatched →
 * Synthesizer AgentResult → RESOLVED, four traces, report from synthesizer,
 * expected_agents unchanged at three.
 */
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SprintThreeE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

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

    // TC-3.1.6: four-trace flow ending in synthesized report
    @Test
    void four_trace_flow_resolves_with_synthesizer_report() throws Exception {
        // 1. POST an alert.
        var alert = Map.of(
            "source", "demo", "service", "demo-app",
            "alertName", "S3E2ETest", "fingerprint", "s3-e2e-1",
            "severity", "critical",
            "labels", Map.of(), "annotations", Map.of());

        var resp = http.postForEntity("/alerts", alert, Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString((String) resp.getBody().get("id"));

        // 2. Wait for specialists to be dispatched and expected_agents set.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var inc = incidents.findById(id).orElseThrow();
            assertThat(inc.getState()).isEqualTo(IncidentState.DISPATCHED);
            assertThat(inc.getExpectedAgents())
                .containsExactlyInAnyOrder("echo", "log_analyzer", "metrics");
        });

        // 3. Simulate three specialists reporting back.
        for (String agent : List.of("metrics", "echo", "log_analyzer")) {
            AgentResult result = new AgentResult(
                id, agent,
                Map.of("message", agent + " s3 e2e result"),
                50, 200, "ok",
                agent.equals("echo") ? null : "abc123");
            kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                       id.toString(),
                       json.writeValueAsString(result)).get();
        }

        // 4. Wait for aggregator to reach AGGREGATING (all specialists done,
        //    Synthesizer dispatched).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING));

        // 5. expected_agents must NOT include "synthesizer".
        assertThat(incidents.findById(id).orElseThrow().getExpectedAgents())
            .containsExactlyInAnyOrder("echo", "log_analyzer", "metrics");

        // 6. Simulate the Synthesizer reporting back.
        AgentResult synthResult = new AgentResult(
            id, "synthesizer",
            Map.of(
                "summary", "Order-create latency exceeded SLO; likely slow DB query",
                "root_cause", "slow DB query",
                "recommended_action", "check slow query log; scale read replica",
                "confidence", 0.85,
                "dissenting_notes", List.of(
                    "log_analyzer low confidence; metrics agent high confidence with concrete p95 evidence"
                ),
                "contributing_agents", List.of("echo", "log_analyzer", "metrics")
            ),
            150, 600, "ok", "synth-v1");
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(synthResult)).get();

        // 7. Wait for full resolution.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED));

        // 8. Four traces: three specialists + synthesizer.
        assertThat(traces.findByIncidentIdAndAgentName(id, "echo")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "log_analyzer")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "metrics")).isPresent();
        assertThat(traces.findByIncidentIdAndAgentName(id, "synthesizer")).isPresent();

        // 9. Report comes from the Synthesizer, not a specialist.
        assertThat(reports.count()).isEqualTo(1);
        var report = reports.findAll().get(0);
        assertThat(report.getSummary()).contains("latency exceeded SLO");
        assertThat(report.getRootCause()).isEqualTo("slow DB query");
        assertThat(report.getRecommendedAction()).contains("slow query log");
        assertThat(report.getConfidence()).isNotNull();
        assertThat(report.getConfidence().doubleValue()).isGreaterThan(0.8);
    }
}
