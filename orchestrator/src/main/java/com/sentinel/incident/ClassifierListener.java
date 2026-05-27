package com.sentinel.incident;

import com.sentinel.config.KafkaTopicConfig;
import com.sentinel.dispatch.Dispatcher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class ClassifierListener {

    private final IncidentRepository incidents;
    private final IncidentClassifier classifier;
    private final IncidentStateMachine stateMachine;
    private final Dispatcher dispatcher;

    public ClassifierListener(IncidentRepository incidents,
                              IncidentClassifier classifier,
                              IncidentStateMachine stateMachine,
                              Dispatcher dispatcher) {
        this.incidents = incidents;
        this.classifier = classifier;
        this.stateMachine = stateMachine;
        this.dispatcher = dispatcher;
    }

    /**
     * Consume incidents.raw. The message body is the incident id (published by Day 4 ingest).
     * Classifies, drives state machine RECEIVED→CLASSIFIED→DISPATCHED, dispatches echo task.
     * @Transactional keeps all DB ops in one transaction, preventing partial re-inserts on
     * concurrent test cleanup.
     */
    @Transactional
    @KafkaListener(topics = KafkaTopicConfig.INCIDENTS_RAW, groupId = "orchestrator")
    public void onRawIncident(String incidentIdStr) {
        UUID incidentId = UUID.fromString(incidentIdStr.trim());

        Incident inc = incidents.findById(incidentId).orElse(null);
        if (inc == null) {
            // Unknown incident — log and skip; do not crash the listener (Sprint 5: DLQ)
            return;
        }

        // Idempotency guard: Kafka is at-least-once; redelivery must be a no-op.
        if (inc.getState() != IncidentState.RECEIVED) {
            return;
        }

        String severity = classifier.classify(inc.getRawAlert());
        inc.setSeverity(severity);
        incidents.save(inc);

        stateMachine.transition(inc, IncidentState.CLASSIFIED);
        stateMachine.transition(inc, IncidentState.DISPATCHED);

        dispatcher.dispatch(inc, List.of("echo"));
    }
}
