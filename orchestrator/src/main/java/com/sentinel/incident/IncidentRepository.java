package com.sentinel.incident;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Optional<Incident> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT i FROM Incident i WHERE i.deadlineAt <= :now AND " +
           "i.state IN (com.sentinel.incident.IncidentState.DISPATCHED, " +
           "com.sentinel.incident.IncidentState.AGGREGATING)")
    List<Incident> findOverdueActive(@Param("now") OffsetDateTime now);

    @Query("SELECT i FROM Incident i ORDER BY i.createdAt DESC LIMIT :limit")
    List<Incident> findRecent(@Param("limit") int limit);
}
