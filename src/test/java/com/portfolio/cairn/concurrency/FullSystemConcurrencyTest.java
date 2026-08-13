package com.portfolio.cairn.concurrency;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import com.portfolio.cairn.expire.ActiveExpirySweeper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class FullSystemConcurrencyTest {

    @Test
    public void testFullSystemStressUnderLoad() throws InterruptedException {
        // Given: CacheEngine with capacity 1000 and LRU policy
        int capacity = 1000;
        LruEvictionPolicy lruPolicy = new LruEvictionPolicy();
        CacheEngine cache = new CacheEngine(lruPolicy, capacity);
        
        // Active Expiry Sweeper running every 50ms in the background
        ActiveExpirySweeper sweeper = new ActiveExpirySweeper(cache, 50);
        sweeper.start();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // When: Spawning 100 threads executing overlapping SET, GET, and DELETE operations
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.currentTimeMillis();
                    // Run stress operations for 2.5 seconds
                    while (System.currentTimeMillis() - start < 2500) {
                        double action = Math.random();
                        int randomKeyId = (int) (Math.random() * 2000); // 2000 unique keys to trigger evictions
                        String key = "key-" + randomKeyId;
                        
                        try {
                            if (action < 0.4) {
                                // 40% SET operations, some with 1s TTL
                                Long ttl = (Math.random() < 0.5) ? 1L : null;
                                cache.set(key, "value-" + threadId + "-" + randomKeyId, ttl);
                            } else if (action < 0.8) {
                                // 40% GET operations
                                cache.get(key);
                            } else {
                                // 20% DELETE operations
                                cache.delete(key);
                            }
                        } catch (Exception e) {
                            exceptionCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all stress operations to complete
        boolean completed = endLatch.await(6, TimeUnit.SECONDS);
        sweeper.stop();
        executor.shutdown();

        // Then: Assert no unexpected exceptions, deadlocks, or structure corruptions
        assertThat(completed).isTrue();
        assertThat(exceptionCount.get()).isEqualTo(0);
        
        // Verify capacity is strictly respected
        assertThat(cache.size()).isLessThanOrEqualTo(capacity);
        assertThat(lruPolicy.getMapSize()).isEqualTo(cache.size());
        
        // Validate eviction list integrity traverses correctly with no cycles or broken pointers
        assertThat(lruPolicy.checkIntegrity()).isTrue();
    }
}
