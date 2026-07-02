package com.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.deadline.DeadlineSweeper;
import com.sentinel.dispatch.Dispatcher;
import com.sentinel.incident.AgentTrace;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = "sentinel.incident.sweeper-interval-ms=999999")
class DeadlineSweeperTest {

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
    @Autowired AgentTraceRepository traceRepository;
    @Autowired Dispatcher dispatcher;
    @Autowired DeadlineSweeper sweeper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
    }

    private Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-sweeper-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }

    private Incident buildClassifiedIncident() {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("sweeper-test-" + UUID.randomUUID());
        inc.setSource("order-api");
        inc.setSeverity("p1");
        inc.setState(IncidentState.CLASSIFIED);
        inc.setRawAlert("{}");
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        return incidentRepository.save(inc);
    }

    private Incident buildOverdueDispatchedIncident(List<String> expectedAgents) {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("sweeper-test-" + UUID.randomUUID());
        inc.setSource("order-api");
        inc.setSeverity("p1");
        inc.setState(IncidentState.DISPATCHED);
        inc.setRawAlert("{}");
        inc.setExpectedAgents(new ArrayList<>(expectedAgents));
        inc.setDeadlineAt(OffsetDateTime.now().minusSeconds(10));
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        return incidentRepository.save(inc);
    }

    private AgentTrace buildTrace(UUID incidentId, String agentName) {
        AgentTrace t = new AgentTrace();
        t.setId(UUID.randomUUID());
        t.setIncidentId(incidentId);
        t.setAgentName(agentName);
        t.setOutput("{\"message\":\"done\"}");
        t.setTokensUsed(50);
        t.setCostUsd(BigDecimal.ZERO);
        t.setLatencyMs(100);
        t.setStatus("ok");
        t.setCreatedAt(OffsetDateTime.now());
        return traceRepository.save(t);
    }

    // TC-3.2.1: Dispatcher.dispatch sets deadline_at on the incident
    @Test
    void tc_3_2_1_dispatch_sets_deadline_at() {
        Incident inc = buildClassifiedIncident();
        inc.setState(IncidentState.CLASSIFIED);
        incidentRepository.save(inc);

        dispatcher.dispatch(inc, List.of("echo", "log_analyzer", "metrics"));

        Incident updated = incidentRepository.findById(inc.getId()).orElseThrow();
        assertThat(updated.getDeadlineAt()).isNotNull();
        assertThat(updated.getDeadlineAt()).isAfter(OffsetDateTime.now().minusSeconds(5));
        assertThat(updated.getExpectedAgents()).containsExactlyInAnyOrder("echo", "log_analyzer", "metrics");
    }

    // TC-3.2.2: sweeper on overdue DISPATCHED incident with no traces → AGGREGATING_PARTIAL + Synthesizer task
    @Test
    void tc_3_2_2_sweeper_progresses_overdue_no_traces() throws Exception {
        Incident inc = buildOverdueDispatchedIncident(List.of("echo", "log_analyzer", "metrics"));

        List<TopicPartition> taskPartitions = List.of(
            new TopicPartition("agent.tasks", 0),
            new TopicPartition("agent.tasks", 1),
            new TopicPartition("agent.tasks", 2)
        );
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.assign(taskPartitions);
            var endOffsets = consumer.endOffsets(taskPartitions);
            for (var tp : taskPartitions) {
                consumer.seek(tp, endOffsets.get(tp));
            }

            sweeper.sweep();

            Incident updated = incidentRepository.findById(inc.getId()).orElseThrow();
            assertThat(updated.getState()).isEqualTo(IncidentState.AGGREGATING_PARTIAL);

            var records = consumer.poll(Duration.ofSeconds(5));
            var allRecords = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            records.records("agent.tasks").forEach(allRecords::add);
            var synthTask = allRecords.stream()
                .filter(r -> {
                    try {
                        return "synthesizer".equals(objectMapper.readTree(r.value()).path("agentName").asText());
                    } catch (Exception e) { return false; }
                })
                .findFirst();

            assertThat(synthTask).isPresent();
        }
    }

    // TC-3.2.3: sweeper with 1 of 3 specialists reported → missing_specialists has 2 names + partial:true
    @Test
    void tc_3_2_3_sweeper_computes_missing_specialists() throws Exception {
        Incident inc = buildOverdueDispatchedIncident(List.of("echo", "log_analyzer", "metrics"));
        buildTrace(inc.getId(), "echo");

        List<TopicPartition> taskPartitions = List.of(
            new TopicPartition("agent.tasks", 0),
            new TopicPartition("agent.tasks", 1),
            new TopicPartition("agent.tasks", 2)
        );
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.assign(taskPartitions);
            var endOffsets = consumer.endOffsets(taskPartitions);
            for (var tp : taskPartitions) {
                consumer.seek(tp, endOffsets.get(tp));
            }

            sweeper.sweep();

            var records = consumer.poll(Duration.ofSeconds(5));
            var allRecords = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            records.records("agent.tasks").forEach(allRecords::add);
            var synthTask = allRecords.stream()
                .filter(r -> {
                    try {
                        return "synthesizer".equals(objectMapper.readTree(r.value()).path("agentName").asText());
                    } catch (Exception e) { return false; }
                })
                .findFirst();

            assertThat(synthTask).isPresent();
            var taskNode = objectMapper.readTree(synthTask.get().value());
            var payload = taskNode.path("payload");
            assertThat(payload.path("partial").asBoolean()).isTrue();
            var missing = payload.path("missing_specialists");
            assertThat(missing.isArray()).isTrue();
            assertThat(missing.size()).isEqualTo(2);
            var missingNames = new ArrayList<String>();
            missing.forEach(n -> missingNames.add(n.asText()));
            assertThat(missingNames).containsExactlyInAnyOrder("log_analyzer", "metrics");
        }
    }

    // TC-3.2.9: overdue DISPATCHED incident progresses to AGGREGATING_PARTIAL when sweep() is called
    @Test
    void tc_3_2_9_overdue_incident_reaches_aggregating_partial() {
        Incident inc = buildOverdueDispatchedIncident(List.of("echo", "log_analyzer", "metrics"));

        sweeper.sweep();

        Incident updated = incidentRepository.findById(inc.getId()).orElseThrow();
        assertThat(updated.getState()).isEqualTo(IncidentState.AGGREGATING_PARTIAL);
    }

    // TC-5.1.1: deadline breach counter increments when sweep() transitions an overdue incident
    @Test
    void tc_5_1_1_deadline_breach_counter_increments() {
        buildOverdueDispatchedIncident(List.of("echo", "log_analyzer", "metrics"));

        double before = meterRegistry.counter("sentinel_deadline_breaches_total").count();
        sweeper.sweep();
        double after = meterRegistry.counter("sentinel_deadline_breaches_total").count();

        assertThat(after).isEqualTo(before + 1);
    }
}
