package com.sentinel.demo.failure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class DownstreamSimulator {

    private static final Logger log = LoggerFactory.getLogger(DownstreamSimulator.class);

    private final FailureModeController modeController;
    private final Timer callTimer;
    private final Counter timeoutCounter;

    public DownstreamSimulator(FailureModeController modeController, MeterRegistry meters) {
        this.modeController = modeController;
        this.callTimer = Timer.builder("downstream_call_latency")
            .description("Latency of simulated downstream calls")
            .publishPercentileHistogram().register(meters);
        this.timeoutCounter = Counter.builder("downstream_timeouts_total")
            .description("Simulated downstream timeouts").register(meters);
    }

    /** Returns true on success, false on timeout. */
    public boolean call() {
        return Boolean.TRUE.equals(callTimer.record(() -> {
            try {
                if (modeController.current() == FailureMode.DOWNSTREAM_TIMEOUT) {
                    Thread.sleep(Duration.ofSeconds(3));
                    timeoutCounter.increment();
                    log.error("downstream_timeout",
                        kv("dependency", "payment-gateway"),
                        kv("timeout_ms", 2000));
                    return false;
                }
                Thread.sleep(Duration.ofMillis(20));
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }));
    }
}
