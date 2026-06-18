package com.sentinel;

import com.sentinel.config.KafkaTopicConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@Testcontainers
@SpringBootTest(webEnvironment = RANDOM_PORT)
class KafkaTopicIntegrationTest {

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

    @LocalServerPort
    int port;

    @Autowired
    private org.springframework.web.client.RestTemplate restTemplate;

    // TC-1.3.2: all six topics are provisioned on startup
    @Test
    void tc_1_3_2_all_six_topics_are_provisioned() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {

            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            assertThat(topics).contains(
                KafkaTopicConfig.INCIDENTS_RAW,
                KafkaTopicConfig.AGENT_TASKS,
                KafkaTopicConfig.AGENT_RESULTS,
                KafkaTopicConfig.INCIDENTS_SYNTHESIZED,
                KafkaTopicConfig.AUDIT_EVENTS,
                KafkaTopicConfig.AGENT_TASKS_DLQ
            );
        }
    }

    // TC-1.3.1: health endpoint reports UP with kafka component
    @Test
    void tc_1_3_1_health_endpoint_reports_kafka_up() {
        String url = "http://localhost:" + port + "/actuator/health";
        String body = new org.springframework.web.client.RestTemplate().getForObject(url, String.class);
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).contains("kafka");
    }
}
