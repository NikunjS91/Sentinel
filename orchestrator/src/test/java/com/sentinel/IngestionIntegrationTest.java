package com.sentinel;

import com.sentinel.incident.IncidentRepository;
import com.sentinel.ingest.AlertRequest;
import com.sentinel.ingest.IncidentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class IngestionIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired IncidentRepository incidentRepository;

    static final AlertRequest VALID_ALERT = new AlertRequest(
            "demo", "order-api", "HighErrorRate", "fp-test-001",
            "critical", Map.of("env", "test"), Map.of()
    );

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
    }

    // TC-1.4.1: valid alert → 201, one RECEIVED incident in DB
    @Test
    void tc_1_4_1_valid_alert_creates_received_incident() {
        ResponseEntity<IncidentDto> resp = http.postForEntity("/alerts", VALID_ALERT, IncidentDto.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().state()).isEqualTo("RECEIVED");

        long count = incidentRepository.count();
        assertThat(count).isEqualTo(1);
    }

    // TC-1.4.2: same alert posted twice → 200, same id, still 1 row
    @Test
    void tc_1_4_2_duplicate_alert_is_deduplicated() {
        ResponseEntity<IncidentDto> first = http.postForEntity("/alerts", VALID_ALERT, IncidentDto.class);
        ResponseEntity<IncidentDto> second = http.postForEntity("/alerts", VALID_ALERT, IncidentDto.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(incidentRepository.count()).isEqualTo(1);
    }

    // TC-1.4.3: invalid payload (missing required fields) → 400, no incident
    @Test
    void tc_1_4_3_invalid_alert_rejected_with_400() {
        AlertRequest bad = new AlertRequest("demo", null, null, null, null, null, null);
        ResponseEntity<String> resp = http.postForEntity("/alerts", bad, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("invalid_alert");
        assertThat(incidentRepository.count()).isEqualTo(0);
    }

    // TC-1.4.4: successful ingestion writes an INGESTED audit row
    @Test
    void tc_1_4_4_ingestion_writes_audit_row() {
        http.postForEntity("/alerts", VALID_ALERT, IncidentDto.class);

        List<String> events = jdbc.queryForList(
                "SELECT event_type FROM audit_log WHERE event_type = 'INGESTED'",
                String.class
        );
        assertThat(events).hasSize(1);
    }
}
