package com.sentinel.incident;

public enum IncidentState {
    RECEIVED,
    CLASSIFIED,
    DISPATCHED,
    AGGREGATING,
    AGGREGATING_PARTIAL,   // deadline fired; Synthesizer dispatched with partial data
    SYNTHESIZED,
    SYNTHESIZED_PARTIAL,   // deadline-path Synthesizer result processed
    RESOLVED,
    PARTIAL,               // terminal — incomplete but synthesized
    FAILED;

    public boolean isTerminal() {
        return this == RESOLVED || this == PARTIAL || this == FAILED;
    }
}
