package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.InvalidationService;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class CacheInvalidationTest {

    private CacheEngine cacheEngine;
    private InvalidationService invalidationService;

    @BeforeEach
    public void setUp() {
        cacheEngine = new CacheEngine(new LruEvictionPolicy(), 1000);
        invalidationService = new InvalidationService(cacheEngine);
    }

    @Test
    public void testExactKeyInvalidation() {
        cacheEngine.set("key1", "val1");
        cacheEngine.set("key2", "val2");

        int count = invalidationService.invalidateByPattern("key1");
        assertThat(count).isEqualTo(1);
        assertThat(cacheEngine.exists("key1")).isFalse();
        assertThat(cacheEngine.exists("key2")).isTrue();
    }

    @Test
    public void testWildcardPatternInvalidation() {
        cacheEngine.set("user:1", "val1");
        cacheEngine.set("user:2", "val2");
        cacheEngine.set("product:1", "val3");

        int count = invalidationService.invalidateByPattern("user:*");
        assertThat(count).isEqualTo(2);
        assertThat(cacheEngine.exists("user:1")).isFalse();
        assertThat(cacheEngine.exists("user:2")).isFalse();
        assertThat(cacheEngine.exists("product:1")).isTrue();
    }

    @Test
    public void testFullFlushInvalidation() {
        cacheEngine.set("user:1", "val1");
        cacheEngine.set("product:1", "val3");

        int count = invalidationService.invalidateByPattern("*");
        assertThat(count).isEqualTo(2);
        assertThat(cacheEngine.size()).isEqualTo(0);
    }

    @Test
    public void testConcurrentInvalidationUnderLoad() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Pre-populate with some keys
        for (int i = 0; i < 500; i++) {
            cacheEngine.set("user:" + i, "value-" + i);
            cacheEngine.set("item:" + i, "value-" + i);
        }

        // Concurrent GET, SET, and Invalidation
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 200; i++) {
                        double rand = ThreadLocalRandom.current().nextDouble();
                        if (rand < 0.4) {
                            // SET
                            cacheEngine.set("user:" + ThreadLocalRandom.current().nextInt(1000), "val");
                        } else if (rand < 0.8) {
                            // GET
                            cacheEngine.get("user:" + ThreadLocalRandom.current().nextInt(1000));
                        } else {
                            // Invalidate prefix
                            invalidationService.invalidateByPattern("user:" + ThreadLocalRandom.current().nextInt(500) + "*");
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isEqualTo(0); // confirms no ConcurrentModificationExceptions or other crashes
    }
}
