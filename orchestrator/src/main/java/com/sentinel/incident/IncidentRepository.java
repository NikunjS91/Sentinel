package com.sentinel.incident;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Optional<Incident> findByIdempotencyKey(String idempotencyKey);
}
