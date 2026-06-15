package com.sentinel.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(UUID id) {
        super("Incident not found: " + id);
    }
}
