package com.sentinel.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.OffsetDateTime;
import java.util.List;

public record IncidentFilter(
        List<String> state,
        String service,
        String decision,
        String q,
        @Min(1) @Max(100) Integer limit,
        OffsetDateTime before
) {
    public IncidentFilter {
        if (limit == null) limit = 20;
    }

    public boolean hasReportJoin() {
        return decision != null || (q != null && !q.isBlank());
    }
}
