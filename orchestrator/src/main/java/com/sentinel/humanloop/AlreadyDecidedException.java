package com.sentinel.humanloop;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.CONFLICT)
public class AlreadyDecidedException extends RuntimeException {
    public AlreadyDecidedException(UUID incidentId) {
        super("Decision already recorded for incident " + incidentId);
    }
}
