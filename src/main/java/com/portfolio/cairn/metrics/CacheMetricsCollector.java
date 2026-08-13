package com.portfolio.cairn.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects non-blocking metrics for Cairn cache operations, including hits,
 * misses, evictions, and decay-buffer latency percentiles (p50, p95, p99).
 */
@Component
public class CacheMetricsCollector {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder policyEvictions = new LongAdder();
    private final LongAdder ttlEvictions = new LongAdder();
    private final Timer timer;

    @Autowired
    public CacheMetricsCollector(MeterRegistry registry) {
        this.timer = Timer.builder("cairn.cache.latency")
                .description("Cairn cache operations latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .minimumExpectedValue(Duration.ofNanos(1000))
                .maximumExpectedValue(Duration.ofMillis(10000))
                .register(registry);
    }

    public void recordHit() {
        hits.increment();
    }

    public void recordMiss() {
        misses.increment();
    }

    public void recordPolicyEviction() {
        policyEvictions.increment();
    }

    public void recordTtlEviction() {
        ttlEvictions.increment();
    }

    public void recordLatency(long durationNanos) {
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public long getHits() {
        return hits.sum();
    }

    public long getMisses() {
        return misses.sum();
    }

    public long getPolicyEvictions() {
        return policyEvictions.sum();
    }

    public long getTtlEvictions() {
        return ttlEvictions.sum();
    }

    public Timer getTimer() {
        return timer;
    }

    public void reset() {
        hits.reset();
        misses.reset();
        policyEvictions.reset();
        ttlEvictions.reset();
    }
}
