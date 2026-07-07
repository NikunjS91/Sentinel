package com.sentinel.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class IncidentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IncidentEventPublisher.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper json;

    public IncidentEventPublisher(ObjectMapper json) {
        this.json = json;
    }

    /** Register a new client. Returns the emitter to register with Spring MVC. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(t -> emitters.remove(emitter));
        emitters.add(emitter);
        log.debug("SSE subscriber added; total={}", emitters.size());
        return emitter;
    }

    /** Broadcast an event to all connected clients. */
    public void publish(Map<String, Object> event) {
        if (emitters.isEmpty()) return;
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (Exception e) {
            log.error("failed to serialize event {}", event, e);
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name((String) event.get("type"))
                    .data(payload));
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    public int subscriberCount() {
        return emitters.size();
    }
}
