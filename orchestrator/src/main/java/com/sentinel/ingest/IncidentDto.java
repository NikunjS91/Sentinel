package com.sentinel.ingest;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IncidentDto(
        UUID id,
        String state,
        String source,
        String severity,
        OffsetDateTime createdAt
) {}
