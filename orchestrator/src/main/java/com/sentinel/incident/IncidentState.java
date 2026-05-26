package com.sentinel.incident;

public enum IncidentState {
    RECEIVED,
    CLASSIFIED,
    DISPATCHED,
    AGGREGATING,
    PARTIAL,
    SYNTHESIZED,
    RESOLVED,
    FAILED;

    public boolean isTerminal() {
        return this == RESOLVED || this == FAILED;
    }
}
