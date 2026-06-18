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
import org.springframework.test.context.TestPropertySource;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = "sentinel.incident.sweeper-interval-ms=999999")
class AggregatorIntegrationTest {

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

    private Incident buildAggregatingIncident() {
        Incident inc = buildDispatchedIncident();
        inc.setState(IncidentState.AGGREGATING);
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

    private String synthesizerResultJson(UUID incidentId) throws Exception {
        Map<String, Object> payload = Map.of(
            "incidentId", incidentId.toString(),
            "agentName", "synthesizer",
            "output", Map.of(
                "summary", "Synthesized: slow DB query causing latency spike",
                "root_cause", "slow DB query",
                "recommended_action", "check slow query log",
                "confidence", 0.8,
                "dissenting_notes", List.of(),
                "contributing_agents", List.of("echo", "log_analyzer", "metrics")
            ),
            "tokensUsed", 300,
            "latencyMs", 500,
            "status", "ok"
        );
        return objectMapper.writeValueAsString(payload);
    }

    /** Send all three specialist results. */
    private void sendThreeResults(UUID incidentId, String status) throws Exception {
        kafkaTemplate.send("agent.results", incidentId.toString(), resultJson(incidentId, "echo", status)).get();
        kafkaTemplate.send("agent.results", incidentId.toString(), resultJson(incidentId, "log_analyzer", status)).get();
        kafkaTemplate.send("agent.results", incidentId.toString(), resultJson(incidentId, "metrics", status)).get();
    }

    /** Send three specialist results, wait for AGGREGATING, then send Synthesizer result. */
    private void sendThreeResultsThenSynthesizer(UUID incidentId) throws Exception {
        sendThreeResults(incidentId, "ok");
        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(incidentId).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING)
        );
        kafkaTemplate.send("agent.results", incidentId.toString(), synthesizerResultJson(incidentId)).get();
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

    // TC-1.9.2: incident resolves after all agent results + synthesizer arrive,
    //           report written, incidents.synthesized published
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

            sendThreeResultsThenSynthesizer(inc.getId());

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

        sendThreeResultsThenSynthesizer(inc.getId());

