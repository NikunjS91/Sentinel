package com.sentinel.incident;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.sentinel.incident.IncidentState.*;

@Component
public class IncidentStateMachine {

    private static final Map<IncidentState, Set<IncidentState>> ALLOWED =
            new EnumMap<>(IncidentState.class);
    static {
        ALLOWED.put(RECEIVED,    Set.of(CLASSIFIED, FAILED));
        ALLOWED.put(CLASSIFIED,  Set.of(DISPATCHED, FAILED));
        ALLOWED.put(DISPATCHED,  Set.of(AGGREGATING, FAILED));
        ALLOWED.put(AGGREGATING, Set.of(SYNTHESIZED, PARTIAL, FAILED));
        ALLOWED.put(PARTIAL,     Set.of(SYNTHESIZED, FAILED));
        ALLOWED.put(SYNTHESIZED, Set.of(RESOLVED, FAILED));
        ALLOWED.put(RESOLVED,    Set.of());
        ALLOWED.put(FAILED,      Set.of());
    }

    private final IncidentRepository incidents;
    private final AuditWriter audit;

    public IncidentStateMachine(IncidentRepository incidents, AuditWriter audit) {
        this.incidents = incidents;
        this.audit = audit;
    }

    public boolean canTransition(IncidentState from, IncidentState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    @Transactional
    public void transition(Incident incident, IncidentState target) {
        IncidentState current = incident.getState();

        if (!canTransition(current, target)) {
            throw new IllegalStateTransitionException(current, target);
        }

        incident.setState(target);
        incident.setUpdatedAt(OffsetDateTime.now());
        incidents.save(incident);

        audit.write(incident.getId(),
                    "STATE_" + current + "_TO_" + target,
                    "system");
    }
}
