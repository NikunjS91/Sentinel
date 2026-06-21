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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = "sentinel.incident.sweeper-interval-ms=999999")
class IncidentFilterIntegrationTest {

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
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE incidents CASCADE");
    }

    private Incident buildIncident(IncidentState state, String source) {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setIdempotencyKey("filter-test-" + UUID.randomUUID());
        inc.setSource(source);
        inc.setSeverity("p1");
        inc.setState(state);
        inc.setRawAlert("{}");
        inc.setExpectedAgents(List.of());
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        return incidentRepository.save(inc);
    }

    private IncidentReport buildReport(Incident inc, String humanDecision,
                                       String summary, String rootCause, String action) {
        IncidentReport r = new IncidentReport();
        r.setId(UUID.randomUUID());
        r.setIncidentId(inc.getId());
        r.setSummary(summary);
        r.setRootCause(rootCause);
        r.setRecommendedAction(action);
        r.setConfidence(BigDecimal.valueOf(0.8));
        r.setHumanDecision(humanDecision);
        if (humanDecision != null) r.setHumanDecidedAt(OffsetDateTime.now());
        r.setCreatedAt(OffsetDateTime.now());
        return reportRepository.save(r);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchItems(String query) {
        ResponseEntity<Map> resp = http.getForEntity("/incidents" + query, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) resp.getBody().get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchBody(String query) {
        ResponseEntity<Map> resp = http.getForEntity("/incidents" + query, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    // TC-3.5.1: No filter returns all incidents newest first
    @Test
    void tc_3_5_1_no_filter_returns_all_newest_first() throws Exception {
        Incident i1 = buildIncident(IncidentState.RESOLVED, "svc-a");
        Thread.sleep(5);
        Incident i2 = buildIncident(IncidentState.PARTIAL, "svc-b");
        Thread.sleep(5);
        Incident i3 = buildIncident(IncidentState.DISPATCHED, "svc-c");
        Thread.sleep(5);
        Incident i4 = buildIncident(IncidentState.AGGREGATING, "svc-d");
        Thread.sleep(5);
        Incident i5 = buildIncident(IncidentState.FAILED, "svc-e");

        List<Map<String, Object>> items = fetchItems("?limit=10");
        assertThat(items).hasSize(5);
        assertThat(items.get(0).get("incident_id")).isEqualTo(i5.getId().toString());
        assertThat(items.get(4).get("incident_id")).isEqualTo(i1.getId().toString());
    }

    // TC-3.5.2: State filter — single, comma-list, and invalid
    @Test
    void tc_3_5_2_state_filter() {
        buildIncident(IncidentState.RESOLVED, "svc");
        buildIncident(IncidentState.PARTIAL, "svc");
        buildIncident(IncidentState.DISPATCHED, "svc");

        List<Map<String, Object>> resolved = fetchItems("?state=RESOLVED");
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).get("state")).isEqualTo("RESOLVED");

        List<Map<String, Object>> terminal = fetchItems("?state=RESOLVED,PARTIAL");
        assertThat(terminal).hasSize(2);
        assertThat(terminal.stream().map(m -> m.get("state")))
            .containsExactlyInAnyOrder("RESOLVED", "PARTIAL");

        ResponseEntity<Map> bad = http.getForEntity("/incidents?state=BOGUS", Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // TC-3.5.3: Service filter
    @Test
    void tc_3_5_3_service_filter() {
        buildIncident(IncidentState.RESOLVED, "demo-app");
        buildIncident(IncidentState.RESOLVED, "demo-app");
        buildIncident(IncidentState.RESOLVED, "other-service");

        List<Map<String, Object>> items = fetchItems("?service=demo-app");
        assertThat(items).hasSize(2);
        assertThat(items).allMatch(m -> "demo-app".equals(m.get("service")));
    }

    // TC-3.5.4: decision=undecided includes no-report AND undecided-report; excludes decided
    @Test
    void tc_3_5_4_decision_undecided_includes_no_report() {
        Incident dispatched = buildIncident(IncidentState.DISPATCHED, "svc");  // no report
        Incident undecided = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(undecided, null, "Summary", "Root", "Action");             // report, no decision
        Incident accepted = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(accepted, "ACCEPTED", "Summary", "Root", "Action");        // decided

        List<Map<String, Object>> items = fetchItems("?decision=undecided");
        List<String> ids = items.stream().map(m -> m.get("incident_id").toString()).toList();
        assertThat(ids).contains(dispatched.getId().toString(), undecided.getId().toString());
        assertThat(ids).doesNotContain(accepted.getId().toString());
    }

    // TC-3.5.5: Free-text search across summary, root_cause, recommended_action (case-insensitive)
    @Test
    void tc_3_5_5_free_text_search() {
        Incident i1 = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(i1, null, "DB latency spike", "Slow query", "Check indexes");
        Incident i2 = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(i2, null, "CPU spike", "high latency detected", "Scale up");
        Incident i3 = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(i3, null, "Memory leak", "GC pressure", "Reduce latency threshold");
        Incident i4 = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(i4, null, "Network timeout", "DNS failure", "Check routing");

        List<Map<String, Object>> items = fetchItems("?q=latency");
        List<String> ids = items.stream().map(m -> m.get("incident_id").toString()).toList();
        assertThat(ids).contains(i1.getId().toString(), i2.getId().toString(), i3.getId().toString());
        assertThat(ids).doesNotContain(i4.getId().toString());

        // Case-insensitive
        List<Map<String, Object>> upper = fetchItems("?q=LATENCY");
        assertThat(upper).hasSize(items.size());
    }

    // TC-3.5.6: Limit bounds — enforced and exceeded
    @Test
    void tc_3_5_6_limit_bounds() {
        for (int i = 0; i < 10; i++) buildIncident(IncidentState.RESOLVED, "svc");

        Map<String, Object> body = fetchBody("?limit=3");
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) body.get("items");
        assertThat(items).hasSize(3);
        assertThat(body.get("nextBefore")).isNotNull();

        ResponseEntity<Map> bad = http.getForEntity("/incidents?limit=200", Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // TC-3.5.7: Cursor pagination via before — no overlap between pages
    @Test
    void tc_3_5_7_cursor_pagination() throws Exception {
        for (int i = 0; i < 5; i++) {
            buildIncident(IncidentState.RESOLVED, "svc");
            Thread.sleep(5);
        }

        Map<String, Object> page1Body = fetchBody("?limit=2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page1 = (List<Map<String, Object>>) page1Body.get("items");
        String nextBefore = (String) page1Body.get("nextBefore");

        assertThat(page1).hasSize(2);
        assertThat(nextBefore).isNotNull();

        Map<String, Object> page2Body = fetchBody("?limit=2&before=" + nextBefore);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page2 = (List<Map<String, Object>>) page2Body.get("items");

        assertThat(page2).hasSize(2);

        List<String> page1Ids = page1.stream().map(m -> m.get("incident_id").toString()).toList();
        List<String> page2Ids = page2.stream().map(m -> m.get("incident_id").toString()).toList();
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
    }

    // TC-3.5.8: Combined filters AND semantics
    @Test
    void tc_3_5_8_combined_filters_and_semantics() {
        // RESOLVED + undecided + latency in summary → should match
        Incident match = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(match, null, "latency spike detected", "slow query", "optimize index");

        // RESOLVED + undecided but no latency → should not match
        Incident noQ = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(noQ, null, "memory leak", "GC pressure", "restart pod");

        // RESOLVED + decided + latency → should not match (already decided)
        Incident decided = buildIncident(IncidentState.RESOLVED, "svc");
        buildReport(decided, "ACCEPTED", "latency issue", "DB slow", "fix query");

        // PARTIAL + undecided + latency → wrong state
        Incident wrongState = buildIncident(IncidentState.PARTIAL, "svc");
        buildReport(wrongState, null, "latency detected", "network", "check routing");

        List<Map<String, Object>> items = fetchItems("?state=RESOLVED&decision=undecided&q=latency");
        List<String> ids = items.stream().map(m -> m.get("incident_id").toString()).toList();
        assertThat(ids).containsExactly(match.getId().toString());
    }
}
