package com.sentinel.demo.failure;

public enum FailureMode {
    NONE,
    MEMORY_LEAK,
    DOWNSTREAM_TIMEOUT,
    SLOW_QUERY;

    public static FailureMode parse(String wire) {
        if (wire == null) return NONE;
        try { return FailureMode.valueOf(wire.trim().toUpperCase()); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown failure mode: " + wire);
        }
    }

    public String wire() { return name().toLowerCase(); }
}
