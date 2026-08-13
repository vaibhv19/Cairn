package com.portfolio.cairn.concurrency;

import com.portfolio.cairn.engine.evict.LfuEvictionPolicy;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class EvictionRaceConditionTest {

    @Test
    public void testConcurrentLruIntegrity() throws InterruptedException {
        // Given
        LruEvictionPolicy lruPolicy = new LruEvictionPolicy();
        int threadCount = 50;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        // When: Spawning threads doing concurrent updates
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronized start
                    for (int j = 0; j < operationsPerThread; j++) {
                        String key = "key-" + threadId + "-" + j;
                        
                        // Perform a mix of insert, access, remove, and evict operations
                        double action = Math.random();
                        if (action < 0.4) {
                            lruPolicy.onInsert(key);
                        } else if (action < 0.7) {
                            // Access some existing key of this thread
                            int randomPrevIndex = (int) (Math.random() * (j + 1));
                            lruPolicy.onAccess("key-" + threadId + "-" + randomPrevIndex);
                        } else if (action < 0.9) {
                            int randomPrevIndex = (int) (Math.random() * (j + 1));
                            lruPolicy.onRemove("key-" + threadId + "-" + randomPrevIndex);
                        } else {
                            lruPolicy.evictVictim();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Release the start latch to begin execution simultaneously
        startLatch.countDown();

        // Wait for all threads to finish
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: List integrity must hold, and node counts must match Map size
        assertThat(completed).isTrue();
        assertThat(lruPolicy.checkIntegrity()).isTrue();
    }

    @Test
    public void testConcurrentLfuIntegrity() throws InterruptedException {
        // Given
        LfuEvictionPolicy lfuPolicy = new LfuEvictionPolicy();
        int threadCount = 50;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        // When: Spawning threads doing concurrent LFU updates
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronized start
                    for (int j = 0; j < operationsPerThread; j++) {
                        String key = "key-" + threadId + "-" + j;
                        
                        // Perform a mix of LFU insert, access, remove, and evict operations
                        double action = Math.random();
                        if (action < 0.4) {
                            lfuPolicy.onInsert(key);
                        } else if (action < 0.7) {
                            int randomPrevIndex = (int) (Math.random() * (j + 1));
                            lfuPolicy.onAccess("key-" + threadId + "-" + randomPrevIndex);
                        } else if (action < 0.9) {
                            int randomPrevIndex = (int) (Math.random() * (j + 1));
                            lfuPolicy.onRemove("key-" + threadId + "-" + randomPrevIndex);
                        } else {
                            lfuPolicy.evictVictim();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Release start latch to begin execution
        startLatch.countDown();

        // Wait for threads to finish
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: LFU integrity must hold, and internal node states must be clean
        assertThat(completed).isTrue();
        assertThat(lfuPolicy.checkIntegrity()).isTrue();
    }
}
