package com.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentResult;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.incident.Incident;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * TC-4.2.6 — KbWriter inserts a past_incident row when an incident reaches RESOLVED.
 * TC-4.2.7 — KbWriter does NOT insert when an incident reaches PARTIAL.
 */
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class KbWriterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired TestRestTemplate http;
    @Autowired IncidentRepository incidents;
    @Autowired IncidentReportRepository reports;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
        jdbc.execute("DELETE FROM knowledge_base.kb_documents WHERE source_type = 'past_incident' AND metadata->>'incident_id' IS NOT NULL");
    }

    // TC-4.2.6: KbWriter inserts a past_incident row on RESOLVED
    @Test
    void kb_writer_inserts_past_incident_on_resolved() throws Exception {
        var alert = Map.of(
            "source", "demo", "service", "demo-app",
            "alertName", "KbWriterTest", "fingerprint", "kbw-e2e-1",
            "severity", "critical",
            "labels", Map.of(), "annotations", Map.of());

        var resp = http.postForEntity("/alerts", alert, Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString((String) resp.getBody().get("id"));

        // Wait for DISPATCHED
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.DISPATCHED));

        // Send six specialist results
        for (String agent : List.of("echo", "log_analyzer", "metrics", "history", "topology", "runbook")) {
            AgentResult result = new AgentResult(
                id, agent,
                Map.of("message", agent + " kbwriter test"),
                50, 200, "ok",
                agent.equals("echo") ? null : "abc123");
            kafkaTemplate.send(KafkaTopicConfig.AGENT_RESULTS,
                               id.toString(),
                               json.writeValueAsString(result)).get();
        }

        // Wait for AGGREGATING
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING));

        // Send synthesizer result
        AgentResult synth = new AgentResult(
            id, "synthesizer",
            Map.of(
                "summary", "KbWriter test synthesized report",
                "root_cause", "test root cause",
                "recommended_action", "test action",
                "confidence", 0.9,
                "dissenting_notes", List.of(),
                "contributing_agents", List.of("echo", "log_analyzer", "metrics")
            ),
            100, 500, "ok", "synth-v1");
        kafkaTemplate.send(KafkaTopicConfig.AGENT_RESULTS,
                           id.toString(),
                           json.writeValueAsString(synth)).get();

        // Wait for RESOLVED
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED));

        // KbWriter must have inserted a past_incident row for this incident
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base.kb_documents WHERE source_type = 'past_incident' AND metadata->>'incident_id' = ?",
                Integer.class, id.toString());
            assertThat(count).isEqualTo(1);
        });

        // Verify metadata fields
        var row = jdbc.queryForMap(
            "SELECT title, body, metadata FROM knowledge_base.kb_documents WHERE source_type = 'past_incident' AND metadata->>'incident_id' = ?",
            id.toString());
        assertThat(row.get("title").toString()).contains("KbWriter test");
        assertThat(row.get("body").toString()).contains("test root cause");
    }

    // TC-4.2.7: KbWriter does NOT insert a past_incident row when incident reaches PARTIAL
    @Test
    void kb_writer_does_not_insert_on_partial() throws Exception {
        int seedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base.kb_documents WHERE source_type = 'past_incident'",
            Integer.class);

        // Build an overdue incident directly (bypasses classifier)
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey(UUID.randomUUID().toString());
        inc.setSource("demo");
        inc.setSeverity("critical");
        inc.setState(IncidentState.DISPATCHED);
        inc.setExpectedAgents(List.of("echo", "log_analyzer", "metrics", "history", "topology", "runbook"));
        inc.setDeadlineAt(OffsetDateTime.now().minusSeconds(10));
        inc.setRawAlert("{}");
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        incidents.save(inc);
        UUID id = inc.getId();

        // Only one specialist reports; the other three never do
        AgentResult echoResult = new AgentResult(
            id, "echo",
            Map.of("message", "echo only partial"),
            30, 100, "ok", null);
        kafkaTemplate.send(KafkaTopicConfig.AGENT_RESULTS, id.toString(),
                           json.writeValueAsString(echoResult)).get();

        // Sweeper fires and advances to AGGREGATING_PARTIAL (deadline already past)
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING_PARTIAL));

        // Synthesizer reports partial findings
        AgentResult synth = new AgentResult(
            id, "synthesizer",
            Map.of(
                "summary", "Partial: limited specialist input",
                "root_cause", "",
                "recommended_action", "Manual review required",
                "confidence", 0.3,
                "dissenting_notes", List.of("missing: log_analyzer", "missing: metrics", "missing: history"),
                "contributing_agents", List.of("echo")
            ),
            100, 500, "ok", "synth-partial-v1");
        kafkaTemplate.send(KafkaTopicConfig.AGENT_RESULTS, id.toString(),
                           json.writeValueAsString(synth)).get();

        // Wait for PARTIAL
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.PARTIAL));

        // Give a moment for any async side-effects
        Thread.sleep(2000);

        // KB must NOT have a new row for this incident
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base.kb_documents WHERE source_type = 'past_incident' AND metadata->>'incident_id' = ?",
            Integer.class, id.toString());
        assertThat(count).isZero();

        // Total past_incident count must still be the seed count
        Integer total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base.kb_documents WHERE source_type = 'past_incident'",
            Integer.class);
        assertThat(total).isEqualTo(seedCount);
    }
}
