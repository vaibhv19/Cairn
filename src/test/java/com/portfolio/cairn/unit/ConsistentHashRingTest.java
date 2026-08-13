package com.portfolio.cairn.unit;

import com.portfolio.cairn.sharding.ConsistentHashRing;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

public class ConsistentHashRingTest {

    @Test
    public void testDeterministicMapping() {
        ConsistentHashRing ring = new ConsistentHashRing();
        ring.addNode("Node-A", 150);
        ring.addNode("Node-B", 150);
        ring.addNode("Node-C", 150);

        for (int i = 0; i < 1000; i++) {
            String key = "key-" + i;
            String node1 = ring.getNode(key);
            String node2 = ring.getNode(key);
            assertThat(node1).isNotNull();
            assertThat(node1).isEqualTo(node2);
        }
    }

    @Test
    public void testWraparound() {
        ConsistentHashRing ring = new ConsistentHashRing();
        ring.addNode("Node-A", 1); // single virtual node to make it simple
        
        // Ensure that any key maps to Node-A
        assertThat(ring.getNode("some-key-1")).isEqualTo("Node-A");
        assertThat(ring.getNode("some-key-2")).isEqualTo("Node-A");
    }

    @Test
    public void testDistributionUniformity() {
        ConsistentHashRing ring = new ConsistentHashRing();
        int nodeCount = 3;
        int virtualNodes = 150;
        int keyCount = 100000;

        ring.addNode("Node-A", virtualNodes);
        ring.addNode("Node-B", virtualNodes);
        ring.addNode("Node-C", virtualNodes);

        Map<String, Integer> allocation = new HashMap<>();
        allocation.put("Node-A", 0);
        allocation.put("Node-B", 0);
        allocation.put("Node-C", 0);

        for (int i = 0; i < keyCount; i++) {
            String key = "key-uuid-" + i;
            String node = ring.getNode(key);
            allocation.put(node, allocation.get(node) + 1);
        }

        double mean = (double) keyCount / nodeCount;
        double varianceSum = 0;
        for (int count : allocation.values()) {
            varianceSum += Math.pow(count - mean, 2);
        }
        double stdDev = Math.sqrt(varianceSum / nodeCount);
        double coefficientOfVariation = stdDev / mean;

        System.out.println("Uniformity Test Key Counts: " + allocation);
        System.out.println("Uniformity Test Mean: " + mean);
        System.out.println("Uniformity Test Standard Deviation: " + stdDev);
        System.out.println("Uniformity Test Coefficient of Variation: " + (coefficientOfVariation * 100) + "%");

        // Success metric: coefficient of variation < 15%
        assertThat(coefficientOfVariation).isLessThan(0.15);
    }

    @Test
    public void testMinimalMigrationOnNodeAddition() {
        ConsistentHashRing ring = new ConsistentHashRing();
        int n = 3; // start with 3 nodes
        int virtualNodes = 150;
        int keyCount = 100000;

        ring.addNode("Node-A", virtualNodes);
        ring.addNode("Node-B", virtualNodes);
        ring.addNode("Node-C", virtualNodes);

        // Record initial key mappings
        Map<String, String> initialMappings = new HashMap<>();
        for (int i = 0; i < keyCount; i++) {
            String key = "key-migration-" + i;
            initialMappings.put(key, ring.getNode(key));
        }

        // Add 4th node (Node-D)
        ring.addNode("Node-D", virtualNodes);

        int migratedCount = 0;
        for (int i = 0; i < keyCount; i++) {
            String key = "key-migration-" + i;
            String newNode = ring.getNode(key);
            if (!newNode.equals(initialMappings.get(key))) {
                migratedCount++;
                // Verify that if a key migrated, it migrated to the NEW node (Node-D)
                // This is a core property of consistent hashing!
                assertThat(newNode).isEqualTo("Node-D");
            }
        }

        double migrationRate = (double) migratedCount / keyCount;
        double expectedRate = 1.0 / (n + 1); // 1 / 4 = 25%

        System.out.println("Migration Count: " + migratedCount + " / " + keyCount + " (" + (migrationRate * 100) + "%)");
        System.out.println("Expected Migration Rate: " + (expectedRate * 100) + "%");

        // Verify that actual migration rate is close to expected (e.g. within 5% tolerance: 30%)
        assertThat(migrationRate).isLessThan(expectedRate + 0.05);
    }

    @Test
    public void testConcurrentReadsUnderLoad() throws InterruptedException {
        ConsistentHashRing ring = new ConsistentHashRing();
        ring.addNode("Node-A", 150);
        ring.addNode("Node-B", 150);
        ring.addNode("Node-C", 150);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 5000; i++) {
                        String key = "concurrent-key-" + ThreadLocalRandom.current().nextInt(10000);
                        String node = ring.getNode(key);
                        if (node == null) {
                            exceptionCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(exceptionCount.get()).isEqualTo(0);
    }
}
