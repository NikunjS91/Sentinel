package com.sentinel.incident;

import com.sentinel.events.IncidentEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.sentinel.incident.IncidentState.*;

@Component
public class IncidentStateMachine {

    private static final Map<IncidentState, Set<IncidentState>> ALLOWED =
            new EnumMap<>(IncidentState.class);
    static {
        ALLOWED.put(RECEIVED,            Set.of(CLASSIFIED, FAILED));
        ALLOWED.put(CLASSIFIED,          Set.of(DISPATCHED, FAILED));
        ALLOWED.put(DISPATCHED,          Set.of(AGGREGATING, AGGREGATING_PARTIAL, FAILED));
        ALLOWED.put(AGGREGATING,         Set.of(SYNTHESIZED, FAILED));
        ALLOWED.put(AGGREGATING_PARTIAL, Set.of(SYNTHESIZED_PARTIAL, FAILED));
        ALLOWED.put(SYNTHESIZED,         Set.of(RESOLVED, FAILED));
        ALLOWED.put(SYNTHESIZED_PARTIAL, Set.of(PARTIAL, FAILED));
        ALLOWED.put(RESOLVED,            Set.of());
        ALLOWED.put(PARTIAL,             Set.of());
        ALLOWED.put(FAILED,              Set.of());
    }

    private final IncidentRepository incidents;
    private final AuditWriter audit;
    private final IncidentEventPublisher events;

    public IncidentStateMachine(IncidentRepository incidents,
                                AuditWriter audit,
                                IncidentEventPublisher events) {
        this.incidents = incidents;
        this.audit = audit;
        this.events = events;
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

        events.publish(stateChangedEvent(incident));
    }

    public Map<String, Object> stateChangedEvent(Incident inc) {
        boolean terminal = inc.getState().isTerminal();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", terminal ? "incident.completed" : "incident.state_changed");
        e.put("ts", OffsetDateTime.now().toString());
        e.put("incident_id", inc.getId().toString());
        e.put("state", inc.getState().name());
        e.put("service", inc.getSource());
        e.put("severity", inc.getSeverity());
        e.put("alert_name", null);
        e.put("expected_agents", inc.getExpectedAgents());
        e.put("report", null);
        return e;
    }
}
