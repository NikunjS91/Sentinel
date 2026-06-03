package com.sentinel.demo.inventory;

import com.sentinel.demo.failure.SlowQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InventoryController {

    private final Inventory inventory;
    private final SlowQuery slowQuery;

    public InventoryController(Inventory inventory, SlowQuery slowQuery) {
        this.inventory = inventory;
        this.slowQuery = slowQuery;
    }

    @GetMapping("/inventory")
    public Map<String, Integer> snapshot() {
        return slowQuery.runQuery(inventory::snapshot);
    }
}
