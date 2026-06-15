package com.sentinel.api;

import com.sentinel.events.IncidentEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class IncidentStreamController {

    private final IncidentEventPublisher events;

    public IncidentStreamController(IncidentEventPublisher events) {
        this.events = events;
    }

    @CrossOrigin(origins = "${sentinel.ui.cors-origin:http://localhost:5173}")
    @GetMapping(value = "/incidents/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return events.subscribe();
    }
}
