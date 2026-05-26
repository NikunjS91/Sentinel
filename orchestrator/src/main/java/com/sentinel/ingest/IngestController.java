package com.sentinel.ingest;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/alerts")
public class IngestController {

    private final IngestService ingest;

    public IngestController(IngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping
    public ResponseEntity<IncidentDto> receive(@Valid @RequestBody AlertRequest req) {
        IngestService.IngestResult result = ingest.handle(req);
        IncidentDto dto = toDto(result.incident());
        return result.created()
                ? ResponseEntity.status(201).body(dto)
                : ResponseEntity.ok(dto);
    }

    private IncidentDto toDto(com.sentinel.incident.Incident inc) {
        return new IncidentDto(inc.getId(), inc.getState().name(),
                inc.getSource(), inc.getSeverity(), inc.getCreatedAt());
    }
}
