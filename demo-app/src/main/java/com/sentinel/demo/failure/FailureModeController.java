package com.sentinel.demo.failure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
@RestController
@RequestMapping("/admin/failure-mode")
public class FailureModeController {

    private static final Logger log = LoggerFactory.getLogger(FailureModeController.class);

    private final AtomicReference<FailureMode> mode = new AtomicReference<>(FailureMode.NONE);
    private final MemoryLeak memoryLeak;

    public FailureModeController(MeterRegistry meters, MemoryLeak memoryLeak) {
        this.memoryLeak = memoryLeak;
        // ordinal: 0=NONE, 1=MEMORY_LEAK, 2=DOWNSTREAM_TIMEOUT, 3=SLOW_QUERY
        Gauge.builder("demo_failure_mode", mode, m -> (double) m.get().ordinal())
             .description("Active failure mode: 0=none 1=memory_leak 2=downstream_timeout 3=slow_query")
             .register(meters);
    }

    public FailureMode current() { return mode.get(); }

    @GetMapping
    public Map<String, String> get() {
        return Map.of("mode", mode.get().wire());
    }

    @PostMapping
    public ResponseEntity<?> set(@RequestBody Map<String, String> body) {
        FailureMode next;
        try {
            next = FailureMode.parse(body.get("mode"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        FailureMode prev = mode.getAndSet(next);
        if (next == FailureMode.NONE) {
            memoryLeak.clear();
        }
        log.warn("failure_mode_changed", kv("from", prev.wire()), kv("to", next.wire()));
        return ResponseEntity.ok(Map.of("from", prev.wire(), "to", next.wire()));
    }
}
