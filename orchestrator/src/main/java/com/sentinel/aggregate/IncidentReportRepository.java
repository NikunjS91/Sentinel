package com.sentinel.aggregate;

import com.sentinel.incident.IncidentReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, UUID> {
    Optional<IncidentReport> findByIncidentId(UUID incidentId);
}
