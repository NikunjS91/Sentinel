package com.sentinel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentResult;
import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import com.sentinel.kb.KbDocument;
import com.sentinel.kb.KbDocumentRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
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
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SprintFourE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("pgvector/pgvector:pg16");

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

    @Autowired IncidentRepository incidents;
    @Autowired IncidentReportRepository reports;
    @Autowired KbDocumentRepository kbDocs;
    @Autowired AgentTraceRepository traces;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
    }

    // TC-4.5.1 — Sprint-4 6-agent e2e with KB write-back
    @Test
    void six_agent_pipeline_resolves_and_writes_back_to_kb() throws Exception {
        // 1. Count past_incidents BEFORE the test (assert a delta of exactly 1).
        long beforeCount = kbDocs.findAll().stream()
            .filter(d -> "past_incident".equals(d.getSourceType()))
            .count();

        // 2. POST an alert.
        Map<String, Object> alert = Map.of(
                "source", "demo", "service", "demo-app",
                "alertName", "S4E2ETest", "fingerprint", "s4-e2e-1",
                "severity", "critical",
                "labels", Map.of(), "annotations", Map.of());

        var resp = http.postForEntity("/alerts", alert, Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString((String) resp.getBody().get("id"));

        // 3. Wait until dispatched with all 6 specialists in expected_agents.
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Incident inc = incidents.findById(id).orElseThrow();
            assertThat(inc.getState()).isEqualTo(IncidentState.DISPATCHED);
            assertThat(inc.getExpectedAgents()).containsExactlyInAnyOrder(
                "echo", "log_analyzer", "metrics", "history", "topology", "runbook");
            assertThat(inc.getDeadlineAt()).isNotNull();
        });

        // 4. Simulate all 6 specialists reporting back.
        for (String agent : List.of(
                "echo", "log_analyzer", "metrics", "history", "topology", "runbook")) {
            AgentResult result = new AgentResult(
                    id, agent,
                    Map.of("message", agent + " stub finding", "confidence", 0.7),
                    50, 200, "ok",
                    agent.equals("echo") ? null : "abc123def456");
            kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                       id.toString(),
                       json.writeValueAsString(result)).get();
        }

        // 5. Wait for AGGREGATING (Synthesizer dispatched).
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING));

        // 6. Simulate the Synthesizer reporting.
        AgentResult synth = new AgentResult(
                id, "synthesizer",
                Map.of(
                    "summary", "stub synthesized summary",
                    "root_cause", "stub root cause",
                    "recommended_action", "stub action",
                    "confidence", 0.85,
                    "dissenting_notes", List.of(),
                    "contributing_agents", List.of(
                        "echo", "log_analyzer", "metrics",
                        "history", "topology", "runbook")
                ),
                100, 500, "ok", "def456abc789");
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(synth)).get();

        // 7. Wait for RESOLVED.
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED));

        // 8. Confirm KB write-back: exactly ONE new past_incident row.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long afterCount = kbDocs.findAll().stream()
                .filter(d -> "past_incident".equals(d.getSourceType()))
                .count();
            assertThat(afterCount).isEqualTo(beforeCount + 1);
        });

        // 9. Spot-check the report came from the Synthesizer.
        var report = reports.findByIncidentId(id).orElseThrow();
        assertThat(report.getSummary()).isEqualTo("stub synthesized summary");
    }

    // TC-4.5.2 — Knowledge feedback loop (skip-guarded: requires TOOL_MODE=fixture)
    @Test
    void knowledge_loop_finds_just_resolved_incident_on_repeat() throws Exception {
        // Requires deterministic fixture embeddings to make vector search repeatable.
        // Set TOOL_MODE=fixture in the environment to un-skip.
        Assumptions.assumeTrue(
            "fixture".equals(System.getenv("TOOL_MODE")),
            "this test requires TOOL_MODE=fixture for deterministic vector search");

        // 1. Resolve incident A (training incident).
        UUID a = resolveIncidentE2E("LearningA", "s4-loop-a");

        // 2. Wait for the KB write-back AND for its embedding to be populated.
        Awaitility.await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            long withEmbedding = kbDocs.findAll().stream()
                .filter(d -> "past_incident".equals(d.getSourceType()))
                .filter(this::embeddingPopulated)
                .count();
            assertThat(withEmbedding).isGreaterThanOrEqualTo(1);
        });

        // 3. Resolve a similar incident B.
        UUID b = resolveIncidentE2E("LearningB", "s4-loop-b");

        // 4. Inspect incident B's history-agent trace — it should find A.
        var historyTrace = traces.findByIncidentIdAndAgentName(b, "history")
            .orElseThrow();
        Map<String, Object> traceOutput = json.readValue(
            historyTrace.getOutput(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches =
            (List<Map<String, Object>>) traceOutput.get("matched_incidents");
        assertThat(matches).isNotEmpty();
    }

    // ---- helpers ----

    private UUID resolveIncidentE2E(String alertName, String fingerprint) throws Exception {
        Map<String, Object> alert = Map.of(
                "source", "demo", "service", "demo-app",
                "alertName", alertName, "fingerprint", fingerprint,
                "severity", "critical",
                "labels", Map.of(), "annotations", Map.of());

        var resp = http.postForEntity("/alerts", alert, Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString((String) resp.getBody().get("id"));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.DISPATCHED));

        for (String agent : List.of(
                "echo", "log_analyzer", "metrics", "history", "topology", "runbook")) {
            AgentResult result = new AgentResult(
                    id, agent,
                    Map.of("message", agent + " stub finding", "confidence", 0.7),
                    50, 200, "ok",
                    agent.equals("echo") ? null : "abc123def456");
            kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                       id.toString(),
                       json.writeValueAsString(result)).get();
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING));

        AgentResult synth = new AgentResult(
                id, "synthesizer",
                Map.of(
                    "summary", "memory_leak: high heap after deploy",
                    "root_cause", "memory_leak",
                    "recommended_action", "restart pod, check allocations",
                    "confidence", 0.85,
                    "dissenting_notes", List.of(),
                    "contributing_agents", List.of(
                        "echo", "log_analyzer", "metrics",
                        "history", "topology", "runbook")
                ),
                100, 500, "ok", "def456abc789");
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(synth)).get();

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED));

        return id;
    }

    private boolean embeddingPopulated(KbDocument d) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base.kb_documents WHERE id = ? AND embedding IS NOT NULL",
            Integer.class, d.getId());
        return count != null && count > 0;
    }
}
