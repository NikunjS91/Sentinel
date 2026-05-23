package com.sentinel;

import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class SchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IncidentRepository incidentRepository;

    @Test
    void migration_applies_and_app_context_loads() {
        // If Spring context loaded, Flyway ran and Hibernate validated — that is the base assertion.
    }

    // TC-1.2.1: all five core tables exist after migration
    @Test
    void tc_1_2_1_all_five_core_tables_exist() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            String.class
        );
        assertThat(tables).contains(
            "incidents",
            "agent_traces",
            "incident_reports",
            "audit_log",
            "prompt_versions"
        );
    }

    // TC-1.2.2: idempotency_key unique constraint is enforced
    @Test
    void tc_1_2_2_idempotency_key_unique_constraint_enforced() {
        OffsetDateTime now = OffsetDateTime.now();

        Incident first = new Incident();
        first.setId(UUID.randomUUID());
        first.setIdempotencyKey("dup-key-001");
        first.setSource("test");
        first.setState("RECEIVED");
        first.setRawAlert("{}");
        first.setCreatedAt(now);
        first.setUpdatedAt(now);
        incidentRepository.saveAndFlush(first);

        Incident second = new Incident();
        second.setId(UUID.randomUUID());
        second.setIdempotencyKey("dup-key-001");
        second.setSource("test");
        second.setState("RECEIVED");
        second.setRawAlert("{}");
        second.setCreatedAt(now);
        second.setUpdatedAt(now);

        assertThatThrownBy(() -> incidentRepository.saveAndFlush(second))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
