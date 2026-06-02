package com.sentinel.demo.orders;

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
    private final Counter ordersCreated;
    private final Counter ordersRejected;
    private final Timer createTimer;

    public OrderController(OrderStore orders, Inventory inventory, MeterRegistry meters) {
        this.orders = orders;
        this.inventory = inventory;
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
            if (!inventory.decrement(req.sku(), req.quantity())) {
                ordersRejected.increment();
                log.warn("order_rejected",
                    kv("sku", req.sku()),
                    kv("qty", req.quantity()),
                    kv("reason", "insufficient_stock"));
                return ResponseEntity.status(409).body(
                    Map.of("error", "insufficient_stock", "sku", req.sku()));
            }
            Order o = new Order(UUID.randomUUID(), req.sku(), req.quantity(),
                                req.totalUsd(), Instant.now());
            orders.save(o);
            ordersCreated.increment();
            log.info("order_created",
                kv("order_id", o.id()),
                kv("sku", o.sku()),
                kv("qty", o.quantity()),
                kv("total", o.totalUsd()));
            return ResponseEntity.status(201).body(o);
        });
    }

    public record OrderRequest(String sku, int quantity, BigDecimal totalUsd) {}
}
