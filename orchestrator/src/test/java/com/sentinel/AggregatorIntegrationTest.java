package com.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.aggregate.AgentTraceRepository;
import com.sentinel.aggregate.IncidentReportRepository;
import com.sentinel.incident.AgentTrace;
import com.sentinel.incident.Incident;
import com.sentinel.incident.IncidentRepository;
import com.sentinel.incident.IncidentState;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class AggregatorIntegrationTest {

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
    @Autowired AgentTraceRepository traceRepository;
    @Autowired IncidentReportRepository reportRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

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

    private Incident buildDispatchedIncident() {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("agg-test-" + UUID.randomUUID());
        inc.setSource("order-api");
        inc.setSeverity("p1");
        inc.setState(IncidentState.DISPATCHED);
        inc.setRawAlert("{}");
        inc.setExpectedAgents(List.of("echo", "log_analyzer", "metrics"));
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        return incidentRepository.save(inc);
    }

    private String resultJson(UUID incidentId, String agentName, String status) throws Exception {
        Map<String, Object> payload = Map.of(
            "incidentId", incidentId.toString(),
            "agentName", agentName,
            "output", Map.of("message", "Acknowledged incident for order-api."),
            "tokensUsed", 42,
            "latencyMs", 150,
            "status", status
        );
        return objectMapper.writeValueAsString(payload);
    }

    // Convenience: send all three expected agent results (echo + log_analyzer + metrics) to trigger resolution.
    private void sendThreeResults(UUID incidentId, String status) throws Exception {
        kafkaTemplate.send("agent.results", incidentId.toString(), resultJson(incidentId, "echo", status)).get();
        kafkaTemplate.send("agent.results", incidentId.toString(), resultJson(incidentId, "log_analyzer", status)).get();
        kafkaTemplate.send("agent.results", incidentId.toString(), resultJson(incidentId, "metrics", status)).get();
    }

    // TC-1.9.1: result creates a trace row with correct fields
    @Test
    void tc_1_9_1_result_creates_trace() throws Exception {
        Incident inc = buildDispatchedIncident();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "ok")).get();

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var trace = traceRepository.findByIncidentIdAndAgentName(inc.getId(), "echo");
            assertThat(trace).isPresent();
            assertThat(trace.get().getTokensUsed()).isEqualTo(42);
            assertThat(trace.get().getLatencyMs()).isEqualTo(150);
            assertThat(trace.get().getStatus()).isEqualTo("ok");
        });
    }

    // TC-1.9.2: incident resolves after both agent results arrive, report written, incidents.synthesized published
    @Test
    void tc_1_9_2_incident_resolves() throws Exception {
        Incident inc = buildDispatchedIncident();

        List<TopicPartition> synthPartitions = List.of(
            new TopicPartition("incidents.synthesized", 0),
            new TopicPartition("incidents.synthesized", 1),
            new TopicPartition("incidents.synthesized", 2)
        );
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.assign(synthPartitions);
            var endOffsets = consumer.endOffsets(synthPartitions);
            for (var tp : synthPartitions) {
                consumer.seek(tp, endOffsets.get(tp));
            }

            // Sprint 2: need both echo and log_analyzer results to resolve.
            sendThreeResults(inc.getId(), "ok");

            await().atMost(15, SECONDS).untilAsserted(() -> {
                Incident updated = incidentRepository.findById(inc.getId()).orElseThrow();
                assertThat(updated.getState()).isEqualTo(IncidentState.RESOLVED);
                assertThat(reportRepository.count()).isEqualTo(1);
            });

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            assertThat(records.iterator().next().key()).isEqualTo(inc.getId().toString());
        }
    }

    // TC-1.9.3: duplicate result is a no-op — no second trace, no second state change
    @Test
    void tc_1_9_3_duplicate_result_is_noop() throws Exception {
        Incident inc = buildDispatchedIncident();
        String echoPayload = resultJson(inc.getId(), "echo", "ok");

        // Send both to reach RESOLVED
        sendThreeResults(inc.getId(), "ok");

        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED)
        );

        // Send the echo result again — should be a no-op (idempotency on (incident, agent))
        kafkaTemplate.send("agent.results", inc.getId().toString(), echoPayload).get();
        Thread.sleep(3000);

        assertThat(traceRepository.findAll()).hasSize(3);
        assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
            .isEqualTo(IncidentState.RESOLVED);
    }

    // TC-1.9.4: error-status result still records trace; incident resolves once all three results arrive
    @Test
    void tc_1_9_4_error_status_still_records_and_resolves() throws Exception {
        Incident inc = buildDispatchedIncident();

        // Send echo with error status, then log_analyzer + metrics with ok — all three needed to resolve
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "error")).get();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "log_analyzer", "ok")).get();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "metrics", "ok")).get();

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var trace = traceRepository.findByIncidentIdAndAgentName(inc.getId(), "echo");
            assertThat(trace).isPresent();
            assertThat(trace.get().getStatus()).isEqualTo("error");
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED);
        });
    }

    // TC-1.9.5: result for an unknown incident is silently dropped
    @Test
    void tc_1_9_5_unknown_incident_is_dropped() throws Exception {
        UUID unknownId = UUID.randomUUID();
        kafkaTemplate.send("agent.results", unknownId.toString(), resultJson(unknownId, "echo", "ok")).get();

        Thread.sleep(3000);

        assertThat(traceRepository.count()).isZero();
        assertThat(reportRepository.count()).isZero();
    }

    // TC-2.8.7: incident stays DISPATCHED after only one result arrives
    @Test
    void tc_2_8_7_incident_stays_dispatched_after_one_result() throws Exception {
        Incident inc = buildDispatchedIncident();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "ok")).get();

        // Wait for trace to be recorded
        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(traceRepository.findByIncidentIdAndAgentName(inc.getId(), "echo")).isPresent()
        );

        // Incident must NOT be resolved yet — only one of three expected agents reported
        assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
            .isEqualTo(IncidentState.DISPATCHED);
    }

    // TC-2.8.8: incident resolves after all three results arrive, three traces recorded
    @Test
    void tc_2_8_8_incident_resolves_after_all_three_results() throws Exception {
        Incident inc = buildDispatchedIncident();

        sendThreeResults(inc.getId(), "ok");

        await().atMost(15, SECONDS).untilAsserted(() -> {
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED);
            assertThat(traceRepository.countByIncidentId(inc.getId())).isEqualTo(3);
            assertThat(reportRepository.count()).isEqualTo(1);
        });
    }

    // TC-2.10.2: aggregator uses expected_agents size, not a hardcoded count
    @Test
    void tc_2_10_2_aggregator_uses_expected_agents_not_hardcoded_count() throws Exception {
        // Build incident with only 2 expected agents (not 3)
        Incident inc = buildDispatchedIncident();
        inc.setExpectedAgents(List.of("echo", "log_analyzer"));
        incidentRepository.save(inc);

        // Send only two results — should still resolve because expected set = 2
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "ok")).get();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "log_analyzer", "ok")).get();

        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED)
        );
    }

    // TC-2.10.3: empty expected_agents does not resolve on first result
    @Test
    void tc_2_10_3_empty_expected_agents_does_not_resolve() throws Exception {
        // Build incident and explicitly clear expected_agents to test the safety guard.
        Incident inc = buildDispatchedIncident();
        inc.setExpectedAgents(List.of());
        incidentRepository.save(inc);

        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "ok")).get();

        // Give the aggregator time to process
        Thread.sleep(3000);

        assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
            .isEqualTo(IncidentState.DISPATCHED);
    }
}
