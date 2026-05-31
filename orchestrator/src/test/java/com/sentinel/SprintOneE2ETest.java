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

        // 3. Simulate the Python worker — publish a canned AgentResult.
        AgentResult result = new AgentResult(
            id, "echo",
            Map.of("message", "Acknowledged incident for order-api."),
            42, 100, "ok", null
        );
        kafka.send(KafkaTopicConfig.AGENT_RESULTS,
                   id.toString(),
                   json.writeValueAsString(result)).get();

        // 4. Wait for the aggregator to close the loop.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(incidents.findById(id).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED)
        );

        // 5. Confirm artifacts exist.
        assertThat(traces.findByIncidentIdAndAgentName(id, "echo")).isPresent();
        assertThat(reports.count()).isEqualTo(1);
    }
}
