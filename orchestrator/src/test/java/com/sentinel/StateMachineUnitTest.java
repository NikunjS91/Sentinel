package com.sentinel;

import com.sentinel.events.IncidentEventPublisher;
import com.sentinel.incident.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.sentinel.incident.IncidentState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StateMachineUnitTest {

    private IncidentRepository incidentRepository;
    private AuditWriter auditWriter;
    private IncidentEventPublisher eventPublisher;
    private IncidentStateMachine sm;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        auditWriter = mock(AuditWriter.class);
        eventPublisher = mock(IncidentEventPublisher.class);
        sm = new IncidentStateMachine(incidentRepository, auditWriter, eventPublisher);
    }

    private Incident incident(IncidentState state) {
        Incident inc = new Incident();
        inc.setId(UUID.randomUUID());
        inc.setState(state);
        inc.setCreatedAt(OffsetDateTime.now());
        inc.setUpdatedAt(OffsetDateTime.now());
        return inc;
    }

    // TC-1.5.1: legal transition succeeds, audit row written
    @Test
    void tc_1_5_1_legal_transition_succeeds() {
        Incident inc = incident(RECEIVED);
        when(incidentRepository.save(any())).thenReturn(inc);

        sm.transition(inc, CLASSIFIED);

        assertThat(inc.getState()).isEqualTo(CLASSIFIED);
        verify(auditWriter).write(inc.getId(), "STATE_RECEIVED_TO_CLASSIFIED", "system");
    }

    // TC-1.5.2: illegal transition is rejected, state unchanged
    @Test
    void tc_1_5_2_illegal_transition_is_rejected() {
        Incident inc = incident(RECEIVED);

        assertThatThrownBy(() -> sm.transition(inc, RESOLVED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("RECEIVED -> RESOLVED");

        assertThat(inc.getState()).isEqualTo(RECEIVED);
        verifyNoInteractions(incidentRepository);
        verifyNoInteractions(auditWriter);
    }

    // TC-1.5.3: FAILED is reachable from every non-terminal state
    @ParameterizedTest
    @EnumSource(value = IncidentState.class, names = {"RECEIVED", "CLASSIFIED", "DISPATCHED",
        "AGGREGATING", "AGGREGATING_PARTIAL", "SYNTHESIZED", "SYNTHESIZED_PARTIAL"})
    void tc_1_5_3_failed_reachable_from_any_non_terminal(IncidentState from) {
        assertThat(sm.canTransition(from, FAILED)).isTrue();
    }

    // TC-1.5.4: terminal states reject all outgoing transitions
    @ParameterizedTest
    @EnumSource(value = IncidentState.class, names = {"RESOLVED", "PARTIAL", "FAILED"})
    void tc_1_5_4_terminal_states_reject_all_transitions(IncidentState terminal) {
        Incident inc = incident(terminal);

        for (IncidentState target : IncidentState.values()) {
            assertThatThrownBy(() -> sm.transition(inc, target))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
        verifyNoInteractions(incidentRepository);
        verifyNoInteractions(auditWriter);
    }

    // TC-1.5.5: full happy path RECEIVED→CLASSIFIED→DISPATCHED→AGGREGATING→SYNTHESIZED→RESOLVED
    @Test
    void tc_1_5_5_full_happy_path_is_walkable() {
        Incident inc = incident(RECEIVED);
        when(incidentRepository.save(any())).thenReturn(inc);

        sm.transition(inc, CLASSIFIED);
        sm.transition(inc, DISPATCHED);
        sm.transition(inc, AGGREGATING);
        sm.transition(inc, SYNTHESIZED);
        sm.transition(inc, RESOLVED);

        assertThat(inc.getState()).isEqualTo(RESOLVED);
        verify(incidentRepository, times(5)).save(inc);
    }
}
