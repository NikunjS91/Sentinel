package com.sentinel;

import com.sentinel.incident.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.sentinel.incident.IncidentState.CLASSIFIED;
import static com.sentinel.incident.IncidentState.RECEIVED;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class StateMachineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

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
    @Autowired IncidentStateMachine stateMachine;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("DELETE FROM audit_log");
        jdbc.execute("DELETE FROM incidents");
    }

    // TC-1.5.6: transition persists state and writes audit row in real DB
    @Test
    void tc_1_5_6_transition_persists_state_and_audit_row() throws InterruptedException {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("sm-test-" + UUID.randomUUID());
        inc.setSource("test");
        inc.setSeverity("critical");
        inc.setState(RECEIVED);
        inc.setRawAlert("{}");
        OffsetDateTime before = OffsetDateTime.now();
        inc.setCreatedAt(before);
        inc.setUpdatedAt(before);
        incidentRepository.save(inc);

        Thread.sleep(5); // ensure updated_at advances

        stateMachine.transition(inc, CLASSIFIED);

        Incident reloaded = incidentRepository.findById(inc.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(CLASSIFIED);
        assertThat(reloaded.getUpdatedAt()).isAfter(before);

        List<String> auditEvents = jdbc.queryForList(
                "SELECT event_type FROM audit_log WHERE event_type = 'STATE_RECEIVED_TO_CLASSIFIED'",
                String.class
        );
        assertThat(auditEvents).hasSize(1);
    }
}
