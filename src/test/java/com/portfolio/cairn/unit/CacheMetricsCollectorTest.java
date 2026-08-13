package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import com.portfolio.cairn.metrics.CacheMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

public class CacheMetricsCollectorTest {

    private SimpleMeterRegistry registry;
    private CacheMetricsCollector collector;
    private CacheEngine cacheEngine;

    @BeforeEach
    public void setUp() {
        registry = new SimpleMeterRegistry();
        collector = new CacheMetricsCollector(registry);
        // Inject custom collector into CacheEngine
        cacheEngine = new CacheEngine(
                new LruEvictionPolicy(),
                3, // small capacity to force evictions
                new com.portfolio.cairn.engine.MockDatabase(),
                collector
        );
    }

    @Test
    public void testMetricsIncrements() {
        // 1. Test Hit and Miss
        cacheEngine.get("k1"); // Miss
        assertThat(collector.getMisses()).isEqualTo(1);
        assertThat(collector.getHits()).isEqualTo(0);

        cacheEngine.set("k1", "v1"); // Set
        cacheEngine.get("k1"); // Hit
        assertThat(collector.getMisses()).isEqualTo(1);
        assertThat(collector.getHits()).isEqualTo(1);

        // 2. Test Policy Eviction
        cacheEngine.set("k2", "v2");
        cacheEngine.set("k3", "v3");
        cacheEngine.set("k4", "v4"); // Should trigger eviction of k1 (LRU)
        assertThat(collector.getPolicyEvictions()).isEqualTo(1);

        // 3. Test TTL Eviction (passive)
        cacheEngine.set("k5", "v5", 1L); // 1s TTL
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cacheEngine.get("k5"); // Triggers passive TTL eviction & Miss
        assertThat(collector.getTtlEvictions()).isEqualTo(1);
        // k1 (miss) + k5 (miss) = 2 misses
        assertThat(collector.getMisses()).isEqualTo(2);
    }

    @Test
    public void testConcurrentCounterAccuracy() throws InterruptedException {
        int threadCount = 20;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        collector.recordHit();
                        collector.recordMiss();
                        collector.recordLatency(100_000); // 100 microseconds
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        int expected = threadCount * operationsPerThread;
        assertThat(collector.getHits()).isEqualTo(expected);
        assertThat(collector.getMisses()).isEqualTo(expected);
        assertThat(collector.getTimer().count()).isEqualTo(expected);
    }
}
