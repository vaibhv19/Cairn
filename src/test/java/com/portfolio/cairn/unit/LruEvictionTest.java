package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class LruEvictionTest {

    private LruEvictionPolicy lruPolicy;

    @BeforeEach
    public void setUp() {
        lruPolicy = new LruEvictionPolicy();
    }

    @Test
    public void testInsertAndEvictOrder() {
        // When inserting keys: k1, then k2, then k3
        lruPolicy.onInsert("k1");
        lruPolicy.onInsert("k2");
        lruPolicy.onInsert("k3");

        // The order should be: head -> k3 -> k2 -> k1 -> tail
        assertThat(lruPolicy.getHeadKey()).isEqualTo("k3");
        assertThat(lruPolicy.getTailKey()).isEqualTo("k1");
        assertThat(lruPolicy.getMapSize()).isEqualTo(3);
        assertThat(lruPolicy.checkIntegrity()).isTrue();

        // Evicting should remove and return "k1" (least recently used)
        String victim1 = lruPolicy.evictVictim();
        assertThat(victim1).isEqualTo("k1");
        assertThat(lruPolicy.getTailKey()).isEqualTo("k2");
        assertThat(lruPolicy.checkIntegrity()).isTrue();

        // Evicting next should return "k2"
        String victim2 = lruPolicy.evictVictim();
        assertThat(victim2).isEqualTo("k2");
        assertThat(lruPolicy.checkIntegrity()).isTrue();
    }

    @Test
    public void testPromotion() {
        lruPolicy.onInsert("k1");
        lruPolicy.onInsert("k2");
        lruPolicy.onInsert("k3");

        // List is: k3 -> k2 -> k1
        // Access k2
        lruPolicy.onAccess("k2");

        // List should become: k2 -> k3 -> k1
        assertThat(lruPolicy.getHeadKey()).isEqualTo("k2");
        assertThat(lruPolicy.getTailKey()).isEqualTo("k1");
        assertThat(lruPolicy.checkIntegrity()).isTrue();

        // Access k1 (oldest tail)
        lruPolicy.onAccess("k1");

        // List should become: k1 -> k2 -> k3
        assertThat(lruPolicy.getHeadKey()).isEqualTo("k1");
        assertThat(lruPolicy.getTailKey()).isEqualTo("k3");
        assertThat(lruPolicy.checkIntegrity()).isTrue();
    }

    @Test
    public void testRemove() {
        lruPolicy.onInsert("k1");
        lruPolicy.onInsert("k2");
        lruPolicy.onInsert("k3");

        // Remove middle element k2
        lruPolicy.onRemove("k2");

        // List should become: k3 -> k1
        assertThat(lruPolicy.getHeadKey()).isEqualTo("k3");
        assertThat(lruPolicy.getTailKey()).isEqualTo("k1");
        assertThat(lruPolicy.getMapSize()).isEqualTo(2);
        assertThat(lruPolicy.checkIntegrity()).isTrue();

        // Remove head element k3
        lruPolicy.onRemove("k3");

        // List should become: k1
        assertThat(lruPolicy.getHeadKey()).isEqualTo("k1");
        assertThat(lruPolicy.getTailKey()).isEqualTo("k1");
        assertThat(lruPolicy.getMapSize()).isEqualTo(1);
        assertThat(lruPolicy.checkIntegrity()).isTrue();

        // Remove tail element k1
        lruPolicy.onRemove("k1");

        // List should become empty
        assertThat(lruPolicy.getHeadKey()).isNull();
        assertThat(lruPolicy.getTailKey()).isNull();
        assertThat(lruPolicy.getMapSize()).isEqualTo(0);
        assertThat(lruPolicy.checkIntegrity()).isTrue();
    }
}
