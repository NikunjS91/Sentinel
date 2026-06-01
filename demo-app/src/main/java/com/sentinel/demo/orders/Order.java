package com.sentinel.demo.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
        UUID id,
        String sku,
        int quantity,
        BigDecimal totalUsd,
        Instant createdAt
) {}
