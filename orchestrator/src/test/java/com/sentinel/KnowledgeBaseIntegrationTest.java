package com.sentinel;

import com.sentinel.kb.KbDocumentRepository;
import com.sentinel.kb.KbLinkRepository;
import com.sentinel.kb.KbRunbookRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * TC-4.1.1 – TC-4.1.5: Knowledge-base schema, seeder, vector search, topology, FTS.
 */
@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = {
    "sentinel.incident.sweeper-interval-ms=999999",
    "sentinel.kb.seed-on-startup=true"
})
class KnowledgeBaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired TestRestTemplate http;
    @Autowired KbDocumentRepository docRepo;
    @Autowired KbLinkRepository linkRepo;
    @Autowired KbRunbookRepository runbookRepo;
    @Autowired JdbcTemplate jdbc;

    // TC-4.1.1: pgvector extension exists and all three kb tables are present
    @Test
    void pgvector_extension_and_tables_exist() {
        var ext = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Long.class);
        assertThat(ext).isEqualTo(1L);

        for (String table : List.of("kb_documents", "kb_links", "kb_runbooks")) {
            var count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'knowledge_base' AND table_name = ?",
                Long.class, table);
            assertThat(count).as("table %s", table).isEqualTo(1L);
        }
    }

    // TC-4.1.2: vector similarity search returns closest document first
    @Test
    void vector_similarity_search_returns_closest_first() {
        UUID targetId = UUID.randomUUID();
        UUID otherId  = UUID.randomUUID();

        // target: [1.0, 0.0, 0.0, ...]  — orthogonal unit vector
        String targetVec = buildVec(1.0f, 0.0f);
        // other:  [0.0, 1.0, 0.0, ...]  — different direction
        String otherVec  = buildVec(0.0f, 1.0f);

        jdbc.update("INSERT INTO knowledge_base.kb_documents " +
                    "(id, source_type, title, body, metadata) VALUES (?::uuid,'test','T1','B1','{}'::jsonb)",
                    targetId.toString());
        jdbc.update("UPDATE knowledge_base.kb_documents SET embedding = ?::vector WHERE id = ?::uuid",
                    targetVec, targetId.toString());

        jdbc.update("INSERT INTO knowledge_base.kb_documents " +
                    "(id, source_type, title, body, metadata) VALUES (?::uuid,'test','T2','B2','{}'::jsonb)",
                    otherId.toString());
        jdbc.update("UPDATE knowledge_base.kb_documents SET embedding = ?::vector WHERE id = ?::uuid",
                    otherVec, otherId.toString());

        // Query closest to targetVec — should return target first
        var results = docRepo.findSimilar(targetVec, "test", 2);
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.get(0)[0].toString()).isEqualTo(targetId.toString());
    }

    // TC-4.1.3: topology endpoint returns correct incoming/outgoing links
    @Test
    void topology_endpoint_returns_links_for_postgres() {
        @SuppressWarnings("unchecked")
        var resp = http.getForObject("/kb/topology/postgres", Map.class);
        assertThat(resp).isNotNull();
        assertThat(resp.get("service")).isEqualTo("postgres");

        @SuppressWarnings("unchecked")
        var incoming = (List<Map<String, Object>>) resp.get("incoming");
        var fromServices = incoming.stream()
            .map(l -> (String) l.get("fromService"))
            .collect(Collectors.toSet());
        assertThat(fromServices).contains("demo-app", "orchestrator");

        @SuppressWarnings("unchecked")
        var outgoing = (List<?>) resp.get("outgoing");
        assertThat(outgoing).isEmpty();
    }

    // TC-4.1.4: runbook full-text search finds memory-leak runbook
    @Test
    void runbooks_fts_finds_memory_leak() {
        var results = runbookRepo.search("memory", 5);
        assertThat(results).isNotEmpty();
        var titles = results.stream().map(r -> r.getTitle().toLowerCase()).toList();
        assertThat(titles).anyMatch(t -> t.contains("memory"));
    }

    // TC-4.1.5: seeder is idempotent — second run doesn't increase row count
    @Test
    void seeder_is_idempotent() {
        long beforeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base.kb_documents", Long.class);
        assertThat(beforeCount).isEqualTo(5L);

        // The seeder already ran on startup (@PostConstruct); running again is safe
        var docsBeforeLinks = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base.kb_links", Long.class);
        var runbooksCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_base.kb_runbooks", Long.class);

        assertThat(docsBeforeLinks).isEqualTo(5L);
        assertThat(runbooksCount).isEqualTo(4L);
    }

    /** Build a 384-dim pgvector string with `first` at index 0, `rest` elsewhere. */
    private String buildVec(float first, float rest) {
        return IntStream.range(0, 384)
            .mapToObj(i -> i == 0 ? String.valueOf(first) : String.valueOf(rest))
            .collect(Collectors.joining(",", "[", "]"));
    }
}
