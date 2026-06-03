package com.sentinel.demo;

import com.sentinel.demo.failure.FailureMode;
import com.sentinel.demo.failure.FailureModeController;
import com.sentinel.demo.failure.MemoryLeak;
import com.sentinel.demo.orders.OrderController.OrderRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FailureModeIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MeterRegistry meters;
    @Autowired FailureModeController modeController;
    @Autowired MemoryLeak memoryLeak;

    private String base() { return "http://localhost:" + port; }

    @AfterEach
    void resetToNone() {
        modeController.current(); // no-op read to satisfy compiler
        rest.postForEntity(base() + "/admin/failure-mode",
            Map.of("mode", "none"), String.class);
    }

    @Test
    void tc_2_2_1_failure_mode_is_settable() {
        ResponseEntity<Map> resp = rest.postForEntity(
            base() + "/admin/failure-mode",
            Map.of("mode", "memory_leak"), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("to", "memory_leak");

        ResponseEntity<Map> get = rest.getForEntity(
            base() + "/admin/failure-mode", Map.class);
        assertThat(get.getBody()).containsEntry("mode", "memory_leak");

        double gauge = meters.find("demo_failure_mode").gauge().value();
        assertThat(gauge).isEqualTo((double) FailureMode.MEMORY_LEAK.ordinal());
    }

    @Test
    void tc_2_2_2_slow_query_mode_increases_latency() {
        rest.postForEntity(base() + "/admin/failure-mode",
            Map.of("mode", "slow_query"), String.class);

        long beforeCount = meters.find("db_query_latency").timer().count();

        long t0 = System.currentTimeMillis();
        rest.postForEntity(base() + "/orders",
            new OrderRequest("SKU-001", 1, BigDecimal.ONE), String.class);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(elapsed).isGreaterThan(500);
        assertThat(meters.find("db_query_latency").timer().count())
            .isGreaterThan(beforeCount);
    }

    @Test
    void tc_2_2_3_downstream_timeout_returns_502_and_increments_counter() {
        rest.postForEntity(base() + "/admin/failure-mode",
            Map.of("mode", "downstream_timeout"), String.class);

        double beforeTimeouts = meters.find("downstream_timeouts_total").counter().count();

        ResponseEntity<Map> resp = rest.postForEntity(base() + "/orders",
            new OrderRequest("SKU-001", 1, BigDecimal.ONE), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody()).containsKey("error");
        assertThat(meters.find("downstream_timeouts_total").counter().count() - beforeTimeouts)
            .isEqualTo(1.0);
    }

    @Test
    void tc_2_2_4_memory_leak_gauge_grows_and_resets_on_none() {
        rest.postForEntity(base() + "/admin/failure-mode",
            Map.of("mode", "memory_leak"), String.class);

        int n = 5;
        for (int i = 0; i < n; i++) {
            rest.postForEntity(base() + "/orders",
                new OrderRequest("SKU-001", 1, BigDecimal.ONE), String.class);
        }

        assertThat(meters.find("demo_leak_objects").gauge().value()).isEqualTo((double) n);

        rest.postForEntity(base() + "/admin/failure-mode",
            Map.of("mode", "none"), String.class);

        assertThat(meters.find("demo_leak_objects").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void tc_2_2_5_unknown_mode_is_rejected() {
        ResponseEntity<Map> resp = rest.postForEntity(
            base() + "/admin/failure-mode",
            Map.of("mode", "explode"), Map.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();

        ResponseEntity<Map> current = rest.getForEntity(
            base() + "/admin/failure-mode", Map.class);
        assertThat(current.getBody()).containsEntry("mode", "none");
    }
}
