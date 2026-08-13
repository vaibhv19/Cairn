package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.evict.LfuEvictionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class LfuEvictionTest {

    private LfuEvictionPolicy lfuPolicy;

    @BeforeEach
    public void setUp() {
        lfuPolicy = new LfuEvictionPolicy();
    }

    @Test
    public void testInsertAndEvictOrder() {
        // When inserting keys: k1, then k2, then k3
        lfuPolicy.onInsert("k1");
        lfuPolicy.onInsert("k2");
        lfuPolicy.onInsert("k3");

        assertThat(lfuPolicy.getMapSize()).isEqualTo(3);
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(1);
        assertThat(lfuPolicy.getFrequency("k1")).isEqualTo(1);
        assertThat(lfuPolicy.getFrequency("k2")).isEqualTo(1);
        assertThat(lfuPolicy.getFrequency("k3")).isEqualTo(1);
        assertThat(lfuPolicy.checkIntegrity()).isTrue();

        // Evicting should remove and return "k1" (oldest with freq=1, tie-breaker is FIFO)
        String victim1 = lfuPolicy.evictVictim();
        assertThat(victim1).isEqualTo("k1");
        assertThat(lfuPolicy.getMapSize()).isEqualTo(2);
        assertThat(lfuPolicy.checkIntegrity()).isTrue();

        // Evicting next should return "k2"
        String victim2 = lfuPolicy.evictVictim();
        assertThat(victim2).isEqualTo("k2");
        assertThat(lfuPolicy.checkIntegrity()).isTrue();
    }

    @Test
    public void testPromotion() {
        lfuPolicy.onInsert("k1");
        lfuPolicy.onInsert("k2");

        // Freqs: k1=1, k2=1. minFreq=1
        lfuPolicy.onAccess("k1");
        // Freqs: k1=2, k2=1. minFreq=1
        assertThat(lfuPolicy.getFrequency("k1")).isEqualTo(2);
        assertThat(lfuPolicy.getFrequency("k2")).isEqualTo(1);
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(1);
        assertThat(lfuPolicy.checkIntegrity()).isTrue();

        // Evicting should target k2 since it has lowest freq (1)
        String victim = lfuPolicy.evictVictim();
        assertThat(victim).isEqualTo("k2");
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(2);
        assertThat(lfuPolicy.checkIntegrity()).isTrue();

        // Evict key k1, now empty
        victim = lfuPolicy.evictVictim();
        assertThat(victim).isEqualTo("k1");
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(-1);
        assertThat(lfuPolicy.checkIntegrity()).isTrue();
    }

    @Test
    public void testRemove() {
        lfuPolicy.onInsert("k1");
        lfuPolicy.onInsert("k2");
        lfuPolicy.onAccess("k1"); // k1=2, k2=1

        // Remove k2
        lfuPolicy.onRemove("k2");
        assertThat(lfuPolicy.getMapSize()).isEqualTo(1);
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(2); // Only k1=2 left
        assertThat(lfuPolicy.checkIntegrity()).isTrue();

        // Remove k1
        lfuPolicy.onRemove("k1");
        assertThat(lfuPolicy.getMapSize()).isEqualTo(0);
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(-1);
        assertThat(lfuPolicy.checkIntegrity()).isTrue();
    }

    @Test
    public void testEvictTiesByFifo() {
        lfuPolicy.onInsert("k1");
        lfuPolicy.onInsert("k2");
        lfuPolicy.onInsert("k3");

        // Freqs: k1=1, k2=1, k3=1
        // Access k2 and k3 to promote them
        lfuPolicy.onAccess("k2"); // k2=2
        lfuPolicy.onAccess("k3"); // k3=2
        lfuPolicy.onAccess("k2"); // k2=3

        // Freqs: k1=1, k2=3, k3=2. minFreq=1
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(1);

        // Evicting min frequency key: must be k1
        assertThat(lfuPolicy.evictVictim()).isEqualTo("k1");

        // Freqs: k2=3, k3=2. minFreq=2
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(2);

        // Evicting next lowest: must be k3
        assertThat(lfuPolicy.evictVictim()).isEqualTo("k3");

        // Freqs: k2=3. minFreq=3
        assertThat(lfuPolicy.getMinFrequency()).isEqualTo(3);
        assertThat(lfuPolicy.checkIntegrity()).isTrue();
    }
}
