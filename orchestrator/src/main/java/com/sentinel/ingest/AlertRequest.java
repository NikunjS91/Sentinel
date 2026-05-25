package com.sentinel.ingest;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record AlertRequest(
        @NotBlank String source,
        @NotBlank String service,
        @NotBlank String alertName,
        @NotBlank String fingerprint,
        String severity,
        Map<String, Object> labels,
        Map<String, Object> annotations
) {}
