package com.portfolio.cairn.concurrency;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class ConcurrentEvictionIntegrationTest {

    @Test
    public void testConcurrentSetExceedingCapacity() throws InterruptedException {
        // Given: Capacity of 100
        int capacity = 100;
        LruEvictionPolicy lruPolicy = new LruEvictionPolicy();
        CacheEngine cache = new CacheEngine(lruPolicy, capacity);

        int threadCount = 20;
        int keysPerThread = 50; // Total 1000 keys inserted, far exceeding 100 capacity
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        // When
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < keysPerThread; j++) {
                        String key = "key-" + threadId + "-" + j;
                        cache.set(key, "val-" + threadId + "-" + j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertThat(completed).isTrue();
        // The cache size must remain exactly at capacity (or under if keys overwrite, but here they are unique, so exactly capacity)
        assertThat(cache.size()).isEqualTo(capacity);
        assertThat(lruPolicy.getMapSize()).isEqualTo(capacity);
        assertThat(lruPolicy.checkIntegrity()).isTrue();
    }
}
