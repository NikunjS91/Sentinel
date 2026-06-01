package com.sentinel.demo.inventory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class InventoryController {

    private final Inventory inventory;

    public InventoryController(Inventory inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/inventory")
    public Map<String, Integer> snapshot() {
        return inventory.snapshot();
    }
}
