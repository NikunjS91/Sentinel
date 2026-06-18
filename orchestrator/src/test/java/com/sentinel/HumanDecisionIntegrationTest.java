package com.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentReport;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = "sentinel.incident.sweeper-interval-ms=999999")
class HumanDecisionIntegrationTest {

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

    @Autowired IncidentRepository incidentRepository;
    @Autowired IncidentReportRepository reportRepository;
    @Autowired AgentTraceRepository traceRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
    }

    private Incident buildResolvedIncidentWithReport(String summary, String rootCause, String action) {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("hil-test-" + UUID.randomUUID());
        inc.setSource("order-api");
        inc.setSeverity("p1");
        inc.setState(IncidentState.RESOLVED);
        inc.setRawAlert("{}");
        inc.setExpectedAgents(List.of("echo"));
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        inc = incidentRepository.save(inc);

        IncidentReport report = new IncidentReport();
        report.setId(UUID.randomUUID());
        report.setIncidentId(inc.getId());
        report.setSummary(summary);
        report.setRootCause(rootCause);
        report.setRecommendedAction(action);
        report.setConfidence(BigDecimal.valueOf(0.85));
        report.setCreatedAt(OffsetDateTime.now());
        reportRepository.save(report);

        return inc;
    }

    // TC-3.4.1: POST /incidents/{id}/accept → 204, human_decision=ACCEPTED, human_decided_at set
    @Test
    void tc_3_4_1_accept_sets_decision() {
        Incident inc = buildResolvedIncidentWithReport("Summary", "Root", "Action");

        ResponseEntity<Void> resp = http.postForEntity(
            "/incidents/" + inc.getId() + "/accept", null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        IncidentReport report = reportRepository.findByIncidentId(inc.getId()).orElseThrow();
        assertThat(report.getHumanDecision()).isEqualTo("ACCEPTED");
        assertThat(report.getHumanDecidedAt()).isNotNull();
    }

    // TC-3.4.2: POST /incidents/{id}/reject → 204, human_decision=REJECTED, reason stored
    @Test
    void tc_3_4_2_reject_sets_decision_and_reason() throws Exception {
        Incident inc = buildResolvedIncidentWithReport("Summary", "Root", "Action");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = objectMapper.writeValueAsString(Map.of("reason", "Looks wrong"));
        HttpEntity<String> req = new HttpEntity<>(body, headers);

        ResponseEntity<Void> resp = http.postForEntity(
            "/incidents/" + inc.getId() + "/reject", req, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        IncidentReport report = reportRepository.findByIncidentId(inc.getId()).orElseThrow();
        assertThat(report.getHumanDecision()).isEqualTo("REJECTED");
        assertThat(report.getHumanDecisionReason()).isEqualTo("Looks wrong");
        assertThat(report.getHumanDecidedAt()).isNotNull();
    }

    // TC-3.4.3: PATCH /incidents/{id}/report → 204, edited_* columns set, AI originals unchanged
    @Test
    void tc_3_4_3_edit_sets_edited_columns() throws Exception {
        Incident inc = buildResolvedIncidentWithReport("AI Summary", "AI Root", "AI Action");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = objectMapper.writeValueAsString(Map.of(
            "summary", "Human Summary",
            "rootCause", "Human Root",
            "recommendedAction", "Human Action"
        ));
        HttpEntity<String> req = new HttpEntity<>(body, headers);

        ResponseEntity<Void> resp = http.exchange(
            "/incidents/" + inc.getId() + "/report", HttpMethod.PATCH, req, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        IncidentReport report = reportRepository.findByIncidentId(inc.getId()).orElseThrow();
        assertThat(report.getHumanDecision()).isEqualTo("EDITED");
        assertThat(report.getEditedSummary()).isEqualTo("Human Summary");
        assertThat(report.getEditedRootCause()).isEqualTo("Human Root");
        assertThat(report.getEditedRecommendedAction()).isEqualTo("Human Action");
    }

    // TC-3.4.4: double accept → 409 CONFLICT
    @Test
    void tc_3_4_4_double_accept_returns_conflict() {
        Incident inc = buildResolvedIncidentWithReport("Summary", "Root", "Action");

        http.postForEntity("/incidents/" + inc.getId() + "/accept", null, Void.class);
        ResponseEntity<Void> second = http.postForEntity(
            "/incidents/" + inc.getId() + "/accept", null, Void.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // TC-3.4.5: accept then reject → 409 CONFLICT
    @Test
    void tc_3_4_5_accept_then_reject_returns_conflict() throws Exception {
        Incident inc = buildResolvedIncidentWithReport("Summary", "Root", "Action");

        http.postForEntity("/incidents/" + inc.getId() + "/accept", null, Void.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> req = new HttpEntity<>(
            objectMapper.writeValueAsString(Map.of("reason", "Changed mind")), headers);
        ResponseEntity<Void> resp = http.postForEntity(
            "/incidents/" + inc.getId() + "/reject", req, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // TC-3.4.6: accept on incident with no report → 404
    @Test
    void tc_3_4_6_accept_no_report_returns_not_found() {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("hil-noreport-" + UUID.randomUUID());
        inc.setSource("svc");
        inc.setSeverity("p2");
        inc.setState(IncidentState.DISPATCHED);
        inc.setRawAlert("{}");
        inc.setExpectedAgents(List.of());
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        inc = incidentRepository.save(inc);

        ResponseEntity<Void> resp = http.postForEntity(
            "/incidents/" + inc.getId() + "/accept", null, Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // TC-3.4.7: edit preserves AI original fields; edited_* columns hold human values
    @Test
    void tc_3_4_7_edit_preserves_ai_originals_as_labeled_training_data() throws Exception {
        Incident inc = buildResolvedIncidentWithReport("AI Summary", "AI Root Cause", "AI Action");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = objectMapper.writeValueAsString(Map.of(
            "summary", "Corrected Summary",
            "rootCause", "Corrected Root",
            "recommendedAction", "Corrected Action"
        ));
        http.exchange("/incidents/" + inc.getId() + "/report",
            HttpMethod.PATCH, new HttpEntity<>(body, headers), Void.class);

        IncidentReport report = reportRepository.findByIncidentId(inc.getId()).orElseThrow();

        // AI originals preserved (labeled training data)
        assertThat(report.getSummary()).isEqualTo("AI Summary");
        assertThat(report.getRootCause()).isEqualTo("AI Root Cause");
        assertThat(report.getRecommendedAction()).isEqualTo("AI Action");

        // Human edits in edited_* columns
        assertThat(report.getEditedSummary()).isEqualTo("Corrected Summary");
        assertThat(report.getEditedRootCause()).isEqualTo("Corrected Root");
        assertThat(report.getEditedRecommendedAction()).isEqualTo("Corrected Action");
    }
}
