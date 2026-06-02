package com.sentinel.demo;

import com.sentinel.demo.orders.OrderController.OrderRequest;
import com.sentinel.demo.orders.OrderStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrdersIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MeterRegistry meters;
    @Autowired OrderStore orderStore;

    // Baselines captured before each test so delta assertions are test-isolated
    private double createdBefore;
    private double rejectedBefore;
    private int storeSizeBefore;

    private String base() { return "http://localhost:" + port; }

    @BeforeEach
    void captureBaseline() {
        createdBefore = meters.counter("orders_created_total").count();
        rejectedBefore = meters.counter("orders_rejected_total").count();
        storeSizeBefore = orderStore.size();
    }

    @Test
    void tc_2_1_1_demo_app_serves_requests() {
        OrderRequest req = new OrderRequest("SKU-001", 2, new BigDecimal("29.99"));

        ResponseEntity<String> created = rest.postForEntity(base() + "/orders", req, String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("id");

        ResponseEntity<List> listed = rest.getForEntity(base() + "/orders", List.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).isNotEmpty();
    }

    @Test
    void tc_2_1_2_metrics_reflect_activity() {
        // 2 successful orders
        rest.postForEntity(base() + "/orders", new OrderRequest("SKU-001", 1, BigDecimal.ONE), String.class);
        rest.postForEntity(base() + "/orders", new OrderRequest("SKU-002", 1, BigDecimal.ONE), String.class);
        // 1 rejection (unknown SKU has no stock entry)
        rest.postForEntity(base() + "/orders", new OrderRequest("UNKNOWN-SKU", 1, BigDecimal.ONE), String.class);

        double createdDelta  = meters.counter("orders_created_total").count()  - createdBefore;
        double rejectedDelta = meters.counter("orders_rejected_total").count() - rejectedBefore;

        assertThat(createdDelta).isEqualTo(2.0);
        assertThat(rejectedDelta).isEqualTo(1.0);
    }

    @Test
    void tc_2_1_3_out_of_stock_returns_409_and_increments_reject_counter() {
        // SKU-003 has 25 stock; request 9999
        ResponseEntity<String> resp = rest.postForEntity(
            base() + "/orders",
            new OrderRequest("SKU-003", 9999, BigDecimal.ONE),
            String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).contains("insufficient_stock");
        assertThat(orderStore.size()).isEqualTo(storeSizeBefore);
        assertThat(meters.counter("orders_rejected_total").count() - rejectedBefore).isEqualTo(1.0);
    }
}
