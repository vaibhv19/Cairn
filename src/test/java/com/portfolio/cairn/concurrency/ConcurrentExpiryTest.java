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

public class ConcurrentExpiryTest {

    @Test
    public void testConcurrentPassiveAndActiveExpiry() throws InterruptedException {
        // Given: CacheEngine with capacity 150 and LruEvictionPolicy
        LruEvictionPolicy lruPolicy = new LruEvictionPolicy();
        CacheEngine cache = new CacheEngine(lruPolicy, 150);
        ActiveExpirySweeper sweeper = new ActiveExpirySweeper(cache, 1000);

        int keyCount = 100;
        // Insert keys with 300ms TTL
        for (int i = 0; i < keyCount; i++) {
            cache.set("key-" + i, "val-" + i, 300L / 1000L > 0 ? 300L / 1000L : 1L); // Force 1s TTL to prevent instant expiration before threads start, or set specific time.
            // Wait, we can specify a small TTL. In CacheEngine.set(key, val, ttlSeconds), it multiplies by 1000.
            // If we set to 1 second, it will expire in 1000ms. That is perfect.
        }
        
        // Let's re-write using 1s TTL
        for (int i = 0; i < keyCount; i++) {
            cache.set("key-" + i, "val-" + i, 1L);
        }

        int readerThreads = 10;
        int sweeperThreads = 5;
        int totalThreads = readerThreads + sweeperThreads;
        
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // When: Spawning concurrent readers triggering passive expiries
        for (int i = 0; i < readerThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.currentTimeMillis();
                    // Keep reading for 2 seconds (overlapping the 1s TTL expiration window)
                    while (System.currentTimeMillis() - start < 2000) {
                        for (int j = 0; j < keyCount; j++) {
                            try {
                                cache.get("key-" + j);
                            } catch (Exception e) {
                                exceptionCount.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // When: Spawning concurrent sweepers triggering active expiries
        for (int i = 0; i < sweeperThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.currentTimeMillis();
                    while (System.currentTimeMillis() - start < 2000) {
                        try {
                            sweeper.sweep();
                        } catch (Exception e) {
                            exceptionCount.incrementAndGet();
                        }
                        Thread.sleep(50); // Small pause between sweeps
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
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
        assertThat(exceptionCount.get()).isEqualTo(0);
        // All keys must be expired and removed
        assertThat(cache.size()).isEqualTo(0);
        assertThat(lruPolicy.getMapSize()).isEqualTo(0);
        assertThat(lruPolicy.checkIntegrity()).isTrue();
        // Each key must be evicted exactly once
        assertThat(cache.getTtlEvictions()).isEqualTo(keyCount);
    }
}
