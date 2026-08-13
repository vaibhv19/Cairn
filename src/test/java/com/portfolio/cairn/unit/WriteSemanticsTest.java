package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.MockDatabase;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class WriteSemanticsTest {

    private MockDatabase mockDatabase;
    private CacheEngine cacheEngine;

    @BeforeEach
    public void setUp() {
        mockDatabase = new MockDatabase();
        cacheEngine = new CacheEngine(new LruEvictionPolicy(), 10000, mockDatabase);
    }

    @Test
    public void testWriteThroughSuccess() {
        cacheEngine.writeThrough("k1", "v1");

        // Verify both cache and DB are updated
        assertThat(cacheEngine.get("k1").value()).isEqualTo("v1");
        assertThat(mockDatabase.find("k1")).isEqualTo("v1");
    }

    @Test
    public void testWriteThroughFailure() {
        mockDatabase.setShouldFail(true);

        // Verify that it fails and throws an exception
        assertThatThrownBy(() -> cacheEngine.writeThrough("k1", "v1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated DB Write Failure");

        // Note: the local cache set completed, but DB failed.
        // The mock DB does not contain the key.
        assertThat(mockDatabase.find("k1")).isNull();
    }

    @Test
    public void testWriteBackAsynchronousDraining() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        cacheEngine.writeBack("k1", "v1");
        long duration = System.currentTimeMillis() - startTime;

        // Verify writeBack returns immediately (practically 0ms)
        assertThat(duration).isLessThan(50);

        // Poll/await until the queued write eventually lands in MockDatabase
        boolean success = false;
        for (int i = 0; i < 50; i++) {
            if ("v1".equals(mockDatabase.find("k1"))) {
                success = true;
                break;
            }
            Thread.sleep(20);
        }
        assertThat(success).isTrue();
    }

    @Test
    public void testConcurrentHighVolumeWriteBack() throws InterruptedException {
        int threadCount = 20;
        int writesPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        cacheEngine.writeBack("key-" + tid + "-" + i, "value-" + i);
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
        assertThat(errorCount.get()).isEqualTo(0);

        // Await draining of the writeBack queue
        boolean queueDrained = false;
        for (int i = 0; i < 100; i++) {
            if (cacheEngine.getWriteBackQueueSize() == 0) {
                queueDrained = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(queueDrained).isTrue();

        // Verify no lost writes in MockDatabase
        int expectedTotal = threadCount * writesPerThread;
        assertThat(mockDatabase.size()).isEqualTo(expectedTotal);
    }
}
