package com.sentinel.incident;

public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(IncidentState from, IncidentState to) {
        super("Illegal incident state transition: " + from + " -> " + to);
    }
}