        await().atMost(20, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED)
        );

        // Send the echo result again — should be a no-op (idempotency on (incident, agent))
        kafkaTemplate.send("agent.results", inc.getId().toString(), echoPayload).get();
        Thread.sleep(3000);

        // 4 traces: 3 specialists + 1 synthesizer
        assertThat(traceRepository.findAll()).hasSize(4);
        assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
            .isEqualTo(IncidentState.RESOLVED);
    }

    // TC-1.9.4: error-status result still records trace; incident resolves once all results arrive
    @Test
    void tc_1_9_4_error_status_still_records_and_resolves() throws Exception {
        Incident inc = buildDispatchedIncident();

        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "error")).get();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "log_analyzer", "ok")).get();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "metrics", "ok")).get();

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING)
        );

        kafkaTemplate.send("agent.results", inc.getId().toString(), synthesizerResultJson(inc.getId())).get();

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

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(traceRepository.findByIncidentIdAndAgentName(inc.getId(), "echo")).isPresent()
        );

        assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
            .isEqualTo(IncidentState.DISPATCHED);
    }

    // TC-2.8.8: incident resolves after all three specialists + synthesizer, four traces recorded
    @Test
    void tc_2_8_8_incident_resolves_after_all_results() throws Exception {
        Incident inc = buildDispatchedIncident();

        sendThreeResultsThenSynthesizer(inc.getId());

        await().atMost(20, SECONDS).untilAsserted(() -> {
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED);
            // 4 traces: 3 specialists + 1 synthesizer
            assertThat(traceRepository.countByIncidentId(inc.getId())).isEqualTo(4);
            assertThat(reportRepository.count()).isEqualTo(1);
        });
    }

    // TC-2.10.2: aggregator uses expected_agents size, not a hardcoded count
    @Test
    void tc_2_10_2_aggregator_uses_expected_agents_not_hardcoded_count() throws Exception {
        Incident inc = buildDispatchedIncident();
        inc.setExpectedAgents(List.of("echo", "log_analyzer"));
        incidentRepository.save(inc);

        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "ok")).get();
        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "log_analyzer", "ok")).get();

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.AGGREGATING)
        );

        kafkaTemplate.send("agent.results", inc.getId().toString(), synthesizerResultJson(inc.getId())).get();

        await().atMost(15, SECONDS).untilAsserted(() ->
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED)
        );
    }

    // TC-2.10.3: empty expected_agents does not resolve on first result
    @Test
    void tc_2_10_3_empty_expected_agents_does_not_resolve() throws Exception {
        Incident inc = buildDispatchedIncident();
        inc.setExpectedAgents(List.of());
        incidentRepository.save(inc);

        kafkaTemplate.send("agent.results", inc.getId().toString(), resultJson(inc.getId(), "echo", "ok")).get();

        Thread.sleep(3000);

        assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
            .isEqualTo(IncidentState.DISPATCHED);
    }

    // TC-3.1.4: aggregator dispatches Synthesizer after all specialists complete
    @Test
    void tc_3_1_4_aggregator_dispatches_synthesizer_after_specialists() throws Exception {
        Incident inc = buildDispatchedIncident();

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

            sendThreeResults(inc.getId(), "ok");

            await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                    .isEqualTo(IncidentState.AGGREGATING)
            );

            // The Synthesizer task should have been published to agent.tasks.
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            var allTaskRecords = new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            records.records("agent.tasks").forEach(allTaskRecords::add);
            var synthTask = allTaskRecords.stream()
                .filter(r -> {
                    try {
                        var node = objectMapper.readTree(r.value());
                        return "synthesizer".equals(node.path("agentName").asText());
                    } catch (Exception e) { return false; }
                })
                .findFirst();

            assertThat(synthTask).isPresent();
            var taskNode = objectMapper.readTree(synthTask.get().value());
            assertThat(taskNode.path("agentName").asText()).isEqualTo("synthesizer");
            assertThat(taskNode.path("payload").path("specialist_findings").isArray()).isTrue();
            assertThat(taskNode.path("payload").path("specialist_findings").size()).isEqualTo(3);
        }
    }

    private Incident buildAggregatingPartialIncident() {
        Incident inc = buildDispatchedIncident();
        inc.setState(IncidentState.AGGREGATING_PARTIAL);
        return incidentRepository.save(inc);
    }

    private Incident buildPartialIncident() {
        Incident inc = buildDispatchedIncident();
        inc.setState(IncidentState.PARTIAL);
        return incidentRepository.save(inc);
    }

    // TC-3.1.5: Synthesizer result drives the final report (not a specialist's output)
    @Test
    void tc_3_1_5_synthesizer_result_drives_report() throws Exception {
        Incident inc = buildAggregatingIncident();

        String synthJson = objectMapper.writeValueAsString(Map.of(
            "incidentId", inc.getId().toString(),
            "agentName", "synthesizer",
            "output", Map.of(
                "summary", "DB connection pool exhausted under load",
                "root_cause", "connection pool limit too low",
                "recommended_action", "increase pool size to 50",
                "confidence", 0.92,
                "dissenting_notes", List.of(),
                "contributing_agents", List.of("log_analyzer", "metrics")
            ),
            "tokensUsed", 200,
            "latencyMs", 400,
            "status", "ok"
        ));

        kafkaTemplate.send("agent.results", inc.getId().toString(), synthJson).get();

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.RESOLVED);
            assertThat(reportRepository.count()).isEqualTo(1);
        });

        var report = reportRepository.findAll().get(0);
        assertThat(report.getSummary()).isEqualTo("DB connection pool exhausted under load");
        assertThat(report.getRootCause()).isEqualTo("connection pool limit too low");
        assertThat(report.getRecommendedAction()).isEqualTo("increase pool size to 50");
        assertThat(report.getConfidence()).isNotNull();
        assertThat(report.getConfidence().doubleValue()).isGreaterThan(0.9);
    }

    // TC-3.2.4: Synthesizer result on AGGREGATING_PARTIAL incident → state PARTIAL, report written
    @Test
    void tc_3_2_4_synthesizer_result_on_partial_path() throws Exception {
        Incident inc = buildAggregatingPartialIncident();

        String synthJson = objectMapper.writeValueAsString(Map.of(
            "incidentId", inc.getId().toString(),
            "agentName", "synthesizer",
            "output", Map.of(
                "summary", "Partial synthesis: only echo reported",
                "root_cause", "unknown — specialists timed out",
                "recommended_action", "manual investigation required",
                "confidence", 0.3,
                "dissenting_notes", List.of("missing specialist: log_analyzer", "missing specialist: metrics"),
                "contributing_agents", List.of("echo")
            ),
            "tokensUsed", 150,
            "latencyMs", 300,
            "status", "ok"
        ));

        kafkaTemplate.send("agent.results", inc.getId().toString(), synthJson).get();

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
                .isEqualTo(IncidentState.PARTIAL);
            assertThat(reportRepository.count()).isEqualTo(1);
        });

        var report = reportRepository.findAll().get(0);
        assertThat(report.getSummary()).isEqualTo("Partial synthesis: only echo reported");
    }

    // TC-3.2.5: late specialist result on PARTIAL (terminal) incident → trace recorded, state unchanged
    @Test
    void tc_3_2_5_late_specialist_result_on_partial_is_noop() throws Exception {
        Incident inc = buildPartialIncident();

        kafkaTemplate.send("agent.results", inc.getId().toString(),
            resultJson(inc.getId(), "log_analyzer", "ok")).get();

        await().atMost(10, SECONDS).untilAsserted(() ->
            assertThat(traceRepository.findByIncidentIdAndAgentName(inc.getId(), "log_analyzer")).isPresent()
        );

        assertThat(incidentRepository.findById(inc.getId()).orElseThrow().getState())
            .isEqualTo(IncidentState.PARTIAL);
    }
}
