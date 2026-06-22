package com.sentinel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.dispatch.Dispatcher;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import com.sentinel.ingest.AlertRequest;
import com.sentinel.ingest.IncidentDto;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.Properties;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class ClassifierDispatchIntegrationTest {

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
    @Autowired IncidentRepository incidentRepository;
    @Autowired Dispatcher dispatcher;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    static final AlertRequest CRITICAL_ALERT = new AlertRequest(
            "demo", "order-api", "HighErrorRate", "fp-d6-001",
            "critical", Map.of("env", "test"), Map.of()
    );

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
    }

    private Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }

    // TC-1.6.2: dispatcher publishes an AgentTask to agent.tasks
    @Test
    void tc_1_6_2_dispatch_publishes_agent_task() throws Exception {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("dispatch-test-" + UUID.randomUUID());
        inc.setSource("order-api");
        inc.setSeverity("p1");
        inc.setState(IncidentState.DISPATCHED);
        inc.setRawAlert("{}");
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        incidentRepository.save(inc);

        List<TopicPartition> partitions = List.of(
            new TopicPartition("agent.tasks", 0),
            new TopicPartition("agent.tasks", 1),
            new TopicPartition("agent.tasks", 2)
        );
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.assign(partitions);
            var endOffsets = consumer.endOffsets(partitions);
            for (var tp : partitions) {
                consumer.seek(tp, endOffsets.get(tp));
            }

            dispatcher.dispatch(inc, List.of("echo"));

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));

            assertThat(records.count()).isEqualTo(1);
            var record = records.iterator().next();
            assertThat(record.key()).isEqualTo(inc.getId().toString());

            JsonNode task = objectMapper.readTree(record.value());
            assertThat(task.get("agentName").asText()).isEqualTo("echo");
            assertThat(task.get("incidentId").asText()).isEqualTo(inc.getId().toString());
        }
    }

    // TC-2.8.6 / TC-4.2.8: classifier dispatches four tasks per incident (echo + log_analyzer + metrics + history)
    @Test
    void tc_2_8_6_three_tasks_dispatched_per_incident() throws Exception {
        List<TopicPartition> partitions = List.of(
            new TopicPartition("agent.tasks", 0),
            new TopicPartition("agent.tasks", 1),
            new TopicPartition("agent.tasks", 2)
        );
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.assign(partitions);
            var endOffsets = consumer.endOffsets(partitions);
            for (var tp : partitions) {
                consumer.seek(tp, endOffsets.get(tp));
            }

            // POST an alert — ClassifierListener drives it to DISPATCHED and dispatches all three agents.
            var resp = http.postForEntity("/alerts", CRITICAL_ALERT, Object.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(201);

            // Await three messages on agent.tasks.
            await().atMost(10, SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(3));
                // Accumulate across multiple polls
                var agentNames = new java.util.HashSet<String>();
                records.forEach(r -> {
                    try {
                        agentNames.add(objectMapper.readTree(r.value()).get("agentName").asText());
                    } catch (Exception ignored) {}
                });
                assertThat(agentNames).contains("echo", "log_analyzer", "metrics", "history", "topology");
            });
        }
    }

    // TC-2.9.7: three tasks dispatched per incident, all keyed by incident id
    @Test
    void tc_2_9_7_three_tasks_dispatched_keyed_by_incident_id() throws Exception {
        List<TopicPartition> partitions = List.of(
            new TopicPartition("agent.tasks", 0),
            new TopicPartition("agent.tasks", 1),
            new TopicPartition("agent.tasks", 2)
        );
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.assign(partitions);
            var endOffsets = consumer.endOffsets(partitions);
            for (var tp : partitions) {
                consumer.seek(tp, endOffsets.get(tp));
            }

            ResponseEntity<IncidentDto> resp = http.postForEntity("/alerts", CRITICAL_ALERT, IncidentDto.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
            UUID incidentId = resp.getBody().id();

            var agentNames = new java.util.HashSet<String>();
            var recordedKeys = new java.util.HashSet<String>();

            await().atMost(10, SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(3));
                records.forEach(r -> {
                    try {
                        agentNames.add(objectMapper.readTree(r.value()).get("agentName").asText());
                        recordedKeys.add(r.key());
                    } catch (Exception ignored) {}
                });
                assertThat(agentNames).containsExactlyInAnyOrder("echo", "log_analyzer", "metrics", "history", "topology");
            });

            // All five tasks must be keyed by the same incident id
            assertThat(recordedKeys).containsExactly(incidentId.toString());
        }
    }

    // TC-1.6.3: end-to-end — POST alert → listener classifies → state reaches DISPATCHED
    @Test
    void tc_1_6_3_state_progresses_to_dispatched_after_ingestion() {
        ResponseEntity<IncidentDto> resp = http.postForEntity("/alerts", CRITICAL_ALERT, IncidentDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID incidentId = resp.getBody().id();

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Incident inc = incidentRepository.findById(incidentId).orElseThrow();
            assertThat(inc.getState()).isEqualTo(IncidentState.DISPATCHED);
            assertThat(inc.getSeverity()).isEqualTo("p1");
        });
    }

    // TC-1.6.4: redelivery of incidents.raw message is a no-op (idempotency guard)
    @Test
    void tc_1_6_4_redelivered_raw_incident_is_noop() throws Exception {
        // Drive incident to DISPATCHED via POST
        ResponseEntity<IncidentDto> resp = http.postForEntity("/alerts", CRITICAL_ALERT, IncidentDto.class);
        UUID incidentId = resp.getBody().id();

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(incidentId).orElseThrow().getState())
                .isEqualTo(IncidentState.DISPATCHED)
        );

        // Simulate at-least-once redelivery
        kafkaTemplate.send("incidents.raw", incidentId.toString(), incidentId.toString()).get();

        // Wait for the listener to have a chance to process the redelivery
        Thread.sleep(3000);

        // State must still be DISPATCHED — idempotency guard prevented re-processing
        assertThat(incidentRepository.findById(incidentId).orElseThrow().getState())
            .isEqualTo(IncidentState.DISPATCHED);

        // Audit log must have exactly 2 STATE_ rows — no duplicate transitions
        int stateRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE incident_id = ? AND event_type LIKE 'STATE_%'",
            Integer.class, incidentId
        );
        assertThat(stateRows).isEqualTo(2);
    }

    // TC-2.10.1: dispatcher records expected_agents on the incident row
    @Test
    void tc_2_10_1_dispatcher_records_expected_agents() {
        ResponseEntity<IncidentDto> resp = http.postForEntity("/alerts", CRITICAL_ALERT, IncidentDto.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        UUID incidentId = resp.getBody().id();

        await().atMost(10, SECONDS).untilAsserted(() -> {
            Incident inc = incidentRepository.findById(incidentId).orElseThrow();
            assertThat(inc.getState()).isEqualTo(IncidentState.DISPATCHED);
            assertThat(inc.getExpectedAgents())
                .containsExactlyInAnyOrder("echo", "log_analyzer", "metrics", "history", "topology");
        });
    }
}
