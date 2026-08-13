package com.portfolio.cairn.unit;

import com.portfolio.cairn.engine.CacheEngine;
import com.portfolio.cairn.engine.evict.LfuEvictionPolicy;
import com.portfolio.cairn.engine.evict.LruEvictionPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CacheEvictionIntegrationTest {

    @Test
    public void testLruEvictionIntegration() {
        // Given: Capacity of 3 and LRU policy
        CacheEngine cache = new CacheEngine(new LruEvictionPolicy(), 3);
        
        // When
        cache.set("k1", "v1");
        cache.set("k2", "v2");
        cache.set("k3", "v3");
        
        // Access k1 to promote it (head -> k1 -> k3 -> k2 -> tail)
        cache.get("k1");
        
        // Insert k4, which should trigger eviction of tail (k2)
        cache.set("k4", "v4");

        // Then
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.exists("k2")).isFalse();
        assertThat(cache.exists("k1")).isTrue();
        assertThat(cache.exists("k3")).isTrue();
        assertThat(cache.exists("k4")).isTrue();
    }

    @Test
    public void testLfuEvictionIntegration() {
        // Given: Capacity of 3 and LFU policy
        CacheEngine cache = new CacheEngine(new LfuEvictionPolicy(), 3);
        
        // When
        cache.set("k1", "v1");
        cache.set("k2", "v2");
        cache.set("k3", "v3");
        
        // Access k2 (freq 3), k3 (freq 2)
        cache.get("k2");
        cache.get("k2");
        cache.get("k3");
        
        // k1 remains freq 1. Inserting k4 should evict key with lowest frequency (k1)
        cache.set("k4", "v4");

        // Then
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.exists("k1")).isFalse();
        assertThat(cache.exists("k2")).isTrue();
        assertThat(cache.exists("k3")).isTrue();
        assertThat(cache.exists("k4")).isTrue();
    }
}
