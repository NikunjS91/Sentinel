package com.sentinel.demo.orders;

import com.sentinel.demo.failure.DownstreamSimulator;
import com.sentinel.demo.failure.FailureMode;
import com.sentinel.demo.failure.FailureModeController;
import com.sentinel.demo.failure.MemoryLeak;
import com.sentinel.demo.failure.SlowQuery;
import com.sentinel.demo.inventory.Inventory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderStore orders;
    private final Inventory inventory;
    private final DownstreamSimulator downstream;
    private final SlowQuery slowQuery;
    private final MemoryLeak memoryLeak;
    private final FailureModeController modeController;
    private final Counter ordersCreated;
    private final Counter ordersRejected;
    private final Timer createTimer;

    public OrderController(OrderStore orders, Inventory inventory,
                           DownstreamSimulator downstream, SlowQuery slowQuery,
                           MemoryLeak memoryLeak, FailureModeController modeController,
                           MeterRegistry meters) {
        this.orders = orders;
        this.inventory = inventory;
        this.downstream = downstream;
        this.slowQuery = slowQuery;
        this.memoryLeak = memoryLeak;
        this.modeController = modeController;
        this.ordersCreated = Counter.builder("orders_created_total")
            .description("Orders successfully created").register(meters);
        this.ordersRejected = Counter.builder("orders_rejected_total")
            .description("Orders rejected (e.g. out of stock)").register(meters);
        this.createTimer = Timer.builder("orders_create_latency")
            .description("Order creation latency").publishPercentileHistogram()
            .register(meters);
    }

    @GetMapping
    public Collection<Order> list() {
        return orders.all();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody OrderRequest req) {
        return createTimer.record(() -> {
            // 1. memory_leak: accumulate ~1MB per request when active
            if (modeController.current() == FailureMode.MEMORY_LEAK) {
                memoryLeak.leakOne();
            }

            // 2. inventory read via slow-query wrapper (injects latency under slow_query mode)
            int available = slowQuery.runQuery(() -> inventory.stockOf(req.sku()));
            if (available < req.quantity()) {
                ordersRejected.increment();
                log.warn("order_rejected",
                    kv("sku", req.sku()), kv("qty", req.quantity()),
                    kv("reason", "insufficient_stock"));
                return ResponseEntity.status(409).body(
                    Map.of("error", "insufficient_stock", "sku", req.sku()));
            }

            // 3. downstream payment call (times out under downstream_timeout mode)
            if (!downstream.call()) {
                ordersRejected.increment();
                log.error("order_failed",
                    kv("sku", req.sku()), kv("reason", "downstream_unavailable"));
                return ResponseEntity.status(502).body(
                    Map.of("error", "downstream_unavailable"));
            }

            // 4. happy path — decrement only after downstream confirms
            inventory.decrement(req.sku(), req.quantity());
            Order o = new Order(UUID.randomUUID(), req.sku(), req.quantity(),
                                req.totalUsd(), Instant.now());
            orders.save(o);
            ordersCreated.increment();
            log.info("order_created",
                kv("order_id", o.id()), kv("sku", o.sku()),
                kv("qty", o.quantity()), kv("total", o.totalUsd()));
            return ResponseEntity.status(201).body(o);
        });
    }

    public record OrderRequest(String sku, int quantity, BigDecimal totalUsd) {}
}
