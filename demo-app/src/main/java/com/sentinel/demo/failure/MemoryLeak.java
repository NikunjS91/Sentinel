package com.sentinel.demo.failure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MemoryLeak {

    private final List<byte[]> sink = new ArrayList<>();

    public MemoryLeak(MeterRegistry meters) {
        Gauge.builder("demo_leak_objects", sink, List::size)
             .description("Number of leaked 1MB byte arrays retained in memory")
             .register(meters);
    }

    public void leakOne() { sink.add(new byte[1_000_000]); }
    public void clear()   { sink.clear(); }
    public int size()     { return sink.size(); }
}
