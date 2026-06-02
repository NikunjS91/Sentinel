package com.sentinel.demo.failure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class SlowQuery {

    private final FailureModeController modeController;
    private final Timer queryTimer;

    public SlowQuery(FailureModeController modeController, MeterRegistry meters) {
        this.modeController = modeController;
        this.queryTimer = Timer.builder("db_query_latency")
            .description("Simulated DB query latency")
            .publishPercentileHistogram().register(meters);
    }

    public <T> T runQuery(Supplier<T> op) {
        return queryTimer.record(() -> {
            try {
                if (modeController.current() == FailureMode.SLOW_QUERY) {
                    Thread.sleep(Duration.ofMillis(800));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return op.get();
        });
    }
}
