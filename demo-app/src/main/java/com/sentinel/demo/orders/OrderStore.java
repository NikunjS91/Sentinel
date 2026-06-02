package com.sentinel.demo.orders;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class OrderStore {

    private final ConcurrentMap<UUID, Order> orders = new ConcurrentHashMap<>();

    public void save(Order o) { orders.put(o.id(), o); }
    public Collection<Order> all() { return orders.values(); }
    public int size() { return orders.size(); }
}
