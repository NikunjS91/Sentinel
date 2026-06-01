package com.sentinel.demo.inventory;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class Inventory {

    private final ConcurrentMap<String, AtomicInteger> stock = new ConcurrentHashMap<>(
        Map.of(
            "SKU-001", new AtomicInteger(100),
            "SKU-002", new AtomicInteger(50),
            "SKU-003", new AtomicInteger(25)
        ));

    public int stockOf(String sku) {
        AtomicInteger c = stock.get(sku);
        return c == null ? 0 : c.get();
    }

    /** Returns true if the decrement succeeded (enough stock). */
    public boolean decrement(String sku, int qty) {
        AtomicInteger c = stock.get(sku);
        if (c == null) return false;
        int prev, next;
        do {
            prev = c.get();
            if (prev < qty) return false;
            next = prev - qty;
        } while (!c.compareAndSet(prev, next));
        return true;
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new LinkedHashMap<>();
        stock.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
